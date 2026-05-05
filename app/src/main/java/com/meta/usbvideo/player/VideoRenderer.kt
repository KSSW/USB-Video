package com.meta.usbvideo.player

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView

private const val TAG = "VideoRenderer"
private const val FPS_SAMPLE_INTERVAL_MS = 1000L

/**
 * Manages video preview rendering on a TextureView.
 * Tracks frame rate and provides surface lifecycle callbacks.
 * Overlay updates are done externally (no onSurfaceTextureUpdated work).
 */
class VideoRenderer {

    interface Callback {
        fun onSurfaceAvailable(surface: Surface)
        fun onSurfaceDestroyed()
        fun onFrameRendered(fps: Float)
    }

    private var surface: Surface? = null
    private var callback: Callback? = null

    private var fpsSampleStartTime = 0L
    private var frameCount = 0
    private var currentFps = 0f

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun attachToTextureView(textureView: TextureView) {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "onSurfaceTextureAvailable: ${width}x${height}")
                surfaceTexture.setDefaultBufferSize(width, height)
                surface?.release()
                surface = Surface(surfaceTexture)
                callback?.onSurfaceAvailable(surface!!)
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "onSurfaceTextureSizeChanged: ${width}x${height}")
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                Log.d(TAG, "onSurfaceTextureDestroyed")
                callback?.onSurfaceDestroyed()
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                // Intentionally empty: overlay/FPS updates are done externally
                // to avoid blocking the rendering thread.
            }
        }
    }

    fun getSurface(): Surface? = surface

    fun getCurrentFps(): Float = currentFps

    fun captureFrame(textureView: TextureView): Bitmap? {
        return textureView.bitmap
    }

    fun resetFpsCounter() {
        fpsSampleStartTime = 0L
        frameCount = 0
        currentFps = 0f
    }

    fun release() {
        surface?.release()
        surface = null
        callback = null
        resetFpsCounter()
    }

    private fun updateFrameRate() {
        val now = SystemClock.elapsedRealtime()
        if (fpsSampleStartTime == 0L) {
            fpsSampleStartTime = now
            frameCount = 0
        }
        frameCount++
        val elapsed = now - fpsSampleStartTime
        if (elapsed >= FPS_SAMPLE_INTERVAL_MS) {
            currentFps = frameCount * 1000f / elapsed
            fpsSampleStartTime = now
            frameCount = 0
            callback?.onFrameRendered(currentFps)
        }
    }
}
