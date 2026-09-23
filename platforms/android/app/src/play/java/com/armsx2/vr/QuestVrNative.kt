package com.armsx2.vr

/**
 * No-op twin of the quest flavour's XR bridge (src/quest/.../QuestVrNative.kt), so shared code can
 * call it without knowing the flavour. There is no VR mode outside the quest build, and no native
 * method behind it either.
 */
object QuestVrNative {
    fun setConfig(
        separation: Float,
        convergence: Float,
        screenWidthMetres: Float,
        distanceMetres: Float,
        curved: Boolean,
        reproject: Boolean,
    ) = Unit
}
