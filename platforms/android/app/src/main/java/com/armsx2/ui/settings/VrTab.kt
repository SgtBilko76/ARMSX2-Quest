package com.armsx2.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.armsx2.i18n.I18n.get as str
import com.armsx2.vr.VrSettings

/**
 * Quest VR settings: the virtual screen and the stereoscopic 3D.
 *
 * First tab in the list and reachable from the in-game menu, because these are judged while wearing
 * the headset — every change is pushed to the running session and visible on its next frame, so the
 * value can be dialled in while looking at the game.
 */
@Composable
fun VrTab() {
    LaunchedEffect(Unit) { VrSettings.load() }

    Column(Modifier) {
        HelpText(str("vr.note"))

        ToggleRow(
            str("vr.reproject.label"),
            VrSettings.reproject.value,
            description = str("vr.reproject.description"),
        ) { VrSettings.set(reprojectValue = it) }

        SettingsDivider()

        // Thousandths of the frame width: 11 is the tuned default, 40 is already uncomfortable.
        IntSliderRow(
            label = str("vr.depth.label"),
            value = VrSettings.depth.value,
            min = 1,
            max = 40,
            description = str("vr.depth.description"),
            valueFormatter = { "${it / 10.0f}" },
            onReset = { VrSettings.set(depthValue = VrSettings.DEFAULT_DEPTH) },
        ) { VrSettings.set(depthValue = it) }

        IntSliderRow(
            label = str("vr.convergence.label"),
            value = VrSettings.convergence.value,
            min = 0,
            max = 100,
            description = str("vr.convergence.description"),
            valueFormatter = { "$it%" },
            onReset = { VrSettings.set(convergenceValue = VrSettings.DEFAULT_CONVERGENCE) },
        ) { VrSettings.set(convergenceValue = it) }

        SettingsDivider()

        IntSliderRow(
            label = str("vr.screenWidth.label"),
            value = VrSettings.screenWidthCm.value,
            min = 60,
            max = 600,
            description = str("vr.screenWidth.description"),
            valueFormatter = { "${it / 100.0f} m" },
            onReset = { VrSettings.set(screenWidthValue = VrSettings.DEFAULT_SCREEN_WIDTH_CM) },
        ) { VrSettings.set(screenWidthValue = it) }

        IntSliderRow(
            label = str("vr.distance.label"),
            value = VrSettings.distanceCm.value,
            min = 50,
            max = 500,
            description = str("vr.distance.description"),
            valueFormatter = { "${it / 100.0f} m" },
            onReset = { VrSettings.set(distanceValue = VrSettings.DEFAULT_DISTANCE_CM) },
        ) { VrSettings.set(distanceValue = it) }

        ToggleRow(
            str("vr.curved.label"),
            VrSettings.curved.value,
            description = str("vr.curved.description"),
        ) { VrSettings.set(curvedValue = it) }
    }
}
