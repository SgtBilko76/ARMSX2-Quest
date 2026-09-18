package com.armsx2.vr

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.annotation.Keep
import com.armsx2.EmuState
import com.armsx2.Main
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.InGameOverlay
import com.armsx2.ui.WindowImpl

/**
 * Immersive half of the Quest build: the running game on an OpenXR virtual screen.
 *
 * The VM keeps living in [MainActivityRuntime] (now a stopped panel behind us). All this activity
 * does is start the native OpenXR thread (cpp/xr/QuestXr.cpp), which hands the GS a new render
 * Surface and feeds the Touch controllers into Player 1. Everything that needs the 2D UI — the
 * pause menu, in-game screens, the game ending — sends the user back to the panel.
 */
class QuestVrActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var pausedForFocus = false

    private val frontendWatch = object : Runnable {
        override fun run() {
            if (MainActivityRuntime.eState.value == EmuState.STOPPED ||
                WindowImpl.overlayVisible.value ||
                WindowImpl.inGameScreen.value != null
            ) {
                returnToPanel()
                return
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuestVr.active = true
        nativeStart()
        handler.postDelayed(frontendWatch, 250)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        nativeStop()
        QuestVr.active = false
        // Whatever route we left by (Menu + right grip, Back, the runtime ending the session), the
        // panel must not come back to a game running with nobody watching it. A no-op when the
        // pause menu is what sent us back, and when the game has ended.
        if (MainActivityRuntime.eState.value != EmuState.STOPPED) InGameOverlay.open()
        super.onDestroy()
    }

    /**
     * Horizon OS only shows a panel from Home, so going back is "launch Home, and have Home open
     * the panel" rather than a plain startActivity. Main is singleTop and already alive, so this
     * just brings it back; its onNewIntent finds no game URI in the intent and ignores it.
     */
    private fun returnToPanel() {
        if (isFinishing) return
        val panel = Intent(applicationContext, Main::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingPanel = PendingIntent.getActivity(
            applicationContext, 0, panel,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("extra_launch_in_home_pending_intent", pendingPanel)
        runCatching { startActivity(home) }
        finish()
    }

    // Called from the native XR thread.

    @Keep
    fun onXrFocusChanged(focused: Boolean) = runOnUiThread {
        if (!focused && MainActivityRuntime.eState.value == EmuState.RUNNING) {
            // The system menu is up or the headset came off. userHeldPause stops the panel's
            // stuck-paused backstop from resuming a game that nobody can see.
            MainActivityRuntime.userHeldPause.value = true
            MainActivityRuntime.pause()
            pausedForFocus = true
        } else if (focused && pausedForFocus) {
            pausedForFocus = false
            MainActivityRuntime.resume()
        }
    }

    @Keep
    fun onXrExitRequested() = runOnUiThread { returnToPanel() }

    @Keep
    fun onXrFailed(reason: String) = runOnUiThread {
        QuestVr.markUnavailable()
        Toast.makeText(applicationContext, "VR mode unavailable: $reason", Toast.LENGTH_LONG).show()
        returnToPanel()
    }

    // A Bluetooth gamepad paired to the headset still arrives as Android input, here rather than
    // in the stopped panel. Hand it to the panel's normal controller path (mapping, PadRouter).

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val main = MainActivityRuntime.instance
        if (main != null && isGamepad(event.source)) return main.dispatchKeyEvent(event)
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val main = MainActivityRuntime.instance
        if (main != null && isGamepad(event.source)) return main.dispatchGenericMotionEvent(event)
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isGamepad(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private external fun nativeStart()
    private external fun nativeStop()
}
