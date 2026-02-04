package com.lensdaemon.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Wrapper around Android Camera2 API for simplified camera management.
 * Handles camera enumeration, session management, and frame capture.
 */
class LensDaemonCameraManager(private val context: Context) {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // State flows
    private val _cameraState = MutableStateFlow(CameraState.CLOSED)
    val cameraState: StateFlow<CameraState> = _cameraState

    private val _currentLens = MutableStateFlow<CameraLens?>(null)
    val currentLens: StateFlow<CameraLens?> = _currentLens

    private val _frameFlow = MutableSharedFlow<CameraFrame>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frameFlow: SharedFlow<CameraFrame> = _frameFlow

    // Available lenses cache
    private var _availableLenses: List<CameraLens> = emptyList()
    val availableLenses: List<CameraLens> get() = _availableLenses

    // Current configuration
    private var currentConfig = CaptureConfig()
    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null

    init {
        enumerateCameras()
    }

    /**
     * Enumerate all available cameras and categorize by lens type.
     */
    private fun enumerateCameras() {
        val lenses = mutableListOf<CameraLens>()

        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: continue

                // Only consider back-facing cameras for streaming
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val hasOis = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) ?: false

                val focalLength = focalLengths?.firstOrNull() ?: 0f
                val aperture = apertures?.firstOrNull() ?: 0f

