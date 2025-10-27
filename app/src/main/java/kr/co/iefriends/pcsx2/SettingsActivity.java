/*

may the android, pcsx2 and the java gods bless me with material you support for this, amen.

*/

package kr.co.iefriends.pcsx2;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;
import android.view.animation.AnimationUtils;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import kr.co.iefriends.pcsx2.input.ControllerMappingManager;
import kr.co.iefriends.pcsx2.util.DeviceProfiles;
import kr.co.iefriends.pcsx2.util.AvatarLoader;

public class SettingsActivity extends AppCompatActivity {

    private boolean mIgnoreRendererInit = true;
    private static final int REQ_IMPORT_MEMCARD = 9911;
    private static final int SECTION_GENERAL = 0;
	private static final int SECTION_GRAPHICS = 1;
	private static final int SECTION_PERFORMANCE = 2;
	private static final int SECTION_CONTROLLER = 3;
	private static final int SECTION_CUSTOMIZATION = 4;
	private static final int SECTION_STORAGE = 5;
	private static final int SECTION_ACHIEVEMENTS = 6;
	private static final String STATE_SELECTED_SECTION = "settings_selected_section";
	private TextView tvDataDirPath;
	private AlertDialog dataDirProgressDialog;
    private boolean disableTouchControls;
    private MaterialToolbar toolbar;
    private ViewFlipper sectionFlipper;
    private MaterialButtonToggleGroup sectionToggleGroup;
    private TabLayout sectionTabs;
    private int currentSection = SECTION_GENERAL;
    private boolean suppressNavigationCallbacks;
    private TextView tvDiscordStatus;
	private MaterialButton btnDiscordConnect;
	private View groupDiscordIdentity;
	private ShapeableImageView imgDiscordAvatar;
	private TextView tvDiscordLoggedInAs;
	private TextView tvOnScreenUiStyleValue;
	private MaterialButton btnDiscordLogout;
    private MaterialSwitch switchRaEnabled;
    private MaterialSwitch switchRaHardcore;
    private MaterialButton btnRaLogin;
    private MaterialButton btnRaLogout;
    private TextView tvRaStatus;
    private TextView tvRaProfile;
    private TextView tvRaGame;
	private View groupRaIdentity;
	private ShapeableImageView imgRaAvatar;
	private TextView tvRaLoggedInAs;
    private boolean updatingRaUi;

