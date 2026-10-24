// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.shilapi.xcertplay.network.WifiRadioStartup;

import java.util.ArrayList;
import java.util.List;

/** User-approved connection access. Does not attempt to grant privileged tethering permissions. */
public final class PermissionAccess {
    private static final String PREFERENCES = "diplay_connection_access";
    private static final String ASKED = "asked_";
    private static final int REQUEST_CONNECTION = 0x6d51;
    private static final int TEXT = Color.rgb(241, 245, 252);
    private static final int MUTED = Color.rgb(177, 190, 208);
    private static final int SURFACE = Color.rgb(21, 30, 44);
    private static final int BORDER = Color.rgb(42, 56, 75);

    private PermissionAccess() {}

    /** Invoked once per app launch, including before an iPhone has been paired. */
    public static void enableWifiOnLaunch(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        int result = WifiRadioStartup.requestEnable(activity);
        if (result == WifiRadioStartup.USER_ACTION) {
            Toast.makeText(activity, text(activity, "请在系统页面开启 Wi-Fi 后返回 Autunn Play。",
                    "Turn on Wi-Fi in the system panel, then return to Autunn Play."), Toast.LENGTH_LONG).show();
            openWifiSettings(activity);
        } else if (result == WifiRadioStartup.UNAVAILABLE) {
            Toast.makeText(activity, text(activity, "Wi-Fi 暂不可用，请检查车机 Wi-Fi 设置。",
                    "Wi-Fi is unavailable. Check the head unit Wi-Fi settings."), Toast.LENGTH_LONG).show();
        }
    }

