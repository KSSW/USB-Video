package com.meta.usbvideo.ui

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.meta.usbvideo.util.FileLogger
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.meta.usbvideo.R
import com.meta.usbvideo.UsbVideoNativeLibrary
import com.meta.usbvideo.eventloop.EventLooper
import com.meta.usbvideo.player.VideoRenderer
import com.meta.usbvideo.player.AudioRenderer
import com.meta.usbvideo.ui.AudioLevelMeterView
import com.meta.usbvideo.record.RawMuxer
import com.meta.usbvideo.record.RawRecorder
import com.meta.usbvideo.record.ContainerFormat
import com.meta.usbvideo.usb.AudioStreamingConnection
import com.meta.usbvideo.usb.UacManager
import com.meta.usbvideo.usb.UsbDeviceState
import com.meta.usbvideo.usb.UsbMonitor
import com.meta.usbvideo.usb.UsbPermission
import com.meta.usbvideo.usb.UvcManager
import com.meta.usbvideo.usb.VideoFormat
import com.meta.usbvideo.usb.VideoStreamingConnection
import com.meta.usbvideo.player.UsbAudioPlayer
import com.meta.usbvideo.audio.XbmcAudioManager
import com.meta.usbvideo.audio.XbmcAudioSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

private const val TAG = "PreviewActivity"

/**
 * Main preview activity with UI controls for preview, capture, and recording.
 * UI design is based on UVCAndroid's MainActivity, integrated with
 * usb-video-main's native UVC/UAC backend.
 */
class PreviewActivity : AppCompatActivity() {

    // Views
    private lateinit var videoContainer: VideoContainerView
    private lateinit var tvConnectTip: android.widget.TextView
    private lateinit var tvRecordTime: android.widget.TextView
    private lateinit var tvPreviewInfo: android.widget.TextView
    private lateinit var tvStreamingStats: android.widget.TextView
    private lateinit var fabPicture: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var fabVideo: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var btnMenu: ImageButton
    private lateinit var audioLevelMeter: AudioLevelMeterView
    private lateinit var btnSelectDevice: com.google.android.material.button.MaterialButton
    private lateinit var connectTipContainer: View

    // Components
    private val videoRenderer = VideoRenderer()
    private val audioRenderer = AudioRenderer()
    private val rawMuxer = RawMuxer()
    private val usbAudioPlayer = UsbAudioPlayer()
    private var videoTextureView: TextureView? = null

    // State
    private var isCameraConnected = false
    private var isRecording = false
    private var showStats = false
    private var userEjected = false
    private var wasReselected = false  // true when reconnecting via Select Device (no mute needed)
    private var isConnecting = false
    private var currentVideoFormat: VideoFormat? = null
    private var recordTimer: Timer? = null
    private var recordStartTime = 0L
    private var stateObserverJob: Job? = null
    private var usbPollingJob: Job? = null
    private var videoSurface: Surface? = null
    private var lastKnownDeviceName: String? = null
    private var pendingConnectedState: UsbDeviceState.Connected? = null
    private var pendingRestartUsbDevice: UsbDevice? = null
    private var pendingRestartAudioConn: AudioStreamingConnection? = null
    private var pendingRestartVideoConn: VideoStreamingConnection? = null

    // Audio format info for recording
    private var audioSampleRate: Int = 0
    private var audioChannels: Int = 0
    private var audioBitsPerSample: Int = 0

    // xbmc-Audio manager
    private lateinit var xbmcAudioManager: XbmcAudioManager

    // FPS readiness: buttons disabled until FPS reaches target after format switch
    private var isFpsReady = false
    private var fpsWaitJob: Job? = null

