package com.lensdaemon.camera

/**
 * Names a phone's lenses the way phones do, and finds the lens a request means.
 *
 * Phones call the main camera "1x": about 24mm (35mm equivalent) on current
 * models, with ultra-wides around 13–16mm and telephotos from about 50mm (2x)
 * to 230mm (10x). The buttons on the phone and in the dashboard ask for a lens
 * family ("wide", "main", "tele"); a phone may have an ultra-wide and no plain
 * wide lens, or a 5x and no 3x, so a family request takes the nearest lens in it.
 */
object LensSelection {

    private const val ULTRA_WIDE_BELOW_MM = 16f
    private const val WIDE_BELOW_MM = 20f
    private const val MAIN_BELOW_MM = 35f
    private const val TELEPHOTO_BELOW_MM = 100f

    /** The lens type for a 35mm-equivalent focal length, with the phone's ~24mm main camera as [LensType.MAIN]. */
    fun typeForEquivalentFocalLength(equivalentMm: Float): LensType = when {
        equivalentMm.isNaN() || equivalentMm <= 0f -> LensType.UNKNOWN
        equivalentMm < ULTRA_WIDE_BELOW_MM -> LensType.ULTRA_WIDE
        equivalentMm < WIDE_BELOW_MM -> LensType.WIDE
        equivalentMm < MAIN_BELOW_MM -> LensType.MAIN
        equivalentMm < TELEPHOTO_BELOW_MM -> LensType.TELEPHOTO
        else -> LensType.SUPER_TELEPHOTO
    }

    /**
     * The available lens type a name asks for: a [LensType] name ("ultra_wide",
     * "telephoto"…) or a family ("wide", "main", "tele"), nearest first within
     * the family. Null when the phone has no such lens or the name is unknown.
     */
    fun pick(name: String, available: Collection<LensType>): LensType? =
        candidatesFor(name)?.firstOrNull { it in available }

    /** The available lens type for [requested]: that type, else the nearest of its family. */
    fun pick(requested: LensType, available: Collection<LensType>): LensType? =
        (candidatesFor(requested.name) ?: listOf(requested)).firstOrNull { it in available }

    /** Whether [name] is a lens name or family the API understands, whether or not this phone has it. */
    fun isKnownName(name: String): Boolean = candidatesFor(name) != null

    /** The button a lens belongs under: "wide", "main", "tele", or "other". */
    fun familyOf(type: LensType): String = when (type) {
        LensType.ULTRA_WIDE, LensType.WIDE -> "wide"
        LensType.MAIN -> "main"
        LensType.TELEPHOTO, LensType.SUPER_TELEPHOTO -> "tele"
        LensType.UNKNOWN -> "other"
    }

    private fun candidatesFor(name: String): List<LensType>? =
        when (name.trim().lowercase().replace('-', '_')) {
            "wide" -> listOf(LensType.WIDE, LensType.ULTRA_WIDE)
            "ultrawide", "ultra_wide" -> listOf(LensType.ULTRA_WIDE, LensType.WIDE)
            "main", "primary", "default" -> listOf(LensType.MAIN)
            "tele", "telephoto", "zoom" -> listOf(LensType.TELEPHOTO, LensType.SUPER_TELEPHOTO)
            "super_telephoto", "supertele" -> listOf(LensType.SUPER_TELEPHOTO, LensType.TELEPHOTO)
            else -> null
        }
}