    /** Request only missing, never-before-requested connection permissions on first app launch. */
    public static void ensure(final Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        final List<String> missing = missing(activity);
        final SharedPreferences preferences = preferences(activity);
        final List<String> firstRequest = new ArrayList<>();
        for (String permission : missing) {
            if (!preferences.getBoolean(ASKED + permission, false)) firstRequest.add(permission);
        }
        if (firstRequest.isEmpty()) return;
        // Record before posting: rendering again while a system prompt is open must not queue it twice.
        markAsked(preferences, firstRequest);
        activity.getWindow().getDecorView().post(new Runnable() {
            @Override public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                List<String> stillMissing = new ArrayList<>();
                for (String permission : firstRequest) {
                    if (!granted(activity, permission)) stillMissing.add(permission);
                }
                if (!stillMissing.isEmpty()) requestSystem(activity, stillMissing);
            }
        });
    }

    /** Explicit retry is available after denial; a permanent denial opens the app's system settings. */
    public static void request(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        List<String> missing = missing(activity);
        if (missing.isEmpty()) {
            Toast.makeText(activity, text(activity, "连接权限已授权", "Connection access is already allowed"), Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences preferences = preferences(activity);
        List<String> requestable = new ArrayList<>();
        for (String permission : missing) {
            if (!preferences.getBoolean(ASKED + permission, false)
                    || activity.shouldShowRequestPermissionRationale(permission)) requestable.add(permission);
        }
        if (requestable.isEmpty()) {
            openAppSettings(activity);
        } else {
            markAsked(preferences, requestable);
            requestSystem(activity, requestable);
        }
    }

    private static void requestSystem(Activity activity, List<String> permissions) {
        if (Build.VERSION.SDK_INT >= 31 && Build.VERSION.SDK_INT < 33
                && permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION)
                && !permissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        try {
            activity.requestPermissions(permissions.toArray(new String[0]), REQUEST_CONNECTION);
        } catch (RuntimeException unavailable) {
            // Some head-unit permission controllers omit the standard prompt. Keep a useful fallback.
            openAppSettings(activity);
        }
    }

    /** Append inline access status and system authorization actions to the existing help card. */
    public static void addControls(final Activity activity, LinearLayout card) {
        final TextView status = new TextView(activity) {
            @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (hasWindowFocus) setText(PermissionAccess.status(activity));
            }
            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                setText(PermissionAccess.status(activity));
            }
        };
        status.setText(status(activity));
        status.setTextColor(TEXT);
        status.setTextSize(16);
        status.setPadding(0, dp(activity, 12), 0, dp(activity, 8));
        card.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button connection = button(activity, text(activity, "申请连接权限", "Allow connection access"));
        connection.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { request(activity); }
        });
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.rightMargin = dp(activity, 8);
        actions.addView(connection, left);
        card.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        TextView explanation = new TextView(activity);
        explanation.setText(text(activity,
                "连接使用 Wi-Fi Direct。请保持车机和 iPhone 的 Wi-Fi、蓝牙开启。",
                "Connection uses Wi-Fi Direct. Keep Wi-Fi and Bluetooth on in the head unit and iPhone."));
        explanation.setTextColor(MUTED);
        explanation.setTextSize(14);
        explanation.setPadding(0, dp(activity, 8), 0, 0);
        card.addView(explanation, new LinearLayout.LayoutParams(-1, -2));
        Button wifi = button(activity, text(activity, "Wi-Fi 设置", "Wi-Fi settings"));
        wifi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openWifiSettings(activity); }
        });
        LinearLayout.LayoutParams wifiParams = new LinearLayout.LayoutParams(-1, -2);
        wifiParams.topMargin = dp(activity, 8);
        card.addView(wifi, wifiParams);
    }

    public static String status(Context context) {
        boolean bluetooth = Build.VERSION.SDK_INT < 31 || granted(context, Manifest.permission.BLUETOOTH_CONNECT);
        boolean wifi = granted(context, Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION);
        return text(context, "蓝牙：", "Bluetooth: ") + allowed(context, bluetooth)
                + (bluetooth ? " · " + bluetoothState(context) : "")
                + "\n" + text(context, "Wi-Fi：", "Wi-Fi: ") + allowed(context, wifi)
                + " · " + wifiState(context)
                + (Build.VERSION.SDK_INT >= 37 ? "\n" + text(context, "局域网：", "Local network: ")
                    + allowed(context, granted(context, Manifest.permission.ACCESS_LOCAL_NETWORK)) : "");
    }

    private static String bluetoothState(Context context) {
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) return text(context, "设备不支持", "Not available");
            return enabled(context, adapter.isEnabled());
        } catch (RuntimeException unavailable) {
            return text(context, "开关状态不可读取", "Switch state unavailable");
        }
    }

    private static String wifiState(Context context) {
        try {
            WifiManager manager = context.getApplicationContext().getSystemService(WifiManager.class);
            if (manager == null) return text(context, "设备不支持", "Not available");
            return enabled(context, manager.isWifiEnabled());
        } catch (RuntimeException unavailable) {
            return text(context, "开关状态不可读取", "Switch state unavailable");
        }
    }

    private static String enabled(Context context, boolean enabled) {
        return enabled ? text(context, "已开启", "On") : text(context, "已关闭", "Off");
    }

    public static boolean canWriteSettings(Context context) {
        try { return Settings.System.canWrite(context); }
        catch (RuntimeException unavailable) { return false; }
    }

    public static void openWriteSettings(Activity activity) {
        open(activity, new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
    }

    public static void openAppSettings(Activity activity) {
        open(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
    }

    public static void openWifiSettings(Activity activity) {
        if (Build.VERSION.SDK_INT >= 29 && tryOpen(activity, new Intent(Settings.Panel.ACTION_WIFI))) return;
        open(activity, new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    public static void openBluetoothSettings(Activity activity) {
        open(activity, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    private static void open(Activity activity, Intent intent) {
        if (!tryOpen(activity, intent)) {
            Toast.makeText(activity, text(activity,
                    "系统未提供此页面，请在系统设置中授权。",
                    "This system does not provide that page. Allow access in system settings."), Toast.LENGTH_LONG).show();
        }
    }

    private static boolean tryOpen(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    private static List<String> missing(Context context) {
        List<String> required = new ArrayList<>();
        if (com.shilapi.xcertplay.hud.HongqiNavigation.supported())
            required.add(com.shilapi.xcertplay.hud.HongqiVehicleSpeed.PERMISSION);
        if (Build.VERSION.SDK_INT >= 31) required.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 37) required.add(Manifest.permission.ACCESS_LOCAL_NETWORK);
        if (Build.VERSION.SDK_INT >= 33) {
            required.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else {
            // Android 12 and earlier require location for Wi-Fi Direct / local-only hotspot.
            required.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            required.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> result = new ArrayList<>();
        for (String permission : required) if (!granted(context, permission)) result.add(permission);
        // Fine location must be requested together with coarse location on Android 12+.
        if (Build.VERSION.SDK_INT >= 31 && Build.VERSION.SDK_INT < 33
                && result.contains(Manifest.permission.ACCESS_FINE_LOCATION)
                && !result.contains(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            result.add(0, Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        return result;
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static void markAsked(SharedPreferences preferences, List<String> permissions) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String permission : permissions) editor.putBoolean(ASKED + permission, true);
        editor.apply();
    }

    private static String allowed(Context context, boolean allowed) {
        return allowed ? text(context, "已授权", "Allowed") : text(context, "待授权", "Not allowed");
    }

    private static String text(Context context, String chinese, String english) {
        return context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh") ? chinese : english;
    }

    private static Button button(Context context, String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(SURFACE);
        background.setCornerRadius(dp(context, 12));
        background.setStroke(dp(context, 1), BORDER);
        button.setBackground(background);
        return button;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
