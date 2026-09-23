#pragma once

// Quest Touch controllers -> DualShock 2. Pure logic, no OpenXR or PCSX2 types, so it can be
// exercised on a desktop compiler.
//
// The Touch pair has 11 buttons and two sticks; the DualShock 2 needs 16 buttons and two sticks.
// The missing five (D-pad, Select) live behind the left Menu button, which is the one button
// that is otherwise only Start:
//
//   A / B / X / Y          Cross / Circle / Square / Triangle
//   Grips / Triggers       L1 R1 / L2 R2 (triggers are analog)
//   Left stick click       Start
//   Right stick click      Select
//   Sticks                 left / right analog; a hard push on the left stick is the D-pad
//   Menu tap               open the ARMSX2 menu (leaves VR for the panel it lives on)
//   Menu held + left stick click   L3
//   Menu held + right stick click  R3
//   Menu held + B              left stick: analog + D-pad on hard push (default), or analog only
//   Menu held + right trigger  recenter the screen
//
// Menu is on the left controller under the left thumb, so every chord partner is on the right.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

namespace ArmsX2Xr
{
	// Android keycode numbers, which is what native-lib.cpp's applyPadButton() switches on.
	enum PadCode : int
	{
		PAD_UP = 19,
		PAD_DOWN = 20,
		PAD_LEFT = 21,
		PAD_RIGHT = 22,
		PAD_CROSS = 96,
		PAD_CIRCLE = 97,
		PAD_SQUARE = 99,
		PAD_TRIANGLE = 100,
		PAD_L1 = 102,
		PAD_R1 = 103,
		PAD_L2 = 104,
		PAD_R2 = 105,
		PAD_L3 = 106,
		PAD_R3 = 107,
		PAD_START = 108,
		PAD_SELECT = 109,
		PAD_L_UP = 110,
		PAD_L_RIGHT = 111,
		PAD_L_DOWN = 112,
		PAD_L_LEFT = 113,
		PAD_R_UP = 120,
		PAD_R_RIGHT = 121,
		PAD_R_DOWN = 122,
		PAD_R_LEFT = 123,
	};

	inline constexpr std::array<PadCode, 24> kPadCodes = {
		PAD_UP, PAD_DOWN, PAD_LEFT, PAD_RIGHT, PAD_CROSS, PAD_CIRCLE, PAD_SQUARE, PAD_TRIANGLE,
		PAD_L1, PAD_R1, PAD_L2, PAD_R2, PAD_L3, PAD_R3, PAD_START, PAD_SELECT,
		PAD_L_UP, PAD_L_RIGHT, PAD_L_DOWN, PAD_L_LEFT, PAD_R_UP, PAD_R_RIGHT, PAD_R_DOWN, PAD_R_LEFT,
	};

	struct ControllerInput
	{
		bool a = false, b = false, x = false, y = false, menu = false;
		bool left_stick_click = false, right_stick_click = false;
		float left_trigger = 0.0f, right_trigger = 0.0f;
		float left_grip = 0.0f, right_grip = 0.0f;
		// OpenXR convention: +x right, +y up.
		float left_x = 0.0f, left_y = 0.0f, right_x = 0.0f, right_y = 0.0f;
	};

	struct PadState
	{
		std::array<float, kPadCodes.size()> values{}; // indexed like kPadCodes

		static std::size_t IndexOf(PadCode code)
		{
			return static_cast<std::size_t>(std::find(kPadCodes.begin(), kPadCodes.end(), code) - kPadCodes.begin());
		}
		float Get(PadCode code) const { return values[IndexOf(code)]; }
		void Set(PadCode code, float value) { values[IndexOf(code)] = value; }
	};

	struct MapperOutput
	{
		PadState pad;
		bool recenter = false;
		bool exit_vr = false;
	};

