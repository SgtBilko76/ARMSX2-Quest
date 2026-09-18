#pragma once

// The seam between the Quest OpenXR layer (QuestXr.cpp, quest flavour only) and the emulator glue
// in native-lib.cpp. QuestXr.cpp includes no PCSX2 header: it only knows these two calls, which
// keeps the XR code buildable and checkable on its own.

struct ANativeWindow;

namespace ArmsX2Xr
{
	// Hand the GS the Surface behind the virtual screen. While set it takes precedence over the 2D
	// EmulationSurface; pass nullptr to give the render target back. The callee takes its own
	// reference, so the caller keeps (and releases) its one.
	void SetRenderWindow(ANativeWindow* window, int width, int height, float refresh_hz);

	// Turn stereoscopic output on or off in the GS. When on, each frame is drawn twice into the
	// double-width surface -- left half for the left eye, right half for the right -- with the
	// image reprojected by the scene's depth buffer, which is what gives it real depth.
	// `separation` is the horizontal shift in source UV at depth 1; `convergence` is the depth that
	// sits on the screen plane.
	void SetStereo(bool enabled, float separation, float convergence);

	// Keep the eye split but skip the depth reprojection (and its GPU passes). Diagnostic A/B.
	void SetStereoReprojection(bool enabled);

	// Set one Player 1 input. `code` is the Android-keycode PS2 code applyPadButton() understands
	// (see QuestPadMapper.h); `value` is 0..1, where 0 releases.
	void SetPadInput(int code, float value);
} // namespace ArmsX2Xr
