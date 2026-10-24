// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import java.util.Map;
import java.util.WeakHashMap;

/** The app's appearance is independent of the projected CarPlay day/night mode. */
public final class AutunnUiTheme {
    private static final String PREFERENCES = "faw_ui";
    private static final String DARK = "dark";
    private static final String MODE = "mode";
    private static final String LAST_AUTOMATIC_DARK = "last_auto_dark";
    // Only live OEM signals are cached here; a stopped Activity cannot override a new uiMode.
    private static final Map<Activity, Boolean> VEHICLE_NIGHT = new WeakHashMap<>();
    private static final Palette LIGHT = new Palette(false,
            0xFFF1DDDF, 0xFFFCF6F7, 0xFFE2CCD0, 0xFF262125,
            0xFF776B72, 0xFFE72D48, 0xFFFCE5E9, Color.WHITE);
    private static final Palette NIGHT = new Palette(true,
            0xFF1A1A1D, 0xFF262629, 0xFF38383D, 0xFFF4F4F6,
            0xFFAFADB5, 0xFF1DDFFF, 0xFF263C42, 0xFF1A1A1D);

    private AutunnUiTheme() {}

    public static Palette palette(Context context) {
        return isDark(context) ? NIGHT : LIGHT;
    }

    public static boolean isDark(Context context) {
        String mode = mode(context);
        if ("light".equals(mode)) return false;
        if ("dark".equals(mode)) return true;
        Boolean night;
        synchronized (VEHICLE_NIGHT) { night = VEHICLE_NIGHT.get(activity(context)); }
        if (night == null) {
            int systemMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (systemMode == Configuration.UI_MODE_NIGHT_YES) night = true;
            else if (systemMode == Configuration.UI_MODE_NIGHT_NO) night = false;
        }
        SharedPreferences preferences = preferences(context);
        if (night == null) return preferences.getBoolean(LAST_AUTOMATIC_DARK, true);
        if (!preferences.contains(LAST_AUTOMATIC_DARK) ||
                preferences.getBoolean(LAST_AUTOMATIC_DARK, true) != night) {
            preferences.edit().putBoolean(LAST_AUTOMATIC_DARK, night).apply();
        }
        return night;
    }

    public static void setDark(Context context, boolean dark) {
        setMode(context, dark ? "dark" : "light");
    }

    /** system follows the vehicle; explicit light/dark choices remain independent of CarPlay. */
    public static String mode(Context context) {
        SharedPreferences preferences = preferences(context);
        String mode = preferences.getString(MODE, null);
        if ("system".equals(mode) || "light".equals(mode) || "dark".equals(mode)) return mode;
        // Preserve a manual choice saved by the first two-theme version.
        if (mode == null && preferences.contains(DARK)) return preferences.getBoolean(DARK, true) ? "dark" : "light";
        return "system";
    }

    public static void setMode(Context context, String mode) {
        if (!"system".equals(mode) && !"light".equals(mode) && !"dark".equals(mode)) {
            throw new IllegalArgumentException("Unknown appearance mode");
        }
        preferences(context).edit().putString(MODE, mode).remove(DARK).apply();
    }

    /** Register while the Activity is visible; close the returned watch from onStop. */
    public static AutoCloseable watch(Activity activity, Runnable onAppearanceChanged) {
        return new AutunnUiAppearanceWatch(activity, onAppearanceChanged);
    }

    static void updateVehicleNight(Activity activity, Boolean night) {
        synchronized (VEHICLE_NIGHT) {
            if (night == null) VEHICLE_NIGHT.remove(activity);
            else VEHICLE_NIGHT.put(activity, night);
        }
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }

    public static final class Palette {
        public final boolean dark;
        public final int background, surface, border, text, muted, accent, selected, onAccent;

        private Palette(boolean dark, int background, int surface, int border, int text,
                        int muted, int accent, int selected, int onAccent) {
            this.dark = dark;
            this.background = background;
            this.surface = surface;
            this.border = border;
            this.text = text;
            this.muted = muted;
            this.accent = accent;
            this.selected = selected;
            this.onAccent = onAccent;
        }
    }
}
