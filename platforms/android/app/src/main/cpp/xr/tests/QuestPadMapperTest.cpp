/* Host-side unit test for the Quest Touch -> DualShock 2 mapping. Not part of the Android build;
   QuestPadMapper.h is pure logic, so it runs on a desktop compiler (from the repo root):

     g++ -std=c++17 -Wall -Wextra -I platforms/android/app/src/main/cpp \
         platforms/android/app/src/main/cpp/xr/tests/QuestPadMapperTest.cpp -o /tmp/qpm && /tmp/qpm
*/

#include "xr/QuestPadMapper.h"

#include <cstdio>
#include <cstdlib>

using namespace ArmsX2Xr;

static int failures = 0;
#define CHECK(c) \
	do \
	{ \
		if (!(c)) \
		{ \
			std::printf("FAIL line %d: %s\n", __LINE__, #c); \
			failures++; \
		} \
	} while (0)

int main()
{
	// Plain buttons.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.a = true;
		in.y = true;
		in.left_trigger = 0.5f;
		auto o = m.Update(in, 0.0);
		CHECK(o.pad.Get(PAD_CROSS) == 1.0f);
		CHECK(o.pad.Get(PAD_TRIANGLE) == 1.0f);
		CHECK(o.pad.Get(PAD_SELECT) == 0.0f);
		CHECK(o.pad.Get(PAD_L2) > 0.4f && o.pad.Get(PAD_L2) < 0.5f);
	}

	// Grip hysteresis.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.left_grip = 0.5f;
		CHECK(m.Update(in, 0).pad.Get(PAD_L1) == 0.0f);
		in.left_grip = 0.7f;
		CHECK(m.Update(in, 0).pad.Get(PAD_L1) == 1.0f);
		in.left_grip = 0.5f;
		CHECK(m.Update(in, 0).pad.Get(PAD_L1) == 1.0f);
		in.left_grip = 0.3f;
		CHECK(m.Update(in, 0).pad.Get(PAD_L1) == 0.0f);
	}

	// Menu tap -> Start pulse on release, not during the press.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.menu = true;
		CHECK(m.Update(in, 1.0).pad.Get(PAD_START) == 0.0f);
		in.menu = false;
		CHECK(m.Update(in, 1.2).pad.Get(PAD_START) == 1.0f);
		CHECK(m.Update(in, 1.25).pad.Get(PAD_START) == 1.0f);
		CHECK(m.Update(in, 1.4).pad.Get(PAD_START) == 0.0f);
	}

	// Long hold -> no Start.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.menu = true;
		m.Update(in, 0);
		in.menu = false;
		CHECK(m.Update(in, 0.8).pad.Get(PAD_START) == 0.0f);
	}

	// Menu long press = Select, on release; a tap is Start and never both.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.menu = true;
		m.Update(in, 0);
		CHECK(m.Update(in, 0.8).pad.Get(PAD_SELECT) == 0.0f); // not while still held
		in.menu = false;
		auto o = m.Update(in, 0.9);
		CHECK(o.pad.Get(PAD_SELECT) == 1.0f);
		CHECK(o.pad.Get(PAD_START) == 0.0f);
		CHECK(m.Update(in, 1.1).pad.Get(PAD_SELECT) == 0.0f); // pulse ends
	}
	// A long press that used a chord fires neither Start nor Select.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.menu = true;
		m.Update(in, 0);
		in.b = true;
		m.Update(in, 0.1);
		in.b = false;
		in.menu = false;
		auto o = m.Update(in, 0.9);
		CHECK(o.pad.Get(PAD_SELECT) == 0.0f);
		CHECK(o.pad.Get(PAD_START) == 0.0f);
	}
	// A is Cross even while Menu is held (it is no longer a chord partner).
	{
		QuestPadMapper m;
		ControllerInput in;
		in.menu = true;
		m.Update(in, 0);
		in.a = true;
		auto o = m.Update(in, 0.05);
		CHECK(o.pad.Get(PAD_CROSS) == 1.0f);
		CHECK(o.pad.Get(PAD_SELECT) == 0.0f);
	}
	// Menu + right stick = D-pad, right analog silent; the chord suppresses Start.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.menu = true;
		in.right_x = -0.9f;
		in.right_y = 0.9f;
		auto o = m.Update(in, 0);
		CHECK(o.pad.Get(PAD_LEFT) == 1.0f);
		CHECK(o.pad.Get(PAD_UP) == 1.0f);
		CHECK(o.pad.Get(PAD_R_LEFT) == 0.0f);
		in.menu = false;
		in.right_x = 0;
		in.right_y = 0;
		CHECK(m.Update(in, 0.1).pad.Get(PAD_START) == 0.0f);
		in.right_x = 1.0f;
		o = m.Update(in, 0.2);
		CHECK(o.pad.Get(PAD_R_RIGHT) == 1.0f);
		CHECK(o.pad.Get(PAD_RIGHT) == 0.0f);
	}

	// Left stick: a hard push is the D-pad and ONLY the D-pad on that axis, so a menu that reads
	// both moves once per push, not twice.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.left_y = -1.0f;
		auto o = m.Update(in, 0);
		CHECK(o.pad.Get(PAD_DOWN) == 1.0f);
		CHECK(o.pad.Get(PAD_L_DOWN) == 0.0f);
		// A moderate deflection steers, analog only. (Coming back from full: under the release point.)
		in.left_y = -0.5f;
		o = m.Update(in, 0.1);
		CHECK(o.pad.Get(PAD_DOWN) == 0.0f);
		CHECK(o.pad.Get(PAD_L_DOWN) > 0.3f);
		// The other axis stays analog while one axis is on the D-pad.
		in.left_y = -1.0f;
		in.left_x = 0.4f;
		o = m.Update(in, 0.2);
		CHECK(o.pad.Get(PAD_DOWN) == 1.0f);
		CHECK(o.pad.Get(PAD_L_RIGHT) > 0.2f);
		CHECK(o.pad.Get(PAD_RIGHT) == 0.0f);
	}

	// Half deflection -- where the D-pad used to trigger -- is analog only now.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.left_y = 0.6f;
		auto o = m.Update(in, 0);
		CHECK(o.pad.Get(PAD_UP) == 0.0f);
		CHECK(o.pad.Get(PAD_L_UP) > 0.4f);
	}

	// Hysteresis: hovering near the edge must not chatter into repeated presses.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.left_y = 0.8f;
		CHECK(m.Update(in, 0).pad.Get(PAD_UP) == 1.0f);
		in.left_y = 0.7f; // under press, over release: still held
		CHECK(m.Update(in, 0.02).pad.Get(PAD_UP) == 1.0f);
		in.left_y = 0.8f;
		CHECK(m.Update(in, 0.04).pad.Get(PAD_UP) == 1.0f);
		in.left_y = 0.5f; // under release: let go
		CHECK(m.Update(in, 0.06).pad.Get(PAD_UP) == 0.0f);
		in.left_y = 0.7f; // not past the press point again yet
		CHECK(m.Update(in, 0.08).pad.Get(PAD_UP) == 0.0f);
		// A straight flick from down to up flips direction without passing through neutral.
		in.left_y = -0.9f;
		CHECK(m.Update(in, 0.10).pad.Get(PAD_DOWN) == 1.0f);
		in.left_y = 0.9f;
		auto o = m.Update(in, 0.12);
		CHECK(o.pad.Get(PAD_UP) == 1.0f);
		CHECK(o.pad.Get(PAD_DOWN) == 0.0f);
	}

	// Menu + B turns the D-pad half off; the analog stick keeps working at full deflection.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.menu = true;
		m.Update(in, 0);
		in.b = true;
		auto o = m.Update(in, 0.05);
		CHECK(!m.DpadMode());
		CHECK(o.pad.Get(PAD_CIRCLE) == 0.0f);
		in.b = false;
		in.menu = false;
		m.Update(in, 0.1);
		in.left_y = -1.0f;
		o = m.Update(in, 0.5);
		CHECK(o.pad.Get(PAD_DOWN) == 0.0f);
		CHECK(o.pad.Get(PAD_L_DOWN) == 1.0f);
		m.ResetHeld();
		CHECK(!m.DpadMode());
	}

	// Menu + right trigger = recenter (one-shot, no R2); Menu + right grip = exit (no R1).
	{
		QuestPadMapper m;
		ControllerInput in;
		in.menu = true;
		m.Update(in, 0);
		in.right_trigger = 1.0f;
		auto o = m.Update(in, 0.05);
		CHECK(o.recenter);
		CHECK(o.pad.Get(PAD_R2) == 0.0f);
		o = m.Update(in, 0.1);
		CHECK(!o.recenter);
		in.right_grip = 1.0f;
		o = m.Update(in, 0.15);
		CHECK(o.exit_vr);
		CHECK(o.pad.Get(PAD_R1) == 0.0f);
	}

	// Stick deadzone and split.
	{
		QuestPadMapper m;
		ControllerInput in;
		in.left_x = 0.05f;
		in.left_y = 0.7f;
		auto o = m.Update(in, 0);
		CHECK(o.pad.Get(PAD_L_RIGHT) == 0.0f);
		CHECK(o.pad.Get(PAD_L_UP) > 0.6f);
		CHECK(o.pad.Get(PAD_L_DOWN) == 0.0f);
	}

	std::printf(failures ? "%d FAILURES\n" : "ALL PASS\n", failures);
	return failures ? EXIT_FAILURE : EXIT_SUCCESS;
}
