package com.armsx2.vr

import android.app.Activity
import android.content.Intent
import android.os.Build
import com.armsx2.BuildConfig

/**
 * Entry point to the Quest VR mode, callable from shared code.
 *
 * The immersive activity itself (com.armsx2.vr.QuestVrActivity) exists only in the quest source
 * set, so it is started by class name — github and play never reference it and need no stubs.
 * [isHeadset] is false in those flavours, which makes every call here a no-op.
 */
object QuestVr {
    private const val ACTIVITY_CLASS = "com.armsx2.vr.QuestVrActivity"

    /**
     * True from the moment entry is requested until the immersive activity is destroyed. Set
     * BEFORE startActivity: the panel's onPause runs before the new activity's onCreate, and it
     * reads this to tell "the game went into the headset" from "the user left the app".
     */
    @Volatile
    var active = false

    /** Set when OpenXR failed to start, so the panel stops bouncing into a mode that can't work. */
    @Volatile
    private var unavailable = false

    // Meta and PICO. The XR layer itself is vendor-neutral OpenXR -- it suggests both controller
    // profiles and falls back where a Meta-only extension is missing -- so the only vendor knowledge
    // here is which devices should go into VR at all. PICO is UNTESTED: written from the OpenXR
    // spec and PICO's manifest requirements, never run on the hardware.
    private val VR_MANUFACTURERS = listOf("oculus", "meta", "pico", "bytedance")

    val isHeadset: Boolean
        get() = BuildConfig.QUEST_VR && !unavailable &&
            VR_MANUFACTURERS.any { Build.MANUFACTURER.lowercase().contains(it) }

    fun markUnavailable() {
        unavailable = true
    }

    fun enter(activity: Activity) {
        if (!isHeadset || active) return
        active = true
        val intent = Intent()
            .setClassName(activity, ACTIVITY_CLASS)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The panel is NOT finished here, unlike Meta's hybrid sample: MainActivityRuntime owns the
        // VM, and its onDestroy shuts the core down and kills the process.
        if (runCatching { activity.startActivity(intent) }.isFailure) {
            active = false
            unavailable = true
        }
    }
}