	class QuestPadMapper
	{
	public:
		static constexpr float kStickDeadzone = 0.12f;
		static constexpr float kTriggerDeadzone = 0.05f;
		static constexpr float kPressThreshold = 0.6f;
		static constexpr float kReleaseThreshold = 0.4f;
		static constexpr float kDpadThreshold = 0.5f;
		// Left-stick D-pad: a HARD push, with hysteresis. Pressed past kStickDpadPress, released
		// only back under kStickDpadRelease, so a push hovering near the edge cannot chatter into a
		// run of presses that skips menu entries.
		static constexpr float kStickDpadPress = 0.75f;
		static constexpr float kStickDpadRelease = 0.55f;
		// A Menu TAP opens the ARMSX2 menu, which lives on the 2D panel, so it leaves VR. Decided on
		// RELEASE: until then the press could still turn into a chord, and a chord must not also
		// throw the user out of VR. A long press does nothing.
		static constexpr double kMenuTapSeconds = 0.5;

		MapperOutput Update(const ControllerInput& in, double now)
		{
			MapperOutput out;

			if (in.menu && !m_menu_down)
			{
				m_menu_down = true;
				m_menu_chorded = false;
				m_menu_pressed_at = now;
			}
			else if (!in.menu && m_menu_down)
			{
				m_menu_down = false;
				if (!m_menu_chorded && now - m_menu_pressed_at < kMenuTapSeconds)
					out.exit_vr = true;
			}
			const bool shift = m_menu_down;

			const bool rt_down = Hysteresis(m_rt_down, in.right_trigger);
			const bool rg_down = Hysteresis(m_rg_down, in.right_grip);

			// A button pressed while Menu is held belongs to the chord until it is released, even
			// if Menu is let go first — otherwise releasing Menu early would fire Circle/R1/R2.
			if (Claim(m_b_claimed, in.b, m_prev_b, shift))
			{
				m_menu_chorded = true;
				m_dpad_mode = !m_dpad_mode;
			}
			if (Claim(m_rt_claimed, rt_down, m_prev_rt, shift))
			{
				m_menu_chorded = true;
				out.recenter = true;
			}
			// The stick clicks are Start and Select; the pad's own L3/R3 ride the Menu chord.
			if (Claim(m_l3_claimed, in.left_stick_click, m_prev_l3, shift))
				m_menu_chorded = true;
			if (Claim(m_r3_claimed, in.right_stick_click, m_prev_r3, shift))
				m_menu_chorded = true;

			PadState& pad = out.pad;
			pad.Set(PAD_CROSS, in.a ? 1.0f : 0.0f);
			pad.Set(PAD_SELECT, (in.right_stick_click && !m_r3_claimed) ? 1.0f : 0.0f);
			pad.Set(PAD_CIRCLE, (in.b && !m_b_claimed) ? 1.0f : 0.0f);
			pad.Set(PAD_SQUARE, in.x ? 1.0f : 0.0f);
			pad.Set(PAD_TRIANGLE, in.y ? 1.0f : 0.0f);
			pad.Set(PAD_L1, Hysteresis(m_lg_down, in.left_grip) ? 1.0f : 0.0f);
			pad.Set(PAD_R1, rg_down ? 1.0f : 0.0f);
			pad.Set(PAD_L2, Deadzone(in.left_trigger, kTriggerDeadzone));
			pad.Set(PAD_R2, m_rt_claimed ? 0.0f : Deadzone(in.right_trigger, kTriggerDeadzone));
			pad.Set(PAD_L3, m_l3_claimed ? 1.0f : 0.0f);
			pad.Set(PAD_R3, m_r3_claimed ? 1.0f : 0.0f);
			pad.Set(PAD_START, (in.left_stick_click && !m_l3_claimed) ? 1.0f : 0.0f);

			bool up = false, down = false, left = false, right = false;
			SplitStick(pad, in.right_x, in.right_y, PAD_R_UP, PAD_R_DOWN, PAD_R_LEFT, PAD_R_RIGHT);

			// Left stick: analog, and by default a hard push is the D-pad INSTEAD on that axis. Plenty of
			// PS2 menus only answer to the D-pad (GT4's in-race pause menu), so the stick has to reach
			// it -- but menus that read both (GT4's main menu) moved two entries per push when the stick
			// sent both at once. Exclusive per axis fixes that, and keeps steering analog through
			// everything short of full lock, where digital and analog amount to the same thing.
			// Menu + B switches to analog only, for games whose D-pad does something of its own.
			SplitStick(pad, in.left_x, in.left_y, PAD_L_UP, PAD_L_DOWN, PAD_L_LEFT, PAD_L_RIGHT);
			if (m_dpad_mode)
			{
				m_left_dpad_x = StickDpadAxis(m_left_dpad_x, in.left_x);
				m_left_dpad_y = StickDpadAxis(m_left_dpad_y, in.left_y);
				if (m_left_dpad_x != 0)
				{
					right |= m_left_dpad_x > 0;
					left |= m_left_dpad_x < 0;
					pad.Set(PAD_L_RIGHT, 0.0f);
					pad.Set(PAD_L_LEFT, 0.0f);
				}
				if (m_left_dpad_y != 0)
				{
					up |= m_left_dpad_y > 0;
					down |= m_left_dpad_y < 0;
					pad.Set(PAD_L_UP, 0.0f);
					pad.Set(PAD_L_DOWN, 0.0f);
				}
			}
			else
			{
				m_left_dpad_x = 0;
				m_left_dpad_y = 0;
			}

			pad.Set(PAD_UP, up ? 1.0f : 0.0f);
			pad.Set(PAD_DOWN, down ? 1.0f : 0.0f);
			pad.Set(PAD_LEFT, left ? 1.0f : 0.0f);
			pad.Set(PAD_RIGHT, right ? 1.0f : 0.0f);
			return out;
		}

