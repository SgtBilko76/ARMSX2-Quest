package com.armsx2.vr

import androidx.annotation.Keep

/**
 * The quest flavour's bridge to the XR thread (cpp/xr/QuestXr.cpp).
 *
 * Values land in the session's own state and take effect on its next frame; calling this with no
 * session running is fine, the session reads them when it starts. The github and play flavours
 * carry a no-op twin of this file, so shared code can call it unconditionally.
 */
@Keep
object QuestVrNative {
    fun setConfig(
        separation: Float,
        convergence: Float,
        screenWidthMetres: Float,
        distanceMetres: Float,
        curved: Boolean,
        reproject: Boolean,
    ) {
        // The emulator core (which carries this JNI method) is loaded by NativeApp's static
        // initialiser. Touching it first keeps a settings screen opened before any game from
        // dying on UnsatisfiedLinkError.
        runCatching {
            kr.co.iefriends.pcsx2.NativeApp.hasActiveVM()
            nativeSetConfig(separation, convergence, screenWidthMetres, distanceMetres, curved, reproject)
        }
    }

    private external fun nativeSetConfig(
        separation: Float,
        convergence: Float,
        screenWidthMetres: Float,
        distanceMetres: Float,
        curved: Boolean,
        reproject: Boolean,
    )
}