                val streamConfigMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ) ?: continue

                val resolutions = streamConfigMap.getOutputSizes(SurfaceTexture::class.java)
                    ?.toList() ?: emptyList()

                val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

                // Determine lens type based on focal length
                val lensType = classifyLensType(focalLength, characteristics)

                // Check for physical cameras (multi-camera support)
                val physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    characteristics.physicalCameraIds.toList()
                } else {
                    emptyList()
                }

                // For logical cameras with multiple physical cameras
                if (physicalCameraIds.isNotEmpty()) {
                    // This is a logical camera - add physical cameras
                    for (physicalId in physicalCameraIds) {
                        try {
                            val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                            val physicalFocalLengths = physicalChars.get(
                                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                            )
                            val physicalFocal = physicalFocalLengths?.firstOrNull() ?: focalLength
                            val physicalType = classifyLensType(physicalFocal, physicalChars)

                            lenses.add(
                                CameraLens(
                                    cameraId = cameraId,
                                    lensFacing = facing,
                                    lensType = physicalType,
                                    focalLength = physicalFocal,
                                    aperture = aperture,
                                    hasOis = hasOis,
                                    supportedResolutions = resolutions,
                                    maxZoom = maxZoom,
                                    physicalCameraId = physicalId
                                )
                            )
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to get physical camera characteristics for $physicalId")
                        }
                    }
                } else {
                    // Single camera
                    lenses.add(
                        CameraLens(
                            cameraId = cameraId,
                            lensFacing = facing,
                            lensType = lensType,
                            focalLength = focalLength,
                            aperture = aperture,
                            hasOis = hasOis,
                            supportedResolutions = resolutions,
                            maxZoom = maxZoom
                        )
                    )
                }
            }

            // Sort by focal length (wide to telephoto)
            _availableLenses = lenses.sortedBy { it.focalLength }

            Timber.i("Found ${_availableLenses.size} camera lenses:")
            _availableLenses.forEach { lens ->
                Timber.i("  - ${lens.lensType.displayName}: ${lens.focalLength}mm (${lens.cameraId})")
            }

        } catch (e: CameraAccessException) {
            Timber.e(e, "Failed to enumerate cameras")
        }
    }

    /**
     * Classify lens type based on focal length.
     */
    private fun classifyLensType(focalLength: Float, characteristics: CameraCharacteristics): LensType {
        // Get sensor size for 35mm equivalent calculation
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val cropFactor = if (sensorSize != null) {
            // 35mm full frame is 36mm x 24mm, diagonal ~43.3mm
            val sensorDiagonal = kotlin.math.sqrt(
                sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height
            )
            43.3f / sensorDiagonal
        } else {
            // Assume typical smartphone crop factor
            6.0f
        }

        val equivalent35mm = focalLength * cropFactor

        return when {
            equivalent35mm < 18 -> LensType.ULTRA_WIDE
            equivalent35mm < 28 -> LensType.WIDE
            equivalent35mm < 50 -> LensType.MAIN
            equivalent35mm < 100 -> LensType.TELEPHOTO
            else -> LensType.SUPER_TELEPHOTO
        }
    }

    /**
     * Get capabilities for a specific camera.
     */
    fun getCameraCapabilities(cameraId: String): CameraCapabilities? {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return null

            val hardwareLevel = characteristics.get(
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
            ) ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

            val resolutions = streamConfigMap.getOutputSizes(SurfaceTexture::class.java)?.toList()
                ?: emptyList()

            // Get supported frame rates for 1080p
            val fpsRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ) ?: emptyArray()
            val frameRates = fpsRanges.map { it.upper }.distinct().sorted()

            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()

            val exposureRange = characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
            ) ?: Range(0, 0)

            val exposureStep = characteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP
            )?.toFloat() ?: 0f

            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?: intArrayOf()

            val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?: intArrayOf()

            CameraCapabilities(
                cameraId = cameraId,
                hardwareLevel = hardwareLevel,
                supportedResolutions = resolutions,
                supportedFrameRates = frameRates,
                supportsManualSensor = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                ),
                supportsManualPostProcessing = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
                ),
                supportsRaw = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
                ),
                supportsLogicalMultiCamera = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        capabilities.contains(
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                        ),
                physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    characteristics.physicalCameraIds.toList()
                } else emptyList(),
                maxZoom = characteristics.get(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
                ) ?: 1f,
                exposureCompensationRange = exposureRange.lower..exposureRange.upper,
                exposureCompensationStep = exposureStep,
                supportedFocusModes = afModes.mapNotNull { mode ->
                    FocusMode.entries.find { it.camera2Mode == mode }
                },
                supportedWhiteBalanceModes = awbModes.mapNotNull { mode ->
                    WhiteBalanceMode.entries.find { it.camera2Mode == mode }
                },
                hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
                hasOis = characteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
                )?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) ?: false
            )
        } catch (e: CameraAccessException) {
            Timber.e(e, "Failed to get camera capabilities for $cameraId")
            null
        }
    }

    /**
     * Start the camera handler thread.
     */
    private fun startCameraThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("CameraThread").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    /**
     * Stop the camera handler thread.
     */
    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            Timber.e(e, "Interrupted while stopping camera thread")
        }
    }

    /**
     * Open a camera by lens type.
     */
    @SuppressLint("MissingPermission")
    suspend fun openCamera(lensType: LensType): Boolean {
        val lens = _availableLenses.find { it.lensType == lensType }
            ?: _availableLenses.find { it.lensType == LensType.MAIN }
            ?: _availableLenses.firstOrNull()

        if (lens == null) {
            Timber.e("No camera available")
            return false
        }

        return openCamera(lens)
    }

    /**
     * Open a specific camera lens.
     */
    @SuppressLint("MissingPermission")
    suspend fun openCamera(lens: CameraLens): Boolean {
        Timber.i("Opening camera: ${lens.lensType.displayName} (${lens.cameraId})")

        // Close existing camera if open
        closeCamera()

        startCameraThread()
        _cameraState.value = CameraState.OPENING

        return suspendCoroutine { continuation ->
            try {
                cameraManager.openCamera(
                    lens.cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            Timber.i("Camera opened: ${lens.cameraId}")
                            cameraDevice = camera
                            _currentLens.value = lens
                            _cameraState.value = CameraState.OPENED
                            continuation.resume(true)
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            Timber.w("Camera disconnected: ${lens.cameraId}")
                            camera.close()
                            cameraDevice = null
                            _currentLens.value = null
                            _cameraState.value = CameraState.CLOSED
                            if (continuation.context.isActive) {
                                continuation.resume(false)
                            }
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            val errorType = when (error) {
                                ERROR_CAMERA_IN_USE -> CameraError.CAMERA_IN_USE
                                ERROR_MAX_CAMERAS_IN_USE -> CameraError.MAX_CAMERAS_IN_USE
                                ERROR_CAMERA_DISABLED -> CameraError.CAMERA_DISABLED
                                ERROR_CAMERA_DEVICE -> CameraError.CAMERA_DEVICE_ERROR
                                ERROR_CAMERA_SERVICE -> CameraError.CAMERA_SERVICE_ERROR
                                else -> CameraError.UNKNOWN
                            }
                            Timber.e("Camera error: ${errorType.message}")
                            camera.close()
                            cameraDevice = null
                            _cameraState.value = CameraState.ERROR
                            continuation.resumeWithException(
                                CameraException(errorType)
                            )
                        }
                    },
                    cameraHandler
                )
            } catch (e: CameraAccessException) {
                Timber.e(e, "Failed to open camera")
                _cameraState.value = CameraState.ERROR
                continuation.resumeWithException(e)
            } catch (e: SecurityException) {
                Timber.e(e, "Camera permission denied")
                _cameraState.value = CameraState.ERROR
                continuation.resumeWithException(
                    CameraException(CameraError.PERMISSION_DENIED)
                )
            }
        }
    }

    /**
     * Configure camera session with preview surface.
     */
    suspend fun startPreview(
        previewSurface: Surface,
        config: CaptureConfig = CaptureConfig()
    ): Boolean {
        val device = cameraDevice ?: run {
            Timber.e("Camera not opened")
            return false
        }

        this.previewSurface = previewSurface
        this.currentConfig = config

        _cameraState.value = CameraState.CONFIGURING

        // Create ImageReader for frame access
        imageReader?.close()
        imageReader = ImageReader.newInstance(
            config.resolution.width,
            config.resolution.height,
            ImageFormat.YUV_420_888,
            2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val frame = CameraFrame(
                        timestamp = image.timestamp,
                        width = image.width,
                        height = image.height,
                        format = image.format
                    )
                    scope.launch {
                        _frameFlow.emit(frame)
                    }
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }

        val surfaces = listOfNotNull(previewSurface, imageReader?.surface)

        return suspendCoroutine { continuation ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val outputConfigs = surfaces.map { OutputConfiguration(it) }
                    val sessionConfig = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputConfigs,
                        cameraExecutor,
                        createSessionCallback(continuation)
                    )
                    device.createCaptureSession(sessionConfig)
                } else {
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(
                        surfaces,
                        createSessionCallback(continuation),
                        cameraHandler
                    )
                }
            } catch (e: CameraAccessException) {
                Timber.e(e, "Failed to create capture session")
                _cameraState.value = CameraState.ERROR
                continuation.resumeWithException(e)
            }
        }
    }

    private fun createSessionCallback(
        continuation: kotlin.coroutines.Continuation<Boolean>
    ): CameraCaptureSession.StateCallback {
        return object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Timber.i("Capture session configured")
                captureSession = session
                _cameraState.value = CameraState.CONFIGURED

                // Start preview immediately
                try {
                    startRepeatingRequest()
                    _cameraState.value = CameraState.PREVIEWING
                    continuation.resume(true)
                } catch (e: CameraAccessException) {
                    Timber.e(e, "Failed to start preview")
                    _cameraState.value = CameraState.ERROR
                    continuation.resumeWithException(e)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Timber.e("Capture session configuration failed")
                _cameraState.value = CameraState.ERROR
                continuation.resumeWithException(
                    CameraException(CameraError.CONFIGURATION_FAILED)
                )
            }

            override fun onClosed(session: CameraCaptureSession) {
                Timber.i("Capture session closed")
                if (captureSession == session) {
                    captureSession = null
                }
            }
        }
    }

    /**
     * Start the repeating capture request for preview.
     */
    @Throws(CameraAccessException::class)
    private fun startRepeatingRequest() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val preview = previewSurface ?: return

        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            imageReader?.surface?.let { addTarget(it) }

            // Apply configuration
            set(CaptureRequest.CONTROL_AF_MODE, currentConfig.focusMode.camera2Mode)
            set(CaptureRequest.CONTROL_AWB_MODE, currentConfig.whiteBalance.camera2Mode)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentConfig.exposureCompensation)

            // Set zoom if supported
            if (currentConfig.zoomRatio != 1.0f) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, currentConfig.zoomRatio)
                }
                // For older APIs, would need to use SCALER_CROP_REGION
            }

            // Video stabilization
            when (currentConfig.stabilizationMode) {
                StabilizationMode.OPTICAL -> {
                    set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                }
                StabilizationMode.ELECTRONIC -> {
                    set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                }
                else -> {}
            }
        }

        session.setRepeatingRequest(
            requestBuilder.build(),
            null,
            cameraHandler
        )
    }

    /**
     * Update capture configuration.
     */
    fun updateConfig(config: CaptureConfig) {
        currentConfig = config
        if (_cameraState.value == CameraState.PREVIEWING ||
            _cameraState.value == CameraState.STREAMING) {
            try {
                startRepeatingRequest()
            } catch (e: CameraAccessException) {
                Timber.e(e, "Failed to update capture config")
            }
        }
    }

    /**
     * Add an encoder surface for streaming/recording.
     */
    suspend fun addEncoderSurface(surface: Surface): Boolean {
        this.encoderSurface = surface
        // Would need to reconfigure session with new surface
        // For now, just store it - full implementation in Phase 4
        return true
    }

    /**
     * Close the camera and release resources.
     */
    fun closeCamera() {
        Timber.i("Closing camera")

        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping capture session")
        }

        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewSurface = null
        encoderSurface = null

        _currentLens.value = null
        _cameraState.value = CameraState.CLOSED

        stopCameraThread()
    }

    /**
     * Release all resources.
     */
    fun release() {
        closeCamera()
        scope.cancel()
        cameraExecutor.shutdown()
    }
}

/**
 * Camera exception with specific error type.
 */
class CameraException(val error: CameraError) : Exception(error.message)