		// Forget held buttons (focus lost) but keep the user's D-pad mode choice.
		void ResetHeld()
		{
			const bool dpad_mode = m_dpad_mode;
			*this = QuestPadMapper();
			m_dpad_mode = dpad_mode;
		}

		bool DpadMode() const { return m_dpad_mode; }

	private:
		static float Deadzone(float v, float dz)
		{
			const float m = std::fabs(v);
			if (m <= dz)
				return 0.0f;
			return std::copysign(std::min(1.0f, (m - dz) / (1.0f - dz)), v);
		}

		static bool Hysteresis(bool& state, float value)
		{
			state = state ? (value > kReleaseThreshold) : (value > kPressThreshold);
			return state;
		}

		// Returns true on the press that starts a claim.
		static bool Claim(bool& claimed, bool pressed, bool& prev, bool shift)
		{
			const bool edge = pressed && !prev;
			prev = pressed;
			if (!pressed)
				claimed = false;
			else if (edge && shift)
			{
				claimed = true;
				return true;
			}
			return false;
		}

		// One axis of the left-stick D-pad: -1, 0 or +1, with hysteresis around the current state.
		static int StickDpadAxis(int state, float value)
		{
			if (state > 0)
				return (value > kStickDpadRelease) ? 1 : (value < -kStickDpadPress ? -1 : 0);
			if (state < 0)
				return (value < -kStickDpadRelease) ? -1 : (value > kStickDpadPress ? 1 : 0);
			return (value > kStickDpadPress) ? 1 : (value < -kStickDpadPress ? -1 : 0);
		}

		static void AddDpad(float x, float y, bool& up, bool& down, bool& left, bool& right)
		{
			up |= y > kDpadThreshold;
			down |= y < -kDpadThreshold;
			right |= x > kDpadThreshold;
			left |= x < -kDpadThreshold;
		}

		static void SplitStick(PadState& pad, float x, float y, PadCode up, PadCode down, PadCode left, PadCode right)
		{
			x = Deadzone(x, kStickDeadzone);
			y = Deadzone(y, kStickDeadzone);
			pad.Set(right, std::max(x, 0.0f));
			pad.Set(left, std::max(-x, 0.0f));
			pad.Set(up, std::max(y, 0.0f));
			pad.Set(down, std::max(-y, 0.0f));
		}

		bool m_dpad_mode = true;
		int m_left_dpad_x = 0, m_left_dpad_y = 0;
		bool m_menu_down = false;
		bool m_menu_chorded = false;
		double m_menu_pressed_at = 0.0;

		bool m_rt_down = false, m_rg_down = false, m_lg_down = false;
		bool m_prev_b = false, m_prev_rt = false, m_prev_l3 = false, m_prev_r3 = false;
		bool m_b_claimed = false, m_rt_claimed = false, m_l3_claimed = false, m_r3_claimed = false;
	};
} // namespace ArmsX2Xr
