// Quest VR mode (quest flavour): the game on a virtual screen in an immersive OpenXR session.
//
// This is deliberately not a VR renderer. XR_KHR_android_surface_swapchain gives us an ordinary
// Android Surface whose buffers the compositor consumes directly, and that Surface is handed to
// the GS exactly like the 2D EmulationSurface (ArmsX2Xr::SetRenderWindow). The GS renders with
// Vulkan or GL as it always does; this thread only submits that swapchain as a head-locked-to-world
// quad layer, so the compositor does the per-eye projection and reprojection, and the emulator
// frame rate is decoupled from the headset's.
//
// The EGL context below exists solely because xrCreateSession demands a graphics binding. Nothing
// is ever drawn with it.

#include "xr/QuestPadMapper.h"
#include "xr/QuestXrBridge.h"

#include <EGL/egl.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <sys/system_properties.h>
#include <jni.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace
{
	constexpr const char* kLogTag = "ARMSX2-XR";
#define XR_LOG(...) __android_log_print(ANDROID_LOG_INFO, kLogTag, __VA_ARGS__)
#define XR_ERR(...) __android_log_print(ANDROID_LOG_ERROR, kLogTag, __VA_ARGS__)

	// 16:9 per eye, so widescreen patches fill the screen. A 4:3 game is pillarboxed by the GS, and
	// black bars against the black void around the screen are simply not visible.
	constexpr int32_t kEyeWidthPx = 1920;
	constexpr int32_t kScreenHeightPx = 1080;
	// One surface, the two eye images side by side in it: the GS draws the frame twice (see
	// GSRenderer::VSync) and each eye's quad samples its own half.
	constexpr int32_t kSurfaceWidthPx = kEyeWidthPx * 2;

	// Stereo strength. Separation is the horizontal shift in source UV at depth 1; convergence is
	// the depth that sits ON the screen plane, with nearer pixels coming out of it.
	//
	// Convergence defaults to 0 — the far end of the PS2 depth range — so the background sits on the
	// screen and everything else comes forward. A convergence in the MIDDLE of the range gives even
	// the background a constant left/right offset, which is not depth at all: it just slides the two
	// images apart until the eyes cannot fuse them.
	constexpr float kStereoSeparation = 0.011f;
	constexpr float kStereoConvergence = 0.0f;

	// Live tuning without a rebuild, since the right numbers can only be judged inside the headset:
	//   adb shell setprop debug.armsx2.sep 0.004
	//   adb shell setprop debug.armsx2.conv 0.2
	// Read a few times a second on the XR thread; unset properties leave the defaults alone.
	//   adb shell setprop debug.armsx2.screenw 3.0   (screen width in metres)
	//   adb shell setprop debug.armsx2.dist 2.0      (distance in metres)
	constexpr const char* kSeparationProperty = "debug.armsx2.sep";
	constexpr const char* kConvergenceProperty = "debug.armsx2.conv";
	constexpr const char* kScreenWidthProperty = "debug.armsx2.screenw";
	constexpr const char* kScreenDistanceProperty = "debug.armsx2.dist";
	//   adb shell setprop debug.armsx2.curved 0      (1 = curved screen, 0 = flat)
	constexpr const char* kCurvedProperty = "debug.armsx2.curved";
	//   adb shell setprop debug.armsx2.reproject 0   (1 = depth 3D, 0 = same image both eyes)
	constexpr const char* kReprojectProperty = "debug.armsx2.reproject";
	//   adb shell setprop debug.armsx2.showdepth 1   (draw the depth buffer instead of the game)
	constexpr const char* kShowDepthProperty = "debug.armsx2.showdepth";

	float ReadFloatProperty(const char* name, float fallback)
	{
		char value[PROP_VALUE_MAX] = {};
		if (__system_property_get(name, value) <= 0)
			return fallback;
		const float parsed = std::strtof(value, nullptr);
		return std::isfinite(parsed) ? parsed : fallback;
	}
	// ~77 degrees wide (2.4 m across at 1.5 m): a big screen that still keeps the HUD corners in
	// view without turning your head. Tuned in the headset; see the live properties above.
	constexpr float kScreenDistanceM = 1.5f;
	constexpr float kScreenWidthM = 2.4f;

	using ArmsX2Xr::ControllerInput;
	using ArmsX2Xr::kPadCodes;
	using ArmsX2Xr::PadState;
	using ArmsX2Xr::QuestPadMapper;

	// The Android loader exports only the core API. Extension functions — including the loader's
	// own xrInitializeLoaderKHR — have to be looked up through xrGetInstanceProcAddr; calling them
	// directly (XR_EXTENSION_PROTOTYPES) compiles and then fails to link.
	using InitializeLoaderFn = XrResult(XRAPI_PTR*)(const XrLoaderInitInfoBaseHeaderKHR*);
	using GetGlesRequirementsFn = XrResult(XRAPI_PTR*)(XrInstance, XrSystemId, XrGraphicsRequirementsOpenGLESKHR*);
	using CreateSurfaceSwapchainFn = XrResult(XRAPI_PTR*)(XrSession, const XrSwapchainCreateInfo*, XrSwapchain*, jobject*);
	using EnumerateRefreshRatesFn = XrResult(XRAPI_PTR*)(XrSession, uint32_t, uint32_t*, float*);
	using GetRefreshRateFn = XrResult(XRAPI_PTR*)(XrSession, float*);
	using RequestRefreshRateFn = XrResult(XRAPI_PTR*)(XrSession, float);
	using SetPerformanceLevelFn = XrResult(XRAPI_PTR*)(XrSession, XrPerfSettingsDomainEXT, XrPerfSettingsLevelEXT);

	template <typename Fn>
	bool LoadFunction(XrInstance instance, const char* name, Fn& out)
	{
		PFN_xrVoidFunction function = nullptr;
		if (XR_FAILED(xrGetInstanceProcAddr(instance, name, &function)) || !function)
			return false;
		out = reinterpret_cast<Fn>(function);
		return true;
	}

	// Latest PS2 rumble for Player 1, stored by the emulator thread (ArmsX2Xr::SetRumbleSink) and
	// turned into controller haptics on the XR thread.
	std::atomic<float> s_rumble_large{0.0f};
	std::atomic<float> s_rumble_small{0.0f};

	void OnPadRumble(float large_motor, float small_motor)
	{
		s_rumble_large.store(large_motor, std::memory_order_relaxed);
		s_rumble_small.store(small_motor, std::memory_order_relaxed);
	}

	class QuestXr
	{
	public:
		QuestXr(JavaVM* vm, jobject activity)
			: m_vm(vm)
			, m_activity(activity)
		{
		}

		~QuestXr() { Stop(); }

		jobject Activity() const { return m_activity; }

		void Start() { m_thread = std::thread(&QuestXr::ThreadMain, this); }

		void Stop()
		{
			m_quit.store(true);
			if (m_thread.joinable())
				m_thread.join();
		}

	private:
		struct Actions
		{
			XrActionSet set = XR_NULL_HANDLE;
			XrAction a = XR_NULL_HANDLE, b = XR_NULL_HANDLE, x = XR_NULL_HANDLE, y = XR_NULL_HANDLE;
			XrAction menu = XR_NULL_HANDLE;
			XrAction left_stick_click = XR_NULL_HANDLE, right_stick_click = XR_NULL_HANDLE;
			XrAction left_trigger = XR_NULL_HANDLE, right_trigger = XR_NULL_HANDLE;
			XrAction left_grip = XR_NULL_HANDLE, right_grip = XR_NULL_HANDLE;
			XrAction left_stick = XR_NULL_HANDLE, right_stick = XR_NULL_HANDLE;
			XrAction left_haptic = XR_NULL_HANDLE, right_haptic = XR_NULL_HANDLE;
		};

		void ThreadMain()
		{
			JNIEnv* env = nullptr;
			if (m_vm->AttachCurrentThread(&env, nullptr) != JNI_OK)
			{
				XR_ERR("AttachCurrentThread failed");
				return;
			}

			if (InitEgl() && InitInstance() && InitSession() && InitActions() && InitScreen(env))
			{
				while (!m_quit.load())
				{
					PollEvents(env);
					if (m_exit_requested && !m_session_running)
						break;
					if (!m_session_running)
					{
						std::this_thread::sleep_for(std::chrono::milliseconds(20));
						continue;
					}
					RunFrame(env);
				}
			}
			else
			{
				XR_ERR("init failed: %s", m_error.c_str());
				jvalue arg;
				arg.l = env->NewStringUTF(m_error.c_str());
				CallActivity(env, "onXrFailed", "(Ljava/lang/String;)V", &arg);
				env->DeleteLocalRef(arg.l);
			}

			Destroy();
			m_vm->DetachCurrentThread();
		}

		bool Fail(const char* what, XrResult result = XR_SUCCESS)
		{
			m_error = what;
			if (result != XR_SUCCESS && m_instance != XR_NULL_HANDLE)
			{
				char name[XR_MAX_RESULT_STRING_SIZE] = {};
				xrResultToString(m_instance, result, name);
				m_error += std::string(" (") + name + ")";
			}
			else if (result != XR_SUCCESS)
			{
				m_error += " (XrResult " + std::to_string(static_cast<int>(result)) + ")";
			}
			return false;
		}

		bool InitEgl()
		{
			m_egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
			if (m_egl_display == EGL_NO_DISPLAY || !eglInitialize(m_egl_display, nullptr, nullptr))
				return Fail("eglInitialize failed");

			const EGLint config_attribs[] = {
				EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
				EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
				EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
				EGL_NONE};
			EGLint count = 0;
			if (!eglChooseConfig(m_egl_display, config_attribs, &m_egl_config, 1, &count) || count == 0)
				return Fail("no EGL config");

			const EGLint context_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
			m_egl_context = eglCreateContext(m_egl_display, m_egl_config, EGL_NO_CONTEXT, context_attribs);
			if (m_egl_context == EGL_NO_CONTEXT)
				return Fail("eglCreateContext failed");

			const EGLint pbuffer_attribs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
			m_egl_surface = eglCreatePbufferSurface(m_egl_display, m_egl_config, pbuffer_attribs);
			if (m_egl_surface == EGL_NO_SURFACE)
				return Fail("eglCreatePbufferSurface failed");

			if (!eglMakeCurrent(m_egl_display, m_egl_surface, m_egl_surface, m_egl_context))
				return Fail("eglMakeCurrent failed");
			return true;
		}

		bool InitInstance()
		{
			// xrInitializeLoaderKHR may only succeed once per process, and the activity can be
			// entered many times in one.
			//
			// ★ The context handed over here must outlive EVERY session. Meta's loader keeps it and
			// reaches for it again on each later init (it re-initializes itself from inside
			// xrEnumerateInstanceExtensionProperties), so passing the ACTIVITY aborted the process on
			// the second entry into VR -- "JNI DETECTED ERROR: java_class == null in GetMethodID",
			// the activity having been destroyed and its global ref deleted meanwhile. The
			// application context lives as long as the process, and this reference is never freed.
			static bool s_loader_initialized = false;
			static jobject s_loader_context = nullptr;
			if (!s_loader_initialized)
			{
				JNIEnv* env = nullptr;
				if (m_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env)
					return Fail("no JNIEnv for the loader init");

				jclass activity_class = env->GetObjectClass(m_activity);
				jmethodID get_app_context = env->GetMethodID(activity_class, "getApplicationContext", "()Landroid/content/Context;");
				jobject app_context = get_app_context ? env->CallObjectMethod(m_activity, get_app_context) : nullptr;
				env->DeleteLocalRef(activity_class);
				if (env->ExceptionCheck())
				{
					env->ExceptionDescribe();
					env->ExceptionClear();
				}
				if (!app_context)
					return Fail("no application context for the loader init");
				s_loader_context = env->NewGlobalRef(app_context);
				env->DeleteLocalRef(app_context);

				XrLoaderInitInfoAndroidKHR loader_info{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
				loader_info.applicationVM = m_vm;
				loader_info.applicationContext = s_loader_context;
				InitializeLoaderFn initialize_loader = nullptr;
				if (!LoadFunction(XR_NULL_HANDLE, "xrInitializeLoaderKHR", initialize_loader))
					return Fail("OpenXR loader has no xrInitializeLoaderKHR");
				const XrResult res = initialize_loader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&loader_info));
				if (XR_FAILED(res))
					return Fail("xrInitializeLoaderKHR failed", res);
				s_loader_initialized = true;
			}

			uint32_t ext_count = 0;
			XrResult res = xrEnumerateInstanceExtensionProperties(nullptr, 0, &ext_count, nullptr);
			if (XR_FAILED(res))
				return Fail("no OpenXR runtime", res);
			std::vector<XrExtensionProperties> props(ext_count, {XR_TYPE_EXTENSION_PROPERTIES});
			xrEnumerateInstanceExtensionProperties(nullptr, ext_count, &ext_count, props.data());
			const auto has_ext = [&props](const char* name) {
				for (const XrExtensionProperties& p : props)
				{
					if (std::strcmp(p.extensionName, name) == 0)
						return true;
				}
				return false;
			};

			std::vector<const char*> extensions = {
				XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
				XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
				XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME,
			};
			for (const char* ext : extensions)
			{
				if (!has_ext(ext))
				{
					m_error = std::string("runtime lacks ") + ext;
					return false;
				}
			}
			if (has_ext(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME))
			{
				extensions.push_back(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
				m_has_refresh_rate_ext = true;
			}
			// CPU/GPU clock requests. Optional.
			if (has_ext(XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME))
			{
				extensions.push_back(XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME);
				m_has_perf_settings_ext = true;
			}
						// Curved screen. Optional: without it the screen is submitted as a flat quad instead.
			if (has_ext(XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME))
			{
				extensions.push_back(XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME);
				m_has_cylinder_ext = true;
			}
						if (has_ext(XR_FB_COMPOSITION_LAYER_IMAGE_LAYOUT_EXTENSION_NAME))
			{
				extensions.push_back(XR_FB_COMPOSITION_LAYER_IMAGE_LAYOUT_EXTENSION_NAME);
				m_has_image_layout_ext = true;
			}
			else
			{
				XR_ERR("%s unavailable: the screen will be upside down", XR_FB_COMPOSITION_LAYER_IMAGE_LAYOUT_EXTENSION_NAME);
			}

			XrInstanceCreateInfoAndroidKHR android_info{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
			android_info.applicationVM = m_vm;
			android_info.applicationActivity = m_activity;

			XrInstanceCreateInfo create_info{XR_TYPE_INSTANCE_CREATE_INFO};
			create_info.next = &android_info;
			std::strncpy(create_info.applicationInfo.applicationName, "ARMSX2", XR_MAX_APPLICATION_NAME_SIZE - 1);
			create_info.applicationInfo.applicationVersion = 1;
			std::strncpy(create_info.applicationInfo.engineName, "PCSX2", XR_MAX_ENGINE_NAME_SIZE - 1);
			create_info.applicationInfo.engineVersion = 1;
			create_info.applicationInfo.apiVersion = XR_API_VERSION_1_0;
			create_info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
			create_info.enabledExtensionNames = extensions.data();
			res = xrCreateInstance(&create_info, &m_instance);
			if (XR_FAILED(res))
				return Fail("xrCreateInstance failed", res);

			XrSystemGetInfo system_info{XR_TYPE_SYSTEM_GET_INFO};
			system_info.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
			res = xrGetSystem(m_instance, &system_info, &m_system);
			if (XR_FAILED(res))
				return Fail("no head-mounted display", res);

			// Required before xrCreateSession, even though we render nothing with GL.
			GetGlesRequirementsFn get_gles_requirements = nullptr;
			if (!LoadFunction(m_instance, "xrGetOpenGLESGraphicsRequirementsKHR", get_gles_requirements) ||
				!LoadFunction(m_instance, "xrCreateSwapchainAndroidSurfaceKHR", m_create_surface_swapchain))
			{
				return Fail("runtime is missing required extension functions");
			}
			if (m_has_perf_settings_ext)
				m_has_perf_settings_ext = LoadFunction(m_instance, "xrPerfSettingsSetPerformanceLevelEXT", m_set_performance_level);
			if (m_has_refresh_rate_ext)
			{
				m_has_refresh_rate_ext =
					LoadFunction(m_instance, "xrEnumerateDisplayRefreshRatesFB", m_enumerate_refresh_rates) &&
					LoadFunction(m_instance, "xrGetDisplayRefreshRateFB", m_get_refresh_rate) &&
					LoadFunction(m_instance, "xrRequestDisplayRefreshRateFB", m_request_refresh_rate);
			}

			XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
			res = get_gles_requirements(m_instance, m_system, &requirements);
			if (XR_FAILED(res))
				return Fail("xrGetOpenGLESGraphicsRequirementsKHR failed", res);
			return true;
		}

		bool InitSession()
		{
			XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
			binding.display = m_egl_display;
			binding.config = m_egl_config;
			binding.context = m_egl_context;

			XrSessionCreateInfo session_info{XR_TYPE_SESSION_CREATE_INFO};
			session_info.next = &binding;
			session_info.systemId = m_system;
			XrResult res = xrCreateSession(m_instance, &session_info, &m_session);
			if (XR_FAILED(res))
				return Fail("xrCreateSession failed", res);

			XrReferenceSpaceCreateInfo space_info{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
			space_info.poseInReferenceSpace.orientation.w = 1.0f;
			space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
			res = xrCreateReferenceSpace(m_session, &space_info, &m_local_space);
			if (XR_FAILED(res))
				return Fail("LOCAL space failed", res);
			space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
			res = xrCreateReferenceSpace(m_session, &space_info, &m_view_space);
			if (XR_FAILED(res))
				return Fail("VIEW space failed", res);
			return true;
		}

		bool InitActions()
		{
			XrActionSetCreateInfo set_info{XR_TYPE_ACTION_SET_CREATE_INFO};
			std::strncpy(set_info.actionSetName, "gameplay", XR_MAX_ACTION_SET_NAME_SIZE - 1);
			std::strncpy(set_info.localizedActionSetName, "Gameplay", XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE - 1);
			XrResult res = xrCreateActionSet(m_instance, &set_info, &m_actions.set);
			if (XR_FAILED(res))
				return Fail("xrCreateActionSet failed", res);

			struct ActionDef
			{
				XrAction* action;
				const char* name;
				XrActionType type;
				const char* path;
			};
			const ActionDef defs[] = {
				{&m_actions.a, "button_a", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/right/input/a/click"},
				{&m_actions.b, "button_b", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/right/input/b/click"},
				{&m_actions.x, "button_x", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/left/input/x/click"},
				{&m_actions.y, "button_y", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/left/input/y/click"},
				{&m_actions.menu, "menu", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/left/input/menu/click"},
				{&m_actions.left_stick_click, "left_stick_click", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/left/input/thumbstick/click"},
				{&m_actions.right_stick_click, "right_stick_click", XR_ACTION_TYPE_BOOLEAN_INPUT, "/user/hand/right/input/thumbstick/click"},
				{&m_actions.left_trigger, "left_trigger", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/left/input/trigger/value"},
				{&m_actions.right_trigger, "right_trigger", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/right/input/trigger/value"},
				{&m_actions.left_grip, "left_grip", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/left/input/squeeze/value"},
				{&m_actions.right_grip, "right_grip", XR_ACTION_TYPE_FLOAT_INPUT, "/user/hand/right/input/squeeze/value"},
				{&m_actions.left_stick, "left_stick", XR_ACTION_TYPE_VECTOR2F_INPUT, "/user/hand/left/input/thumbstick"},
				{&m_actions.right_stick, "right_stick", XR_ACTION_TYPE_VECTOR2F_INPUT, "/user/hand/right/input/thumbstick"},
				{&m_actions.left_haptic, "left_haptic", XR_ACTION_TYPE_VIBRATION_OUTPUT, "/user/hand/left/output/haptic"},
				{&m_actions.right_haptic, "right_haptic", XR_ACTION_TYPE_VIBRATION_OUTPUT, "/user/hand/right/output/haptic"},
			};

			std::vector<XrActionSuggestedBinding> bindings;
			for (const ActionDef& def : defs)
			{
				XrActionCreateInfo action_info{XR_TYPE_ACTION_CREATE_INFO};
				action_info.actionType = def.type;
				std::strncpy(action_info.actionName, def.name, XR_MAX_ACTION_NAME_SIZE - 1);
				std::strncpy(action_info.localizedActionName, def.name, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
				res = xrCreateAction(m_actions.set, &action_info, def.action);
				if (XR_FAILED(res))
					return Fail("xrCreateAction failed", res);

				XrPath path = XR_NULL_PATH;
				xrStringToPath(m_instance, def.path, &path);
				bindings.push_back({*def.action, path});
			}

			// Touch Pro and Touch Plus controllers are presented through this profile too.
			XrInteractionProfileSuggestedBinding suggested{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
			xrStringToPath(m_instance, "/interaction_profiles/oculus/touch_controller", &suggested.interactionProfile);
			suggested.suggestedBindings = bindings.data();
			suggested.countSuggestedBindings = static_cast<uint32_t>(bindings.size());
			res = xrSuggestInteractionProfileBindings(m_instance, &suggested);
			if (XR_FAILED(res))
				return Fail("xrSuggestInteractionProfileBindings failed", res);

			XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
			attach.countActionSets = 1;
			attach.actionSets = &m_actions.set;
			res = xrAttachSessionActionSets(m_session, &attach);
			if (XR_FAILED(res))
				return Fail("xrAttachSessionActionSets failed", res);
			return true;
		}

		bool InitScreen(JNIEnv* env)
		{
			XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
			info.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
			// ★ format, sampleCount, faceCount, arraySize and mipCount MUST be 0 for a surface
			// swapchain — the producer (the GS) owns the buffer format. The usual 1s fail with
			// XR_ERROR_VALIDATION_FAILURE on Quest.
			info.format = 0;
			info.sampleCount = 0;
			info.width = kSurfaceWidthPx;
			info.height = kScreenHeightPx;
			info.faceCount = 0;
			info.arraySize = 0;
			info.mipCount = 0;

			jobject surface = nullptr;
			const XrResult res = m_create_surface_swapchain(m_session, &info, &m_screen_swapchain, &surface);
			if (XR_FAILED(res) || surface == nullptr)
				return Fail("xrCreateSwapchainAndroidSurfaceKHR failed", res);

			m_screen_window = ANativeWindow_fromSurface(env, surface);
			if (!m_screen_window)
				return Fail("ANativeWindow_fromSurface failed");
			return true;
		}

		void Destroy()
		{
			ReleaseInput();

			// Take the render target away from the GS before the swapchain behind it dies. The GS
			// holds its own window reference (Host::AcquireRenderWindow), so a present that is
			// already in flight fails cleanly rather than touching freed memory.
			if (m_window_handed_over)
			{
				ArmsX2Xr::SetRumbleSink(nullptr);
				// The emulator only reports rumble on change; a value left behind here would start the
				// next session vibrating until the game happened to change it.
				s_rumble_large.store(0.0f, std::memory_order_relaxed);
				s_rumble_small.store(0.0f, std::memory_order_relaxed);
				StopHaptics();
				ArmsX2Xr::SetStereo(false, 0.0f, 0.0f);
				ArmsX2Xr::SetRenderWindow(nullptr, 0, 0, 0.0f);
				m_window_handed_over = false;
			}
			if (m_screen_window)
			{
				ANativeWindow_release(m_screen_window);
				m_screen_window = nullptr;
			}
			if (m_screen_swapchain != XR_NULL_HANDLE)
				xrDestroySwapchain(m_screen_swapchain);
			if (m_local_space != XR_NULL_HANDLE)
				xrDestroySpace(m_local_space);
			if (m_view_space != XR_NULL_HANDLE)
				xrDestroySpace(m_view_space);
			if (m_actions.set != XR_NULL_HANDLE)
				xrDestroyActionSet(m_actions.set);
			if (m_session != XR_NULL_HANDLE)
				xrDestroySession(m_session);
			if (m_instance != XR_NULL_HANDLE)
				xrDestroyInstance(m_instance);

			if (m_egl_display != EGL_NO_DISPLAY)
			{
				eglMakeCurrent(m_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
				if (m_egl_surface != EGL_NO_SURFACE)
					eglDestroySurface(m_egl_display, m_egl_surface);
				if (m_egl_context != EGL_NO_CONTEXT)
					eglDestroyContext(m_egl_display, m_egl_context);
				// ★ No eglTerminate. The default display is process-wide, and terminating it would
				// take the GS's own OpenGL context down with ours.
			}
		}

		void PollEvents(JNIEnv* env)
		{
			XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
			while (xrPollEvent(m_instance, &event) == XR_SUCCESS)
			{
				switch (event.type)
				{
					case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED:
						OnSessionStateChanged(env, reinterpret_cast<const XrEventDataSessionStateChanged&>(event).state);
						break;
					case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING:
						RequestExit(env);
						break;
					case XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING:
						// The user long-pressed the Meta button to recenter: follow them.
						m_screen_placed = false;
						break;
					default:
						break;
				}
				event.type = XR_TYPE_EVENT_DATA_BUFFER;
				event.next = nullptr;
			}
		}

		void OnSessionStateChanged(JNIEnv* env, XrSessionState state)
		{
			XR_LOG("session state %d", static_cast<int>(state));
			switch (state)
			{
				case XR_SESSION_STATE_READY:
				{
					XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
					begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
					const XrResult res = xrBeginSession(m_session, &begin);
					if (XR_FAILED(res))
					{
						XR_ERR("xrBeginSession failed: %d", static_cast<int>(res));
						RequestExit(env);
						break;
					}
					m_session_running = true;
					PickRefreshRate();
					RequestPerformanceBoost();
					// Only now: before the session runs nothing consumes the Surface, and a GS
					// presenting into a full buffer queue would stall the whole emulator.
					if (!m_window_handed_over)
					{
						ArmsX2Xr::SetStereo(true, m_stereo_separation, m_stereo_convergence);
						ArmsX2Xr::SetRumbleSink(&OnPadRumble);
						ArmsX2Xr::SetRenderWindow(m_screen_window, kSurfaceWidthPx, kScreenHeightPx, m_refresh_hz);
						m_window_handed_over = true;
					}
					break;
				}
				case XR_SESSION_STATE_STOPPING:
					xrEndSession(m_session);
					m_session_running = false;
					break;
				case XR_SESSION_STATE_EXITING:
				case XR_SESSION_STATE_LOSS_PENDING:
					RequestExit(env);
					break;
				default:
					break;
			}

			// FOCUSED is the only state in which we get controller input. Anything else means a
			// system overlay is up or the headset is off, and the game should not run unseen.
			const bool focused = (state == XR_SESSION_STATE_FOCUSED);
			if (focused != m_focused)
			{
				m_focused = focused;
				if (!focused)
				{
					ReleaseInput();
					StopHaptics();
				}
				jvalue arg;
				arg.z = focused ? JNI_TRUE : JNI_FALSE;
				CallActivity(env, "onXrFocusChanged", "(Z)V", &arg);
			}
		}

		// Cheap enough at a few times a second; __system_property_get is a shared-memory read.
		void PollStereoTuning()
		{
			if (++m_tuning_countdown < 30)
				return;
			m_tuning_countdown = 0;

			// A MODE, not a flag: 1 draws the depth as-is, each step up amplifies it 16x.
			const float show_depth = ReadFloatProperty(kShowDepthProperty, 0.0f);
			if (show_depth != m_show_depth)
			{
				m_show_depth = show_depth;
				XR_LOG("stereo: showdepth=%.1f", show_depth);
				ArmsX2Xr::SetStereoDebugDepth(show_depth);
			}

			const bool reproject = ReadFloatProperty(kReprojectProperty, 1.0f) != 0.0f;
			if (reproject != m_reproject)
			{
				m_reproject = reproject;
				XR_LOG("stereo: reproject=%d", static_cast<int>(reproject));
				ArmsX2Xr::SetStereoReprojection(reproject);
			}

			const bool curved = ReadFloatProperty(kCurvedProperty, 1.0f) != 0.0f;
			if (curved != m_curved)
			{
				m_curved = curved;
				// The two layer types anchor differently -- a cylinder's pose is its axis, a quad's
				// is its face -- so the screen has to be placed again.
				m_screen_placed = false;
				XR_LOG("screen: curved=%d", static_cast<int>(curved));
			}

			const float screen_width = ReadFloatProperty(kScreenWidthProperty, kScreenWidthM);
			const float distance = ReadFloatProperty(kScreenDistanceProperty, kScreenDistanceM);
			if (screen_width != m_screen_width_m || distance != m_screen_distance_m)
			{
				m_screen_width_m = std::max(0.2f, screen_width);
				m_screen_distance_m = std::max(0.3f, distance);
				// Re-place it: the distance is baked into the pose, not just the size.
				m_screen_placed = false;
				XR_LOG("screen: width=%.2fm distance=%.2fm", m_screen_width_m, m_screen_distance_m);
			}

			const float separation = ReadFloatProperty(kSeparationProperty, kStereoSeparation);
			const float convergence = ReadFloatProperty(kConvergenceProperty, kStereoConvergence);
			if (separation == m_stereo_separation && convergence == m_stereo_convergence)
				return;

			m_stereo_separation = separation;
			m_stereo_convergence = convergence;
			XR_LOG("stereo: separation=%.4f convergence=%.4f", separation, convergence);
			ArmsX2Xr::SetStereo(true, separation, convergence);
		}

		void PickRefreshRate()
		{
			if (!m_has_refresh_rate_ext)
				return;

			// The highest rate the headset offers, which is what the Quest Games Optimizer profile
			// this was tuned with asks for (it went further, to 200 Hz, through a debug property an
			// app cannot set -- while QGO runs, its value wins over this request anyway).
			uint32_t count = 0;
			if (XR_SUCCEEDED(m_enumerate_refresh_rates(m_session, 0, &count, nullptr)) && count > 0)
			{
				std::vector<float> rates(count);
				m_enumerate_refresh_rates(m_session, count, &count, rates.data());
				std::string offered;
				float highest = 0.0f;
				for (float rate : rates)
				{
					offered += std::to_string(static_cast<int>(rate + 0.5f)) + " ";
					highest = std::max(highest, rate);
				}
				XR_LOG("display refresh rates offered: %s", offered.c_str());
				if (highest > 0.0f)
					m_request_refresh_rate(m_session, highest);
			}
			float current = 0.0f;
			if (XR_SUCCEEDED(m_get_refresh_rate(m_session, &current)))
				m_refresh_hz = current;
			XR_LOG("display refresh rate %.1f Hz", m_refresh_hz);
		}

		// The closest an app can get to the QGO profile (CPU level 8, GPU level 7): OpenXR's highest
		// performance level for both domains. QGO's levels come from debug properties an app cannot
		// write, so with QGO running its own values still apply.
		void RequestPerformanceBoost()
		{
			if (!m_has_perf_settings_ext)
				return;
			const XrResult cpu = m_set_performance_level(m_session, XR_PERF_SETTINGS_DOMAIN_CPU_EXT, XR_PERF_SETTINGS_LEVEL_BOOST_EXT);
			const XrResult gpu = m_set_performance_level(m_session, XR_PERF_SETTINGS_DOMAIN_GPU_EXT, XR_PERF_SETTINGS_LEVEL_BOOST_EXT);
			XR_LOG("performance level BOOST: cpu %s, gpu %s", XR_SUCCEEDED(cpu) ? "ok" : "refused",
				XR_SUCCEEDED(gpu) ? "ok" : "refused");
		}

		void RunFrame(JNIEnv* env)
		{
			XrFrameWaitInfo wait_info{XR_TYPE_FRAME_WAIT_INFO};
			XrFrameState frame_state{XR_TYPE_FRAME_STATE};
			if (XR_FAILED(xrWaitFrame(m_session, &wait_info, &frame_state)))
				return;
			XrFrameBeginInfo begin_info{XR_TYPE_FRAME_BEGIN_INFO};
			if (XR_FAILED(xrBeginFrame(m_session, &begin_info)))
				return;

			if (m_focused)
			{
				UpdateInput(env);
				UpdateHaptics();
			}

			PollStereoTuning();

			XrCompositionLayerQuad quads[2] = {{XR_TYPE_COMPOSITION_LAYER_QUAD}, {XR_TYPE_COMPOSITION_LAYER_QUAD}};
			XrCompositionLayerCylinderKHR cylinders[2] = {
				{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR}, {XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR}};
			// The compositor samples a surface swapchain GL-style (origin bottom-left), but the GS
			// writes the buffer top row first like any Android window, so without this the game
			// shows upside down.
			XrCompositionLayerImageLayoutFB image_layout{XR_TYPE_COMPOSITION_LAYER_IMAGE_LAYOUT_FB};
			image_layout.flags = XR_COMPOSITION_LAYER_IMAGE_LAYOUT_VERTICAL_FLIP_BIT_FB;
			const XrCompositionLayerBaseHeader* layers[2] = {};
			uint32_t layer_count = 0;
			if (frame_state.shouldRender)
			{
				if (!m_screen_placed)
					PlaceScreen(frame_state.predictedDisplayTime);
				if (m_screen_placed)
				{
					// Same screen, in the same place, for both eyes -- only the half of the surface
					// each one samples differs. That difference IS the stereo: the two halves hold
					// the frame reprojected left and right by the scene's depth.
					const bool curved = m_curved && m_has_cylinder_ext;
					for (uint32_t eye = 0; eye < 2; eye++)
					{
						XrSwapchainSubImage sub_image{};
						sub_image.swapchain = m_screen_swapchain;
						sub_image.imageRect.offset = {static_cast<int32_t>(eye) * kEyeWidthPx, 0};
						sub_image.imageRect.extent = {kEyeWidthPx, kScreenHeightPx};
						sub_image.imageArrayIndex = 0;
						const XrEyeVisibility visibility = (eye == 0) ? XR_EYE_VISIBILITY_LEFT : XR_EYE_VISIBILITY_RIGHT;

						if (curved)
						{
							XrCompositionLayerCylinderKHR& cylinder = cylinders[eye];
							if (m_has_image_layout_ext)
								cylinder.next = &image_layout;
							cylinder.space = m_local_space;
							cylinder.eyeVisibility = visibility;
							cylinder.subImage = sub_image;
							cylinder.pose = m_screen_pose;
							cylinder.radius = m_screen_distance_m;
							// The chord across the curve is the screen width the user asked for, so
							// widening it wraps further around rather than pushing the screen away.
							cylinder.centralAngle = 2.0f * std::atan((m_screen_width_m * 0.5f) / m_screen_distance_m);
							cylinder.aspectRatio = static_cast<float>(kEyeWidthPx) / static_cast<float>(kScreenHeightPx);
							layers[layer_count++] = reinterpret_cast<const XrCompositionLayerBaseHeader*>(&cylinder);
						}
						else
						{
							XrCompositionLayerQuad& quad = quads[eye];
							if (m_has_image_layout_ext)
								quad.next = &image_layout;
							quad.space = m_local_space;
							quad.eyeVisibility = visibility;
							quad.subImage = sub_image;
							quad.pose = m_flat_screen_pose;
							quad.size = {m_screen_width_m, m_screen_width_m * kScreenHeightPx / kEyeWidthPx};
							layers[layer_count++] = reinterpret_cast<const XrCompositionLayerBaseHeader*>(&quad);
						}
					}
				}
			}

			XrFrameEndInfo end_info{XR_TYPE_FRAME_END_INFO};
			end_info.displayTime = frame_state.predictedDisplayTime;
			end_info.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
			end_info.layerCount = layer_count;
			end_info.layers = layer_count ? layers : nullptr;
			xrEndFrame(m_session, &end_info);
		}

		// Put the screen straight ahead of the head at eye height, upright (yaw only), so looking
		// down at the controllers while placing it does not leave it tilted into the floor.
		void PlaceScreen(XrTime time)
		{
			XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
			if (XR_FAILED(xrLocateSpace(m_view_space, m_local_space, time, &location)))
				return;
			const XrSpaceLocationFlags needed = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
			if ((location.locationFlags & needed) != needed)
				return;

			// Forward = orientation * (0, 0, -1).
			const XrQuaternionf& q = location.pose.orientation;
			const float fx = -2.0f * (q.x * q.z + q.w * q.y);
			const float fz = -(1.0f - 2.0f * (q.x * q.x + q.y * q.y));
			const float yaw = std::atan2(-fx, -fz);

			const XrVector3f& head = location.pose.position;
			const XrQuaternionf yaw_orientation{0.0f, std::sin(yaw * 0.5f), 0.0f, std::cos(yaw * 0.5f)};

			// A cylinder layer is posed at its AXIS -- the viewer's own position, with the image
			// wrapped around them at `radius`. A quad is posed at its face, out where the screen is.
			m_screen_pose.orientation = yaw_orientation;
			m_screen_pose.position = head;
			m_flat_screen_pose.orientation = yaw_orientation;
			m_flat_screen_pose.position = {head.x - std::sin(yaw) * m_screen_distance_m, head.y,
				head.z - std::cos(yaw) * m_screen_distance_m};
			m_screen_placed = true;
		}

		bool GetBool(XrAction action)
		{
			XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
			info.action = action;
			XrActionStateBoolean state{XR_TYPE_ACTION_STATE_BOOLEAN};
			return XR_SUCCEEDED(xrGetActionStateBoolean(m_session, &info, &state)) && state.isActive && state.currentState;
		}

		float GetFloat(XrAction action)
		{
			XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
			info.action = action;
			XrActionStateFloat state{XR_TYPE_ACTION_STATE_FLOAT};
			return (XR_SUCCEEDED(xrGetActionStateFloat(m_session, &info, &state)) && state.isActive) ? state.currentState : 0.0f;
		}

		XrVector2f GetVector(XrAction action)
		{
			XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
			info.action = action;
			XrActionStateVector2f state{XR_TYPE_ACTION_STATE_VECTOR2F};
			if (XR_SUCCEEDED(xrGetActionStateVector2f(m_session, &info, &state)) && state.isActive)
				return state.currentState;
			return {0.0f, 0.0f};
		}

		void UpdateInput(JNIEnv* env)
		{
			XrActiveActionSet active{m_actions.set, XR_NULL_PATH};
			XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
			sync.countActiveActionSets = 1;
			sync.activeActionSets = &active;
			if (XR_FAILED(xrSyncActions(m_session, &sync)))
				return;

			ControllerInput in;
			in.a = GetBool(m_actions.a);
			in.b = GetBool(m_actions.b);
			in.x = GetBool(m_actions.x);
			in.y = GetBool(m_actions.y);
			in.menu = GetBool(m_actions.menu);
			in.left_stick_click = GetBool(m_actions.left_stick_click);
			in.right_stick_click = GetBool(m_actions.right_stick_click);
			in.left_trigger = GetFloat(m_actions.left_trigger);
			in.right_trigger = GetFloat(m_actions.right_trigger);
			in.left_grip = GetFloat(m_actions.left_grip);
			in.right_grip = GetFloat(m_actions.right_grip);
			const XrVector2f left = GetVector(m_actions.left_stick);
			const XrVector2f right = GetVector(m_actions.right_stick);
			in.left_x = left.x;
			in.left_y = left.y;
			in.right_x = right.x;
			in.right_y = right.y;

			const double now = std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
			const ArmsX2Xr::MapperOutput out = m_mapper.Update(in, now);
			SendPad(out.pad);
			if (out.recenter)
				m_screen_placed = false;
			if (out.exit_vr)
				RequestExit(env);
		}

		// PS2 rumble -> Touch haptics. A DualShock 2 has both motors in one body, so you feel both in
		// both hands; with two controllers each one takes the stronger of "its" motor and half of the
		// other -- the large (heavy) motor leans left, the small (buzz) motor right, like the grips.
		// Re-applied a few times a second while it lasts: an OpenXR vibration has a finite duration,
		// and a game holds rumble on for as long as it likes.
		void UpdateHaptics()
		{
			const float large = s_rumble_large.load(std::memory_order_relaxed);
			const float small = s_rumble_small.load(std::memory_order_relaxed);
			const float amplitudes[2] = {std::max(large, 0.5f * small), std::max(small, 0.5f * large)};
			const XrAction actions[2] = {m_actions.left_haptic, m_actions.right_haptic};
			const auto now = std::chrono::steady_clock::now();

			for (int hand = 0; hand < 2; hand++)
			{
				const float amplitude = std::clamp(amplitudes[hand], 0.0f, 1.0f);
				XrHapticActionInfo info{XR_TYPE_HAPTIC_ACTION_INFO};
				info.action = actions[hand];

				if (amplitude < 0.02f)
				{
					if (m_haptic_amplitude[hand] > 0.0f)
						xrStopHapticFeedback(m_session, &info);
					m_haptic_amplitude[hand] = 0.0f;
					continue;
				}

				const bool changed = std::fabs(amplitude - m_haptic_amplitude[hand]) >= 0.05f;
				const bool expiring = (now - m_haptic_applied_at[hand]) >= kHapticRefresh;
				if (!changed && !expiring)
					continue;

				XrHapticVibration vibration{XR_TYPE_HAPTIC_VIBRATION};
				// XrDuration is in NANOseconds.
				vibration.duration = std::chrono::duration_cast<std::chrono::nanoseconds>(kHapticPulse).count();
				vibration.frequency = XR_FREQUENCY_UNSPECIFIED;
				vibration.amplitude = amplitude;
				xrApplyHapticFeedback(m_session, &info, reinterpret_cast<const XrHapticBaseHeader*>(&vibration));
				m_haptic_amplitude[hand] = amplitude;
				m_haptic_applied_at[hand] = now;
			}
		}

		void StopHaptics()
		{
			if (m_session == XR_NULL_HANDLE)
				return;
			const XrAction actions[2] = {m_actions.left_haptic, m_actions.right_haptic};
			for (int hand = 0; hand < 2; hand++)
			{
				if (m_haptic_amplitude[hand] <= 0.0f || actions[hand] == XR_NULL_HANDLE)
					continue;
				XrHapticActionInfo info{XR_TYPE_HAPTIC_ACTION_INFO};
				info.action = actions[hand];
				xrStopHapticFeedback(m_session, &info);
				m_haptic_amplitude[hand] = 0.0f;
			}
		}

		// Only changes are sent. A Bluetooth pad forwarded through the activity drives the same
		// port, and re-sending an idle Touch state every frame would keep releasing its buttons.
		void SendPad(const PadState& pad)
		{
			for (std::size_t i = 0; i < kPadCodes.size(); i++)
			{
				const float value = pad.values[i];
				const float last = m_sent.values[i];
				const bool crossed_zero = (value > 0.0f) != (last > 0.0f);
				if (crossed_zero || std::fabs(value - last) >= (1.0f / 256.0f))
				{
					ArmsX2Xr::SetPadInput(kPadCodes[i], value);
					m_sent.values[i] = value;
				}
			}
		}

		void ReleaseInput()
		{
			m_mapper.ResetHeld();
			SendPad(PadState());
		}

		void RequestExit(JNIEnv* env)
		{
			if (m_exit_requested)
				return;
			m_exit_requested = true;
			CallActivity(env, "onXrExitRequested", "()V", nullptr);
		}

		void CallActivity(JNIEnv* env, const char* name, const char* signature, const jvalue* args)
		{
			jclass cls = env->GetObjectClass(m_activity);
			jmethodID method = env->GetMethodID(cls, name, signature);
			if (method)
				env->CallVoidMethodA(m_activity, method, args);
			if (env->ExceptionCheck())
			{
				env->ExceptionDescribe();
				env->ExceptionClear();
			}
			env->DeleteLocalRef(cls);
		}

		JavaVM* m_vm;
		jobject m_activity; // global ref, owned by the JNI entry points below
		std::thread m_thread;
		std::atomic<bool> m_quit{false};
		std::string m_error;

		EGLDisplay m_egl_display = EGL_NO_DISPLAY;
		EGLConfig m_egl_config = nullptr;
		EGLContext m_egl_context = EGL_NO_CONTEXT;
		EGLSurface m_egl_surface = EGL_NO_SURFACE;

		XrInstance m_instance = XR_NULL_HANDLE;
		XrSystemId m_system = XR_NULL_SYSTEM_ID;
		XrSession m_session = XR_NULL_HANDLE;
		XrSpace m_local_space = XR_NULL_HANDLE;
		XrSpace m_view_space = XR_NULL_HANDLE;
		Actions m_actions;
		float m_screen_width_m = kScreenWidthM;
		float m_screen_distance_m = kScreenDistanceM;
		float m_stereo_separation = kStereoSeparation;
		float m_stereo_convergence = kStereoConvergence;
		int m_tuning_countdown = 0;
		bool m_has_refresh_rate_ext = false;
		bool m_has_perf_settings_ext = false;
		SetPerformanceLevelFn m_set_performance_level = nullptr;
		bool m_has_image_layout_ext = false;
		float m_refresh_hz = 0.0f;
		CreateSurfaceSwapchainFn m_create_surface_swapchain = nullptr;
		EnumerateRefreshRatesFn m_enumerate_refresh_rates = nullptr;
		GetRefreshRateFn m_get_refresh_rate = nullptr;
		RequestRefreshRateFn m_request_refresh_rate = nullptr;

		bool m_session_running = false;
		bool m_focused = false;
		bool m_exit_requested = false;

		XrSwapchain m_screen_swapchain = XR_NULL_HANDLE;
		ANativeWindow* m_screen_window = nullptr;
		bool m_window_handed_over = false;
		XrPosef m_screen_pose{{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
		XrPosef m_flat_screen_pose{{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, -kScreenDistanceM}};
		bool m_has_cylinder_ext = false;
		bool m_curved = true;
		bool m_reproject = true;
		float m_show_depth = 0.0f;
		bool m_screen_placed = false;

		// Pulses outlast the refresh, so sustained rumble has no gaps between re-applies.
		static constexpr std::chrono::milliseconds kHapticPulse{300};
		static constexpr std::chrono::milliseconds kHapticRefresh{200};
		float m_haptic_amplitude[2] = {0.0f, 0.0f};
		std::chrono::steady_clock::time_point m_haptic_applied_at[2] = {};

		QuestPadMapper m_mapper;
		PadState m_sent;
	};

	std::mutex s_xr_mutex;
	std::unique_ptr<QuestXr> s_xr;
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_armsx2_vr_QuestVrActivity_nativeStart(JNIEnv* env, jobject thiz)
{
	std::lock_guard<std::mutex> lock(s_xr_mutex);
	if (s_xr)
		return;
	JavaVM* vm = nullptr;
	env->GetJavaVM(&vm);
	s_xr = std::make_unique<QuestXr>(vm, env->NewGlobalRef(thiz));
	s_xr->Start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_armsx2_vr_QuestVrActivity_nativeStop(JNIEnv* env, jobject /*thiz*/)
{
	std::unique_ptr<QuestXr> xr;
	{
		std::lock_guard<std::mutex> lock(s_xr_mutex);
		xr = std::move(s_xr);
	}
	if (!xr)
		return;
	// Joins the XR thread. That thread calls back into the activity only via CallVoidMethod, never
	// by posting and waiting on the UI thread, so joining from the UI thread cannot deadlock.
	xr->Stop();
	const jobject activity = xr->Activity();
	xr.reset();
	env->DeleteGlobalRef(activity);
}
