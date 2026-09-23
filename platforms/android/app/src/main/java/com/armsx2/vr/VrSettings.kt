package com.armsx2.vr

import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.armsx2.runtime.MainActivityRuntime

/**
 * The Quest VR settings, as shown in the VR settings tab.
 *
 * Kept here rather than in [com.armsx2.config.Settings] because none of it reaches the emulator
 * core: these are properties of the virtual screen and of the stereo reprojection, pushed straight
 * to the running XR session ([QuestVrNative]) and applied on the next frame. Plain prefs, so they
 * survive a restart without a place in the per-game settings tiers.
 *
 * Distances are stored in CENTIMETRES: sliders are integer, and a centimetre is a fine enough step
 * for a screen you are looking at from a metre and a half away.
 */
object VrSettings {
    // Defaults tuned in the headset; the same numbers the native side falls back to (QuestXr.cpp).
    const val DEFAULT_DEPTH = 11 // 0.011 in source UV, per thousand
    const val DEFAULT_CONVERGENCE = 0 // percent of the depth range that sits on the screen
    const val DEFAULT_SCREEN_WIDTH_CM = 240
    const val DEFAULT_DISTANCE_CM = 150
    const val DEFAULT_CURVED = true
    const val DEFAULT_REPROJECT = true

    private const val KEY_DEPTH = "vr.depth"
    private const val KEY_CONVERGENCE = "vr.convergence"
    private const val KEY_SCREEN_WIDTH = "vr.screenWidthCm"
    private const val KEY_DISTANCE = "vr.distanceCm"
    private const val KEY_CURVED = "vr.curved"
    private const val KEY_REPROJECT = "vr.reproject"

    private val prefs get() = MainActivityRuntime.prefs

    /** 1..40, in thousandths of the frame width. */
    val depth = mutableStateOf(DEFAULT_DEPTH)

    /** 0..100, percent of the depth range that sits on the screen plane. */
    val convergence = mutableStateOf(DEFAULT_CONVERGENCE)

    val screenWidthCm = mutableStateOf(DEFAULT_SCREEN_WIDTH_CM)
    val distanceCm = mutableStateOf(DEFAULT_DISTANCE_CM)
    val curved = mutableStateOf(DEFAULT_CURVED)
    val reproject = mutableStateOf(DEFAULT_REPROJECT)

    private var loaded = false

    /** Reads the stored values. Safe to call repeatedly; only the first call does anything. */
    fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            depth.value = prefs.getInt(KEY_DEPTH, DEFAULT_DEPTH)
            convergence.value = prefs.getInt(KEY_CONVERGENCE, DEFAULT_CONVERGENCE)
            screenWidthCm.value = prefs.getInt(KEY_SCREEN_WIDTH, DEFAULT_SCREEN_WIDTH_CM)
            distanceCm.value = prefs.getInt(KEY_DISTANCE, DEFAULT_DISTANCE_CM)
            curved.value = prefs.getBoolean(KEY_CURVED, DEFAULT_CURVED)
            reproject.value = prefs.getBoolean(KEY_REPROJECT, DEFAULT_REPROJECT)
        }
        push()
    }

    fun set(
        depthValue: Int = depth.value,
        convergenceValue: Int = convergence.value,
        screenWidthValue: Int = screenWidthCm.value,
        distanceValue: Int = distanceCm.value,
        curvedValue: Boolean = curved.value,
        reprojectValue: Boolean = reproject.value,
    ) {
        depth.value = depthValue.coerceIn(1, 40)
        convergence.value = convergenceValue.coerceIn(0, 100)
        screenWidthCm.value = screenWidthValue.coerceIn(60, 600)
        distanceCm.value = distanceValue.coerceIn(50, 500)
        curved.value = curvedValue
        reproject.value = reprojectValue

        runCatching {
            prefs.edit {
                putInt(KEY_DEPTH, depth.value)
                putInt(KEY_CONVERGENCE, convergence.value)
                putInt(KEY_SCREEN_WIDTH, screenWidthCm.value)
                putInt(KEY_DISTANCE, distanceCm.value)
                putBoolean(KEY_CURVED, curved.value)
                putBoolean(KEY_REPROJECT, reproject.value)
            }
        }
        push()
    }

    fun resetToDefaults() = set(
        DEFAULT_DEPTH, DEFAULT_CONVERGENCE, DEFAULT_SCREEN_WIDTH_CM, DEFAULT_DISTANCE_CM,
        DEFAULT_CURVED, DEFAULT_REPROJECT,
    )

    /** Hands the current values to the XR session, which picks them up on its next frame. */
    fun push() {
        QuestVrNative.setConfig(
            depth.value / 1000.0f,
            convergence.value / 100.0f,
            screenWidthCm.value / 100.0f,
            distanceCm.value / 100.0f,
            curved.value,
            reproject.value,
        )
    }
}
