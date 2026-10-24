// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed HS5 operating parameters. These are not user-configurable settings. */
public final class AutunnSystemParameters {
    public static final int AMBIENT_LUX = 30;
    // Music arrives in ~400–460 ms bursts on wireless CarPlay; keep a full burst before play.
    public static final int MEDIA_BUFFER_MS = 500;
    public static final int WIDTH_MM = 300;
    private AutunnSystemParameters() {}

    public static int sanitizeFps(int value) {
        return value == 50 || value == 60 ? value : 30;
    }
    public static int fps(Context context) {
        return sanitizeFps(context.getSharedPreferences("xcertplay_airplay", 0).getInt("display_fps", 30));
    }
    public static void saveFps(Context context, int value) {
        context.getSharedPreferences("xcertplay_airplay", 0).edit()
                .putInt("display_fps", sanitizeFps(value)).apply();
    }
    /** Reapply only changed fixed values; never clears pairing, keys, or independent audio routing. */
    public static void enforce(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("xcertplay_airplay", 0);
        Map<String, Object> fixed = new LinkedHashMap<>();
        fixed.put("carplay_night_mode", "ambient");
        fixed.put("ambient_lux_threshold", AMBIENT_LUX);
        fixed.put("ambient_delay_seconds", 0);
        fixed.put("display_width_physical_mm", WIDTH_MM);
        fixed.put("display_physical_size_basis", "WIDTH");
        fixed.put("display_scale_percent", 100);
        fixed.put("display_scale_tenths", 10);
        fixed.put("ui_scale_percent", 100);
        fixed.put("media_buffer_ms", MEDIA_BUFFER_MS);
        fixed.put("hevc_enabled", false);
        fixed.put("hevc_software_decoder", false);
        fixed.put("right_hand_drive", false);
        fixed.put("hide_top_bar", true);
        fixed.put("hide_bottom_bar", true);
        fixed.put("safe_area_draw_outside", true);
        fixed.put("adapt_pip_resolution", true);
        fixed.put("display_fps", fps(context));
        fixed.put("manufacturer", "Autunn Play");
        fixed.put("model", "Autunn Play");
        // Preserve the vehicle default only on first initialization; rendering must respect a
        // later explicit opt-out made by the user.
        if (com.shilapi.xcertplay.hud.HongqiNavigation.supported() && !prefs.contains("location_reporting_enabled")) {
            fixed.put("location_reporting_enabled", true);
        }
        SharedPreferences.Editor editor = null;
        Map<String, ?> stored = prefs.getAll();
        for (Map.Entry<String, Object> entry : fixed.entrySet()) {
            if (entry.getValue().equals(stored.get(entry.getKey()))) continue;
            if (editor == null) editor = prefs.edit();
            Object value = entry.getValue();
            if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else editor.putString(entry.getKey(), (String) value);
        }
        if (editor != null) editor.apply();
        SharedPreferences picture = context.getSharedPreferences("carplay_picture", 0);
        String[] keys = {"brightness", "contrast", "saturation", "warmth"};
        int[] values = {0, 100, 100, 0};
        SharedPreferences.Editor color = null;
        for (int i = 0; i < keys.length; i++) {
            if (picture.getInt(keys[i], values[i]) == values[i]) continue;
            if (color == null) color = picture.edit();
            color.putInt(keys[i], values[i]);
        }
        if (color != null) color.apply();
    }
}
