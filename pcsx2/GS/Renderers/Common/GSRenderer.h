// SPDX-FileCopyrightText: 2002-2026 PCSX2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

#pragma once

#include "GS/GSState.h"
#include "GS/MultiISA.h"
#include <memory>
#include <string>

MULTI_ISA_DEF(class GSSwPrimRenderFunctions;)

#include <atomic>

/// Quest VR stereoscopic output (platforms/android/app/src/main/cpp/xr). Driven from the XR thread
/// via ArmsX2Xr::SetStereo, read by GSRenderer::VSync on the GS thread, hence the atomics. Off
/// everywhere else, which leaves the present path exactly as it was.
namespace GSStereo
{
	extern std::atomic<bool> enabled;
	/// Horizontal shift, in source UV, applied at depth 1 relative to the convergence plane.
	extern std::atomic<float> separation;
	/// Depth value that lands on the screen plane; nearer pixels come forward, further ones recede.
	extern std::atomic<float> convergence;
	/// When false, frames still go to both eyes but without the depth reprojection -- flat, and
	/// without its extra full-screen passes. For isolating what the 3D costs.
	extern std::atomic<bool> reproject;
} // namespace GSStereo

class GSRenderer : public GSState
{
	// The software prim render reads the drawing state through a GSRenderer reference, so its
	// friendship has to sit here and not on one derived renderer: any hardware renderer can
	// send draws down that road.
	MULTI_ISA_FRIEND(GSSwPrimRenderFunctions)

private:
	enum class MergeMode
	{
		Full,
		SkipFinalComposition,
		InterlaceHistoryOnly,
	};

	template <MergeMode mode>
	bool Merge(int field);
	bool BeginPresentFrame(bool frame_skip);
	void EndPresentFrame();

	u64 m_shader_time_start = 0;

	std::string m_snapshot;
	/// What the queued request asked for, 0 for a screenshot only. Distinct from m_dump_frames on
	/// purpose: a request must never reach into a recording that is already running.
	u32 m_snapshot_dump_frames = 0;
	/// Frames the open dump still owes. Owned by the recording, set once when it is created.
	u32 m_dump_frames = 0;
	u32 m_skipped_duplicate_frames = 0;
	/// Manual frame-skip phase counter (Android "Frame Skip" 0..5). GS thread only.
	u32 m_manual_frameskip_phase = 0;
	/// Accumulator pacer for the max-presented-FPS cap: the tick deadline at which the next
	/// present is due. 0 = not started/disabled. GS thread only.
	u64 m_next_present_deadline = 0;
	// Tracking draw counters for idle frame detection.
	u64 m_last_draw_n = 0;
	u64 m_last_transfer_n = 0;

	/// Length of the current run of blank (nothing-to-merge) frames, reset by the first frame that
	/// produces output. Feeds ShouldSkipAndroidBlankFrame — a lone alternating blank is an
	/// interlaced-field artefact, while a RUN of them is a real fade the game is drawing. GS thread
	/// only. Ported alongside the presentation policy from sashkinbro/EmuCoreX.
	u32 m_consecutive_blank_frames = 0;

protected:
	GSVector2i m_real_size{0, 0};

	virtual GSTexture* GetOutput(int i, float& scale, int& y_offset) = 0;

	/// Splits the presentation surface into the two side-by-side eye halves, and returns false
	/// when stereo is off. EVERY present path has to use these rects while it is on: a frame drawn
	/// once across the whole surface is seen half by each eye.
	bool CalculateStereoDrawRects(const GSVector4i& src_rect, const GSVector2i& src_size,
		GSVector4& left_rect, GSVector4& right_rect);

	/// Depth buffer of this frame's main 3D pass, for stereo reprojection. Null when there is
	/// none to be had (software renderer, 2D-only frame).
	virtual GSTexture* GetStereoDepthTexture() { return nullptr; }
	virtual GSTexture* GetFeedbackOutput(float& scale) { return nullptr; }

public:
	GSRenderer();
	virtual ~GSRenderer();

	virtual void Reset(bool hardware_reset) override;

	virtual void Destroy();

	virtual void UpdateRenderFixes();

	virtual void VSync(u32 field, bool registers_written, bool idle_frame);
	void SubmitVsync(u32 field, bool registers_written);
	void ExecVsyncRecord(const GSBackQueue::VsyncRecord& rec) override;
	virtual bool CanUpscale() { return false; }
	virtual float GetUpscaleMultiplier() { return 1.0f; }
	virtual float GetTextureScaleFactor() { return 1.0f; }
	GSVector2i GetInternalResolution();
	float GetModXYOffset();

	virtual GSTexture* LookupPaletteSource(u32 CBP, u32 CPSM, u32 CBW, GSVector2i& offset, float* scale, const GSVector2i& size);

	bool IsIdleFrame() const;

	bool SaveSnapshotToMemory(u32 window_width, u32 window_height, bool apply_aspect, bool crop_borders,
		u32* width, u32* height, std::vector<u32>* pixels);

	// False if a snapshot is already queued and this request was dropped.
	bool QueueSnapshot(const std::string& path, const u32 gsdump_frames);
	// True while a dump is open and taking frames. A queued snapshot does not count: the
	// dump is not created until the VSync that services it.
	bool IsDumpRecording() const { return static_cast<bool>(m_dump); }
	void StopGSDump();
	void PresentCurrentFrame();
};

extern std::unique_ptr<GSRenderer> g_gs_renderer;