	private final ActivityResultLauncher<Intent> startActivityResultPickDataDir =
		registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
			if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
				Intent data = result.getData();
				Uri tree = data.getData();
				if (tree != null) {
					final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
					try {
						getContentResolver().takePersistableUriPermission(tree, takeFlags);
					} catch (SecurityException ignored) {}
					handleDataDirectorySelection(tree);
				}
			}
		});

	@Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_new);
        LogcatRecorder.initialize(getApplicationContext());
        disableTouchControls = DeviceProfiles.isTvOrDesktop(this);
	DiscordBridge.updateEngineActivity(this);

        String displayName = DeviceProfiles.getProductDisplayName(this, getString(R.string.app_name));
        toolbar = findViewById(R.id.settings_toolbar);
        if (toolbar != null) {
            toolbar.setTitle(displayName + " Settings");
            toolbar.setSubtitle(getString(R.string.settings_section_general));
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }
            toolbar.setNavigationOnClickListener(v -> finish());
        } else {
            setTitle(displayName + " Settings");
        }

		initializeGeneralSettings();
		initializeGraphicsSettings();
		initializeControllerSettings();
		initializePerformanceSettings();
		initializeCustomizationSettings();
		initializeMemoryCardSettings();
		initializeStorageSettings();
		initializeActionButtons();
		initializeAchievementsSettings();

        int initialSection = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_SELECTED_SECTION, SECTION_GENERAL)
                : SECTION_GENERAL;
        setupSectionNavigation(initialSection);
    }

    @Override
    protected void onDestroy() {
        DiscordBridge.setListener(null);
        RetroAchievementsBridge.setListener(null);
        super.onDestroy();
    }

    @Override
	protected void onResume() {
		super.onResume();
		DiscordBridge.updateEngineActivity(this);
        updateDataDirSummary();
        updateOnScreenUiStyleSummary();
        updateDiscordUi(DiscordBridge.isLoggedIn());
        RetroAchievementsBridge.refreshState();
    }

    private final RetroAchievementsBridge.Listener retroAchievementsListener = new RetroAchievementsBridge.Listener() {
        @Override
        public void onStateUpdated(RetroAchievementsBridge.State state) {
            updateRetroAchievementsUi(state);
        }

        @Override
        public void onLoginRequested(int reason) {
            runOnUiThread(() -> handleRetroAchievementsLoginRequest(reason));
        }

        @Override
        public void onLoginSuccess(String username, int points, int softPoints, int unreadMessages) {
			runOnUiThread(() -> showRetroAchievementsLoginToast(username));
        }

        @Override
        public void onHardcoreModeChanged(boolean enabled) {
            runOnUiThread(() -> {
                if (switchRaHardcore != null && !updatingRaUi) {
                    switchRaHardcore.setChecked(enabled);
                }
            });
        }
    };

    private void initializeAchievementsSettings() {
        switchRaEnabled = findViewById(R.id.switch_retroachievements_enabled);
        switchRaHardcore = findViewById(R.id.switch_retroachievements_hardcore);
        btnRaLogin = findViewById(R.id.btn_connect_retroachievements);
        btnRaLogout = findViewById(R.id.btn_logout_retroachievements);
        tvRaStatus = findViewById(R.id.tv_ra_status);
        tvRaProfile = findViewById(R.id.tv_ra_profile);
        tvRaGame = findViewById(R.id.tv_ra_game);
	groupRaIdentity = findViewById(R.id.group_ra_identity);
	imgRaAvatar = findViewById(R.id.img_ra_avatar);
	tvRaLoggedInAs = findViewById(R.id.tv_ra_logged_in_as);

        if (switchRaEnabled != null) {
            switchRaEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (updatingRaUi) {
                    return;
                }
                RetroAchievementsBridge.setEnabled(isChecked);
            });
        }

        if (switchRaHardcore != null) {
            switchRaHardcore.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (updatingRaUi) {
                    return;
                }
                RetroAchievementsBridge.setHardcore(isChecked);
            });
        }

        if (btnRaLogin != null) {
            btnRaLogin.setOnClickListener(v -> {
                if (switchRaEnabled != null && !switchRaEnabled.isChecked()) {
                    try {
                        Toast.makeText(this, R.string.settings_achievements_login_required, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                    return;
                }
                showRetroAchievementsLoginDialog();
            });
        }

        if (btnRaLogout != null) {
            btnRaLogout.setOnClickListener(v -> showRetroAchievementsLogoutDialog());
        }

        RetroAchievementsBridge.setListener(retroAchievementsListener);
        RetroAchievementsBridge.refreshState();

	tvDiscordStatus = findViewById(R.id.tv_discord_status);
	groupDiscordIdentity = findViewById(R.id.group_discord_identity);
	imgDiscordAvatar = findViewById(R.id.img_discord_avatar);
	tvDiscordLoggedInAs = findViewById(R.id.tv_discord_logged_in_as);
	btnDiscordConnect = findViewById(R.id.btn_discord_connect);
	btnDiscordLogout = findViewById(R.id.btn_discord_logout);

	boolean discordAvailable = DiscordBridge.isAvailable();
	if (!discordAvailable) {
		if (tvDiscordStatus != null) {
			tvDiscordStatus.setText("Discord integration is unavailable in this build.");
		}
		if (btnDiscordConnect != null) {
			btnDiscordConnect.setEnabled(false);
			btnDiscordConnect.setText("Discord Unavailable");
		}
		if (btnDiscordLogout != null) {
			btnDiscordLogout.setVisibility(View.GONE);
		}
		if (groupDiscordIdentity != null) {
			groupDiscordIdentity.setVisibility(View.GONE);
		}
		DiscordBridge.setListener(null);
		return;
	}
        if (btnDiscordConnect != null) {
            updateDiscordUi(DiscordBridge.isLoggedIn());
            btnDiscordConnect.setOnClickListener(v -> {
                if (!DiscordBridge.isLoggedIn()) {
                    DiscordBridge.beginAuthorize(this);
                }
            });
        }
        if (btnDiscordLogout != null) {
            btnDiscordLogout.setOnClickListener(v -> {
                DiscordBridge.clearTokens();
                updateDiscordUi(false);
                try {
                    Toast.makeText(this, "Disconnected from Discord.", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            });
        }

        DiscordBridge.setListener(new DiscordBridge.DiscordStateListener() {
            @Override
            public void onLoginStateChanged(boolean loggedIn) {
                runOnUiThread(() -> updateDiscordUi(loggedIn));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    if (!TextUtils.isEmpty(message)) {
                        try {
                            Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {}
                    }
                });
            }

            @Override
            public void onUserInfoUpdated(String username) {
                runOnUiThread(() -> updateDiscordUi(DiscordBridge.isLoggedIn()));
            }
        });
    }

    private void updateDiscordUi(boolean loggedIn) {
        if (tvDiscordStatus == null || btnDiscordConnect == null) {
            return;
        }
		if (!DiscordBridge.isAvailable()) {
			tvDiscordStatus.setText("Discord integration is unavailable in this build.");
			btnDiscordConnect.setEnabled(false);
			btnDiscordConnect.setText("Discord Unavailable");
			if (tvDiscordLoggedInAs != null) {
				tvDiscordLoggedInAs.setVisibility(View.GONE);
			}
			if (groupDiscordIdentity != null) {
				groupDiscordIdentity.setVisibility(View.GONE);
			}
			if (imgDiscordAvatar != null) {
				AvatarLoader.clear(imgDiscordAvatar);
			}
			if (btnDiscordLogout != null) {
				btnDiscordLogout.setVisibility(View.GONE);
			}
			return;
		}
        if (loggedIn) {
            tvDiscordStatus.setText("Discord Rich Presence is active.");
            btnDiscordConnect.setEnabled(false);
            btnDiscordConnect.setText("Connect Discord");
            if (tvDiscordLoggedInAs != null) {
                String username = DiscordBridge.getLoggedInUsername();
                if (TextUtils.isEmpty(username)) {
                    username = "Unknown user";
                }
				tvDiscordLoggedInAs.setText(getString(R.string.settings_identity_logged_in_as, username));
				tvDiscordLoggedInAs.setVisibility(View.VISIBLE);
            }
			if (groupDiscordIdentity != null) {
				groupDiscordIdentity.setVisibility(View.VISIBLE);
			}
			if (imgDiscordAvatar != null) {
				String avatarUrl = DiscordBridge.getLoggedInAvatarUrl();
				if (!TextUtils.isEmpty(avatarUrl)) {
					AvatarLoader.loadRemote(imgDiscordAvatar, avatarUrl);
				} else {
					AvatarLoader.clear(imgDiscordAvatar);
				}
			}
            if (btnDiscordLogout != null) {
                btnDiscordLogout.setVisibility(View.VISIBLE);
                btnDiscordLogout.setEnabled(true);
            }
        } else {
            tvDiscordStatus.setText("Connect to show your ARMSX2 activity on Discord.");
            btnDiscordConnect.setEnabled(true);
            btnDiscordConnect.setText("Connect Discord");
            if (tvDiscordLoggedInAs != null) {
                tvDiscordLoggedInAs.setVisibility(View.GONE);
            }
			if (groupDiscordIdentity != null) {
				groupDiscordIdentity.setVisibility(View.GONE);
			}
			if (imgDiscordAvatar != null) {
				AvatarLoader.clear(imgDiscordAvatar);
			}
            if (btnDiscordLogout != null) {
                btnDiscordLogout.setVisibility(View.GONE);
            }
        }

        String pendingError = DiscordBridge.consumeLastError();
        if (!TextUtils.isEmpty(pendingError)) {
            try {
                Toast.makeText(this, pendingError, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
    }

    private void updateRetroAchievementsUi(RetroAchievementsBridge.State state) {
        if (state == null) {
            return;
        }

        updatingRaUi = true;

        if (switchRaEnabled != null) {
            switchRaEnabled.setEnabled(true);
            switchRaEnabled.setChecked(state.achievementsEnabled);
        }

        if (btnRaLogin != null) {
            btnRaLogin.setEnabled(state.achievementsEnabled);
            btnRaLogin.setText(state.loggedIn
                    ? R.string.settings_achievements_button_change
                    : R.string.settings_achievements_button);
        }

        if (btnRaLogout != null) {
            btnRaLogout.setVisibility(state.loggedIn ? View.VISIBLE : View.GONE);
            btnRaLogout.setEnabled(state.loggedIn);
        }

        if (switchRaHardcore != null) {
            switchRaHardcore.setEnabled(state.achievementsEnabled && state.loggedIn);
            switchRaHardcore.setChecked(state.hardcorePreference);
        }

		if (tvRaStatus != null) {
			String status;
			if (!state.achievementsEnabled) {
				status = getString(R.string.settings_achievements_status_signed_out);
			} else if (state.loggedIn) {
				status = getString(R.string.settings_achievements_status_connected, state.displayName);
				if (state.hardcorePreference && !state.hardcoreActive) {
					status = status + "\n" + getString(R.string.settings_achievements_hardcore_inactive);
				}
			} else {
				status = getString(R.string.settings_achievements_status_signed_out);
			}
			tvRaStatus.setText(status);
		}

		if (groupRaIdentity != null) {
			boolean showIdentity = state.achievementsEnabled && state.loggedIn;
			groupRaIdentity.setVisibility(showIdentity ? View.VISIBLE : View.GONE);
			if (showIdentity) {
				if (tvRaLoggedInAs != null) {
					String name = !TextUtils.isEmpty(state.displayName) ? state.displayName : state.username;
					if (TextUtils.isEmpty(name)) {
						name = getString(R.string.settings_achievements_title);
					}
					tvRaLoggedInAs.setText(getString(R.string.settings_identity_logged_in_as, name));
				}
				if (imgRaAvatar != null) {
					AvatarLoader.loadLocal(imgRaAvatar, state.avatarPath);
				}
			} else {
				if (tvRaLoggedInAs != null) {
					tvRaLoggedInAs.setText(R.string.settings_achievements_status_signed_out);
				}
				if (imgRaAvatar != null) {
					AvatarLoader.clear(imgRaAvatar);
				}
			}
		}

        if (tvRaProfile != null) {
            tvRaProfile.setText(state.loggedIn
                    ? getString(R.string.settings_achievements_profile_fmt, state.points, state.softcorePoints, state.unreadMessages)
                    : getString(R.string.settings_achievements_profile_signed_out));
        }

        if (tvRaGame != null) {
            if (state.hasActiveGame && !TextUtils.isEmpty(state.gameTitle)) {
                tvRaGame.setText(getString(
                        R.string.settings_achievements_game_fmt,
                        state.gameTitle,
                        state.unlockedAchievements,
                        state.totalAchievements,
                        state.unlockedPoints,
                        state.totalPoints));
            } else {
                tvRaGame.setText(R.string.settings_achievements_no_game);
            }
        }

        updatingRaUi = false;
    }

	private void showRetroAchievementsLoginToast(String fallbackUsername) {
		try {
			RetroAchievementsBridge.State state = RetroAchievementsBridge.getLastState();
			String displayName = fallbackUsername;
			String avatarPath = null;
			if (state != null) {
				if (!TextUtils.isEmpty(state.displayName)) {
					displayName = state.displayName;
				} else if (!TextUtils.isEmpty(state.username)) {
					displayName = state.username;
				}
				if (!TextUtils.isEmpty(state.avatarPath)) {
					avatarPath = state.avatarPath;
				}
			}

			if (TextUtils.isEmpty(displayName)) {
				displayName = getString(R.string.settings_achievements_title);
			}

			View toastView = LayoutInflater.from(this).inflate(R.layout.toast_ra_login, null);
			ShapeableImageView avatarView = toastView.findViewById(R.id.img_toast_ra_avatar);
			TextView messageView = toastView.findViewById(R.id.tv_toast_ra_message);
			messageView.setText(getString(R.string.settings_achievements_login_success_fmt, displayName));
			AvatarLoader.loadLocal(avatarView, avatarPath);

			Toast toast = new Toast(getApplicationContext());
			toast.setDuration(Toast.LENGTH_SHORT);
			toast.setView(toastView);
			toast.show();
		} catch (Throwable ignored) {
			try {
				Toast.makeText(this, getString(R.string.settings_achievements_login_success), Toast.LENGTH_SHORT).show();
			} catch (Throwable ignored2) {}
		}
	}

    private void handleRetroAchievementsLoginRequest(int reason) {
        if (isFinishing()) {
            return;
        }

        showRetroAchievementsLoginDialog();
        if (reason == RetroAchievementsBridge.LOGIN_REASON_TOKEN_INVALID) {
            try {
                Toast.makeText(this, R.string.settings_achievements_token_invalid, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
    }

    private void showRetroAchievementsLoginDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_retroachievements_login, null);
        TextInputEditText etUsername = dialogView.findViewById(R.id.et_ra_username);
        TextInputEditText etPassword = dialogView.findViewById(R.id.et_ra_password);

        RetroAchievementsBridge.State state = RetroAchievementsBridge.getLastState();
        if (state != null && !TextUtils.isEmpty(state.username) && etUsername != null) {
            etUsername.setText(state.username);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_achievements_login_title)
                .setView(dialogView)
                .setPositiveButton(R.string.settings_achievements_button, null)
                .setNegativeButton(android.R.string.cancel, (d, which) -> d.dismiss())
                .create();

        dialog.setOnShowListener(dlg -> {
            MaterialButton positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE) instanceof MaterialButton
                    ? (MaterialButton) dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                    : null;
            View.OnClickListener loginClickListener = v -> {
                String user = etUsername != null && etUsername.getText() != null
                        ? etUsername.getText().toString().trim()
                        : "";
                String pass = etPassword != null && etPassword.getText() != null
                        ? etPassword.getText().toString()
                        : "";
                if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
                    try {
                        Toast.makeText(this, R.string.settings_achievements_error_credentials, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                    return;
                }
                dialog.dismiss();
                beginRetroAchievementsLogin(user, pass);
            };

            if (positive != null) {
                positive.setOnClickListener(loginClickListener);
            } else {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(loginClickListener);
            }
        });

        dialog.show();
    }

    private void beginRetroAchievementsLogin(String username, String password) {
        AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_achievements_logging_in)
                .setMessage(R.string.settings_achievements_logging_in_message)
                .setCancelable(false)
                .create();
        progressDialog.show();

        RetroAchievementsBridge.login(username, password, (success, message) -> {
            progressDialog.dismiss();
            if (!success && !TextUtils.isEmpty(message)) {
                try {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.settings_achievements_login_title)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } catch (Throwable ignored) {}
            }
        });
    }

    private void showRetroAchievementsLogoutDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_achievements_logout_title)
                .setMessage(R.string.settings_achievements_logout_message)
                .setPositiveButton(R.string.settings_achievements_logout, (dialog, which) -> {
                    RetroAchievementsBridge.logout();
                    try {
                        Toast.makeText(this, R.string.settings_achievements_logout_success, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

	private void initializeGeneralSettings() {
		MaterialSwitch swFsui = findViewById(R.id.sw_fsui);
		if (swFsui != null) {
			try {
				String fsui = NativeApp.getSetting("UI", "EnableFullscreenUI", "bool");
				swFsui.setChecked("true".equalsIgnoreCase(fsui));
			} catch (Exception ignored) {}
			swFsui.setOnCheckedChangeListener((buttonView, isChecked) -> {
				NativeApp.setSetting("UI", "EnableFullscreenUI", "bool", isChecked ? "true" : "false");
				new MaterialAlertDialogBuilder(this)
						.setTitle("Restart Required")
						.setMessage("Fullscreen UI setting will take effect after you restart the app.")
						.setPositiveButton("OK", (d, w) -> d.dismiss())
						.show();
			});
		}

		final Slider sbFpsLimit = findViewById(R.id.sb_fps_limit);
		final TextView tvFpsLimit = findViewById(R.id.tv_fps_limit_value);

		MaterialSwitch swFrameLimiter = findViewById(R.id.sw_frame_limiter);
		if (swFrameLimiter != null) {
			try {
				String ns = NativeApp.getSetting("Framerate", "NominalScalar", "float");
				float scalar = (ns == null || ns.isEmpty()) ? 1.0f : Float.parseFloat(ns);
				swFrameLimiter.setChecked(scalar < 5.0f);
			} catch (Exception ignored) {}
			swFrameLimiter.setOnCheckedChangeListener((buttonView, isChecked) -> {
				if (!isChecked) {
					NativeApp.setSetting("Framerate", "NominalScalar", "float", Float.toString(10.0f));
				} else {
					float baseFps = 59.94f;
					try {
						String ntsc = NativeApp.getSetting("EmuCore/GS", "FramerateNTSC", "float");
						if (ntsc != null && !ntsc.isEmpty()) {
							baseFps = Float.parseFloat(ntsc);
						}
					} catch (Exception ignored2) {}
					int fps = 60;
					if (sbFpsLimit != null) {
						fps = Math.max(30, Math.min(180, Math.round(sbFpsLimit.getValue())));
					}
					float scalar = fps / baseFps;
					NativeApp.setSetting("Framerate", "NominalScalar", "float", Float.toString(scalar));
				}
			});
		}

		if (sbFpsLimit != null && tvFpsLimit != null) {
			try {
				float baseFps = 59.94f;
				try {
					String ntsc = NativeApp.getSetting("EmuCore/GS", "FramerateNTSC", "float");
					if (ntsc != null && !ntsc.isEmpty()) baseFps = Float.parseFloat(ntsc);
				} catch (Exception ignored2) {}

				String ns = NativeApp.getSetting("Framerate", "NominalScalar", "float");
				float scalar = (ns == null || ns.isEmpty()) ? 1.0f : Float.parseFloat(ns);
				int fpsValue = Math.round(scalar * baseFps);
				if (fpsValue < 30) fpsValue = 30;
				if (fpsValue > 180) fpsValue = 180;
				sbFpsLimit.setValue(fpsValue);
				tvFpsLimit.setText("Custom FPS Limit: " + fpsValue);
			} catch (Exception ignored) {
				sbFpsLimit.setValue(60f);
				tvFpsLimit.setText("Custom FPS Limit: 60");
			}
			sbFpsLimit.addOnChangeListener((slider, value, fromUser) -> {
				int fps = Math.max(30, Math.min(180, Math.round(value)));
				if (fps != Math.round(value)) slider.setValue(fps);
				tvFpsLimit.setText("Custom FPS Limit: " + fps);
				float baseFps = 59.94f;
				try {
					String ntsc = NativeApp.getSetting("EmuCore/GS", "FramerateNTSC", "float");
					if (ntsc != null && !ntsc.isEmpty()) baseFps = Float.parseFloat(ntsc);
				} catch (Exception ignored2) {}
				float scalar = fps / baseFps;
				NativeApp.setSetting("Framerate", "NominalScalar", "float", Float.toString(scalar));
			});
		}

		Spinner spAspectRatio = findViewById(R.id.sp_aspect_ratio);
		ArrayAdapter<CharSequence> aspectAdapter = ArrayAdapter.createFromResource(this, R.array.aspect_ratios, android.R.layout.simple_spinner_item);
		aspectAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spAspectRatio.setAdapter(aspectAdapter);
		final String[] aspectChoices = getResources().getStringArray(R.array.aspect_ratios);
		try {
			String aspect = NativeApp.getSetting("EmuCore/GS", "AspectRatio", "string");
			int pos = 0;
			if (aspect != null && !aspect.isEmpty()) {
				for (int i = 0; i < aspectChoices.length; i++) {
					if (aspect.equalsIgnoreCase(aspectChoices[i])) {
						pos = i;
						break;
					}
				}
			}
			spAspectRatio.setSelection(Math.max(0, Math.min(aspectAdapter.getCount() - 1, pos)), false);
		} catch (Exception ignored) {}
		spAspectRatio.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				if (position < 0 || position >= aspectChoices.length)
					return;
				String value = aspectChoices[position];
				NativeApp.setSetting("EmuCore/GS", "AspectRatio", "string", value);
				NativeApp.setAspectRatio(position);
			}
			@Override public void onNothingSelected(AdapterView<?> parent) {}
		});

		MaterialSwitch swFastBoot = findViewById(R.id.sw_fast_boot);
		if (swFastBoot != null) {
			try {
				String fast = NativeApp.getSetting("EmuCore", "EnableFastBoot", "bool");
				swFastBoot.setChecked("true".equalsIgnoreCase(fast) || fast == null || fast.isEmpty());
			} catch (Exception ignored) {}
			swFastBoot.setOnCheckedChangeListener((b, isChecked) ->
					NativeApp.setSetting("EmuCore", "EnableFastBoot", "bool", isChecked ? "true" : "false"));
		}

		MaterialSwitch swRecordLogs = findViewById(R.id.sw_record_logs);
		if (swRecordLogs != null) {
			boolean recordLogs = false;
			try {
				String current = NativeApp.getSetting("Logging", "RecordAndroidLog", "bool");
				recordLogs = "true".equalsIgnoreCase(current);
			} catch (Exception ignored) {}
			swRecordLogs.setChecked(recordLogs);
			LogcatRecorder.setEnabled(recordLogs);
			swRecordLogs.setOnCheckedChangeListener((buttonView, isChecked) -> {
				NativeApp.setSetting("Logging", "RecordAndroidLog", "bool", isChecked ? "true" : "false");
				LogcatRecorder.setEnabled(isChecked);
			});
		}

		Slider sbBrightness = findViewById(R.id.sb_brightness);
		TextView tvBrightness = findViewById(R.id.tv_brightness_value);
		if (sbBrightness != null && tvBrightness != null) {
			try {
				String br = NativeApp.getSetting("EmuCore/GS", "BrightnessScale", "float");
				float val = (br == null || br.isEmpty()) ? 1.0f : Float.parseFloat(br);
				int prog = Math.round(val * 100f);
				prog = Math.max(0, Math.min(200, prog));
				sbBrightness.setValue(prog);
				tvBrightness.setText(String.format("Brightness: %.2f", val));
			} catch (Exception ignored) {
				sbBrightness.setValue(100f);
				tvBrightness.setText("Brightness: 1.00");
			}
			sbBrightness.addOnChangeListener((slider, value, fromUser) -> {
				int clamped = Math.max(0, Math.min(200, Math.round(value)));
				if (clamped != Math.round(value)) slider.setValue(clamped);
				float scale = clamped / 100f;
				tvBrightness.setText(String.format("Brightness: %.2f", scale));
				NativeApp.setSetting("EmuCore/GS", "BrightnessScale", "float", Float.toString(scale));
			});
		}

		Slider sbOsc = findViewById(R.id.sb_osc_timeout);
		TextView tvOsc = findViewById(R.id.tv_osc_timeout_value);
		MaterialSwitch swOscNever = findViewById(R.id.sw_osc_never);
		View oscGroup = findViewById(R.id.group_osc_settings);
		if (disableTouchControls) {
			if (oscGroup != null) oscGroup.setVisibility(View.GONE);
		} else if (sbOsc != null && tvOsc != null && swOscNever != null) {
			final String prefsName = "armsx2";
			final String prefKey = "onscreen_timeout_seconds";
			android.content.SharedPreferences sp = getSharedPreferences(prefsName, MODE_PRIVATE);
			int cur = 3;
			try { cur = sp.getInt(prefKey, 3); } catch (Throwable ignored) {}
			if (cur < 0) cur = 0;
			if (cur > 60) cur = 60;
			tvOsc.setText(cur == 0 ? "On-screen controls timeout: never" : ("On-screen controls timeout: " + cur + "s"));
			sbOsc.setValue(cur == 0 ? 3f : cur);
			swOscNever.setChecked(cur == 0);
			sbOsc.setEnabled(cur != 0);
			swOscNever.setOnCheckedChangeListener((b, checked) -> {
				if (checked) {
					sp.edit().putInt(prefKey, 0).apply();
					tvOsc.setText("On-screen controls timeout: never");
					sbOsc.setEnabled(false);
				} else {
					int val = Math.max(1, Math.min(60, Math.round(sbOsc.getValue())));
					sp.edit().putInt(prefKey, val).apply();
					tvOsc.setText("On-screen controls timeout: " + val + "s");
					sbOsc.setEnabled(true);
				}
			});
			sbOsc.addOnChangeListener((slider, value, fromUser) -> {
				if (swOscNever.isChecked()) return;
				int val = Math.max(1, Math.min(60, Math.round(value)));
				if (val != Math.round(value)) slider.setValue(val);
				sp.edit().putInt(prefKey, val).apply();
				tvOsc.setText("On-screen controls timeout: " + val + "s");
			});
		} else if (oscGroup != null) {
			oscGroup.setVisibility(View.GONE);
		}
	}

    private void initializeGraphicsSettings() {
        final boolean[] ignoreInit = new boolean[]{true};

		// Renderer
		Spinner spRenderer = findViewById(R.id.sp_renderer);
		ArrayAdapter<CharSequence> rendererAdapter = ArrayAdapter.createFromResource(this, R.array.renderers, android.R.layout.simple_spinner_item);
		rendererAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spRenderer.setAdapter(rendererAdapter);
		spRenderer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				int value;
				switch (position) {
					case 1: value = 12; break; // OpenGL
					case 2: value = 13; break; // Software
					case 3: value = 14; break; // Vulkan
					default: value = -1; break; // Auto
				}
				if (mIgnoreRendererInit || ignoreInit[0]) { mIgnoreRendererInit = false; ignoreInit[0] = false; return; }
				Intent data = new Intent();
				data.putExtra("SET_RENDERER", value);
				setResult(RESULT_OK, data);
			}
			@Override public void onNothingSelected(AdapterView<?> parent) {}
		});

		Slider sbUpscale = findViewById(R.id.sb_upscale);
		TextView tvUpscale = findViewById(R.id.tv_upscale_value);
		if (sbUpscale != null && tvUpscale != null) {
			try {
				String up = NativeApp.getSetting("EmuCore/GS", "upscale_multiplier", "float");
				float f = up == null || up.isEmpty() ? 1f : Float.parseFloat(up);
				int mult = Math.max(1, Math.min(8, Math.round(f)));
				sbUpscale.setValue(mult);
				tvUpscale.setText("Upscale: " + mult + "x");
			} catch (Exception ignored) {
				sbUpscale.setValue(1f);
				tvUpscale.setText("Upscale: 1x");
			}
			sbUpscale.addOnChangeListener((slider, value, fromUser) -> {
				int mult = Math.max(1, Math.min(8, Math.round(value)));
				if (mult != Math.round(value)) slider.setValue(mult);
				tvUpscale.setText("Upscale: " + mult + "x");
				NativeApp.setSetting("EmuCore/GS", "upscale_multiplier", "float", String.valueOf(mult));
			});
		}

		// Texture Filtering
		Spinner spFiltering = findViewById(R.id.sp_filtering);
		ArrayAdapter<CharSequence> filtAdapter = ArrayAdapter.createFromResource(this, R.array.texture_filtering, android.R.layout.simple_spinner_item);
		filtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spFiltering.setAdapter(filtAdapter);
		try {
			String filt = NativeApp.getSetting("EmuCore/GS", "filter", "int");
			int v = (filt == null || filt.isEmpty()) ? 2 : Integer.parseInt(filt);
			int pos = (v==2)?0: (v==1?1:2);
			spFiltering.setSelection(pos,false);
		} catch (Exception ignored) {}
		spFiltering.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				int value;
				switch (position) {
					case 0: value = 2; break; // PS2
					case 1: value = 1; break; // Forced
					case 2: value = 0; break; // Nearest
					default: value = 2; break;
				}
				NativeApp.setSetting("EmuCore/GS", "filter", "int", Integer.toString(value));
			}
			@Override public void onNothingSelected(AdapterView<?> parent) {}
		});

        // Interlace Mode
        Spinner spInterlace = findViewById(R.id.sp_interlace);
		ArrayAdapter<CharSequence> interAdapter = ArrayAdapter.createFromResource(this, R.array.interlace_modes, android.R.layout.simple_spinner_item);
		interAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spInterlace.setAdapter(interAdapter);
		try {
			String inter = NativeApp.getSetting("EmuCore/GS", "deinterlace_mode", "int");
			int pos = (inter==null||inter.isEmpty())?0:Integer.parseInt(inter);
			spInterlace.setSelection(Math.max(0, Math.min(interAdapter.getCount()-1, pos)), false);
		} catch (Exception ignored) {}
        spInterlace.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                NativeApp.setSetting("EmuCore/GS", "deinterlace_mode", "int", Integer.toString(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // FXAA
        MaterialSwitch swFxaa = findViewById(R.id.sw_fxaa);
        if (swFxaa != null) {
            try {
                String fxaa = NativeApp.getSetting("EmuCore/GS", "fxaa", "bool");
                swFxaa.setChecked("true".equalsIgnoreCase(fxaa));
            } catch (Exception ignored) {}
            swFxaa.setOnCheckedChangeListener((buttonView, isChecked) ->
                    NativeApp.setSetting("EmuCore/GS", "fxaa", "bool", isChecked ? "true" : "false"));
        }

        // CAS Mode
        Spinner spCasMode = findViewById(R.id.sp_cas_mode);
        ArrayAdapter<CharSequence> casAdapter = ArrayAdapter.createFromResource(this, R.array.cas_modes, android.R.layout.simple_spinner_item);
        casAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCasMode.setAdapter(casAdapter);
        try {
            String cas = NativeApp.getSetting("EmuCore/GS", "CASMode", "int");
            int pos = (cas==null||cas.isEmpty())? 0 : Integer.parseInt(cas);
            spCasMode.setSelection(Math.max(0, Math.min(casAdapter.getCount()-1, pos)), false);
        } catch (Exception ignored) {}
        spCasMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                NativeApp.setSetting("EmuCore/GS", "CASMode", "int", Integer.toString(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        Spinner spTexturePreload = findViewById(R.id.sp_texture_preloading);
        if (spTexturePreload != null) {
            ArrayAdapter<CharSequence> preloadAdapter = ArrayAdapter.createFromResource(this, R.array.texture_preloading, android.R.layout.simple_spinner_item);
            preloadAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spTexturePreload.setAdapter(preloadAdapter);
            try {
                String preload = NativeApp.getSetting("EmuCore/GS", "texture_preloading", "int");
                int val = (preload == null || preload.isEmpty()) ? 0 : Integer.parseInt(preload);
                if (val < 0 || val >= preloadAdapter.getCount()) val = 0;
                spTexturePreload.setSelection(val, false);
            } catch (Exception ignored) {}
            spTexturePreload.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    NativeApp.setSetting("EmuCore/GS", "texture_preloading", "int", Integer.toString(position));
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        Spinner spAccBlending = findViewById(R.id.sp_acc_blending);
        if (spAccBlending != null) {
            ArrayAdapter<CharSequence> blendAdapter = ArrayAdapter.createFromResource(this, R.array.acc_blending, android.R.layout.simple_spinner_item);
            blendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spAccBlending.setAdapter(blendAdapter);
            try {
                String blend = NativeApp.getSetting("EmuCore/GS", "accurate_blending_unit", "int");
                int val = (blend == null || blend.isEmpty()) ? 1 : Integer.parseInt(blend);
                if (val < 0 || val >= blendAdapter.getCount()) val = 1;
                spAccBlending.setSelection(val, false);
            } catch (Exception ignored) {}
            spAccBlending.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    NativeApp.setSetting("EmuCore/GS", "accurate_blending_unit", "int", Integer.toString(position));
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        Spinner spAnisotropic = findViewById(R.id.sp_anisotropic);
        if (spAnisotropic != null) {
            ArrayAdapter<CharSequence> anisoAdapter = ArrayAdapter.createFromResource(this, R.array.anisotropic, android.R.layout.simple_spinner_item);
            anisoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spAnisotropic.setAdapter(anisoAdapter);
            final int[] anisoValues = {0, 2, 4, 8, 16};
            try {
                String aniso = NativeApp.getSetting("EmuCore/GS", "MaxAnisotropy", "int");
                int val = (aniso == null || aniso.isEmpty()) ? 0 : Integer.parseInt(aniso);
                int idx = 0;
                for (int i = 0; i < anisoValues.length; i++) {
                    if (anisoValues[i] == val) {
                        idx = i;
                        break;
                    }
                }
                spAnisotropic.setSelection(idx, false);
            } catch (Exception ignored) {}
            spAnisotropic.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    int value = position >= 0 && position < anisoValues.length ? anisoValues[position] : 0;
                    NativeApp.setSetting("EmuCore/GS", "MaxAnisotropy", "int", Integer.toString(value));
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        Spinner spTrilinear = findViewById(R.id.sp_trilinear_filter);
        if (spTrilinear != null) {
            ArrayAdapter<CharSequence> triAdapter = ArrayAdapter.createFromResource(this, R.array.trilinear_filtering, android.R.layout.simple_spinner_item);
            triAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spTrilinear.setAdapter(triAdapter);
            try {
                String tri = NativeApp.getSetting("EmuCore/GS", "TriFilter", "int");
                int val = (tri == null || tri.isEmpty()) ? 0 : Integer.parseInt(tri);
                if (val < 0 || val >= triAdapter.getCount()) val = 0;
                spTrilinear.setSelection(val, false);
            } catch (Exception ignored) {}
            spTrilinear.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    NativeApp.setSetting("EmuCore/GS", "TriFilter", "int", Integer.toString(position));
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        Spinner spDithering = findViewById(R.id.sp_dithering);
        if (spDithering != null) {
            ArrayAdapter<CharSequence> ditheringAdapter = ArrayAdapter.createFromResource(this, R.array.dithering_modes, android.R.layout.simple_spinner_item);
            ditheringAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spDithering.setAdapter(ditheringAdapter);
            try {
                String dither = NativeApp.getSetting("EmuCore/GS", "dithering_ps2", "int");
                int val = (dither == null || dither.isEmpty()) ? 2 : Integer.parseInt(dither);
                if (val < 0 || val >= ditheringAdapter.getCount()) val = 2;
                spDithering.setSelection(val, false);
            } catch (Exception ignored) {}
            spDithering.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    NativeApp.setSetting("EmuCore/GS", "dithering_ps2", "int", Integer.toString(position));
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        Spinner spBilinearPresent = findViewById(R.id.sp_bilinear_upscale);
        if (spBilinearPresent != null) {
            ArrayAdapter<CharSequence> bilinearAdapter = ArrayAdapter.createFromResource(this, R.array.bilinear_present, android.R.layout.simple_spinner_item);
            bilinearAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spBilinearPresent.setAdapter(bilinearAdapter);
            try {
                String linear = NativeApp.getSetting("EmuCore/GS", "linear_present_mode", "int");
                int val = (linear == null || linear.isEmpty()) ? 2 : Integer.parseInt(linear);
                if (val < 0 || val >= bilinearAdapter.getCount()) val = 2;
                spBilinearPresent.setSelection(val, false);
            } catch (Exception ignored) {}
            spBilinearPresent.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    NativeApp.setSetting("EmuCore/GS", "linear_present_mode", "int", Integer.toString(position));
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        Slider sbCas = findViewById(R.id.sb_cas_sharpness);
        TextView tvCas = findViewById(R.id.tv_cas_sharpness_value);
        if (sbCas != null && tvCas != null) {
            try {
                String sharp = NativeApp.getSetting("EmuCore/GS", "CASSharpness", "int");
                int v = (sharp == null || sharp.isEmpty()) ? 50 : Integer.parseInt(sharp);
                if (v < 0) v = 0;
                if (v > 100) v = 100;
                sbCas.setValue(v);
                tvCas.setText("CAS Sharpness: " + v + "%");
            } catch (Exception ignored) {
                sbCas.setValue(50f);
                tvCas.setText("CAS Sharpness: 50%");
            }
            sbCas.addOnChangeListener((slider, value, fromUser) -> {
                int v = Math.max(0, Math.min(100, Math.round(value)));
                if (v != Math.round(value)) slider.setValue(v);
                tvCas.setText("CAS Sharpness: " + v + "%");
                NativeApp.setSetting("EmuCore/GS", "CASSharpness", "int", Integer.toString(v));
            });
        }

		// Hardware Mipmapping
		MaterialSwitch swHWMip = findViewById(R.id.sw_hw_mipmap);
		if (swHWMip != null) {
			try {
				String hw = NativeApp.getSetting("EmuCore/GS", "hw_mipmap", "bool");
				swHWMip.setChecked("true".equalsIgnoreCase(hw));
			} catch (Exception ignored) {}
			swHWMip.setOnCheckedChangeListener((buttonView, isChecked) ->
					NativeApp.setSetting("EmuCore/GS", "hw_mipmap", "bool", isChecked ? "true" : "false"));
		}

        // VSync
        MaterialSwitch swVsync = findViewById(R.id.sw_vsync);
        if (swVsync != null) {
            try {
                String vs = NativeApp.getSetting("EmuCore/GS", "VsyncEnable", "bool");
                swVsync.setChecked("true".equalsIgnoreCase(vs));
            } catch (Exception ignored) {}
            swVsync.setOnCheckedChangeListener((buttonView, isChecked) ->
                    NativeApp.setSetting("EmuCore/GS", "VsyncEnable", "bool", isChecked ? "true" : "false"));
        }

        // Auto Flush (SW)
        MaterialSwitch swAutoFlushSW = findViewById(R.id.sw_autoflush_sw);
        if (swAutoFlushSW != null) {
            try {
                String af = NativeApp.getSetting("EmuCore/GS", "autoflush_sw", "bool");
                swAutoFlushSW.setChecked("true".equalsIgnoreCase(af));
            } catch (Exception ignored) {}
            swAutoFlushSW.setOnCheckedChangeListener((b, isChecked) ->
                    NativeApp.setSetting("EmuCore/GS", "autoflush_sw", "bool", isChecked ? "true" : "false"));
        }

        // Auto Flush (HW)
        Spinner spAutoFlushHW = findViewById(R.id.sp_autoflush_hw);
        if (spAutoFlushHW != null) {
            ArrayAdapter<CharSequence> afAdapter = ArrayAdapter.createFromResource(this, R.array.auto_flush_hw, android.R.layout.simple_spinner_item);
            afAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spAutoFlushHW.setAdapter(afAdapter);
            try {
                String lvl = NativeApp.getSetting("EmuCore/GS", "UserHacks_AutoFlushLevel", "int");
                int pos = (lvl==null||lvl.isEmpty()) ? 0 : Integer.parseInt(lvl);
                if (pos < 0 || pos > 2) pos = 0;
                spAutoFlushHW.setSelection(pos, false);
            } catch (Exception ignored) {}
            spAutoFlushHW.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    NativeApp.setSetting("EmuCore/GS", "UserHacks_AutoFlushLevel", "int", Integer.toString(position));
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        MaterialSwitch swIntegerScaling = findViewById(R.id.sw_integer_scaling);
        if (swIntegerScaling != null) {
            try {
                String integer = NativeApp.getSetting("EmuCore/GS", "IntegerScaling", "bool");
                swIntegerScaling.setChecked("true".equalsIgnoreCase(integer));
            } catch (Exception ignored) {}
            swIntegerScaling.setOnCheckedChangeListener((buttonView, isChecked) ->
                    NativeApp.setSetting("EmuCore/GS", "IntegerScaling", "bool", isChecked ? "true" : "false"));
        }

        MaterialSwitch swScreenOffsets = findViewById(R.id.sw_screen_offsets);
        if (swScreenOffsets != null) {
            try {
                String offsets = NativeApp.getSetting("EmuCore/GS", "pcrtc_offsets", "bool");
                swScreenOffsets.setChecked("true".equalsIgnoreCase(offsets));
            } catch (Exception ignored) {}
            swScreenOffsets.setOnCheckedChangeListener((buttonView, isChecked) ->
                    NativeApp.setSetting("EmuCore/GS", "pcrtc_offsets", "bool", isChecked ? "true" : "false"));
        }

        MaterialSwitch swShowOverscan = findViewById(R.id.sw_show_overscan);
        if (swShowOverscan != null) {
            try {
                String overscan = NativeApp.getSetting("EmuCore/GS", "pcrtc_overscan", "bool");
                swShowOverscan.setChecked("true".equalsIgnoreCase(overscan));
            } catch (Exception ignored) {}
            swShowOverscan.setOnCheckedChangeListener((buttonView, isChecked) ->
                    NativeApp.setSetting("EmuCore/GS", "pcrtc_overscan", "bool", isChecked ? "true" : "false"));
        }

        MaterialSwitch swAntiblur = findViewById(R.id.sw_antiblur);
        if (swAntiblur != null) {
            try {
                String antiblur = NativeApp.getSetting("EmuCore/GS", "pcrtc_antiblur", "bool");
                if (antiblur == null || antiblur.isEmpty()) {
                    swAntiblur.setChecked(true);
                } else {
                    swAntiblur.setChecked("true".equalsIgnoreCase(antiblur));
                }
            } catch (Exception ignored) {}
            swAntiblur.setOnCheckedChangeListener((buttonView, isChecked) ->
                    NativeApp.setSetting("EmuCore/GS", "pcrtc_antiblur", "bool", isChecked ? "true" : "false"));
        }

        // Set initial renderer value
        try {
            String r = NativeApp.getSetting("EmuCore/GS", "Renderer", "int");
            int v = (r==null||r.isEmpty())? -1 : Integer.parseInt(r);
            int pos; 
			switch (v) { 
				case 12: pos=1; break; 
				case 13: pos=2; break; 
				case 14: pos=3; break; 
				default: pos=0; 
			}
			spRenderer.setSelection(pos, false);
		} catch (Exception ignored) {}
	}

	private void initializeControllerSettings() {
		Button btnCalibrateController = findViewById(R.id.btn_calibrate_controller);
		if (btnCalibrateController != null) {
			btnCalibrateController.setOnClickListener(v -> showControllerCalibrationDialog());
		}

		MaterialButton btnEditMapping = findViewById(R.id.btn_edit_controller_mapping);
		if (btnEditMapping != null) {
			ControllerMappingManager.init(this);
			btnEditMapping.setOnClickListener(v -> new ControllerMappingDialog().show(getSupportFragmentManager(), "controller_mapping"));
		}

		// Vibration Toggle
		MaterialSwitch swVibration = findViewById(R.id.sw_vibration);
		boolean vibrationEnabled = true;
		try {
			String vibration = NativeApp.getSetting("Pad1", "Vibration", "bool");
			if (vibration != null && !vibration.isEmpty()) {
				vibrationEnabled = !"false".equalsIgnoreCase(vibration);
			} else {
				NativeApp.setSetting("Pad1", "Vibration", "bool", "true");
				vibrationEnabled = true;
			}
		} catch (Exception ignored) {}
		swVibration.setChecked(vibrationEnabled);
		MainActivity.setVibrationPreference(vibrationEnabled);
		swVibration.setOnCheckedChangeListener((buttonView, isChecked) -> {
			NativeApp.setSetting("Pad1", "Vibration", "bool", isChecked ? "true" : "false");
			MainActivity.setVibrationPreference(isChecked);
		});
	}

    private void initializePerformanceSettings() {
		// OSD FPS
		MaterialSwitch swOsdFps = findViewById(R.id.sw_osd_fps);
		if (swOsdFps != null) {
			try {
				String fps = NativeApp.getSetting("EmuCore/GS", "OsdShowFPS", "bool");
				swOsdFps.setChecked("true".equalsIgnoreCase(fps));
			} catch (Exception ignored) {}
			swOsdFps.setOnCheckedChangeListener((buttonView, isChecked) ->
					NativeApp.setSetting("EmuCore/GS", "OsdShowFPS", "bool", isChecked ? "true" : "false"));
		}

		// Performance Overlay
		MaterialSwitch swPerfOverlay = findViewById(R.id.sw_perf_overlay);
		if (swPerfOverlay != null) {
			try {
				String pos = NativeApp.getSetting("EmuCore/GS", "OsdPerformancePos", "int");
				int v = (pos == null || pos.isEmpty()) ? 0 : Integer.parseInt(pos);
				if (v < 0 || v > 2) v = 0;
				swPerfOverlay.setChecked(v != 0);
			} catch (Exception ignored) {}
			swPerfOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
				int value = isChecked ? 2 : 0;
				NativeApp.setSetting("EmuCore/GS", "OsdPerformancePos", "int", Integer.toString(value));
			});
		}

		// CPU Core (Translator / Interpreter)
		try {
			Spinner spCpu = findViewById(R.id.sp_cpu_core);
			if (spCpu != null) {
				ArrayAdapter<CharSequence> cpuAdapter = ArrayAdapter.createFromResource(
						this,
						R.array.cpu_cores_basic,
						android.R.layout.simple_spinner_item);
				cpuAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
				spCpu.setAdapter(cpuAdapter);

				int pos = 0;
				try {
					String coreTypeStr = NativeApp.getSetting("EmuCore/CPU", "CoreType", "int");
					if (coreTypeStr != null && !coreTypeStr.isEmpty()) {
						int ct = Integer.parseInt(coreTypeStr);
						if (ct < 0 || ct >= cpuAdapter.getCount()) {
							ct = 0;
						}
						pos = ct;
					}
				} catch (Exception ignored) {}
				spCpu.setSelection(pos, false);
				spCpu.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
					@Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
						NativeApp.setSetting("EmuCore/CPU", "CoreType", "int", Integer.toString(position));
					}
					@Override public void onNothingSelected(AdapterView<?> parent) {}
				});
			}
		} catch (Throwable ignored) {}

        // Hardware Readbacks
        MaterialSwitch swHwRead = findViewById(R.id.sw_hw_readbacks);
        if (swHwRead != null) {
            try {
                String hr = NativeApp.getSetting("EmuCore/GS", "HardwareReadbacks", "bool");
                swHwRead.setChecked("true".equalsIgnoreCase(hr));
            } catch (Exception ignored) {}
            swHwRead.setOnCheckedChangeListener((buttonView, isChecked) ->
                    NativeApp.setSetting("EmuCore/GS", "HardwareReadbacks", "bool", isChecked ? "true" : "false"));
        }

        Slider sbEeRate = findViewById(R.id.sb_ee_cycle_rate);
        TextView tvEeRate = findViewById(R.id.tv_ee_cycle_rate);
        if (sbEeRate != null && tvEeRate != null) {
            try {
                String rate = NativeApp.getSetting("EmuCore/Speedhacks", "EECycleRate", "int");
                int v = (rate == null || rate.isEmpty()) ? 0 : Integer.parseInt(rate);
                if (v < -3) v = -3;
                if (v > 3) v = 3;
                sbEeRate.setValue(v);
                tvEeRate.setText("EE Cycle Rate: " + v);
            } catch (Exception ignored) {
                sbEeRate.setValue(0f);
                tvEeRate.setText("EE Cycle Rate: 0");
            }
            sbEeRate.addOnChangeListener((slider, value, fromUser) -> {
                int v = Math.max(-3, Math.min(3, Math.round(value)));
                if (v != Math.round(value)) slider.setValue(v);
                tvEeRate.setText("EE Cycle Rate: " + v);
                NativeApp.setSetting("EmuCore/Speedhacks", "EECycleRate", "int", Integer.toString(v));
            });
        }

        Slider sbEeSkip = findViewById(R.id.sb_ee_cycle_skip);
        TextView tvEeSkip = findViewById(R.id.tv_ee_cycle_skip);
        if (sbEeSkip != null && tvEeSkip != null) {
            try {
                String skip = NativeApp.getSetting("EmuCore/Speedhacks", "EECycleSkip", "int");
                int v = (skip == null || skip.isEmpty()) ? 0 : Integer.parseInt(skip);
                if (v < 0) v = 0;
                if (v > 3) v = 3;
                sbEeSkip.setValue(v);
                tvEeSkip.setText("EE Cycle Skip: " + v);
            } catch (Exception ignored) {
                sbEeSkip.setValue(0f);
                tvEeSkip.setText("EE Cycle Skip: 0");
            }
            sbEeSkip.addOnChangeListener((slider, value, fromUser) -> {
                int v = Math.max(0, Math.min(3, Math.round(value)));
                if (v != Math.round(value)) slider.setValue(v);
                tvEeSkip.setText("EE Cycle Skip: " + v);
                NativeApp.setSetting("EmuCore/Speedhacks", "EECycleSkip", "int", Integer.toString(v));
            });
        }

		MaterialSwitch swWaitLoop = findViewById(R.id.sw_wait_loop);
		if (swWaitLoop != null) {
			boolean enabled = true;
			try {
				String waitLoop = NativeApp.getSetting("EmuCore/Speedhacks", "WaitLoop", "bool");
				if (waitLoop != null && !waitLoop.isEmpty()) {
					enabled = !"false".equalsIgnoreCase(waitLoop);
				}
			} catch (Exception ignored) {}
			swWaitLoop.setChecked(enabled);
			swWaitLoop.setOnCheckedChangeListener((buttonView, isChecked) ->
					NativeApp.setSetting("EmuCore/Speedhacks", "WaitLoop", "bool", isChecked ? "true" : "false"));
		}

		MaterialSwitch swIntc = findViewById(R.id.sw_intc_spin);
		if (swIntc != null) {
			boolean enabled = true;
			try {
				String intc = NativeApp.getSetting("EmuCore/Speedhacks", "IntcStat", "bool");
				if (intc != null && !intc.isEmpty()) {
					enabled = !"false".equalsIgnoreCase(intc);
				}
			} catch (Exception ignored) {}
			swIntc.setChecked(enabled);
			swIntc.setOnCheckedChangeListener((buttonView, isChecked) ->
					NativeApp.setSetting("EmuCore/Speedhacks", "IntcStat", "bool", isChecked ? "true" : "false"));
		}

		MaterialSwitch swMvuFlag = findViewById(R.id.sw_mvu_flag);
		if (swMvuFlag != null) {
			boolean enabled = true;
			try {
				String flag = NativeApp.getSetting("EmuCore/Speedhacks", "vuFlagHack", "bool");
				if (flag != null && !flag.isEmpty()) {
					enabled = !"false".equalsIgnoreCase(flag);
				}
			} catch (Exception ignored) {}
			swMvuFlag.setChecked(enabled);
			swMvuFlag.setOnCheckedChangeListener((buttonView, isChecked) ->
					NativeApp.setSetting("EmuCore/Speedhacks", "vuFlagHack", "bool", isChecked ? "true" : "false"));
		}

		MaterialSwitch swInstantVu1 = findViewById(R.id.sw_instant_vu1);
		if (swInstantVu1 != null) {
			boolean enabled = true;
			try {
				String instant = NativeApp.getSetting("EmuCore/Speedhacks", "vu1Instant", "bool");
				if (instant != null && !instant.isEmpty()) {
					enabled = "true".equalsIgnoreCase(instant);
				}
			} catch (Exception ignored) {}
			swInstantVu1.setChecked(enabled);
			swInstantVu1.setOnCheckedChangeListener((buttonView, isChecked) ->
					NativeApp.setSetting("EmuCore/Speedhacks", "vu1Instant", "bool", isChecked ? "true" : "false"));
		}

		// VU Thread
		MaterialSwitch swVu = findViewById(R.id.sw_vu_thread);
		if (swVu != null) {
			try {
				String vu = NativeApp.getSetting("EmuCore/Speedhacks", "vuThread", "bool");
				swVu.setChecked("true".equalsIgnoreCase(vu));
			} catch (Exception ignored) {}
			if (swInstantVu1 != null) {
				swInstantVu1.setEnabled(!swVu.isChecked());
			}
			swVu.setOnCheckedChangeListener((b, isChecked) -> {
				NativeApp.setSetting("EmuCore/Speedhacks", "vuThread", "bool", isChecked ? "true" : "false");
				if (swInstantVu1 != null) {
					if (isChecked && swInstantVu1.isChecked()) {
						swInstantVu1.setChecked(false);
					}
					swInstantVu1.setEnabled(!isChecked);
				}
			});
		}

        // Fast CDVD
        MaterialSwitch swFastCdvd = findViewById(R.id.sw_fast_cdvd);
        if (swFastCdvd != null) {
            try {
                String fast = NativeApp.getSetting("EmuCore/Speedhacks", "fastCDVD", "bool");
                swFastCdvd.setChecked("true".equalsIgnoreCase(fast));
            } catch (Exception ignored) {}
            swFastCdvd.setOnCheckedChangeListener((b, isChecked) ->
                    NativeApp.setSetting("EmuCore/Speedhacks", "fastCDVD", "bool", isChecked ? "true" : "false"));
        }
    }

	private void initializeCustomizationSettings() {
		MaterialButton btnUiStyle = findViewById(R.id.btn_on_screen_ui_style);
		tvOnScreenUiStyleValue = findViewById(R.id.tv_on_screen_ui_style_value);
		updateOnScreenUiStyleSummary();
		if (btnUiStyle != null) {
			btnUiStyle.setOnClickListener(v -> showOnScreenUiStyleDialog());
		}
	}

	private void updateOnScreenUiStyleSummary() {
		if (tvOnScreenUiStyleValue == null) {
			return;
		}
		String current = getSharedPreferences("armsx2", MODE_PRIVATE)
				.getString("on_screen_ui_style", "default");
		int labelRes = "nether".equalsIgnoreCase(current)
				? R.string.on_screen_ui_style_nether
				: R.string.on_screen_ui_style_default;
		tvOnScreenUiStyleValue.setText(labelRes);
	}

	private void showOnScreenUiStyleDialog() {
		final String prefName = "armsx2";
		final String prefKey = "on_screen_ui_style";
		String current = getSharedPreferences(prefName, MODE_PRIVATE)
				.getString(prefKey, "default");
		int checked = "nether".equalsIgnoreCase(current) ? 1 : 0;
		CharSequence[] options = new CharSequence[]{
				getString(R.string.on_screen_ui_style_default),
				getString(R.string.on_screen_ui_style_nether)
		};
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.dialog_on_screen_ui_style_title)
				.setSingleChoiceItems(options, checked, (dialog, which) -> {
					String selected = which == 1 ? "nether" : "default";
					if (!selected.equalsIgnoreCase(current)) {
						getSharedPreferences(prefName, MODE_PRIVATE)
								.edit()
								.putString(prefKey, selected)
								.apply();
						updateOnScreenUiStyleSummary();
					}
					dialog.dismiss();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void initializeMemoryCardSettings() {
		Button btnImportMc = findViewById(R.id.btn_import_memcard);
		btnImportMc.setOnClickListener(v -> {
			Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
			i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
			i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
			i.setType("application/octet-stream");
			String[] types = new String[]{"application/octet-stream", "application/x-binary"};
			i.putExtra(Intent.EXTRA_MIME_TYPES, types);
			startActivityForResult(Intent.createChooser(i, "Select memory card"), REQ_IMPORT_MEMCARD);
		});
	}

	private void initializeStorageSettings() {
		tvDataDirPath = findViewById(R.id.tv_data_dir_path);
		MaterialButton btnChange = findViewById(R.id.btn_change_data_dir);
		updateDataDirSummary();
		if (btnChange != null) {
			btnChange.setOnClickListener(v -> launchDataDirectoryPicker());
		}

		MaterialButton btnAddSecondaryGameDir = findViewById(R.id.btn_add_secondary_game_dir);
		if (btnAddSecondaryGameDir != null) {
			btnAddSecondaryGameDir.setOnClickListener(v -> {
				Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
				intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
				startActivityForResult(intent, 9912); 
			});
		}

		MaterialSwitch swDev9Hdd = findViewById(R.id.sw_dev9_hdd_enable);
		TextView tvDev9HddPath = findViewById(R.id.tv_dev9_hdd_path);
		MaterialButton btnDev9Reset = findViewById(R.id.btn_dev9_reset_hdd);
		boolean hddEnabled = false;
		if (swDev9Hdd != null) {
			try {
				String value = NativeApp.getSetting("DEV9/Hdd", "HddEnable", "bool");
				hddEnabled = "true".equalsIgnoreCase(value);
			} catch (Exception ignored) {}
			swDev9Hdd.setChecked(hddEnabled);
		}
		updateDev9HddPathSummary(tvDev9HddPath, hddEnabled);
		if (swDev9Hdd != null) {
			final TextView finalTvDev9HddPath = tvDev9HddPath;
			swDev9Hdd.setOnCheckedChangeListener((buttonView, isChecked) -> {
				NativeApp.setSetting("DEV9/Hdd", "HddEnable", "bool", isChecked ? "true" : "false");
				updateDev9HddPathSummary(finalTvDev9HddPath, isChecked);
			});
		}
		if (btnDev9Reset != null) {
			btnDev9Reset.setOnClickListener(v -> {
				NativeApp.setSetting("DEV9/Hdd", "HddFile", "string", "DEV9hdd.raw");
				updateDev9HddPathSummary(tvDev9HddPath, swDev9Hdd != null && swDev9Hdd.isChecked());
				try {
					Toast.makeText(this, R.string.settings_dev9_hdd_reset_toast, Toast.LENGTH_SHORT).show();
				} catch (Throwable ignored) {}
			});
		}

		MaterialSwitch swDev9Network = findViewById(R.id.sw_dev9_network_enable);
		if (swDev9Network != null) {
			boolean networkEnabled = false;
			try {
				String value = NativeApp.getSetting("DEV9/Eth", "EthEnable", "bool");
				networkEnabled = "true".equalsIgnoreCase(value);
			} catch (Exception ignored) {}
			swDev9Network.setChecked(networkEnabled);
			swDev9Network.setOnCheckedChangeListener((buttonView, isChecked) ->
					NativeApp.setSetting("DEV9/Eth", "EthEnable", "bool", isChecked ? "true" : "false"));
		}
	}

	private void updateDev9HddPathSummary(@Nullable TextView target, boolean enabled) {
		if (target == null) {
			return;
		}
		String configured = null;
		try {
			configured = NativeApp.getSetting("DEV9/Hdd", "HddFile", "string");
		} catch (Exception ignored) {}
		if (TextUtils.isEmpty(configured)) {
			configured = "DEV9hdd.raw";
		}
		File resolved = new File(configured);
		if (!resolved.isAbsolute()) {
			File dataRoot = DataDirectoryManager.getDataRoot(getApplicationContext());
			if (dataRoot != null) {
				resolved = new File(dataRoot, configured);
			}
		}
		StringBuilder display = new StringBuilder(resolved.getAbsolutePath());
		if (!enabled) {
			display.append(" (disabled)");
		} else if (!resolved.exists()) {
			display.append(" (will be created on first use)");
		}
		target.setText(display.toString());
	}

	private void setupSectionNavigation(int initialSection) {
		sectionFlipper = findViewById(R.id.settings_view_flipper);
		if (sectionFlipper == null) {
			currentSection = SECTION_GENERAL;
			return;
		}
		sectionFlipper.setInAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in));
		sectionFlipper.setOutAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_out));

		sectionToggleGroup = findViewById(R.id.settings_toggle_group);
		if (sectionToggleGroup != null && sectionToggleGroup.getVisibility() == View.VISIBLE) {
			sectionToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
				if (!isChecked || suppressNavigationCallbacks) {
					return;
				}
				setSection(buttonIdToSection(checkedId));
			});
		}

		sectionTabs = findViewById(R.id.settings_tabs);
		if (sectionTabs != null && sectionTabs.getVisibility() == View.VISIBLE) {
			sectionTabs.clearOnTabSelectedListeners();
            if (sectionTabs.getTabCount() == 0) {
                sectionTabs.addTab(sectionTabs.newTab().setText(R.string.settings_section_general));
                sectionTabs.addTab(sectionTabs.newTab().setText(R.string.settings_section_graphics));
                sectionTabs.addTab(sectionTabs.newTab().setText(R.string.settings_section_performance));
                sectionTabs.addTab(sectionTabs.newTab().setText(R.string.settings_section_controller));
                sectionTabs.addTab(sectionTabs.newTab().setText(R.string.settings_section_customization));
                sectionTabs.addTab(sectionTabs.newTab().setText(R.string.settings_section_storage));
                sectionTabs.addTab(sectionTabs.newTab().setText(R.string.settings_section_achievements));
		}
			sectionTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
				@Override
				public void onTabSelected(TabLayout.Tab tab) {
					if (tab != null && !suppressNavigationCallbacks) {
						setSection(tab.getPosition());
					}
				}

				@Override public void onTabUnselected(TabLayout.Tab tab) {}
				@Override public void onTabReselected(TabLayout.Tab tab) {}
			});
		}

		setSection(initialSection);
	}

	private void setSection(int section) {
		if (sectionFlipper == null) {
			currentSection = SECTION_GENERAL;
			return;
		}
        if (section < SECTION_GENERAL || section > SECTION_ACHIEVEMENTS) {
            section = SECTION_GENERAL;
        }
		if (section >= sectionFlipper.getChildCount()) {
			section = Math.max(SECTION_GENERAL, sectionFlipper.getChildCount() - 1);
		}
		if (sectionFlipper.getDisplayedChild() != section) {
			sectionFlipper.setDisplayedChild(section);
		}
		currentSection = section;
		updateToolbarSubtitle(section);
		syncNavigationState(section);
	}

	private void syncNavigationState(int section) {
		suppressNavigationCallbacks = true;
		try {
			if (sectionToggleGroup != null && sectionToggleGroup.getVisibility() == View.VISIBLE) {
				int buttonId = sectionToButtonId(section);
				if (buttonId != View.NO_ID && sectionToggleGroup.getCheckedButtonId() != buttonId) {
					sectionToggleGroup.check(buttonId);
				}
			}
			if (sectionTabs != null && sectionTabs.getVisibility() == View.VISIBLE) {
				TabLayout.Tab tab = sectionTabs.getTabAt(section);
				if (tab != null && !tab.isSelected()) {
					tab.select();
				}
			}
		} finally {
			suppressNavigationCallbacks = false;
		}
	}

	private void updateToolbarSubtitle(int section) {
		if (toolbar == null) {
			return;
		}
		int resId = getSectionTitleRes(section);
		toolbar.setSubtitle(resId != 0 ? getString(resId) : null);
	}

    private int getSectionTitleRes(int section) {
        switch (section) {
            case SECTION_GRAPHICS: return R.string.settings_section_graphics;
            case SECTION_PERFORMANCE: return R.string.settings_section_performance;
            case SECTION_CONTROLLER: return R.string.settings_section_controller;
            case SECTION_CUSTOMIZATION: return R.string.settings_section_customization;
            case SECTION_STORAGE: return R.string.settings_section_storage;
            case SECTION_ACHIEVEMENTS: return R.string.settings_section_achievements;
            case SECTION_GENERAL:
            default: return R.string.settings_section_general;
        }
	}

	private int buttonIdToSection(int buttonId) {
        if (buttonId == R.id.btn_section_graphics) return SECTION_GRAPHICS;
        if (buttonId == R.id.btn_section_performance) return SECTION_PERFORMANCE;
        if (buttonId == R.id.btn_section_controller) return SECTION_CONTROLLER;
        if (buttonId == R.id.btn_section_customization) return SECTION_CUSTOMIZATION;
        if (buttonId == R.id.btn_section_storage) return SECTION_STORAGE;
        if (buttonId == R.id.btn_section_achievements) return SECTION_ACHIEVEMENTS;
        return SECTION_GENERAL;
    }

	private int sectionToButtonId(int section) {
        switch (section) {
            case SECTION_GRAPHICS: return R.id.btn_section_graphics;
            case SECTION_PERFORMANCE: return R.id.btn_section_performance;
            case SECTION_CONTROLLER: return R.id.btn_section_controller;
            case SECTION_CUSTOMIZATION: return R.id.btn_section_customization;
            case SECTION_STORAGE: return R.id.btn_section_storage;
            case SECTION_ACHIEVEMENTS: return R.id.btn_section_achievements;
            case SECTION_GENERAL:
            default: return R.id.btn_section_general;
        }
	}

	private void launchDataDirectoryPicker() {
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
		startActivityResultPickDataDir.launch(intent);
	}

	private void handleDataDirectorySelection(@NonNull Uri tree) {
		String resolvedPath = DataDirectoryManager.resolveTreeUriToPath(this, tree);
		if (resolvedPath == null || resolvedPath.trim().isEmpty()) {
			try { Toast.makeText(this, "Unable to use selected folder", Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
			return;
		}
		File targetDir = new File(resolvedPath);
		if (!targetDir.exists() && !targetDir.mkdirs()) {
			try { Toast.makeText(this, "Cannot create folders in the selected location", Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
			return;
		}
		if (!DataDirectoryManager.canUseDirectFileAccess(targetDir)) {
			showStorageAccessError(targetDir);
			return;
		}
		File currentDir = DataDirectoryManager.getDataRoot(getApplicationContext());
		if (currentDir != null && currentDir.getAbsolutePath().equals(targetDir.getAbsolutePath())) {
			DataDirectoryManager.storeCustomDataRoot(getApplicationContext(), targetDir.getAbsolutePath(), tree.toString());
			NativeApp.setDataRootOverride(targetDir.getAbsolutePath());
			updateDataDirSummary();
			try { Toast.makeText(this, "Already using that folder", Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
			return;
		}
		beginDataDirectoryMigration(currentDir, targetDir, tree.toString());
	}

	private void beginDataDirectoryMigration(@NonNull File currentDir, @NonNull File targetDir, @NonNull String uriString) {
		showDataDirProgressDialog();
		NativeApp.pause();
		NativeApp.shutdown();
		new Thread(() -> {
			boolean success = DataDirectoryManager.migrateData(currentDir, targetDir);
			if (success) {
				DataDirectoryManager.storeCustomDataRoot(getApplicationContext(), targetDir.getAbsolutePath(), uriString);
				NativeApp.setDataRootOverride(targetDir.getAbsolutePath());
				NativeApp.reinitializeDataRoot(targetDir.getAbsolutePath());
				DataDirectoryManager.copyAssetAll(getApplicationContext(), "resources");
			}
			runOnUiThread(() -> {
				dismissDataDirProgressDialog();
				if (success) {
					try { Toast.makeText(this, "Data location updated", Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
				} else {
					try { Toast.makeText(this, "Failed to move data", Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
				}
				updateDataDirSummary();
			});
		}, "DataDirMigration").start();
	}

	private void showDataDirProgressDialog() {
		runOnUiThread(() -> {
			if (dataDirProgressDialog != null && dataDirProgressDialog.isShowing()) {
				return;
			}
			ProgressBar progressBar = new ProgressBar(this);
			int padding = dpToPx(24);
			progressBar.setPadding(padding, padding, padding, padding);
			dataDirProgressDialog = new MaterialAlertDialogBuilder(this)
					.setTitle("Moving data")
					.setMessage("Moving emulator data to the selected folder…")
					.setView(progressBar)
					.setCancelable(false)
					.create();
			dataDirProgressDialog.show();
		});
	}

	private void dismissDataDirProgressDialog() {
		runOnUiThread(() -> {
			if (dataDirProgressDialog != null) {
				dataDirProgressDialog.dismiss();
				dataDirProgressDialog = null;
			}
		});
	}

	private void updateDataDirSummary() {
		if (tvDataDirPath == null) {
			return;
		}
		File dir = DataDirectoryManager.getDataRoot(getApplicationContext());
		if (dir != null) {
			tvDataDirPath.setText("Current location: " + dir.getAbsolutePath());
		} else {
			tvDataDirPath.setText("Current location: unavailable");
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt(STATE_SELECTED_SECTION, currentSection);
	}

	private int dpToPx(int dp) {
		return Math.round(dp * getResources().getDisplayMetrics().density);
	}

	private void showStorageAccessError(File targetDir) {
		boolean canGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !DataDirectoryManager.hasAllFilesAccess();
		String message = "Android denied direct file access for:\n" + targetDir.getAbsolutePath() +
			"\n\nGrant 'Allow access to all files' in system settings or choose a folder inside ARMSX2's storage.";
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this)
				.setTitle("Permission required")
				.setMessage(message)
				.setNegativeButton("OK", (d, w) -> d.dismiss());
		if (canGrant) {
			builder.setPositiveButton("Open settings", (d, w) -> {
				d.dismiss();
				openAllFilesAccessSettings();
			});
		}
		builder.show();
	}

	private void openAllFilesAccessSettings() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			try {
				Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
				intent.setData(Uri.parse("package:" + getPackageName()));
				startActivity(intent);
			} catch (Exception ignored) {
				try {
					Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
					startActivity(intent);
				} catch (Exception ignored2) {}
			}
		}
	}

	private void initializeActionButtons() {
		Button btnAbout = findViewById(R.id.btn_about);
		btnAbout.setOnClickListener(v -> {
			String versionName = "";
			try { 
				versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; 
			} catch (Exception ignored) {}
			String message = "ARMSX2 (" + versionName + ")\n" +
        		"by ARMSX2 team\n\n" +
        		"Core contributors:\n" +
        		"- MoonPower — App developer\n" +
        		"- jpolo — Management\n" +
        		"- Medieval Shell — Web developer\n" +
        		"- set l — Web developer\n" +
        		"- Alex — QA tester\n" +
        		"- Yua — QA tester\n\n" +
        		"Thanks to:\n" +
        		"- pontos2024 (emulator base)\n" +
        		"- PCSX2 v2.3.430 (core emulator)\n" +
        		"- SDL (SDL3)\n" +
        		"- Fffathur (icon design)";
			new MaterialAlertDialogBuilder(this)
					.setTitle("About")
					.setMessage(message)
					.setPositiveButton("OK", (d, w) -> d.dismiss())
					.show();
		});

		Button btnBack = findViewById(R.id.btn_back);
		btnBack.setOnClickListener(v -> finish());
	}

	private void showControllerCalibrationDialog() {
		View dialogView = getLayoutInflater().inflate(R.layout.dialog_controller_calibration, null);
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setView(dialogView)
				.setCancelable(true)
				.create();

		// Left Stick Deadzone
		SeekBar sbLeftDeadzone = dialogView.findViewById(R.id.sb_left_deadzone);
		TextView tvLeftDeadzone = dialogView.findViewById(R.id.tv_left_deadzone_value);
		try {
			String deadzone = NativeApp.getSetting("InputSources/SDL", "ControllerDeadzone", "float");
			float value = deadzone == null || deadzone.isEmpty() ? 0.10f : Float.parseFloat(deadzone);
			int progress = Math.round(value * 100);
			sbLeftDeadzone.setProgress(progress);
			tvLeftDeadzone.setText("Left Stick Deadzone: " + String.format("%.2f", value));
		} catch (Exception ignored) {}
		sbLeftDeadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				float value = progress / 100.0f;
				tvLeftDeadzone.setText("Left Stick Deadzone: " + String.format("%.2f", value));
			}
			@Override public void onStartTrackingTouch(SeekBar seekBar) {}
			@Override public void onStopTrackingTouch(SeekBar seekBar) {}
		});

		// Right Stick Deadzone
		SeekBar sbRightDeadzone = dialogView.findViewById(R.id.sb_right_deadzone);
		TextView tvRightDeadzone = dialogView.findViewById(R.id.tv_right_deadzone_value);
		try {
			String deadzone = NativeApp.getSetting("InputSources/SDL", "ControllerDeadzone", "float");
			float value = deadzone == null || deadzone.isEmpty() ? 0.10f : Float.parseFloat(deadzone);
			int progress = Math.round(value * 100);
			sbRightDeadzone.setProgress(progress);
			tvRightDeadzone.setText("Right Stick Deadzone: " + String.format("%.2f", value));
		} catch (Exception ignored) {}
		sbRightDeadzone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				float value = progress / 100.0f;
				tvRightDeadzone.setText("Right Stick Deadzone: " + String.format("%.2f", value));
			}
			@Override public void onStartTrackingTouch(SeekBar seekBar) {}
			@Override public void onStopTrackingTouch(SeekBar seekBar) {}
		});

		// Left Stick Sensitivity
		SeekBar sbLeftSensitivity = dialogView.findViewById(R.id.sb_left_sensitivity);
		TextView tvLeftSensitivity = dialogView.findViewById(R.id.tv_left_sensitivity_value);
		try {
			String sensitivity = NativeApp.getSetting("InputSources/SDL", "ControllerSensitivity", "float");
			float value = sensitivity == null || sensitivity.isEmpty() ? 1.0f : Float.parseFloat(sensitivity);
			int progress = Math.round(value * 100);
			sbLeftSensitivity.setProgress(progress);
			tvLeftSensitivity.setText("Left Stick Sensitivity: " + String.format("%.2f", value));
		} catch (Exception ignored) {}
		sbLeftSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				float value = progress / 100.0f;
				tvLeftSensitivity.setText("Left Stick Sensitivity: " + String.format("%.2f", value));
			}
			@Override public void onStartTrackingTouch(SeekBar seekBar) {}
			@Override public void onStopTrackingTouch(SeekBar seekBar) {}
		});

		// Right Stick Sensitivity
		SeekBar sbRightSensitivity = dialogView.findViewById(R.id.sb_right_sensitivity);
		TextView tvRightSensitivity = dialogView.findViewById(R.id.tv_right_sensitivity_value);
		try {
			String sensitivity = NativeApp.getSetting("InputSources/SDL", "ControllerSensitivity", "float");
			float value = sensitivity == null || sensitivity.isEmpty() ? 1.0f : Float.parseFloat(sensitivity);
			int progress = Math.round(value * 100);
			sbRightSensitivity.setProgress(progress);
			tvRightSensitivity.setText("Right Stick Sensitivity: " + String.format("%.2f", value));
		} catch (Exception ignored) {}
		sbRightSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				float value = progress / 100.0f;
				tvRightSensitivity.setText("Right Stick Sensitivity: " + String.format("%.2f", value));
			}
			@Override public void onStartTrackingTouch(SeekBar seekBar) {}
			@Override public void onStopTrackingTouch(SeekBar seekBar) {}
		});

		// Dialog buttons
		Button btnCancel = dialogView.findViewById(R.id.btn_calibrate_cancel);
		Button btnApply = dialogView.findViewById(R.id.btn_calibrate_apply);

		btnCancel.setOnClickListener(v -> dialog.dismiss());
		btnApply.setOnClickListener(v -> {
			// Apply settings
			float leftDeadzone = sbLeftDeadzone.getProgress() / 100.0f;
			float rightDeadzone = sbRightDeadzone.getProgress() / 100.0f;
			float leftSensitivity = sbLeftSensitivity.getProgress() / 100.0f;
			float rightSensitivity = sbRightSensitivity.getProgress() / 100.0f;

			NativeApp.setSetting("InputSources/SDL", "ControllerDeadzone", "float", String.valueOf(Math.max(leftDeadzone, rightDeadzone)));
			NativeApp.setSetting("InputSources/SDL", "ControllerSensitivity", "float", String.valueOf(Math.max(leftSensitivity, rightSensitivity)));
			
			Toast.makeText(this, "Controller settings applied", Toast.LENGTH_SHORT).show();
			dialog.dismiss();
		});

		dialog.show();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQ_IMPORT_MEMCARD && resultCode == RESULT_OK && data != null && data.getData() != null) {
			Uri uri = data.getData();
			try { 
				getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); 
			} catch (Exception ignored) {}
			if (importMemcardToSlot1(uri)) {
				NativeApp.setSetting("MemoryCards", "Slot1_Enable", "bool", "false");
				NativeApp.setSetting("MemoryCards", "Slot1_Filename", "string", "Mcd001.ps2");
				NativeApp.setSetting("MemoryCards", "Slot1_Enable", "bool", "true");
				Toast.makeText(this, "Memory card inserted (Slot 1)", Toast.LENGTH_SHORT).show();
			} else {
				Toast.makeText(this, "Failed to import memory card", Toast.LENGTH_LONG).show();
			}
		} else if (requestCode == 9912 && resultCode == RESULT_OK && data != null && data.getData() != null) {
			Uri uri = data.getData();
			try {
				final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				getContentResolver().takePersistableUriPermission(uri, takeFlags);
				
				// Store the secondary game directory URI in shared preferences
				String uriString = uri.toString();
				android.content.SharedPreferences prefs = getSharedPreferences("armsx2", MODE_PRIVATE);
				java.util.Set<String> existingDirs = prefs.getStringSet("secondary_game_dirs", new java.util.HashSet<>());
				java.util.Set<String> newDirs = new java.util.HashSet<>(existingDirs);
				newDirs.add(uriString);
				prefs.edit().putStringSet("secondary_game_dirs", newDirs).apply();
				
				Toast.makeText(this, "Secondary game directory added", Toast.LENGTH_SHORT).show();
			} catch (Exception e) {
				Toast.makeText(this, "Failed to add secondary game directory", Toast.LENGTH_LONG).show();
			}
		}
	}

	private boolean importMemcardToSlot1(Uri uri) {
		try {
			File base = DataDirectoryManager.getDataRoot(getApplicationContext());
			File memDir = new File(base, "memcards");
			if (!memDir.exists() && !memDir.mkdirs()) return false;
			File out = new File(memDir, "Mcd001.ps2");
			try (InputStream in = getContentResolver().openInputStream(uri);
				 OutputStream os = new FileOutputStream(out)) {
				if (in == null) return false;
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
				os.flush();
			}
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