    // Permission launchers
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(TAG, "Camera permission granted")
            tryConnectDevice()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(TAG, "Record audio permission granted")
        } else {
            Toast.makeText(this, "Record audio permission required for recording", Toast.LENGTH_SHORT).show()
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "Storage permission granted: $granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(applicationContext)
        FileLogger.log(TAG, "onCreate")
        UvcManager.initContext(applicationContext)
        currentVideoFormat = UvcManager.currentVideoFormat
        setContentView(R.layout.activity_preview)

        bindViews()

        // Immersive full-screen: hide system status bar, swipe from top to show
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        xbmcAudioManager = XbmcAudioManager(applicationContext)

        // Restore persisted capture audio channel setting
        captureAudioChannel = loadCaptureAudioChannel()
        val chCount = when (captureAudioChannel) {
            "5.1" -> 6
            "7.1" -> 8
            else -> 2
        }
        UsbMonitor.desiredAudioChannelCount = chCount
        FileLogger.log(TAG, "Restored captureAudioChannel=$captureAudioChannel → desiredAudioChannelCount=$chCount")

        loadPreviewInfoSettings()

        setupVideoRenderer()
        setupClickListeners()
        registerUsbReceivers()
        observeUsbState()
        startUsbDevicePolling()

        // Probe audio capabilities on startup
        lifecycleScope.launch {
            val caps = xbmcAudioManager.probeCapabilities()
            val hasSpeaker = caps.hasSpeaker
            FileLogger.log(TAG, "Audio probe: speaker=$hasSpeaker, useXbmc=${xbmcAudioManager.shouldUseXbmcAudio()}")
            if (!hasSpeaker) {
                runOnUiThread {
                    Toast.makeText(this@PreviewActivity,
                        "No built-in speaker detected, using xbmc-Audio output",
                        Toast.LENGTH_LONG).show()
                }
            }
        }

        // Request permissions
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        // Request MANAGE_EXTERNAL_STORAGE for direct file access on Android 11+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            }
        }
        FileLogger.log(TAG, "onCreate complete, polling started")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            if (userEjected) {
                return
            }
            val device: UsbDevice? = UsbMonitor.findUvcDevice()
            if (device != null && !isCameraConnected) {
                if (!UsbPermission.hasPermission(this, device)) {
                    UsbPermission.requestPermission(this, device)
                }
            }
        } else if (intent.action == Intent.ACTION_MAIN) {
            // App reopened from launcher after Safely Eject — allow reconnection
            if (userEjected && !isCameraConnected) {
                FileLogger.log(TAG, "App relaunched after eject, re-enabling device scan")
                userEjected = false
                startUsbDevicePolling()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isRecording) {
            toggleRecord(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FileLogger.log(TAG, "onDestroy")
        usbPollingJob?.cancel()
        stateObserverJob?.cancel()
        usbAudioPlayer.stop()
        UsbMonitor.disconnect()
        FileLogger.close()
    }


    // ---- View binding ----

    private fun bindViews() {
        videoContainer = findViewById(R.id.viewMainPreview)
        tvConnectTip = findViewById(R.id.tvConnectUSBCameraTip)
        tvRecordTime = findViewById(R.id.tvVideoRecordTime)
        tvPreviewInfo = findViewById(R.id.tvPreviewInfo)
        tvStreamingStats = findViewById(R.id.tvStreamingStats)
        fabPicture = findViewById(R.id.fabPicture)
        fabVideo = findViewById(R.id.fabVideo)
        btnMenu = findViewById(R.id.btnMenu)
        audioLevelMeter = findViewById(R.id.audioLevelMeter)
        btnSelectDevice = findViewById(R.id.btnSelectDevice)
        connectTipContainer = findViewById(R.id.connectTipContainer)
    }

    // ---- Video renderer setup ----

    private fun setupVideoRenderer() {
        videoTextureView = TextureView(this).apply {
            isOpaque = false
        }
        videoRenderer.setCallback(object : VideoRenderer.Callback {
            override fun onSurfaceAvailable(surface: Surface) {
                videoSurface = surface
                FileLogger.log(TAG, "Preview surface available")
                // If device was already connected but waiting for surface
                val pending = pendingConnectedState
                if (pending != null) {
                    FileLogger.log(TAG, "Surface ready, starting deferred streaming")
                    pendingConnectedState = null
                    startStreaming(pending, surface)
                } else {
                    tryConnectDevice()
                }
            }

            override fun onSurfaceDestroyed() {
                videoSurface = null
                FileLogger.log(TAG, "Preview surface destroyed")
            }

            override fun onFrameRendered(fps: Float) {
                // FPS is now polled via JNI in startVideoFpsMonitor
            }
        })
        videoRenderer.attachToTextureView(videoTextureView!!)
        videoContainer.addVideoTextureView(videoTextureView!!, 1920, 1080)
    }

    // ---- Click listeners ----

    private fun setupClickListeners() {
        fabPicture.setOnClickListener {
            takePicture()
        }

        fabVideo.setOnClickListener {
            toggleRecord(!isRecording)
        }

        btnSelectDevice.setOnClickListener {
            showDeviceListDialog()
        }

        btnMenu.setOnClickListener {
            PopupMenu(this, btnMenu).apply {
                menu.add(Menu.NONE, 0, Menu.NONE, "About")
                menu.add(Menu.NONE, 1, Menu.NONE, getString(R.string.action_settings)).apply {
                    isEnabled = isCameraConnected
                }
                menu.add(Menu.NONE, 2, Menu.NONE, getString(R.string.action_xbmc_audio))
                menu.add(Menu.NONE, 3, Menu.NONE, getString(R.string.action_safely_eject)).apply {
                    isEnabled = isCameraConnected
                }
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        0 -> showAboutDialog()
                        1 -> showSettingsDialog()
                        2 -> showXbmcAudioDialog()
                        3 -> safelyEject()
                    }
                    true
                }
                show()
            }
        }
    }

    // ---- USB state observation ----

    private fun registerUsbReceivers() {
        UsbPermission.registerUsbReceivers(
            this,
            onAttach = { device -> handleDeviceAttached(device) },
            onDetach = { device -> handleDeviceDetached(device) },
            onPermissionResult = { device, granted ->
                if (granted) {
                    UsbMonitor.setState(UsbDeviceState.PermissionGranted(device))
                } else {
                    Toast.makeText(this, "USB permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun observeUsbState() {
        stateObserverJob = lifecycleScope.launch {
            UsbMonitor.usbDeviceStateFlow.collectLatest { state ->
                FileLogger.log(TAG, "USB state changed: $state")
                when (state) {
                    is UsbDeviceState.NotFound -> {
                        onDeviceDisconnected()
                    }
                    is UsbDeviceState.Attached -> {
                        val hasPermission = UsbMonitor.getUsbManager()?.hasPermission(state.usbDevice) == true
                        if (hasPermission) {
                            UsbMonitor.setState(UsbDeviceState.PermissionGranted(state.usbDevice))
                        } else {
                            UsbPermission.requestPermission(this@PreviewActivity, state.usbDevice)
                        }
                    }
                    is UsbDeviceState.PermissionRequired -> {
                        UsbPermission.requestPermission(this@PreviewActivity, state.usbDevice)
                    }
                    is UsbDeviceState.PermissionGranted -> {
                        UsbMonitor.connect(state.usbDevice)
                    }
                    is UsbDeviceState.Connected -> {
                        onDeviceConnected(state)
                    }
                    is UsbDeviceState.Streaming -> {
                        onStreamingStarted(state)
                    }
                    is UsbDeviceState.Detached -> {
                        lifecycleScope.launch {
                            EventLooper.call {
                                UsbVideoNativeLibrary.stopUsbAudioStreamingNative()
                                UsbVideoNativeLibrary.stopUsbVideoStreamingNative()
                                UsbVideoNativeLibrary.disconnectUsbAudioStreamingNative()
                                UsbVideoNativeLibrary.disconnectUsbVideoStreamingNative()
                                UsbMonitor.disconnect()
                            }
                        }
                        onDeviceDisconnected()
                    }
                    else -> { /* PermissionRequested, PermissionDenied, etc. */ }
                }
            }
        }
    }

    private fun handleDeviceAttached(device: UsbDevice) {
        if (userEjected) {
            FileLogger.log(TAG, "Device attached ignored (user ejected): ${device.productName}")
            return
        }
        FileLogger.log(TAG, "Device attached: ${device.productName}")
        UsbMonitor.setState(UsbDeviceState.Attached(device))
    }

    private fun handleDeviceDetached(device: UsbDevice?) {
        userEjected = false
        wasReselected = false  // Physical detach → next connect is fresh plug → needs mute
        if (!isCameraConnected) return
        UsbMonitor.disconnect()
        onDeviceDisconnected()
    }

    private fun tryConnectDevice() {
        val device = UsbMonitor.findUvcDevice() ?: return
        if (!isCameraConnected) {
            UsbMonitor.setState(UsbDeviceState.Attached(device))
        }
    }

    /**
     * Poll for USB device changes every 2 seconds.
     * This is needed because USB_DEVICE_ATTACHED broadcast cannot be received
     * by dynamically registered BroadcastReceivers on Android.
     */
    private fun startUsbDevicePolling() {
        usbPollingJob = lifecycleScope.launch {
            while (true) {
                delay(2000)
                if (isCameraConnected) continue

                val device = UsbMonitor.findUvcDevice()
                val currentState = UsbMonitor.usbDeviceState

                if (device != null && currentState is UsbDeviceState.NotFound) {
                    FileLogger.log(TAG, "USB polling: device found: ${device.productName}")
                    lastKnownDeviceName = device.productName
                    UsbMonitor.setState(UsbDeviceState.Attached(device))
                } else if (device == null && currentState !is UsbDeviceState.NotFound
                    && currentState !is UsbDeviceState.Detached) {
                    FileLogger.log(TAG, "USB polling: device removed")
                    UsbMonitor.setState(UsbDeviceState.NotFound)
                }
            }
        }
    }

    // ---- Device lifecycle callbacks ----

    private fun onDeviceConnected(state: UsbDeviceState.Connected) {
        if (isConnecting || isCameraConnected || pendingConnectedState != null) {
            FileLogger.log(TAG, "Device already connecting/connected, skipping duplicate onDeviceConnected")
            return
        }
        isConnecting = true
        FileLogger.log(TAG, "Device connected, starting streaming...")

        val formats = state.videoStreamingConnection.videoFormats
        // Preserve user's selected format across reconnects; only auto-select on first connect
        val existingFormat = currentVideoFormat
        if (existingFormat != null && formats.any {
                it.fourccFormat == existingFormat.fourccFormat &&
                it.width == existingFormat.width &&
                it.height == existingFormat.height &&
                it.fps == existingFormat.fps
            }) {
            FileLogger.log(TAG, "Preserving user-selected format: $currentVideoFormat")
        } else {
            currentVideoFormat = state.videoStreamingConnection.findBestVideoFormat(1920, 1080)
            FileLogger.log(TAG, "Auto-selected format: $currentVideoFormat")
        }
        FileLogger.log(TAG, "Available formats: ${formats.size}, selected: $currentVideoFormat")

        // Make container visible so SurfaceView surface can be created
        runOnUiThread {
            videoContainer.visibility = View.VISIBLE
            connectTipContainer.visibility = View.GONE
        }

        val surface = videoSurface
        if (surface == null) {
            FileLogger.log(TAG, "Surface not ready yet, deferring streaming")
            pendingConnectedState = state
            return
        }

        startStreaming(state, surface)
    }

    private fun startStreaming(state: UsbDeviceState.Connected, surface: Surface) {
        FileLogger.log(TAG, "startStreaming: surface=$surface")

        // Log audio format details for debugging
        val audioConn = state.audioStreamingConnection
        FileLogger.log(TAG, "Audio: supportsStreaming=${audioConn.supportsAudioStreaming}, " +
                "hasSupportedFormat=${audioConn.hasSupportedAudioFormat}, " +
                "hasFormatType=${audioConn.hasFormatTypeDescriptor}")
        if (audioConn.hasFormatTypeDescriptor) {
            val fmt = audioConn.formatTypeDescriptor
            audioSampleRate = fmt.tSamFreq.firstOrNull() ?: 0
            audioChannels = fmt.bNrChannels
            audioBitsPerSample = fmt.bBitResolution
            FileLogger.log(TAG, "Audio format: channels=${fmt.bNrChannels}, " +
                    "subFrameSize=${fmt.bSubFrameSize}, bits=${fmt.bBitResolution}, " +
                    "sampleRates=${fmt.tSamFreq.joinToString(",")}")
        }

        lifecycleScope.launch {
            try {
                // Start audio via native layer (24-to-16-bit conversion fixed in C++)
                // Mute for 3s on fresh USB plug to suppress startup noise; no mute on re-select
                val muteMs = if (wasReselected) 0 else 3000
                val audioResult = EventLooper.call {
                    UsbVideoNativeLibrary.connectUsbAudioStreaming(
                        applicationContext, state.audioStreamingConnection, muteMs
                    ).also { UsbVideoNativeLibrary.startUsbAudioStreamingNative() }
                }
                wasReselected = false  // Reset after use
                FileLogger.log(TAG, "Audio streaming result: success=${audioResult.first}, msg=${audioResult.second}")

                // Start video
                val videoConnectResult = EventLooper.call {
                    UsbVideoNativeLibrary.connectUsbVideoStreaming(
                        state.videoStreamingConnection, surface, currentVideoFormat
                    )
                }
                val videoStartResult = EventLooper.call {
                    UsbVideoNativeLibrary.startUsbVideoStreamingNative()
                }
                FileLogger.log(TAG, "Video connect result: success=$videoConnectResult")
                FileLogger.log(TAG, "Video start result: success=$videoStartResult")

                lifecycleScope.launch {
                    delay(2000)
                    val stats = UsbVideoNativeLibrary.streamingStatsSummaryString()
                    FileLogger.log(TAG, "Native video stats after 2s: $stats")
                    // Log audio diagnostics (control transfers + raw hex) separately
                    delay(1000)
                    try {
                        val diag = UsbVideoNativeLibrary.getAudioDiagnostics()
                        FileLogger.log(TAG, "AudioDiag: $diag")
                    } catch (e: Exception) {
                        FileLogger.log(TAG, "AudioDiag error: ${e.message}")
                    }
                    // Log UVC Extension Unit diagnostics
                    try {
                        val xuDiag = UsbVideoNativeLibrary.getUvcXuDiagnostics()
                        FileLogger.log(TAG, "UVC_XU_Diag:\n$xuDiag")
                    } catch (e: Exception) {
                        FileLogger.log(TAG, "UVC_XU_Diag error: ${e.message}")
                    }
                }

                UsbMonitor.setState(
                    UsbDeviceState.Streaming(
                        state.usbDevice,
                        state.audioStreamingConnection,
                        audioResult.first, audioResult.second,
                        state.videoStreamingConnection,
                        videoConnectResult.first, if (videoStartResult) "Success" else "Native start failed"
                    )
                )
                FileLogger.log(TAG, "Streaming state set successfully")
            } catch (e: Exception) {
                FileLogger.error(TAG, "Failed to start streaming", e)
            }
        }
    }

    private fun restartVideoStreaming() {
        FileLogger.log(TAG, "restartVideoStreaming() called, format=$currentVideoFormat")
        val state = UsbMonitor.usbDeviceState
        if (state !is UsbDeviceState.Streaming && state !is UsbDeviceState.StreamingStopped && state !is UsbDeviceState.Connected) {
            FileLogger.log(TAG, "restartVideoStreaming() aborted: state is not active")
            return
        }
        if (isRecording) {
            toggleRecord(false)
        }
        val format = currentVideoFormat
        val surface = videoSurface
        if (format == null || surface == null) {
            FileLogger.log(TAG, "restartVideoStreaming() aborted: format or surface is null")
            return
        }

        // Disable buttons until FPS recovers to target
        isFpsReady = false
        fpsWaitJob?.cancel()
        runOnUiThread {
            fabPicture.isEnabled = false
            fabVideo.isEnabled = false
            fabPicture.alpha = 0.4f
            fabVideo.alpha = 0.4f
        }

        lifecycleScope.launch {
            try {
                FileLogger.log(TAG, "Reconfiguring video stream to: $format")
                val result = EventLooper.call {
                    UsbVideoNativeLibrary.reconfigureVideoStreaming(surface, format)
                }
                FileLogger.log(TAG, "Reconfigure result: success=${result.first}, msg=${result.second}")
                if (!result.first) {
                    runOnUiThread {
                        Toast.makeText(this@PreviewActivity, "Format switch failed: ${result.second}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                // Start waiting for FPS to reach target
                startFpsReadyWait(format.fps)
            } catch (e: Exception) {
                FileLogger.error(TAG, "Failed to reconfigure video streaming", e)
            }
        }
    }

    /**
     * Poll native FPS until it reaches [targetFps]. Once reached, re-enable
     * the record & screenshot buttons.  If FPS doesn't recover within 10 s
     * the buttons stay disabled (user can try switching format again).
     */
    private fun startFpsReadyWait(targetFps: Int) {
        fpsWaitJob?.cancel()
        fpsWaitJob = lifecycleScope.launch {
            val timeoutMs = 10_000L
            val startTime = System.currentTimeMillis()
            FileLogger.log(TAG, "FPS ready wait started, target=$targetFps")
            while (true) {
                delay(250)
                val elapsed = System.currentTimeMillis() - startTime
                val fps = try {
                    UsbVideoNativeLibrary.getNativeVideoFps()
                } catch (_: Exception) { 0 }
                if (fps >= targetFps) {
                    FileLogger.log(TAG, "FPS reached target: measured=$fps target=$targetFps (${elapsed}ms)")
                    isFpsReady = true
                    runOnUiThread {
                        fabPicture.isEnabled = true
                        fabVideo.isEnabled = true
                        fabPicture.alpha = 1.0f
                        fabVideo.alpha = 1.0f
                    }
                    return@launch
                }
                if (elapsed > timeoutMs) {
                    FileLogger.log(TAG, "FPS ready wait timed out: measured=$fps target=$targetFps")
                    runOnUiThread {
                        Toast.makeText(this@PreviewActivity,
                            "FPS not reached ($fps/$targetFps), buttons disabled",
                            Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
            }
        }
    }

    private fun recreateTextureView() {
        videoSurface = null
        val oldTextureView = videoTextureView
        if (oldTextureView != null) {
            videoContainer.removeView(oldTextureView)
            oldTextureView.surfaceTextureListener = null
        }
        val newTextureView = TextureView(this).apply {
            isOpaque = false
        }
        videoTextureView = newTextureView
        videoRenderer.release()
        videoRenderer.setCallback(object : VideoRenderer.Callback {
            override fun onSurfaceAvailable(surface: Surface) {
                videoSurface = surface
                FileLogger.log(TAG, "Restart surface available")
                val pending = pendingConnectedState
                if (pending != null) {
                    FileLogger.log(TAG, "Surface ready, starting deferred streaming")
                    pendingConnectedState = null
                    startStreaming(pending, surface)
                } else {
                    tryConnectDevice()
                }
            }
            override fun onSurfaceDestroyed() {
                videoSurface = null
                FileLogger.log(TAG, "Preview surface destroyed")
            }
            override fun onFrameRendered(fps: Float) {
                // FPS is now polled via JNI in startVideoFpsMonitor
            }
        })
        videoRenderer.attachToTextureView(newTextureView)
        videoContainer.addVideoTextureView(newTextureView, 1920, 1080)
    }

    private fun onStreamingStarted(state: UsbDeviceState.Streaming) {
        isCameraConnected = true
        isConnecting = false
        isFpsReady = true // buttons enabled on initial connect
        FileLogger.log(TAG, "onStreamingStarted: video=${state.videoStreamingSuccess}, audio=${state.audioStreamingSuccess}")

        if (state.audioStreamingSuccess) {
            // Set level meter channel count from user setting
            val chCount = when (captureAudioChannel) {
                "5.1" -> 6
                "7.1" -> 8
                else -> 2
            }
            audioLevelMeter.setChannelCount(chCount)
            startAudioLevelMonitor()
        }
        startVideoFpsMonitor()

        // Do NOT recreate SurfaceView here - the native layer is already
        // rendering to the Surface that was passed in startStreaming().
        // Recreating it would destroy that Surface and cause black screen.

        runOnUiThread {
            updateUIControls()

            if (!state.videoStreamingSuccess) {
                Toast.makeText(this, "Video: ${state.videoStreamingMessage}", Toast.LENGTH_LONG).show()
            }
            if (!state.audioStreamingSuccess) {
                Toast.makeText(this, "Audio: ${state.audioStreamingMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onDeviceDisconnected() {
        isCameraConnected = false
        isConnecting = false
        isFpsReady = false
        fpsWaitJob?.cancel()
        pendingConnectedState = null
        usbAudioPlayer.stop()
        // Invalidate stale surface so reconnection uses a fresh one
        videoSurface = null
        runOnUiThread {
            updateUIControls()
            // Recreate TextureView so next connection gets a fresh Surface/ANativeWindow
            recreateTextureView()
        }
    }

    // ---- UI updates ----

    private fun updateUIControls() {
        if (isCameraConnected) {
            videoContainer.visibility = View.VISIBLE
            connectTipContainer.visibility = View.GONE
            fabPicture.visibility = View.VISIBLE
            fabVideo.visibility = View.VISIBLE
            btnMenu.visibility = View.VISIBLE
            audioLevelMeter.visibility = if (showAudioLevelMeter) View.VISIBLE else View.GONE
            tvPreviewInfo.visibility = if (showPreviewInfo) View.VISIBLE else View.GONE

            // Buttons enabled only when FPS is ready (after format switch)
            if (!isRecording) {
                fabPicture.isEnabled = isFpsReady
                fabVideo.isEnabled = isFpsReady
                fabPicture.alpha = if (isFpsReady) 1.0f else 0.4f
                fabVideo.alpha = if (isFpsReady) 1.0f else 0.4f
            }

            // Update record button color
            val colorRes = if (isRecording) android.R.color.holo_red_dark else android.R.color.white
            fabVideo.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
        } else {
            videoContainer.visibility = View.INVISIBLE
            connectTipContainer.visibility = View.VISIBLE
            fabPicture.visibility = View.GONE
            fabVideo.visibility = View.GONE
            btnMenu.visibility = View.GONE
            tvPreviewInfo.visibility = View.GONE
            tvRecordTime.visibility = View.GONE
            tvStreamingStats.visibility = View.GONE
            audioLevelMeter.visibility = View.GONE
        }
    }

    private fun updatePreviewInfo(fps: Float) {
        val format = currentVideoFormat
        tvPreviewInfo.text = if (format != null) {
            "${format.fourccFormat} ${format.width}x${format.height} @${String.format("%.2f", fps)}fps"
        } else {
            ""
        }
    }

    // ---- Capture / Record ----

    private fun takePicture() {
        if (isRecording) return
        val textureView = videoTextureView ?: return
        val bitmap = videoRenderer.captureFrame(textureView)
        if (bitmap != null) {
            val file = RawMuxer.captureScreenshot(bitmap)
            if (file != null) {
                Toast.makeText(this, getString(R.string.screenshot_saved, file.name), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to save screenshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleRecord(start: Boolean) {
        if (start) {
            val format = currentVideoFormat
            if (format == null) {
                Toast.makeText(this, "No video format available", Toast.LENGTH_SHORT).show()
                return
            }

            if (recVideoFormat == "Copy") {
                // Use native RawRecorder (ffmpeg passthrough)
                val timestamp = java.text.SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", java.util.Locale.getDefault()).format(java.util.Date())
                val ext = when (recMux) {
                    "AVI" -> "avi"
                    "MP4" -> "mp4"
                    "MOV" -> "mov"
                    "MKV" -> "mkv"
                    else -> "avi"
                }
                val outputFile = File(RawRecorder.getVideoOutputDir(), "VID_$timestamp.$ext")
                val container = when (recMux) {
                    "avi" -> ContainerFormat.AVI
                    "mp4" -> ContainerFormat.MP4
                    "mov" -> ContainerFormat.MOV
                    "mkv" -> ContainerFormat.MKV
                    else -> ContainerFormat.AVI
                }
                val success = UsbVideoNativeLibrary.startRecording(
                    outputFile.absolutePath,
                    container,
                    audioSampleRate,
                    audioChannels,
                    audioBitsPerSample
                )
                if (success) {
                    isRecording = true
                    startRecordTimer()
                } else {
                    Toast.makeText(this, "Failed to start recording: ${UsbVideoNativeLibrary.isRecordingNative()}", Toast.LENGTH_LONG).show()
                }
            } else {
                // Use MediaCodec H.264 encoder (RawMuxer)
                rawMuxer.setCallback(object : RawMuxer.RecordCallback {
                    override fun onRecordStarted() {
                        runOnUiThread {
                            startRecordTimer()
                            Toast.makeText(this@PreviewActivity, getString(R.string.recording_started), Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onRecordStopped(file: File) {
                        runOnUiThread {
                            stopRecordTimer()
                            Toast.makeText(
                                this@PreviewActivity,
                                getString(R.string.recording_saved, file.name),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onRecordError(message: String) {
                        runOnUiThread {
                            stopRecordTimer()
                            Toast.makeText(this@PreviewActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onRecordTimeUpdate(timeText: String) {
                        runOnUiThread { tvRecordTime.text = timeText }
                    }
                })

                rawMuxer.startRecording(format.width, format.height, format.fps)
                isRecording = true
            }
            fabPicture.isEnabled = false
            btnMenu.isEnabled = false
        } else {
            if (recVideoFormat == "Copy") {
                UsbVideoNativeLibrary.stopRecordingNative()
            } else {
                rawMuxer.stopRecording()
            }
            isRecording = false
            stopRecordTimer()
            fabPicture.isEnabled = true
            btnMenu.isEnabled = true
        }
        updateUIControls()
    }

    private fun startRecordTimer() {
        tvRecordTime.visibility = View.VISIBLE
        tvRecordTime.text = "00:00:00"
        recordStartTime = SystemClock.elapsedRealtime()
        recordTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val seconds = (SystemClock.elapsedRealtime() - recordStartTime) / 1000
                    val timeText = rawMuxer.formatTime(seconds)
                    runOnUiThread { tvRecordTime.text = timeText }
                }
            }, 250, 250)
        }
    }

    private fun stopRecordTimer() {
        recordTimer?.cancel()
        recordTimer = null
        recordStartTime = 0
        tvRecordTime.visibility = View.GONE
        tvRecordTime.text = "00:00:00"
    }

    // ---- Menu actions ----

    // ---- Settings dialog ----

    // Recording settings state (persisted for mux/encoder selection)
    private var recVideoFormat = "Copy"
    private var recAudioFormat = "Copy"
    private var recMux = "avi"

    // Capture audio channel config: "2.0", "5.1", "7.1" — persisted via SharedPreferences
    private var captureAudioChannel = "2.0"
    // Preview info & audio level meter visibility toggles
    private var showPreviewInfo = true
    private var showAudioLevelMeter = true

    private fun loadCaptureAudioChannel(): String {
        val prefs = getSharedPreferences("usb_video_prefs", MODE_PRIVATE)
        return prefs.getString("capture_audio_channel", "2.0") ?: "2.0"
    }

    private fun saveCaptureAudioChannel(value: String) {
        getSharedPreferences("usb_video_prefs", MODE_PRIVATE)
            .edit().putString("capture_audio_channel", value).apply()
    }

    private fun loadPreviewInfoSettings() {
        val prefs = getSharedPreferences("usb_video_prefs", MODE_PRIVATE)
        showPreviewInfo = prefs.getBoolean("show_preview_info", true)
        showAudioLevelMeter = prefs.getBoolean("show_audio_level_meter", true)
    }

    private fun savePreviewInfoSettings() {
        getSharedPreferences("usb_video_prefs", MODE_PRIVATE).edit()
            .putBoolean("show_preview_info", showPreviewInfo)
            .putBoolean("show_audio_level_meter", showAudioLevelMeter)
            .apply()
    }

    private fun showAboutDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.about, null)
        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val spinnerCapture = view.findViewById<Spinner>(R.id.spinnerCaptureFormat)
        val spinnerResolution = view.findViewById<Spinner>(R.id.spinnerResolution)
        val spinnerFrameRate = view.findViewById<Spinner>(R.id.spinnerFrameRate)
        val spinnerRecVideo = view.findViewById<Spinner>(R.id.spinnerRecVideoFormat)
        val spinnerRecAudio = view.findViewById<Spinner>(R.id.spinnerRecAudioFormat)
        val spinnerMux = view.findViewById<Spinner>(R.id.spinnerMux)
        val spinnerAudioChannel = view.findViewById<Spinner>(R.id.spinnerAudioChannel)
        val tvAudioInfo = view.findViewById<android.widget.TextView>(R.id.tvAudioInfo)
        val cbShowPreviewInfo = view.findViewById<android.widget.CheckBox>(R.id.cbShowPreviewInfo)
        val cbShowAudioLevelMeter = view.findViewById<android.widget.CheckBox>(R.id.cbShowAudioLevelMeter)

        val formats = UvcManager.videoFormats
        if (formats.isEmpty()) {
            Toast.makeText(this, "No video formats available", Toast.LENGTH_SHORT).show()
            return
        }

        val current = currentVideoFormat
        val formatLabels = formats.map { it.fourccFormat }.distinct().sortedBy {
            when (it) {
                "MJPG" -> 0
                "NV12" -> 1
                "YUY2" -> 2
                "BGR24" -> 3
                else -> 4
            }
        }

        fun refreshResolutions(selectedFormat: String) {
            val resolutions = formats
                .filter { it.fourccFormat == selectedFormat }
                .map { "${it.width}x${it.height}" }
                .distinct()
            spinnerResolution.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val currentRes = "${current?.width}x${current?.height}"
            val resIdx = resolutions.indexOf(currentRes).takeIf { it >= 0 } ?: 0
            spinnerResolution.setSelection(resIdx)
        }

        fun refreshFrameRates(selectedFormat: String, selectedResolution: String) {
            val frameRates = formats
                .filter { it.fourccFormat == selectedFormat && "${it.width}x${it.height}" == selectedResolution }
                .map { "${it.fps}" }
                .distinct()
            spinnerFrameRate.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, frameRates)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val currentFps = "${current?.fps}"
            val fpsIdx = frameRates.indexOf(currentFps).takeIf { it >= 0 } ?: 0
            spinnerFrameRate.setSelection(fpsIdx)
        }

        // Format spinner
        spinnerCapture.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, formatLabels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val currentFormatIdx = formatLabels.indexOf(current?.fourccFormat).coerceAtLeast(0)
        spinnerCapture.setSelection(currentFormatIdx)

        // Initial cascading population
        val initialFormat = formatLabels.getOrElse(currentFormatIdx) { "" }
        refreshResolutions(initialFormat)
        val initialResolution = spinnerResolution.selectedItem?.toString() ?: "${current?.width}x${current?.height}"
        refreshFrameRates(initialFormat, initialResolution)

        // Cascading listeners
        spinnerCapture.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedFormat = formatLabels[position]
                refreshResolutions(selectedFormat)
                val selectedResolution = spinnerResolution.selectedItem?.toString() ?: return
                refreshFrameRates(selectedFormat, selectedResolution)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedFormat = spinnerCapture.selectedItem?.toString() ?: return
                val selectedResolution = spinnerResolution.selectedItem?.toString() ?: return
                refreshFrameRates(selectedFormat, selectedResolution)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // --- Capture Audio: Channel spinner ---
        val channelOptions = arrayOf("2.0", "5.1", "7.1")
        spinnerAudioChannel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, channelOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerAudioChannel.setSelection(channelOptions.indexOf(captureAudioChannel).coerceAtLeast(0))

        val tvChannelWarning = view.findViewById<TextView>(R.id.tvChannelWarning)

        // Helper: check if selected channel count is supported by currently connected device
        fun checkChannelCompatibility(selectedChannel: String) {
            val desiredCh = when (selectedChannel) {
                "5.1" -> 6
                "7.1" -> 8
                else -> 2
            }
            val audioConn = when (val s = UsbMonitor.usbDeviceState) {
                is UsbDeviceState.Connected -> s.audioStreamingConnection
                is UsbDeviceState.Streaming -> s.audioStreamingConnection
                is UsbDeviceState.StreamingStopped -> s.audioStreamingConnection
                else -> null
            }
            val supportedMax = audioConn?.allAudioFormats?.maxOfOrNull { it.channelCount } ?: 0
            if (desiredCh > supportedMax && supportedMax > 0) {
                tvChannelWarning.visibility = View.VISIBLE
                tvChannelWarning.text = "Device does not support $selectedChannel (max ${supportedMax}ch). " +
                        "Will fallback on reconnect. Audio monitoring still works."
            } else if (supportedMax == 0) {
                tvChannelWarning.visibility = View.VISIBLE
                tvChannelWarning.text = "Device not connected. Selection will apply on next connection."
            } else {
                tvChannelWarning.visibility = View.GONE
                tvChannelWarning.text = ""
            }
        }

        // Show audio info from capture card (read-only)
        if (audioSampleRate > 0) {
            tvAudioInfo.text = "Sample Rate: ${audioSampleRate}Hz | Bit Depth: ${audioBitsPerSample}bit | Ch: ${audioChannels}"
        } else {
            tvAudioInfo.text = "Sample Rate: -- | Bit Depth: -- (no audio device)"
        }

        // Initial compatibility check
        checkChannelCompatibility(captureAudioChannel)

        // Recording Settings: static options
        val recVideoOptions = arrayOf("H264", "Copy")
        spinnerRecVideo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, recVideoOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerRecVideo.setSelection(recVideoOptions.indexOf(recVideoFormat).coerceAtLeast(0))

        val recAudioOptions = arrayOf("LPCM", "Copy")
        spinnerRecAudio.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, recAudioOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerRecAudio.setSelection(recAudioOptions.indexOf(recAudioFormat).coerceAtLeast(0))

        fun updateMuxOptions(videoFormat: String) {
            val muxOptions = if (videoFormat == "Copy") {
                arrayOf("AVI")
            } else {
                arrayOf("MP4")
            }
            spinnerMux.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, muxOptions)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            // Reset selection based on valid options
            val defaultMux = if (videoFormat == "Copy") "AVI" else "MP4"
            spinnerMux.setSelection(0)
        }

        // Set initial mux options based on current video format
        updateMuxOptions(recVideoFormat)

        spinnerRecVideo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                updateMuxOptions(recVideoOptions[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Preview info checkboxes
        cbShowPreviewInfo.isChecked = showPreviewInfo
        cbShowAudioLevelMeter.isChecked = showAudioLevelMeter

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title))
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selectedFormat = spinnerCapture.selectedItem?.toString()
                val selectedResolution = spinnerResolution.selectedItem?.toString()
                val selectedFps = spinnerFrameRate.selectedItem?.toString()?.toIntOrNull()
                val matched = formats.find {
                    it.fourccFormat == selectedFormat &&
                    "${it.width}x${it.height}" == selectedResolution &&
                    it.fps == selectedFps
                }
                if (matched != null && matched != currentVideoFormat) {
                    currentVideoFormat = matched
                    UvcManager.selectVideoFormat(matched)
                    Log.i(TAG, "Video format changed to: $matched")
                    Toast.makeText(this, "Format: ${matched.label()}", Toast.LENGTH_SHORT).show()
                    if (isCameraConnected) {
                        restartVideoStreaming()
                    }
                }
                recVideoFormat = spinnerRecVideo.selectedItem?.toString() ?: "Copy"
                recAudioFormat = spinnerRecAudio.selectedItem?.toString() ?: "Copy"
                recMux = spinnerMux.selectedItem?.toString() ?: "AVI"

                // Save audio channel selection
                val newChannel = spinnerAudioChannel.selectedItem?.toString() ?: "2.0"
                if (newChannel != captureAudioChannel) {
                    captureAudioChannel = newChannel
                    saveCaptureAudioChannel(newChannel)
                    val chCount = when (newChannel) {
                        "5.1" -> 6
                        "7.1" -> 8
                        else -> 2
                    }
                    audioLevelMeter.setChannelCount(chCount)
                    UsbMonitor.desiredAudioChannelCount = chCount
                    FileLogger.log(TAG, "Capture audio channel changed to $newChannel ($chCount ch)")
                    Toast.makeText(this, "Audio: $newChannel channel — reconnect device to apply", Toast.LENGTH_LONG).show()
                }

                showPreviewInfo = cbShowPreviewInfo.isChecked
                showAudioLevelMeter = cbShowAudioLevelMeter.isChecked
                savePreviewInfoSettings()
                updateUIControls()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showXbmcAudioDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_xbmc_audio, null)
        val settings = xbmcAudioManager.getSettings()
        val caps = xbmcAudioManager.probeCapabilities()

        // Speaker status banner
        val tvSpeakerStatus = view.findViewById<android.widget.TextView>(R.id.tvSpeakerStatus)
        tvSpeakerStatus.text = if (caps.hasSpeaker) {
            "Built-in speaker detected"
        } else {
            "No built-in speaker — audio will route via HDMI/SPDIF"
        }
        tvSpeakerStatus.setTextColor(
            if (caps.hasSpeaker) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_orange_dark)
        )

        // --- Audio Decoder section ---
        val spinnerOutputDevice = view.findViewById<Spinner>(R.id.spinnerOutputDevice)
        val deviceNames = xbmcAudioManager.getOutputDeviceNames()
        spinnerOutputDevice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerOutputDevice.setSelection(
            deviceNames.indexOf(settings.outputDevice.displayName).coerceAtLeast(0)
        )

        val spinnerChannelCount = view.findViewById<Spinner>(R.id.spinnerChannelCount)
        val channelOptions = XbmcAudioSettings.ChannelConfig.entries.map { it.displayName }
        spinnerChannelCount.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, channelOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerChannelCount.setSelection(settings.channelCount.ordinal)

        val spinnerOutputConfig = view.findViewById<Spinner>(R.id.spinnerOutputConfig)
        val outputConfigOptions = XbmcAudioSettings.OutputConfig.entries.map { it.displayName }
        spinnerOutputConfig.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, outputConfigOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerOutputConfig.setSelection(settings.outputConfig.ordinal)

        // Volume steps
        val seekVolumeSteps = view.findViewById<android.widget.SeekBar>(R.id.seekVolumeSteps)
        val tvVolumeSteps = view.findViewById<android.widget.TextView>(R.id.tvVolumeSteps)
        seekVolumeSteps.progress = settings.volumeSteps
        tvVolumeSteps.text = "${settings.volumeSteps}"
        seekVolumeSteps.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                tvVolumeSteps.text = "$progress"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        // Switches
        val switchKeepVolume = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchKeepVolume)
        switchKeepVolume.isChecked = settings.keepOriginalVolume

        val switchStereoUpmix = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchStereoUpmix)
        switchStereoUpmix.isChecked = settings.stereoUpmix

        val spinnerResampleQuality = view.findViewById<Spinner>(R.id.spinnerResampleQuality)
        val resampleOptions = XbmcAudioSettings.ResampleQuality.entries.map { it.displayName }
        spinnerResampleQuality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resampleOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerResampleQuality.setSelection(settings.resampleQuality.ordinal)

        // Keep alive
        val spinnerKeepAlive = view.findViewById<Spinner>(R.id.spinnerKeepAlive)
        val keepAliveOptions = arrayOf("Off", "1 min", "2 min", "5 min", "10 min")
        val keepAliveValues = intArrayOf(0, 1, 2, 5, 10)
        spinnerKeepAlive.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, keepAliveOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerKeepAlive.setSelection(
            keepAliveValues.indexOf(settings.keepAliveMinutes).coerceAtLeast(0)
        )

        val switchLowNoise = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchLowNoise)
        switchLowNoise.isChecked = settings.sendLowVolumeNoise

        val spinnerUiSounds = view.findViewById<Spinner>(R.id.spinnerUiSounds)
        val uiSoundOptions = XbmcAudioSettings.UiSoundMode.entries.map { it.displayName }
        spinnerUiSounds.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, uiSoundOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerUiSounds.setSelection(settings.playUiSounds.ordinal)

        // --- Passthrough section ---
        val switchPassthrough = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPassthrough)
        val spinnerPassthroughDevice = view.findViewById<Spinner>(R.id.spinnerPassthroughDevice)
        val switchAC3 = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAC3)
        val switchAC3Transcoding = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchAC3Transcoding)
        val switchDTS = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchDTS)
        val switchDTSHD = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchDTSHD)

        switchPassthrough.isChecked = settings.enablePassthrough
        switchAC3.isChecked = settings.enableAC3
        switchAC3Transcoding.isChecked = settings.enableAC3Transcoding
        switchDTS.isChecked = settings.enableDTS
        switchDTSHD.isChecked = settings.enableDTSHD

        spinnerPassthroughDevice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerPassthroughDevice.setSelection(
            deviceNames.indexOf(settings.passthroughDevice.displayName).coerceAtLeast(0)
        )

        // Enable/disable passthrough sub-options based on toggle
        fun updatePassthroughUI(enabled: Boolean) {
            spinnerPassthroughDevice.isEnabled = enabled
            switchAC3.isEnabled = enabled && caps.supportsAC3
            switchAC3Transcoding.isEnabled = enabled && caps.supportsAC3
            switchDTS.isEnabled = enabled && caps.supportsDTS
            switchDTSHD.isEnabled = enabled && caps.supportsDTSHD
            val alpha = if (enabled) 1.0f else 0.4f
            view.findViewById<View>(R.id.tvPassthroughDevice).alpha = alpha
            view.findViewById<View>(R.id.layoutAC3).alpha = if (enabled && caps.supportsAC3) 1.0f else 0.4f
            view.findViewById<View>(R.id.layoutAC3Transcoding).alpha = if (enabled && caps.supportsAC3) 1.0f else 0.4f
            view.findViewById<View>(R.id.layoutDTS).alpha = if (enabled && caps.supportsDTS) 1.0f else 0.4f
            view.findViewById<View>(R.id.layoutDTSHD).alpha = if (enabled && caps.supportsDTSHD) 1.0f else 0.4f
        }
        updatePassthroughUI(settings.enablePassthrough)
        switchPassthrough.setOnCheckedChangeListener { _, isChecked -> updatePassthroughUI(isChecked) }

        // Reset defaults button
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnResetDefaults).setOnClickListener {
            val defaults = XbmcAudioSettings()
            seekVolumeSteps.progress = defaults.volumeSteps
            switchKeepVolume.isChecked = defaults.keepOriginalVolume
            switchStereoUpmix.isChecked = defaults.stereoUpmix
            spinnerResampleQuality.setSelection(defaults.resampleQuality.ordinal)
            spinnerKeepAlive.setSelection(keepAliveValues.indexOf(defaults.keepAliveMinutes).coerceAtLeast(0))
            switchLowNoise.isChecked = defaults.sendLowVolumeNoise
            spinnerUiSounds.setSelection(defaults.playUiSounds.ordinal)
            switchPassthrough.isChecked = defaults.enablePassthrough
            switchAC3.isChecked = defaults.enableAC3
            switchAC3Transcoding.isChecked = defaults.enableAC3Transcoding
            switchDTS.isChecked = defaults.enableDTS
            switchDTSHD.isChecked = defaults.enableDTSHD
            spinnerChannelCount.setSelection(defaults.channelCount.ordinal)
            spinnerOutputConfig.setSelection(defaults.outputConfig.ordinal)
            Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show()
        }

        // Capabilities summary
        val tvCapabilities = view.findViewById<android.widget.TextView>(R.id.tvCapabilities)
        tvCapabilities.text = xbmcAudioManager.getCapabilitiesSummary()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_xbmc_audio))
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newSettings = settings.copy(
                    outputDevice = XbmcAudioSettings.AudioOutputDevice.entries.find {
                        it.displayName == spinnerOutputDevice.selectedItem?.toString()
                    } ?: XbmcAudioSettings.AudioOutputDevice.AUDIOTRACK_RAW,
                    channelCount = XbmcAudioSettings.ChannelConfig.entries[spinnerChannelCount.selectedItemPosition],
                    outputConfig = XbmcAudioSettings.OutputConfig.entries[spinnerOutputConfig.selectedItemPosition],
                    volumeSteps = seekVolumeSteps.progress.coerceIn(1, 100),
                    keepOriginalVolume = switchKeepVolume.isChecked,
                    stereoUpmix = switchStereoUpmix.isChecked,
                    resampleQuality = XbmcAudioSettings.ResampleQuality.entries[spinnerResampleQuality.selectedItemPosition],
                    keepAliveMinutes = keepAliveValues[spinnerKeepAlive.selectedItemPosition],
                    sendLowVolumeNoise = switchLowNoise.isChecked,
                    playUiSounds = XbmcAudioSettings.UiSoundMode.entries[spinnerUiSounds.selectedItemPosition],
                    enablePassthrough = switchPassthrough.isChecked,
                    passthroughDevice = XbmcAudioSettings.AudioOutputDevice.entries.find {
                        it.displayName == spinnerPassthroughDevice.selectedItem?.toString()
                    } ?: XbmcAudioSettings.AudioOutputDevice.AUDIOTRACK_RAW,
                    enableAC3 = switchAC3.isChecked,
                    enableAC3Transcoding = switchAC3Transcoding.isChecked,
                    enableDTS = switchDTS.isChecked,
                    enableDTSHD = switchDTSHD.isChecked,
                )
                xbmcAudioManager.updateSettings(newSettings)
                Toast.makeText(this, "xbmc-Audio settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startVideoFpsMonitor() {
        lifecycleScope.launch {
            var lastFpsLogTime = 0L
            while (isCameraConnected) {
                val fps = try {
                    UsbVideoNativeLibrary.getNativeVideoFps().toFloat()
                } catch (_: Exception) {
                    0f
                }
                val now = System.currentTimeMillis()
                if (now - lastFpsLogTime >= 1000) {
                    lastFpsLogTime = now
                    val fmt = currentVideoFormat
                    if (fmt != null) {
                        FileLogger.log(TAG, "previewFps measured=${String.format("%.2f", fps)}, current={format=${fmt.fourccFormat},size=${fmt.width}x${fmt.height},fps=${fmt.fps}}")
                    }
                }
                runOnUiThread { updatePreviewInfo(fps) }
                delay(200)
            }
        }
    }

    private fun startAudioLevelMonitor() {
        lifecycleScope.launch {
            var diagCount = 0
            while (isCameraConnected) {
                val levels = try {
                    UsbVideoNativeLibrary.getNativeAudioLevels()
                } catch (_: Exception) {
                    floatArrayOf(0f, 0f)
                }
                runOnUiThread { audioLevelMeter.setLevels(levels) }

                // Diagnostic: log per-channel levels every 2 seconds for first 20 samples
                diagCount++
                if (diagCount <= 20 && diagCount % 2 == 0) {
                    val levelsStr = levels.mapIndexed { i, v -> "ch$i=%.4f".format(v) }.joinToString(" ")
                    FileLogger.log(TAG, "AudioLevels[${levels.size}ch] #$diagCount: $levelsStr")
                }

                delay(50)
            }
        }
    }

    private fun showDeviceListDialog() {
        val dialog = DeviceListDialogFragment()
        dialog.onDeviceSelected = { device ->
            FileLogger.log(TAG, "Device selected from list: ${device.productName}")
            userEjected = false
            wasReselected = true
            if (!isCameraConnected) {
                startUsbDevicePolling()
                if (UsbPermission.hasPermission(this, device)) {
                    UsbMonitor.setState(UsbDeviceState.Attached(device))
                } else {
                    UsbPermission.requestPermission(this, device)
                }
            }
        }
        dialog.show(supportFragmentManager, DeviceListDialogFragment.TAG)
    }

    private fun safelyEject() {
        if (isRecording) {
            toggleRecord(false)
        }
        userEjected = true
        usbPollingJob?.cancel()
        // Immediately stop local audio playback
        usbAudioPlayer.stop()
        val state = UsbMonitor.usbDeviceState
        if (state is UsbDeviceState.Streaming || state is UsbDeviceState.StreamingStopped || state is UsbDeviceState.Connected) {
            lifecycleScope.launch {
                EventLooper.call {
                    // Always stop and disconnect native streaming regardless of state
                    UsbVideoNativeLibrary.stopUsbAudioStreamingNative()
                    UsbVideoNativeLibrary.stopUsbVideoStreamingNative()
                    UsbVideoNativeLibrary.disconnectUsbAudioStreamingNative()
                    UsbVideoNativeLibrary.disconnectUsbVideoStreamingNative()
                    UsbMonitor.disconnect()
                }
                onDeviceDisconnected()
                UsbMonitor.setState(UsbDeviceState.NotFound)
            }
        }
    }
}
