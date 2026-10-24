// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay;

import com.shilapi.xcertplay.airplay.AirPlayPhoneState;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Four opt-in, learned wheel keys, filtered by the existing WheelKeyService. */
public final class SteeringWheelControls {
    public static final int PREVIOUS = 0;
    public static final int NEXT = 1;
    public static final int PHONE = 2;
    public static final int SIRI = 3;
    private static final String[] ROLES = {"PREVIOUS", "NEXT", "PHONE", "SIRI"};
    private static final String PREFS = "diplay_steering_controls";
    private static final String CARD_TAG = "diplay_steering_controls_card";
    private static final String TAG = "DiPlay-Steering";
    private static final Object LOCK = new Object();
    private static final Map<PhysicalKey, Press> PRESSES = new HashMap<>();
    private static Learning learning;
    private static Handler mainHandler;
    private static final int TEXT = Color.rgb(241, 245, 252);
    private static final int MUTED = Color.rgb(177, 190, 208);
    private static final int SURFACE = Color.rgb(21, 30, 44);
    private static final int BORDER = Color.rgb(42, 56, 75);
    private static final int ACCENT = Color.rgb(166, 200, 255);

    private SteeringWheelControls() {}

    private static final class PhysicalKey {
        final int device;
        final int code;
        final int scan;
        PhysicalKey(KeyEvent event) { device = event.getDeviceId(); code = event.getKeyCode(); scan = event.getScanCode(); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof PhysicalKey)) return false;
            PhysicalKey key = (PhysicalKey) other;
            return device == key.device && code == key.code && scan == key.scan;
        }
        @Override public int hashCode() { return (device * 31 + code) * 31 + scan; }
    }

    private static final class Press {
        final long downTime;
        final boolean consumed;
        SiriPress siri;
        Press(KeyEvent event, boolean consumed) { downTime = event.getDownTime(); this.consumed = consumed; }
    }

    private static final class Binding {
        final int code;
        final int scan;
        final String deviceName;
        Binding(int code, int scan, String deviceName) { this.code = code; this.scan = scan; this.deviceName = deviceName; }
        static Binding from(KeyEvent event) {
            InputDevice device = InputDevice.getDevice(event.getDeviceId());
            return new Binding(event.getKeyCode(), event.getScanCode(), device == null ? "?" : device.getName());
        }
        boolean matches(Binding other) {
            return code == other.code && scan == other.scan && deviceName.equals(other.deviceName);
        }
    }

    private static final class Learning {
        final int role;
        final long deadline;
        final WeakReference<ControlCard> owner;
        final Runnable timeout;
        Learning(int role, ControlCard card) {
            this.role = role;
            deadline = SystemClock.uptimeMillis() + 10_000;
            owner = new WeakReference<>(card);
            timeout = new Runnable() {
                @Override public void run() {
                    synchronized (LOCK) {
                        if (learning != Learning.this) return;
                        finishLearning(text(cardContext(), "学习已超时，请重新点击学习。", "Learning timed out. Select Learn again."));
                    }
                }
            };
        }
        Context cardContext() {
            ControlCard card = owner.get();
            return card == null ? null : card.getContext();
        }
    }

    /** Called first from WheelKeyService.onKeyEvent. Unbound and disconnected keys pass through. */
    public static boolean dispatch(Context context, KeyEvent event) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_WHEEL_CONTROL_ENABLED) return false;
        if (event == null || (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP)) return false;
        synchronized (LOCK) {
            PhysicalKey physical = new PhysicalKey(event);
            Press previous = PRESSES.get(physical);
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (previous != null && previous.downTime != event.getDownTime()) return previous.consumed;
                PRESSES.remove(physical);
                if (previous != null && previous.siri != null) previous.siri.release();
                return previous != null && previous.consumed;
            }
            if (previous != null && (event.getRepeatCount() > 0 || previous.downTime == event.getDownTime())) return previous.consumed;
            // A repeat with no matching initial DOWN cannot start a command or learning operation.
            if (event.getRepeatCount() != 0 || event.isCanceled()) return false;
            PRESSES.remove(physical); // A new press replaces an old sequence whose UP was lost.
            if (previous != null && previous.siri != null) previous.siri.release();
            // Remember pass decisions too: changing settings while held must not capture half a press.
            PRESSES.put(physical, new Press(event, false));

            if (learning != null) {
                ControlCard owner = learning.owner.get();
                if (owner == null || !owner.attached || SystemClock.uptimeMillis() >= learning.deadline || !serviceConnected()) {
                    finishLearning(text(context, "学习已取消。", "Learning cancelled."));
                } else {
                    Binding key = Binding.from(event);
                    SharedPreferences prefs = preferences(context);
                    int duplicate = roleOf(prefs, key);
                    int role = learning.role;
                    PRESSES.put(physical, new Press(event, true));
                    if (duplicate >= 0 && duplicate != role) {
                        notifyOwner(learning.owner, text(context, "此按键已用于", "This key is assigned to ")
                                + title(context, duplicate) + text(context, "，请先清空该项。", ". Clear that assignment first."));
                        return true;
                    }
                    saveBinding(prefs, role, key);
                    Log.i(TAG, "role=" + ROLES[role] + " code=" + key.code + " scan=" + key.scan + " sent=false learning=true");
                    finishLearning(text(context, "已识别", "Learned ") + title(context, role));
                    return true; // Learning records the press; it never sends it to the phone.
                }
            }

            SharedPreferences prefs = preferences(context);
            if (!enabled(context)) return false;
            int role = roleOf(prefs, Binding.from(event));
            if (role < 0) return false;
            Object controller = currentController();
            SiriPress siri = role == SIRI ? beginSiriPress(controller) : null;
            boolean sent = role == SIRI ? siri != null : ready(controller) && send(controller, role);
            Press press = new Press(event, sent);
            press.siri = siri;
            PRESSES.put(physical, press);
            Log.i(TAG, "role=" + ROLES[role] + " code=" + event.getKeyCode() + " scan=" + event.getScanCode() + " sent=" + sent);
            return sent;
        }
    }

    /** A physical DOWN/UP pair remains attached to the phone that received DOWN. */
    static final class SiriPress {
        private final Object controller;
        private final Object token;
        private boolean released;
        SiriPress(Object controller, Object token) { this.controller = controller; this.token = token; }
        synchronized void release() {
            if (released) return;
            released = true;
            try {
                // Finishing an accepted press must not depend on a changed license or setting.
                if (activeToken(controller) == token)
                    controller.getClass().getMethod("sendSiriButton", boolean.class, Object.class).invoke(controller, false, token);
            } catch (ReflectiveOperationException | RuntimeException failure) { logFailure(SIRI, failure); }
        }
    }

    static SiriPress beginSiriPress(Object controller) {
        if (!ready(controller)) return null;
        try {
            Object token = activeToken(controller);
            if (token != null && Boolean.TRUE.equals(controller.getClass()
                    .getMethod("sendSiriButton", boolean.class, Object.class).invoke(controller, true, token)))
                return new SiriPress(controller, token);
        } catch (ReflectiveOperationException | RuntimeException failure) { logFailure(SIRI, failure); }
        return null;
    }

    static boolean send(Object controller, int role) {
        if (com.shilapi.xcertplay.licensing.ProductLicense.isRequired() && !ready(controller)) return false;
        try {
            if (role == PHONE) {
                return requestPhoneUI(controller);
            }
            Method method = controller.getClass().getMethod(role == SIRI ? "requestSiri" : "sendMediaButton",
                    role == SIRI ? new Class<?>[0] : new Class<?>[] {int.class});
            return Boolean.TRUE.equals(role == SIRI ? method.invoke(controller) : method.invoke(controller, role == PREVIOUS ? 5 : 4));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            logFailure(role, failure);
            return false;
        }
    }

    /** One queued press: answer a ringing iPhone, end an active call, or open its phone UI. */
    private static boolean requestPhoneUI(final Object controller) {
        if (!ready(controller)) return false;
        try {
            final Field sessionField = field(controller, "activeSession");
            final Object session = sessionField.get(controller);
            final Object token = activeToken(controller);
            Object executor = field(controller, "touchExecutor").get(controller);
            if (session == null || token == null || !(executor instanceof Executor)) return false;
            final Method send = session.getClass().getMethod("sendCommand", Map.class);
            final Map<String, Object> params = new HashMap<>();
            params.put("url", "mobilephone://");
            final Map<String, Object> command = new HashMap<>();
            command.put("type", "requestUI");
            command.put("params", params);
            ((Executor) executor).execute(new Runnable() {
                @Override public void run() {
                    try {
                        if (!ready(controller) || sessionField.get(controller) != session || activeToken(controller) != token) return;
                        synchronized (field(session, "eventWriteLock").get(session)) {
                            AirPlayPhoneState.CallKeyState callState = AirPlayPhoneState.callKeyState(session);
                            int index = callState == AirPlayPhoneState.CallKeyState.IN_CALL ? 3 : 1;
                            Log.i(TAG, "phone key callState=" + callState
                                    + " action=" + (index == 3 ? "end" : "answer_or_open"));
                            session.getClass().getMethod("sendTelephony", int.class).invoke(session, index);
                            if (index == 3 || callState == AirPlayPhoneState.CallKeyState.RINGING) return;
                            AirPlayPhoneState.CallKeyState updated = AirPlayPhoneState.callKeyState(session);
                            if (updated == AirPlayPhoneState.CallKeyState.RINGING || updated == AirPlayPhoneState.CallKeyState.IN_CALL) return;
                            if (!ready(controller) || sessionField.get(controller) != session || activeToken(controller) != token) return;
                            // Retain the previously verified idle phone page; never supply a number.
                            boolean sent = Boolean.TRUE.equals(send.invoke(session, command));
                            Log.i(TAG, "phone UI request sent=" + sent + " cseq=" + field(session, "eventCseq").getInt(session));
                        }
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        logFailure(PHONE, failure);
                    }
                }
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            logFailure(PHONE, failure);
            return false;
        }
    }

    /** A true result means queued, not an experimentally confirmed iPhone call-state change. */
    public static boolean sendTelephony(final Object controller, final int index) {
        if ((index != 1 && index != 3) || !ready(controller)) return false;
        try {
            try {
                return Boolean.TRUE.equals(controller.getClass().getMethod("sendTelephonyButton", int.class).invoke(controller, index));
            } catch (NoSuchMethodException oldRelease) {
                // Compatibility with the original APK. A session token is not an AirPlaySession.
            }
            final Field sessionField = field(controller, "activeSession");
            final Object session = sessionField.get(controller);
            final Object token = activeToken(controller);
            Object executor = field(controller, "touchExecutor").get(controller);
            if (session == null || token == null || !(executor instanceof Executor) || !ready(controller)) return false;
            final Method send = session.getClass().getMethod("sendTelephony", int.class);
            if (sessionField.get(controller) != session || activeToken(controller) != token || !ready(controller)) return false;
            ((Executor) executor).execute(new Runnable() {
                @Override public void run() {
                    try {
                        if (!ready(controller) || sessionField.get(controller) != session || activeToken(controller) != token) return;
                        send.invoke(session, index);
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        logFailure(PHONE, failure);
                    }
                }
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            logFailure(PHONE, failure);
            return false;
        }
    }

    private static Field field(Object receiver, String name) throws ReflectiveOperationException {
        Field field = receiver.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static boolean closed(Object controller) {
        if (controller == null) return true;
        try { return !Boolean.FALSE.equals(controller.getClass().getMethod("isClosed").invoke(controller)); }
        catch (ReflectiveOperationException | RuntimeException failure) { return true; }
    }

    static boolean ready(Object controller) {
        if (!connectionReady(controller)) return false;
        try {
            if (com.shilapi.xcertplay.licensing.ProductLicense.isRequired()) {
                Object app = field(controller, "appContext").get(controller);
                if (!(app instanceof Context) || !com.shilapi.xcertplay.licensing.ProductLicense.featureAllowed((Context) app,
                        com.shilapi.xcertplay.licensing.LicenseFeatures.STEERING_WHEEL)) return false;
            }
            return true;
        }
        catch (ReflectiveOperationException | RuntimeException failure) { logFailure(PHONE, failure); return false; }
    }
    static boolean connectionReady(Object controller) {
        if (closed(controller)) return false;
        try { return activeToken(controller) != null && field(controller, "activeSession").get(controller) != null; }
        catch (ReflectiveOperationException | RuntimeException failure) { logFailure(PHONE, failure); return false; }
    }

    static Object sessionToken(Object controller) {
        try { return controller == null ? null : activeToken(controller); }
        catch (ReflectiveOperationException | RuntimeException failure) { return null; }
    }

    private static Object activeToken(Object controller) throws ReflectiveOperationException {
        // The primary-stream token is cleared on TEARDOWN before the TCP session object disappears.
        return controller.getClass().getMethod("activeAirPlaySessionToken").invoke(controller);
    }

    private static Object currentController() {
        try {
            Class<?> type = Class.forName("com.shilapi.xcertplay.CarPlayBackgroundSession");
            Object singleton = type.getField("INSTANCE").get(null);
            Object snapshot = type.getMethod("snapshot").invoke(singleton);
            return snapshot == null ? null : snapshot.getClass().getMethod("getController").invoke(snapshot);
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }

    private static boolean serviceConnected() {
        try {
            Class<?> type = Class.forName("com.shilapi.xcertplay.WheelKeyService");
            Object companion = type.getField("Companion").get(null);
            return Boolean.TRUE.equals(companion.getClass().getMethod("connected").invoke(companion));
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }

    private static void logFailure(int role, Exception failure) {
        Log.w(TAG, "role=" + ROLES[role] + " sent=false failureClass=" + failure.getClass().getSimpleName());
    }

    /** Append a normal settings section; the landscape renderer can classify its heading. */
    public static void addCard(Activity activity, ScrollView scroll) {
        if (scroll.getChildCount() == 0 || !(scroll.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout content = (LinearLayout) scroll.getChildAt(0);
        for (int i = 0; i < content.getChildCount(); i++) if (CARD_TAG.equals(content.getChildAt(i).getTag())) return;
        ControlCard card = new ControlCard(activity);
        card.setTag(CARD_TAG);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(activity, 16);
        content.addView(card, params);
    }

    private static final class ControlCard extends LinearLayout {
        private final Activity activity;
        private final TextView status;
        private final TextView[] assignments = new TextView[4];
        private final Button cancel;
        private String notice = "";
        private boolean attached;
        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!attached) return;
                refresh();
                handler().postDelayed(this, 1000);
            }
        };

        ControlCard(final Activity activity) {
            super(activity);
            this.activity = activity;
            setOrientation(VERTICAL);
            setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16));
            setBackground(background(activity, SURFACE, BORDER));
            LinearLayout heading = new LinearLayout(activity);
            heading.setOrientation(HORIZONTAL);
            TextView title = label(activity, text(activity, "方向盘按键", "Steering wheel keys"), 20, TEXT);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.addView(title, new LinearLayout.LayoutParams(-1, -2));
            addView(heading, new LinearLayout.LayoutParams(-1, -2));

            Switch enabled = new Switch(activity);
            enabled.setText(text(activity, "启用方向盘按键", "Enable learned wheel keys"));
            enabled.setTextSize(16);
            enabled.setTextColor(TEXT);
            enabled.setMinHeight(dp(activity, 48));
            enabled.setButtonTintList(ColorStateList.valueOf(ACCENT));
            final boolean nativeKeys = HongqiWheelControls.supported(activity);
            enabled.setChecked(enabled(activity));
            enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton view, boolean checked) {
                    preferences(activity).edit().putBoolean("enabled", checked).apply();
                    if (!checked) cancelLearning();
                    notice = "";
                    refresh();
                }
            });
            addView(enabled, new LinearLayout.LayoutParams(-1, -2));
            status = label(activity, "", 14, MUTED);
            addView(status, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout actions = new LinearLayout(activity);
            actions.setOrientation(HORIZONTAL);
            Button access = button(activity, text(activity, "无障碍授权", "Accessibility access"));
            access.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { cancelLearning(); openAccess(activity); }
            });
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
            half.rightMargin = dp(activity, 8);
            actions.addView(access, half);
            cancel = button(activity, text(activity, "取消学习", "Cancel learning"));
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { cancelLearning(); }
            });
            actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
            actionParams.topMargin = dp(activity, 8);
            if (!nativeKeys) addView(actions, actionParams);

            for (int i = 0; i < 4; i++) {
                final int role = i;
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(activity, 8), 0, 0);
                LinearLayout words = new LinearLayout(activity);
                words.setOrientation(VERTICAL);
                TextView name = label(activity, title(activity, i), 16, TEXT);
                name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                words.addView(name, new LinearLayout.LayoutParams(-1, -2));
                assignments[i] = label(activity, "", 14, MUTED);
                assignments[i].setMaxLines(2);
                assignments[i].setEllipsize(TextUtils.TruncateAt.END);
                words.addView(assignments[i], new LinearLayout.LayoutParams(-1, -2));
                row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                Button learn = button(activity, text(activity, "学习", "Learn"));
                learn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) { beginLearning(role, ControlCard.this); }
                });
                LinearLayout.LayoutParams learnParams = new LinearLayout.LayoutParams(-2, -2);
                learnParams.leftMargin = dp(activity, 8);
                if (!nativeKeys) row.addView(learn, learnParams);
                Button clear = button(activity, text(activity, "清空", "Clear"));
                clear.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        cancelLearning();
                        clearBinding(preferences(activity), role);
                        notice = "";
                        refresh();
                    }
                });
                LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-2, -2);
                clearParams.leftMargin = dp(activity, 8);
                if (!nativeKeys) row.addView(clear, clearParams);
                addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            TextView help = label(activity, nativeKeys ? "CarPlay 连接后自动接管这四个按键，断开或关闭后恢复原车功能。" : text(activity,
                    "先授权，再点击学习并按一次对应按键。学习只记录按键；未开启或 CarPlay 未连接时，按键交给原车。",
                    "Allow accessibility access, select Learn and press the key once. Learning only records it. Keys remain with the car when disabled or CarPlay is disconnected."), 14, MUTED);
            help.setPadding(0, dp(activity, 12), 0, 0);
            addView(help, new LinearLayout.LayoutParams(-1, -2));
            refresh();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attached = true;
            handler().removeCallbacks(poll);
            handler().post(poll);
        }
        @Override protected void onDetachedFromWindow() {
            attached = false;
            handler().removeCallbacks(poll);
            synchronized (LOCK) { if (learning != null && learning.owner.get() == this) finishLearning(null); }
            super.onDetachedFromWindow();
        }
        @Override public void onWindowFocusChanged(boolean focus) {
            super.onWindowFocusChanged(focus);
            if (!focus) synchronized (LOCK) { if (learning != null && learning.owner.get() == this) finishLearning(null); }
            if (focus) refresh();
        }
        void refresh() {
            synchronized (LOCK) {
                boolean access = serviceConnected();
                if (learning != null && learning.owner.get() == this && !access) finishLearning(null);
                String base = HongqiWheelControls.supported(activity) ? HongqiWheelControls.status(activity) : !access
                        ? text(activity, "无障碍服务未连接，请授权后学习。", "Accessibility service is not connected. Allow access to learn keys.")
                        : text(activity, "无障碍已连接 · ", "Accessibility connected · ")
                            + (ready(currentController()) ? text(activity, "CarPlay 已连接", "CarPlay connected")
                                : text(activity, "CarPlay 未连接，可先学习按键。", "CarPlay disconnected; key learning is available."));
                String detail = notice;
                if (learning != null && learning.owner.get() == this) {
                    long seconds = Math.max(0, (learning.deadline - SystemClock.uptimeMillis() + 999) / 1000);
                    detail = text(activity, "请按", "Press ") + title(activity, learning.role)
                            + " · " + seconds + text(activity, " 秒", " seconds")
                            + (notice.isEmpty() ? "" : "\n" + notice);
                }
                setIfChanged(status, base + (detail.isEmpty() ? "" : "\n" + detail));
                cancel.setEnabled(learning != null && learning.owner.get() == this);
            }
            SharedPreferences prefs = preferences(activity);
            for (int i = 0; i < 4; i++) {
                Binding binding = readBinding(prefs, i);
                setIfChanged(assignments[i], HongqiWheelControls.supported(activity) ? "原厂按键 · 自动识别" : binding == null ? text(activity, "尚未学习", "Not learned")
                        : "code " + binding.code + " · scan " + binding.scan + "\n" + binding.deviceName);
            }
        }
    }

    private static void beginLearning(int role, ControlCard card) {
        synchronized (LOCK) {
            if (!serviceConnected()) {
                card.notice = text(card.activity, "请先授权无障碍服务，再学习按键。", "Allow accessibility access before learning a key.");
                card.refresh();
                return;
            }
            cancelLearning();
            try {
                Class<?> service = Class.forName("com.shilapi.xcertplay.WheelKeyService");
                Object companion = service.getField("Companion").get(null);
                companion.getClass().getMethod("cancelLearning").invoke(companion);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                // The new learner is independent; the existing service owns the event filter.
            }
            card.notice = "";
            learning = new Learning(role, card);
            handler().postDelayed(learning.timeout, 10_000);
            card.refresh();
        }
    }

    public static void cancelLearning() {
        synchronized (LOCK) { finishLearning(null); }
    }

    private static void finishLearning(String notice) {
        Learning previous = learning;
        learning = null;
        if (previous == null) return;
        handler().removeCallbacks(previous.timeout);
        notifyOwner(previous.owner, notice);
    }

    private static void notifyOwner(final WeakReference<ControlCard> reference, final String notice) {
        handler().post(new Runnable() {
            @Override public void run() {
                ControlCard owner = reference.get();
                if (owner == null || !owner.attached) return;
                owner.notice = notice == null ? "" : notice;
                owner.refresh();
            }
        });
    }

    private static Handler handler() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean enabled(Context context) {
        return com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_WHEEL_CONTROL_ENABLED
                && preferences(context).getBoolean("enabled", HongqiWheelControls.supported(context));
    }
    private static Binding readBinding(SharedPreferences prefs, int role) {
        String key = ROLES[role];
        return prefs.contains(key + "_code") ? new Binding(prefs.getInt(key + "_code", 0),
                prefs.getInt(key + "_scan", 0), prefs.getString(key + "_device", "?")) : null;
    }
    private static int roleOf(SharedPreferences prefs, Binding binding) {
        for (int i = 0; i < 4; i++) {
            Binding saved = readBinding(prefs, i);
            if (saved != null && saved.matches(binding)) return i;
        }
        return -1;
    }
    private static void saveBinding(SharedPreferences prefs, int role, Binding binding) {
        String key = ROLES[role];
        prefs.edit().putInt(key + "_code", binding.code).putInt(key + "_scan", binding.scan)
                .putString(key + "_device", binding.deviceName).apply();
    }
    private static void clearBinding(SharedPreferences prefs, int role) {
        String key = ROLES[role];
        prefs.edit().remove(key + "_code").remove(key + "_scan").remove(key + "_device").apply();
    }

    private static void openAccess(Activity activity) {
        try { activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (ActivityNotFoundException | SecurityException absent) {
            Toast.makeText(activity, text(activity, "请在系统无障碍设置中启用 Autunn Play 服务。", "Enable the Autunn Play service in system accessibility settings."), Toast.LENGTH_LONG).show();
        }
    }

    private static String title(Context context, int role) {
        String[] chinese = {"上一曲", "下一曲", "电话键（拨号/接听/挂断）", "语音键（Siri）"};
        String[] english = {"Previous track", "Next track", "Phone (open/answer/end)", "Voice (Siri)"};
        return text(context, chinese[role], english[role]);
    }
    private static String text(Context context, String chinese, String english) {
        return context != null && "zh".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage()) ? chinese : english;
    }
    private static TextView label(Context context, String text, int size, int color) {
        TextView view = new TextView(context);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    private static void setIfChanged(TextView view, CharSequence value) {
        // Polling must not continuously invalidate an unchanged accessibility tree.
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }
    private static Button button(Context context, String title) {
        Button view = new Button(context);
        view.setText(title); view.setTextColor(TEXT); view.setTextSize(15); view.setAllCaps(false);
        view.setMinHeight(dp(context, 48)); view.setMinimumHeight(dp(context, 48));
        view.setMinWidth(0); view.setMinimumWidth(0);
        view.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        view.setBackground(background(context, SURFACE, BORDER));
        return view;
    }
    private static GradientDrawable background(Context context, int fill, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill); background.setCornerRadius(dp(context, 12)); background.setStroke(dp(context, 1), stroke);
        return background;
    }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
