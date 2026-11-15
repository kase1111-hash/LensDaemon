package com.lensdaemon.director

import android.os.SystemClock
import com.lensdaemon.camera.CameraService
import com.lensdaemon.camera.CameraState
import com.lensdaemon.camera.FocusMode
import com.lensdaemon.camera.LensSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Camera Controller Adapter
 *
 * Bridges the DirectorManager with CameraService, implementing the
 * CameraController interface to translate director commands to camera operations.
 *
 * This adapter lives in :app because it depends on CameraService and LensType.
 * The CameraController interface in :director uses Strings for lens names so the
 * module has no dependency on :app camera types.
 *
 * Commands are compared with the camera's own state (the open lens, the applied zoom,
 * the focus mode, the exposure compensation), not with what the adapter last asked for,
 * so a cue still acts after the camera was changed on the phone or the dashboard. Only a
 * lens switch or zoom animation still under way counts as where the camera is heading.
 * Those windows are measured on [SystemClock.elapsedRealtime], which setting the phone's
 * time doesn't move.
 */
class CameraControllerAdapter(
    private val cameraService: CameraService
) : DirectorManager.CameraController {

    companion object {
        private const val TAG = "CameraControllerAdapter"

        /** Zoom levels closer than this count as the same. */
        private const val ZOOM_TOLERANCE = 0.01f

        /**
         * How long a lens switch the camera accepted counts as under way. CameraService reopens
         * the camera in the background: until it starts, the old lens still shows as open.
         */
        private const val LENS_SWITCH_SETTLE_MS = 2_000L

        /**
         * How long after the director asks for an exposure preset other than AUTO it is put
         * back on a lens that opens. A lens change completes in the background after the
         * command that asked for it returns, and the new lens starts at 0 EV.
         */
        private const val EXPOSURE_KEEP_MS = 5_000L

        /** Exposure compensation of each preset, in EV, as [ExposurePreset] documents them. */
        private fun evFor(preset: ExposurePreset): Float = when (preset) {
            ExposurePreset.AUTO -> 0f
            ExposurePreset.BRIGHT -> 1f
            ExposurePreset.DARK -> -1f
            ExposurePreset.BACKLIT -> 2f
            ExposurePreset.SILHOUETTE -> -2f
        }
    }

    /** A focus target the adapter applied, with the lens family and camera focus mode it left. */
    private data class AppliedFocus(val target: FocusTarget, val lensFamily: String?, val mode: FocusMode)

    /** The lens family ("wide", "main", "tele") of the last switch the camera accepted, and when. */
    @Volatile
    private var lastAcceptedLens: String? = null

    @Volatile
    private var lastAcceptedAtMs = 0L

    /** The zoom an animation of ours is heading to, until [animationEndsAtMs]. */
    @Volatile
    private var animatingTo: Float? = null

    @Volatile
    private var animationEndsAtMs = 0L

    @Volatile
    private var appliedFocus: AppliedFocus? = null

    /** Keeps exposure changes in order: the director's calls and the ones [exposureKeeper] makes. */
    private val exposureLock = Any()

    /** The preset the director asked for last (under [exposureLock]), or null after [reset]. */
    private var wantedExposure: ExposurePreset? = null

    /** Puts [wantedExposure] back on lenses that open after it was asked for. */
    private val exposureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var exposureKeeper: Job? = null

    /**
     * Switch camera lens ("wide", "main", "tele"), unless the camera is on that family or
     * heading there. The last switch the camera accepted is where it is heading while no lens
     * is open (the camera reopens) and for [LENS_SWITCH_SETTLE_MS] after it was asked for,
     * since until the reopen starts the old lens still shows as open: cues that follow at once
     * don't reopen the camera again. Maps String → LensType at the module boundary.
     */
    override fun switchLens(lens: String) {
        val current = currentLensFamily()
        val now = SystemClock.elapsedRealtime()
        val switching = lastAcceptedLens?.takeIf { current == null || now - lastAcceptedAtMs < LENS_SWITCH_SETTLE_MS }
        val heading = switching ?: current
        if (lens == heading) return

        Timber.tag(TAG).d("Switching lens: ${heading ?: "(camera closed)"} -> $lens")
        if (cameraService.switchLens(lens)) {
            lastAcceptedLens = lens
            lastAcceptedAtMs = now
        } else {
            Timber.tag(TAG).w("The camera has no \"$lens\" lens")
        }
    }

    /**
     * Zoom to [level] (clamped to what the open lens allows), unless the camera is there
     * already or an animation of ours is still heading there.
     */
    override fun setZoom(level: Float, animated: Boolean, durationMs: Long) {
        val target = level.coerceIn(1f, cameraService.getMaxZoom().coerceAtLeast(1f))
        val now = SystemClock.elapsedRealtime()
        val heading = animatingTo?.takeIf { now < animationEndsAtMs }
        val reached = heading ?: cameraService.currentZoom.value
        if (abs(target - reached) <= ZOOM_TOLERANCE) return

        Timber.tag(TAG).d("Setting zoom: $reached -> $target (animated=$animated, duration=${durationMs}ms)")
        if (animated && durationMs > 0) {
            cameraService.animateZoom(target, durationMs)
            animatingTo = target
            animationEndsAtMs = now + durationMs
        } else {
            cameraService.setZoom(target)
            animatingTo = null
        }
    }

    /**
     * Apply a focus target, unless it is the one applied last and neither the lens family
     * nor the camera's focus mode has changed since.
     */
    override fun setFocusMode(target: FocusTarget) {
        val family = currentLensFamily()
        val applied = appliedFocus
        if (applied == AppliedFocus(target, family, cameraService.getConfig().focusMode)) return

        Timber.tag(TAG).d("Setting focus target: ${applied?.target} -> $target")
        when (target) {
            FocusTarget.AUTO -> {
                cameraService.setAutoFocus()
            }
            FocusTarget.FACE -> {
                // Continuous autofocus: the camera has no face priority yet
                cameraService.enableFaceDetectionFocus()
            }
            FocusTarget.HANDS -> {
                cameraService.triggerTapToFocus(0.5f, 0.7f, android.util.Size(1920, 1080))
            }
            FocusTarget.OBJECT -> {
                cameraService.triggerTapToFocus(0.5f, 0.5f, android.util.Size(1920, 1080))
            }
            FocusTarget.BACKGROUND -> {
                cameraService.setFocusDistance(0f)
            }
            FocusTarget.MANUAL -> {
                cameraService.lockFocus()
            }
        }
        appliedFocus = AppliedFocus(target, family, cameraService.getConfig().focusMode)
    }

    /**
     * Set the exposure compensation of a preset: AUTO 0, BRIGHT +1, DARK −1, BACKLIT +2 and
     * SILHOUETTE −2 EV, in the open lens's own steps and within its range, unless the camera
     * has it already. The director sends a preset other than AUTO with every command, and
     * AUTO only when a cue goes back to it. A preset other than AUTO also goes onto each lens
     * that opens in the next [EXPOSURE_KEEP_MS]: a lens switch the same command asked for is
     * still under way, and the new lens starts at 0 EV.
     */
    override fun setExposurePreset(preset: ExposurePreset) {
        synchronized(exposureLock) {
            wantedExposure = preset
            exposureKeeper?.cancel()
            applyExposure(preset)
            exposureKeeper = if (preset != ExposurePreset.AUTO) keepExposure(preset) else null
        }
    }

    /** Sets [preset]'s compensation on the open lens, unless the camera has it already (under [exposureLock]). */
    private fun applyExposure(preset: ExposurePreset) {
        val step = cameraService.getExposureCompensationStep()
        val steps = if (step > 0f) (evFor(preset) / step).roundToInt() else 0
        val range = cameraService.getExposureCompensationRange()
        val value = steps.coerceIn(range.lower, range.upper)
        if (cameraService.getConfig().exposureCompensation == value) return

        Timber.tag(TAG).d("Setting exposure preset: $preset ($value steps of $step EV)")
        cameraService.setExposureCompensation(value)
    }

    /**
     * For [EXPOSURE_KEEP_MS], each time the camera starts capturing (a lens opened, or the
     * session restarted) puts [preset] back if the camera lost it, while the director still
     * wants it.
     */
    private fun keepExposure(preset: ExposurePreset): Job = exposureScope.launch {
        withTimeoutOrNull(EXPOSURE_KEEP_MS) {
            combine(cameraService.getCameraState(), cameraService.getCurrentLens()) { state, lens ->
                lens != null && (state == CameraState.PREVIEWING || state == CameraState.STREAMING)
            }.collect { capturing ->
                if (capturing) {
                    synchronized(exposureLock) {
                        if (wantedExposure == preset) applyExposure(preset)
                    }
                }
            }
        }
    }

    override fun getCurrentTemperature(): Int {
        return cameraService.getCurrentCpuTemperature()
    }

    override fun getCapabilities(): ShotMapper.CameraCapabilities = getCameraCapabilities()

    fun getCameraCapabilities(): ShotMapper.CameraCapabilities {
        // The director's "wide" and "telephoto" are the lens families the phone
        // and dashboard buttons use: an ultra-wide or a 5x periscope counts
        val wide = cameraService.findLens("wide")
        val main = cameraService.findLens("main")
        val tele = cameraService.findLens("tele")

        return ShotMapper.CameraCapabilities(
            hasWide = wide != null,
            hasMain = main != null,
            hasTelephoto = tele != null,
            wideFocalLength = wide?.focalLength ?: 16f,
            mainFocalLength = main?.focalLength ?: 24f,
            telephotoFocalLength = tele?.focalLength ?: 70f,
            // The main lens's limit, read when the lenses were listed, holds before the camera opens
            maxDigitalZoom = main?.maxZoom?.takeIf { it > 1f } ?: cameraService.getMaxZoom(),
            supportsSmoothZoom = true,
            supportsFaceDetection = cameraService.supportsFaceDetection(),
            supportsManualFocus = cameraService.supportsManualFocus()
        )
    }

    fun isFocusLocked(): Boolean = cameraService.isFocusLocked()

    fun getNormalizedExposure(): Float = cameraService.getNormalizedExposure()

    fun getMotionShakiness(): Float = cameraService.getMotionShakiness()

    /** Forgets the lens request, zoom animation, focus target and exposure preset the adapter remembers. */
    fun reset() {
        lastAcceptedLens = null
        lastAcceptedAtMs = 0L
        animatingTo = null
        animationEndsAtMs = 0L
        appliedFocus = null
        synchronized(exposureLock) {
            wantedExposure = null
            exposureKeeper?.cancel()
            exposureKeeper = null
        }
    }

    /** The family of the open lens, or null while the camera is closed or reopening. */
    private fun currentLensFamily(): String? =
        cameraService.getCurrentLens().value?.lensType?.let(LensSelection::familyOf)
}

/**
 * Extension to create a MetricsSource from CameraControllerAdapter.
 *
 * Lives in :app (not :director) because CameraControllerAdapter depends on
 * CameraService; :director must not reference :app types.
 *
 * Focus lock is the camera's focus state. Exposure and motion are placeholders: the
 * configured exposure compensation (not a measurement) and a constant shake per
 * stabilisation mode. Audio isn't sampled.
 */
fun CameraControllerAdapter.asMetricsSource(): QualityMetricsCollector.MetricsSource {
    val adapter = this
    return object : QualityMetricsCollector.MetricsSource {
        override fun isFocusLocked(): Boolean = adapter.isFocusLocked()
        override fun getNormalizedExposure(): Float = adapter.getNormalizedExposure()
        override fun getMotionShakiness(): Float = adapter.getMotionShakiness()
        override fun getAudioLevel(): Float = 0f // Audio not implemented in camera adapter
    }
}
