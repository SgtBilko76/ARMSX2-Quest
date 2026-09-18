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
//   Stick clicks           L3 / R3
//   Sticks                 left / right analog (the left stick ALSO presses the D-pad)
//   Menu tap               Start
//   Menu held + right stick    D-pad
//   Menu held + A              Select
//   Menu held + B              left stick: D-pad + analog (default), or analog only
//   Menu held + right trigger  recenter the screen
//   Menu held + right grip     leave VR (back to the panel and the pause menu)
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
		static constexpr double kMenuTapSeconds = 0.5;
		// Start is sent on Menu RELEASE (only then is it known not to be a chord), so it has to be
		// held long enough for the game to poll it at least a few times.
		static constexpr double kStartPulseSeconds = 0.1;

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
					m_start_until = now + kStartPulseSeconds;
			}
			const bool shift = m_menu_down;

			const bool rt_down = Hysteresis(m_rt_down, in.right_trigger);
			const bool rg_down = Hysteresis(m_rg_down, in.right_grip);

			// A button pressed while Menu is held belongs to the chord until it is released, even
			// if Menu is let go first — otherwise releasing Menu early would fire Cross/Circle/R1/R2.
			if (Claim(m_a_claimed, in.a, m_prev_a, shift))
				m_menu_chorded = true;
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
			if (Claim(m_rg_claimed, rg_down, m_prev_rg, shift))
			{
				m_menu_chorded = true;
				out.exit_vr = true;
			}

			PadState& pad = out.pad;
			pad.Set(PAD_CROSS, (in.a && !m_a_claimed) ? 1.0f : 0.0f);
			pad.Set(PAD_SELECT, m_a_claimed ? 1.0f : 0.0f);
			pad.Set(PAD_CIRCLE, (in.b && !m_b_claimed) ? 1.0f : 0.0f);
			pad.Set(PAD_SQUARE, in.x ? 1.0f : 0.0f);
			pad.Set(PAD_TRIANGLE, in.y ? 1.0f : 0.0f);
			pad.Set(PAD_L1, Hysteresis(m_lg_down, in.left_grip) ? 1.0f : 0.0f);
			pad.Set(PAD_R1, (rg_down && !m_rg_claimed) ? 1.0f : 0.0f);
			pad.Set(PAD_L2, Deadzone(in.left_trigger, kTriggerDeadzone));
			pad.Set(PAD_R2, m_rt_claimed ? 0.0f : Deadzone(in.right_trigger, kTriggerDeadzone));
			pad.Set(PAD_L3, in.left_stick_click ? 1.0f : 0.0f);
			pad.Set(PAD_R3, in.right_stick_click ? 1.0f : 0.0f);
			pad.Set(PAD_START, now < m_start_until ? 1.0f : 0.0f);

			bool up = false, down = false, left = false, right = false;
			if (shift)
			{
				AddDpad(in.right_x, in.right_y, up, down, left, right);
				if (up || down || left || right)
					m_menu_chorded = true;
			}
			else
			{
				SplitStick(pad, in.right_x, in.right_y, PAD_R_UP, PAD_R_DOWN, PAD_R_LEFT, PAD_R_RIGHT);
			}

			// The left stick always drives the analog stick, and by default the D-pad as well: plenty
			// of PS2 menus only answer to the D-pad, and with the pad in digital mode the analog
			// sticks send nothing at all -- a menu that cannot be moved is the worse failure. Menu + B
			// turns the D-pad half off for games where the D-pad does something of its own.
			SplitStick(pad, in.left_x, in.left_y, PAD_L_UP, PAD_L_DOWN, PAD_L_LEFT, PAD_L_RIGHT);
			if (m_dpad_mode)
				AddDpad(in.left_x, in.left_y, up, down, left, right);

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
		bool m_menu_down = false;
		bool m_menu_chorded = false;
		double m_menu_pressed_at = 0.0;
		double m_start_until = -1.0;

		bool m_rt_down = false, m_rg_down = false, m_lg_down = false;
		bool m_prev_a = false, m_prev_b = false, m_prev_rt = false, m_prev_rg = false;
		bool m_a_claimed = false, m_b_claimed = false, m_rt_claimed = false, m_rg_claimed = false;
	};
} // namespace ArmsX2Xr
