// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;
import com.shilapi.xcertplay.hud.HongqiNavigation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.WeakHashMap;

/** HS5 instrument navigation uses horizontal swipes to avoid the factory downward Home gesture. */
public final class HongqiNavigationControls {
    private static final String TAG = "DiPlay-HongqiNavi";
    private static final String CARD_TAG = "diplay_hongqi_navigation";
    private static final WeakHashMap<Activity, Gesture> GESTURES = new WeakHashMap<>();
    private HongqiNavigationControls() {}

    /** Instrument visibility is controlled solely by the navigation gesture. */
    public static void addCard(Activity activity, ScrollView scroll) {}

    /** This host hook also compiles against the release APK without shipping API substitute classes. */
    public static boolean onTouch(Activity host, MotionEvent event) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.FAW_NAVIGATION_OUTPUTS_ENABLED || !HongqiNavigation.supported()) return false;
        try {
            Field outside = host.getClass().getDeclaredField("touchOutsideContent");
            outside.setAccessible(true);
            if (outside.getBoolean(host) && event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
            Gesture gesture = GESTURES.get(host);
            if (gesture == null) { gesture = new Gesture(); GESTURES.put(host, gesture); }
            int action = gesture.accept(event, host.getResources().getDisplayMetrics().density,
                    HongqiNavigation.isNavigating());
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 3)
                Log.i(TAG, "three-finger horizontal start navigating=" + HongqiNavigation.isNavigating());
            if (action == 0) return false;
            if (action == 1 || action == 2) {
                Method release = host.getClass().getDeclaredMethod("releaseTouch");
                release.setAccessible(true);
                release.invoke(host);
                boolean shown = action == 1;
                boolean provisioned = provision(host, shown);
                HongqiNavigation.setMapShown(host, shown);
                if (shown && !Settings.canDrawOverlays(host)) {
                    Toast.makeText(host, "请允许 FAW Play 显示在其他应用上层，以显示仪表地图", Toast.LENGTH_LONG).show();
                    host.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + host.getPackageName())));
                    return true;
                }
                if (provisioned) {
                    Method reconnect = host.getClass().getDeclaredMethod("reconnectAfterLoss", String.class,
                            Class.forName("com.shilapi.xcertplay.network.WirelessStartupFailure"));
                    reconnect.setAccessible(true);
                    reconnect.invoke(host, "Instrument map enabled; requesting stream", null);
                }
                Toast.makeText(host, shown ? "仪表地图已开启" : "仪表地图已关闭", Toast.LENGTH_SHORT).show();
                Log.i(TAG, "three-finger horizontal instrument map shown=" + shown);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Log.w(TAG, "instrument gesture unavailable: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean provision(Context app, boolean shown) {
        if (!shown || app.getSharedPreferences("xcertplay_airplay", 0)
                .getBoolean("cluster_map_enabled", true)) return false;
        app.getSharedPreferences("xcertplay_airplay", 0).edit().putBoolean("cluster_map_enabled", true).apply();
        return true;
    }

    private static void reconnectSettings(Activity activity) {
        try {
            Method reconnect = activity.getClass().getDeclaredMethod("reconnectForClusterMap");
            reconnect.setAccessible(true); reconnect.invoke(activity);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Log.w(TAG, "map stream will be enabled on next connection");
        }
    }

    private static TextView label(Context context, String text, int size) {
        TextView label = new TextView(context); label.setText(text); label.setTextSize(size);
        label.setTextColor(Color.rgb(241, 245, 252)); return label;
    }
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** 0 passes through, 1 opens, 2 closes, 3 consumes the rest of an accepted gesture. */
    public static final class Gesture {
        private static final int FINGER_COUNT = 3;
        private static final float MIN_SWIPE_DP = 72;
        private static final float MIN_FINGER_SWIPE_DP = 36;
        private static final float HORIZONTAL_DOMINANCE = 1.3f;
        private boolean tracking, consumed, rejected;
        private final int[] ids = new int[FINGER_COUNT];
        private final float[] xs = new float[FINGER_COUNT], ys = new float[FINGER_COUNT];
        public int accept(MotionEvent event, float density) {
            return accept(event, density, true);
        }
        public int accept(MotionEvent event, float density, boolean navigating) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) { tracking = consumed = rejected = false; return 0; }
            if (consumed) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) consumed = false;
                return 3;
            }
            if (!navigating) { tracking = false; return 0; }
            // Extra fingers invalidate this whole sequence, including after they are lifted.
            if (event.getPointerCount() > FINGER_COUNT) { rejected = true; tracking = false; }
            if (rejected) return 0;
            if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == FINGER_COUNT) {
                tracking = true;
                for (int i = 0; i < FINGER_COUNT; i++) {
                    ids[i] = event.getPointerId(i); xs[i] = event.getX(i); ys[i] = event.getY(i);
                }
                return 0;
            }
            if (event.getPointerCount() != FINGER_COUNT || action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                tracking = false; return 0;
            }
            if (!tracking || action != MotionEvent.ACTION_MOVE) return 0;
            float deltaX = 0, deltaY = 0;
            float[] moves = new float[FINGER_COUNT];
            for (int i = 0; i < FINGER_COUNT; i++) {
                int at = event.findPointerIndex(ids[i]);
                if (at < 0) { tracking = false; return 0; }
                moves[i] = event.getX(at) - xs[i];
                deltaX += moves[i]; deltaY += Math.abs(event.getY(at) - ys[i]);
            }
            deltaX /= FINGER_COUNT; deltaY /= FINGER_COUNT;
            if (Math.abs(deltaX) < MIN_SWIPE_DP * density
                    || Math.abs(deltaX) < deltaY * HORIZONTAL_DOMINANCE) return 0;
            float direction = Math.signum(deltaX);
            for (float move : moves) if (move * direction < MIN_FINGER_SWIPE_DP * density) return 0;
            consumed = true; tracking = false;
            return deltaX < 0 ? 1 : 2;
        }
    }
}
