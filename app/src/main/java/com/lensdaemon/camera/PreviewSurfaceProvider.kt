package com.lensdaemon.camera

import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.graphics.SurfaceTexture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Manages preview surface lifecycle for camera display.
 * Supports both SurfaceView and TextureView.
 */
class PreviewSurfaceProvider {

    private val _surfaceState = MutableStateFlow<SurfaceState>(SurfaceState.Unavailable)
    val surfaceState: StateFlow<SurfaceState> = _surfaceState

    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null
    private var currentSurface: Surface? = null
    private var targetSize: Size = Size(1920, 1080)

    /**
     * Attach to a SurfaceView for preview display.
     */
    fun attachSurfaceView(view: SurfaceView) {
        detach()
        surfaceView = view

        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Timber.d("Surface created")
                currentSurface = holder.surface
                _surfaceState.value = SurfaceState.Available(
                    surface = holder.surface,
                    width = view.width,
                    height = view.height
                )
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Timber.d("Surface changed: ${width}x${height}")
                currentSurface = holder.surface
                _surfaceState.value = SurfaceState.Available(
                    surface = holder.surface,
                    width = width,
                    height = height
                )
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Timber.d("Surface destroyed")
                currentSurface = null
                _surfaceState.value = SurfaceState.Unavailable
            }
        })

        // If surface already exists
        if (view.holder.surface?.isValid == true) {
            currentSurface = view.holder.surface
            _surfaceState.value = SurfaceState.Available(
                surface = view.holder.surface,
                width = view.width,
                height = view.height
            )
        }
    }

    /**
     * Attach to a TextureView for preview display.
     */
    fun attachTextureView(view: TextureView) {
        detach()
        textureView = view

        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                Timber.d("TextureView surface available: ${width}x${height}")
                texture.setDefaultBufferSize(targetSize.width, targetSize.height)
                val surface = Surface(texture)
                currentSurface = surface
                _surfaceState.value = SurfaceState.Available(
                    surface = surface,
                    width = width,
                    height = height
                )
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                Timber.d("TextureView surface size changed: ${width}x${height}")
                currentSurface?.let { surface ->
                    _surfaceState.value = SurfaceState.Available(
                        surface = surface,
                        width = width,
                        height = height
                    )
                }
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                Timber.d("TextureView surface destroyed")
                currentSurface?.release()
                currentSurface = null
                _surfaceState.value = SurfaceState.Unavailable
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                // Frame update - no action needed
            }
        }

        // If surface already exists
        view.surfaceTexture?.let { texture ->
            texture.setDefaultBufferSize(targetSize.width, targetSize.height)
            val surface = Surface(texture)
            currentSurface = surface
            _surfaceState.value = SurfaceState.Available(
                surface = surface,
                width = view.width,
                height = view.height
            )
        }
    }

    /**
     * Set the target buffer size for the surface.
     */
    fun setTargetSize(size: Size) {
        targetSize = size
        textureView?.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
    }

    /**
     * Get the current surface if available.
     */
    fun getSurface(): Surface? = currentSurface

    /**
     * Check if surface is currently available.
     */
    fun isAvailable(): Boolean = _surfaceState.value is SurfaceState.Available

    /**
     * Detach from current view.
     */
    fun detach() {
        surfaceView = null
        textureView?.surfaceTextureListener = null
        textureView = null
        currentSurface = null
        _surfaceState.value = SurfaceState.Unavailable
    }

    /**
     * Calculate optimal preview size that fits the view while maintaining aspect ratio.
     */
    fun calculateOptimalSize(
        availableSizes: List<Size>,
        viewWidth: Int,
        viewHeight: Int,
        targetResolution: Size = Size(1920, 1080)
    ): Size {
        val targetRatio = targetResolution.width.toFloat() / targetResolution.height

        // Filter sizes that match the aspect ratio (with tolerance)
        val matchingSizes = availableSizes.filter { size ->
            val ratio = size.width.toFloat() / size.height
            kotlin.math.abs(ratio - targetRatio) < 0.1f
        }

        // Find the largest size that doesn't exceed the target resolution
        val suitableSizes = matchingSizes.filter {
            it.width <= targetResolution.width && it.height <= targetResolution.height
        }.sortedByDescending { it.width * it.height }

        return suitableSizes.firstOrNull()
            ?: matchingSizes.minByOrNull {
                kotlin.math.abs(it.width - targetResolution.width) +
                        kotlin.math.abs(it.height - targetResolution.height)
            }
            ?: availableSizes.firstOrNull()
            ?: targetResolution
    }

    /**
     * Calculate the transformation matrix for TextureView to handle aspect ratio.
     */
    fun calculateTransformMatrix(
        viewWidth: Int,
        viewHeight: Int,
        previewWidth: Int,
        previewHeight: Int
    ): android.graphics.Matrix {
        val matrix = android.graphics.Matrix()

        val viewRatio = viewWidth.toFloat() / viewHeight
        val previewRatio = previewWidth.toFloat() / previewHeight

        if (viewRatio > previewRatio) {
            // View is wider - scale to fill width
            val scale = viewWidth.toFloat() / previewWidth
            val scaledHeight = previewHeight * scale
            val dy = (viewHeight - scaledHeight) / 2
            matrix.setScale(scale, scale)
            matrix.postTranslate(0f, dy)
        } else {
            // View is taller - scale to fill height
            val scale = viewHeight.toFloat() / previewHeight
            val scaledWidth = previewWidth * scale
            val dx = (viewWidth - scaledWidth) / 2
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, 0f)
        }

        return matrix
    }
}

/**
 * State of the preview surface.
 */
sealed class SurfaceState {
    data object Unavailable : SurfaceState()

    data class Available(
        val surface: Surface,
        val width: Int,
        val height: Int
    ) : SurfaceState()
}
