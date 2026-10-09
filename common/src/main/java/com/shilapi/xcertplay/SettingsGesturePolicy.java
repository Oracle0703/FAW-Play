// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay;

import android.content.Context;
import android.content.SharedPreferences;

/** Three fingers are reserved for the HS5 instrument display; settings default to two. */
public final class SettingsGesturePolicy {
    private static final String PREFS = "xcertplay_airplay";
    private static final String KEY = "settings_gesture_fingers";
    private SettingsGesturePolicy() {}

    public static int normalize(Context context, int fingers) {
        int value = Math.max(2, Math.min(4, fingers));
        return value == 3 && HongqiWheelControls.supported(context) ? 2 : value;
    }

    public static int load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int saved = prefs.getInt(KEY, 2);
        int value = normalize(context, saved);
        if (value != saved) prefs.edit().putInt(KEY, value).apply();
        return value;
    }

    public static void save(Context context, int fingers) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY, normalize(context, fingers)).apply();
    }

    public static int next(Context context, int fingers) {
        int value = normalize(context, fingers);
        return HongqiWheelControls.supported(context) ? (value == 2 ? 4 : 2)
                : (value >= 4 ? 2 : value + 1);
    }
}
