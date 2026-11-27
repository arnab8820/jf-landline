package com.jio.calling.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Manages user configurations and toggles for Jio Landline:
 * - Stock dialer call interception (enabled by default on < Android 6, disabled by default on Android 6+)
 * - Android Telecom calling account registration (Android 6.0+)
 * - In-call UI mode (System in-call UI vs App overlay)
 * - Emergency number safety bypass
 */
public class AppPreferences {
    private static final String PREF_NAME = "jio_calling_config";

    private static final String KEY_INTERCEPT_CALLS = "intercept_outgoing_calls";
    private static final String KEY_TELECOM_ENABLED = "telecom_account_enabled";
    private static final String KEY_USE_TELECOM_UI = "use_telecom_system_ui";
    private static final String KEY_EXCLUDE_EMERGENCY = "exclude_emergency_numbers";

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Whether outgoing calls dialed from the stock phone dialer should be intercepted.
     * Default:
     * - Android < 6.0: true (Telecom Calling Accounts do not exist, so interception is the primary routing method)
     * - Android >= 6.0: false (Android Telecom Manager / Calling Account handles routing natively)
     */
    public static boolean isCallInterceptionEnabled(Context context) {
        boolean defaultIntercept = Build.VERSION.SDK_INT < Build.VERSION_CODES.M;
        return getPrefs(context).getBoolean(KEY_INTERCEPT_CALLS, defaultIntercept);
    }

    public static void setCallInterceptionEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_INTERCEPT_CALLS, enabled).apply();
    }

    /**
     * Whether to register a PhoneAccount with Android's TelecomManager (Android 6.0+).
     * Default: true on API 23+, false on older versions.
     */
    public static boolean isTelecomEnabled(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        return getPrefs(context).getBoolean(KEY_TELECOM_ENABLED, true);
    }

    public static void setTelecomEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_TELECOM_ENABLED, enabled).apply();
    }

    /**
     * Whether to route calls through Android Telecom system in-call UI instead of app's custom UI.
     * Default: true on API 23+, false on older versions.
     */
    public static boolean isUseTelecomUiEnabled(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        return getPrefs(context).getBoolean(KEY_USE_TELECOM_UI, true);
    }

    public static void setUseTelecomUiEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_USE_TELECOM_UI, enabled).apply();
    }

    /**
     * Whether emergency numbers (100, 101, 102, 108, 112, 911) should strictly bypass interception.
     * Default: true
     */
    public static boolean isEmergencyExclusionEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_EXCLUDE_EMERGENCY, true);
    }

    public static void setEmergencyExclusionEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_EXCLUDE_EMERGENCY, enabled).apply();
    }

    private static final String KEY_TARGET_WIFI_SSID = "target_wifi_ssid";

    /**
     * User-configured preferred home JioFiber Wi-Fi SSID.
     * If empty, the app scans configured networks for any known JioFiber network.
     */
    public static String getTargetWifiSsid(Context context) {
        return getPrefs(context).getString(KEY_TARGET_WIFI_SSID, "");
    }

    public static void setTargetWifiSsid(Context context, String ssid) {
        getPrefs(context).edit().putString(KEY_TARGET_WIFI_SSID, ssid != null ? ssid : "").apply();
    }
}
