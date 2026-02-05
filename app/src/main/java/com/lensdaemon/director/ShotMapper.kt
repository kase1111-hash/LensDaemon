package com.lensdaemon.director

import com.lensdaemon.camera.LensType
import timber.log.Timber

/**
 * Shot Mapper
 *
 * Translates parsed shot descriptions (WIDE, CLOSE-UP, etc.) into concrete
 * camera commands (lens selection, zoom level, focus mode).
 *
 * The mapper considers available hardware capabilities and calculates
 * optimal settings to achieve the desired shot composition.
 */
class ShotMapper(
    private val config: DirectorConfig = DirectorConfig.DEFAULT
) {
    companion object {
        private const val TAG = "ShotMapper"

        // Default focal length equivalents (35mm equivalent)
        const val WIDE_FOCAL_LENGTH = 16f      // Ultra-wide lens
        const val MAIN_FOCAL_LENGTH = 24f      // Main/wide lens
        const val TELEPHOTO_FOCAL_LENGTH = 70f // Telephoto lens

        // Zoom ranges for different shot types (relative to main lens)
        private val SHOT_ZOOM_RANGES = mapOf(
            ShotType.ESTABLISHING to 0.5f..1.0f,    // Use wide lens, minimal zoom
            ShotType.WIDE to 0.6f..1.2f,            // Wide lens preferred
            ShotType.FULL_SHOT to 1.0f..1.5f,       // Main lens, slight zoom
            ShotType.MEDIUM to 1.0f..2.0f,          // Main lens, moderate zoom
            ShotType.MEDIUM_CLOSE to 1.5f..2.5f,    // Main or telephoto
            ShotType.CLOSE_UP to 2.0f..3.5f,        // Telephoto preferred
            ShotType.EXTREME_CLOSE to 3.0f..5.0f,   // Telephoto with digital zoom
            ShotType.OVER_SHOULDER to 1.5f..2.5f,   // Telephoto for compression
            ShotType.CUSTOM to 1.0f..1.0f           // No change
        )
    }

    /**
     * Camera capabilities for shot mapping
     */
    data class CameraCapabilities(
        val hasWide: Boolean = true,
        val hasMain: Boolean = true,
        val hasTelephoto: Boolean = false,
        val wideFocalLength: Float = WIDE_FOCAL_LENGTH,
        val mainFocalLength: Float = MAIN_FOCAL_LENGTH,
        val telephotoFocalLength: Float = TELEPHOTO_FOCAL_LENGTH,
        val maxDigitalZoom: Float = 8.0f,
        val supportsSmoothZoom: Boolean = true,
        val supportsFaceDetection: Boolean = true,
        val supportsManualFocus: Boolean = true
    )

    /**
     * Mapped camera command to execute a shot
     */
    data class CameraCommand(
        val lens: LensType,
        val zoomLevel: Float,
        val focusMode: FocusTarget,
        val exposurePreset: ExposurePreset = ExposurePreset.AUTO,
        val transitionType: TransitionType = TransitionType.CUT,
        val transitionDurationMs: Long = 0,
        val notes: String = ""
    ) {
        /**
         * Check if this command differs significantly from another
         */
        fun differsFrom(other: CameraCommand): Boolean {
            return lens != other.lens ||
                    kotlin.math.abs(zoomLevel - other.zoomLevel) > 0.1f ||
                    focusMode != other.focusMode
        }
    }

    /**
     * Shot mapping result with command and metadata
     */
    data class MappingResult(
        val success: Boolean,
        val command: CameraCommand?,
        val cue: DirectorCue,
        val warnings: List<String> = emptyList(),
        val fallbackUsed: Boolean = false
    )

    private var capabilities = CameraCapabilities()
    private var currentCommand: CameraCommand? = null

    /**
     * Update available camera capabilities
     */
    fun updateCapabilities(capabilities: CameraCapabilities) {
        this.capabilities = capabilities
        Timber.tag(TAG).d("Updated capabilities: wide=${capabilities.hasWide}, tele=${capabilities.hasTelephoto}")
    }

    /**
     * Map a director cue to a camera command
     */
    fun mapCue(cue: DirectorCue): MappingResult {
        return when (cue.type) {
            CueType.SHOT -> mapShotCue(cue)
            CueType.TRANSITION -> mapTransitionCue(cue)
            CueType.FOCUS -> mapFocusCue(cue)
            CueType.EXPOSURE -> mapExposureCue(cue)
            CueType.CUT -> mapCutCue(cue)
            else -> MappingResult(
                success = false,
                command = null,
                cue = cue,
                warnings = listOf("Cue type ${cue.type} does not map to camera command")
            )
        }
    }

    /**
     * Map a shot type cue to camera settings
     */
    private fun mapShotCue(cue: DirectorCue): MappingResult {
        val shotType = cue.shotType ?: return MappingResult(
            success = false,
            command = null,
            cue = cue,
            warnings = listOf("Shot cue missing shot type")
        )

        val warnings = mutableListOf<String>()
        var fallbackUsed = false

        // Determine optimal lens and zoom for shot type
        val (lens, zoom) = selectLensAndZoom(shotType, warnings)

        // Determine focus mode based on shot type and cue
        val focusMode = cue.focusTarget ?: selectFocusModeForShot(shotType)

        // Check if we had to use fallbacks
        if (warnings.isNotEmpty()) {
            fallbackUsed = true
        }

        val command = CameraCommand(
            lens = lens,
            zoomLevel = zoom,
            focusMode = focusMode,
            transitionType = cue.transitionType ?: TransitionType.CUT,
            transitionDurationMs = cue.transitionDurationMs,
            notes = "Shot: ${shotType.name}"
        )

        currentCommand = command

        Timber.tag(TAG).d("Mapped shot ${shotType.name} -> lens=$lens, zoom=$zoom, focus=$focusMode")

        return MappingResult(
            success = true,
            command = command,
            cue = cue,
            warnings = warnings,
            fallbackUsed = fallbackUsed
        )
    }

    /**
     * Map a transition cue (zoom animation)
     */
    private fun mapTransitionCue(cue: DirectorCue): MappingResult {
        val transitionType = cue.transitionType ?: return MappingResult(
            success = false,
            command = null,
            cue = cue,
            warnings = listOf("Transition cue missing transition type")
        )

        val warnings = mutableListOf<String>()
        val current = currentCommand ?: getDefaultCommand()

        val command = when (transitionType) {
            TransitionType.PUSH_IN -> {
                // Zoom in by ~1.5x
                val newZoom = (current.zoomLevel * 1.5f).coerceAtMost(capabilities.maxDigitalZoom)
                current.copy(
                    zoomLevel = newZoom,
                    transitionType = TransitionType.PUSH_IN,
                    transitionDurationMs = cue.transitionDurationMs,
                    notes = "Push in to ${String.format("%.1f", newZoom)}x"
                )
            }
            TransitionType.PULL_BACK -> {
                // Zoom out by ~0.67x
                val newZoom = (current.zoomLevel * 0.67f).coerceAtLeast(0.5f)
                current.copy(
                    zoomLevel = newZoom,
                    transitionType = TransitionType.PULL_BACK,
                    transitionDurationMs = cue.transitionDurationMs,
                    notes = "Pull back to ${String.format("%.1f", newZoom)}x"
                )
            }
            TransitionType.RACK_FOCUS -> {
                // Toggle between face and background focus
                val newFocus = if (current.focusMode == FocusTarget.FACE) {
                    FocusTarget.BACKGROUND
                } else {
                    FocusTarget.FACE
                }
                current.copy(
                    focusMode = newFocus,
                    transitionType = TransitionType.RACK_FOCUS,
                    transitionDurationMs = cue.transitionDurationMs,
                    notes = "Rack focus to $newFocus"
                )
            }
            TransitionType.HOLD -> {
                current.copy(
                    transitionType = TransitionType.HOLD,
                    transitionDurationMs = cue.transitionDurationMs,
                    notes = "Hold current settings"
                )
            }
            TransitionType.CUT -> {
                current.copy(
                    transitionType = TransitionType.CUT,
                    transitionDurationMs = 0,
                    notes = "Cut"
                )
            }
        }

        if (!capabilities.supportsSmoothZoom && transitionType in listOf(TransitionType.PUSH_IN, TransitionType.PULL_BACK)) {
            warnings.add("Device may not support smooth zoom transitions")
        }

        currentCommand = command

        return MappingResult(
            success = true,
            command = command,
            cue = cue,
            warnings = warnings
        )
    }

    /**
     * Map a focus cue
     */
    private fun mapFocusCue(cue: DirectorCue): MappingResult {
        val focusTarget = cue.focusTarget ?: return MappingResult(
            success = false,
            command = null,
            cue = cue,
            warnings = listOf("Focus cue missing focus target")
        )

        val warnings = mutableListOf<String>()
        val current = currentCommand ?: getDefaultCommand()

        // Validate focus mode support
        if (focusTarget == FocusTarget.FACE && !capabilities.supportsFaceDetection) {
            warnings.add("Face detection not available, using auto focus")
        }
        if (focusTarget == FocusTarget.MANUAL && !capabilities.supportsManualFocus) {
            warnings.add("Manual focus not available, using auto focus")
        }

        val command = current.copy(
            focusMode = focusTarget,
            notes = "Focus: ${focusTarget.name}"
        )

        currentCommand = command

        return MappingResult(
            success = true,
            command = command,
            cue = cue,
            warnings = warnings
        )
    }

    /**
     * Map an exposure cue
     */
    private fun mapExposureCue(cue: DirectorCue): MappingResult {
        val exposurePreset = cue.exposurePreset ?: return MappingResult(
            success = false,
            command = null,
            cue = cue,
            warnings = listOf("Exposure cue missing preset")
        )

        val current = currentCommand ?: getDefaultCommand()

        val command = current.copy(
            exposurePreset = exposurePreset,
            notes = "Exposure: ${exposurePreset.name}"
        )

        currentCommand = command

        return MappingResult(
            success = true,
            command = command,
            cue = cue
        )
    }

    /**
     * Map a cut cue (instant shot change)
     */
    private fun mapCutCue(cue: DirectorCue): MappingResult {
        val shotType = cue.shotType ?: ShotType.MEDIUM
        val warnings = mutableListOf<String>()

        val (lens, zoom) = selectLensAndZoom(shotType, warnings)
        val focusMode = cue.focusTarget ?: selectFocusModeForShot(shotType)

        val command = CameraCommand(
            lens = lens,
            zoomLevel = zoom,
            focusMode = focusMode,
            transitionType = TransitionType.CUT,
            transitionDurationMs = 0,
            notes = "Cut to: ${shotType.name}"
        )

        currentCommand = command

        return MappingResult(
            success = true,
            command = command,
            cue = cue,
            warnings = warnings
        )
    }

    /**
     * Select optimal lens and zoom level for a shot type
     */
    private fun selectLensAndZoom(
        shotType: ShotType,
        warnings: MutableList<String>
    ): Pair<LensType, Float> {
        val zoomRange = SHOT_ZOOM_RANGES[shotType] ?: (1.0f..1.0f)
        val targetZoom = (zoomRange.start + zoomRange.endInclusive) / 2

        // Determine best lens based on target zoom
        return when {
            // Wide shots - prefer wide lens
            shotType in listOf(ShotType.ESTABLISHING, ShotType.WIDE) -> {
                if (capabilities.hasWide) {
                    Pair(LensType.WIDE, targetZoom.coerceIn(0.5f, 1.0f))
                } else {
                    warnings.add("Wide lens not available, using main lens")
                    Pair(LensType.MAIN, 1.0f)
                }
            }

            // Close shots - prefer telephoto
            shotType in listOf(ShotType.CLOSE_UP, ShotType.EXTREME_CLOSE, ShotType.OVER_SHOULDER) -> {
                if (capabilities.hasTelephoto) {
                    // Telephoto lens, use lower zoom
                    Pair(LensType.TELEPHOTO, targetZoom.coerceIn(1.0f, 2.0f))
                } else {
                    // Digital zoom on main lens
                    warnings.add("Telephoto lens not available, using digital zoom")
                    Pair(LensType.MAIN, targetZoom.coerceIn(1.0f, capabilities.maxDigitalZoom))
                }
            }

            // Medium shots - use main lens
            else -> {
                Pair(LensType.MAIN, targetZoom.coerceIn(1.0f, 2.0f))
            }
        }
    }

    /**
     * Select default focus mode based on shot type
     */
    private fun selectFocusModeForShot(shotType: ShotType): FocusTarget {
        return when (shotType) {
            ShotType.CLOSE_UP, ShotType.EXTREME_CLOSE, ShotType.MEDIUM_CLOSE -> {
                if (capabilities.supportsFaceDetection) FocusTarget.FACE else FocusTarget.AUTO
            }
            ShotType.ESTABLISHING, ShotType.WIDE -> FocusTarget.AUTO
            ShotType.OVER_SHOULDER -> FocusTarget.FACE
            else -> FocusTarget.AUTO
        }
    }

    /**
     * Get default camera command (used when no current state)
     */
    private fun getDefaultCommand(): CameraCommand {
        return CameraCommand(
            lens = LensType.MAIN,
            zoomLevel = 1.0f,
            focusMode = FocusTarget.AUTO,
            exposurePreset = ExposurePreset.AUTO
        )
    }

    /**
     * Get current camera command state
     */
    fun getCurrentCommand(): CameraCommand? = currentCommand

    /**
     * Reset mapper state
     */
    fun reset() {
        currentCommand = null
        Timber.tag(TAG).d("Shot mapper reset")
    }

    /**
     * Calculate the effective focal length for a command
     */
    fun calculateEffectiveFocalLength(command: CameraCommand): Float {
        val baseFocal = when (command.lens) {
            LensType.WIDE -> capabilities.wideFocalLength
            LensType.MAIN -> capabilities.mainFocalLength
            LensType.TELEPHOTO -> capabilities.telephotoFocalLength
        }
        return baseFocal * command.zoomLevel
    }

    /**
     * Get shot presets from config
     */
    fun getPresetForShot(shotType: ShotType): ShotPreset? {
        return config.shotPresets.values.find { it.shotType == shotType }
    }

    /**
     * Map a complete scene to a sequence of commands
     */
    fun mapScene(scene: DirectorScene): List<MappingResult> {
        reset()
        return scene.cues.map { cue -> mapCue(cue) }
    }

    /**
     * Validate that a script can be mapped with current capabilities
     */
    fun validateMapping(script: ParsedScript): List<String> {
        val issues = mutableListOf<String>()

        // Check for shots requiring unavailable hardware
        script.scenes.flatMap { it.cues }.forEach { cue ->
            if (cue.type == CueType.SHOT) {
                when (cue.shotType) {
                    ShotType.ESTABLISHING, ShotType.WIDE -> {
                        if (!capabilities.hasWide) {
                            issues.add("Scene at line ${cue.lineNumber}: Wide shot requested but wide lens not available")
                        }
                    }
                    ShotType.CLOSE_UP, ShotType.EXTREME_CLOSE -> {
                        if (!capabilities.hasTelephoto && capabilities.maxDigitalZoom < 3.0f) {
                            issues.add("Scene at line ${cue.lineNumber}: Close-up requested but telephoto/adequate zoom not available")
                        }
                    }
                    else -> { /* Main lens can handle most shots */ }
                }
            }

            if (cue.type == CueType.FOCUS && cue.focusTarget == FocusTarget.FACE && !capabilities.supportsFaceDetection) {
                issues.add("Scene at line ${cue.lineNumber}: Face focus requested but face detection not available")
            }
        }

        return issues
    }
}
