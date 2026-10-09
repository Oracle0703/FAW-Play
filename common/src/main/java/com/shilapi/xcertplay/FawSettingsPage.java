// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/** Native, classified settings based on the Sunburst design reference. */
public final class FawSettingsPage {
    private static final int BG = Color.rgb(16, 19, 24), SURFACE = Color.rgb(25, 29, 35);
    private static final int BORDER = Color.rgb(48, 54, 64), TEXT = Color.rgb(244, 246, 249);
    private static final int MUTED = Color.rgb(163, 171, 184), RED = Color.rgb(203, 63, 53);
    private static final int GREEN = Color.rgb(32, 132, 82);
    private static final int SELECTED = Color.rgb(61, 35, 38);
    private static final String[] TABS = {"连接设置", "画面显示", com.shilapi.xcertplay.shared.BuildConfig.FAW_NAVIGATION_OUTPUTS_ENABLED ? "导航与 HUD" : "定位", "权限与帮助", "诊断", "关于"};
    private static final WeakHashMap<Activity, Integer> SELECTED_TAB = new WeakHashMap<>();
    private FawSettingsPage() {}

    public static void show(Activity activity, String page) {
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        FawSystemParameters.enforce(activity);
        int selected = "connection".equals(page) ? 0 : selected(activity);
        if ("connection".equals(page)) {
            try { Field state = activity.getClass().getDeclaredField("page"); state.setAccessible(true); state.set(activity, "settings"); }
            catch (ReflectiveOperationException unavailable) { Log.w("FAWSettings", "Page state unavailable"); }
        }
        SELECTED_TAB.put(activity, selected);
        LinearLayout root = column(activity); root.setBackgroundColor(BG); root.setTag("faw_settings_root");
        root.addView(header(activity, "返回", () -> home(activity)), new LinearLayout.LayoutParams(-1, dp(activity, 48)));
        divider(activity, root);
        LinearLayout body = row(activity);
        LinearLayout nav = column(activity); nav.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 6));
        for (int i = 0; i < TABS.length; i++) {
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(-1, 0, 1); item.topMargin = dp(activity, 2);
            nav.addView(tab(activity, i, selected), item);
        }
        body.addView(nav, new LinearLayout.LayoutParams(dp(activity, 160), -1));
        View divider = new View(activity); divider.setBackgroundColor(BORDER); body.addView(divider, new LinearLayout.LayoutParams(dp(activity, 1), -1));
        LinearLayout content = row(activity); content.setGravity(Gravity.TOP);
        content.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        switch (selected) {
            case 0: connection(activity, content); break;
            case 1: display(activity, content); break;
            case 2: navigation(activity, content); break;
            case 3: permissions(activity, content); break;
            case 4: diagnostics(activity, content); break;
            default: about(activity, content);
        }
        body.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        activity.setContentView(root); LandscapeSettingsLayout.fullscreen(activity);
    }
    private static LinearLayout header(Activity activity, String backTitle, Runnable backAction) {
        LinearLayout header = row(activity);
        header.setPadding(dp(activity, 18), dp(activity, 4), dp(activity, 18), dp(activity, 4));
        ImageView logo = new ImageView(activity);
        logo.setContentDescription("红旗官方标志原图"); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try (InputStream input = activity.getAssets().open("hongqi_shield.png")) { logo.setImageBitmap(BitmapFactory.decodeStream(input)); }
        catch (IOException unavailable) { Log.w("FAWSettings", "Header logo unavailable"); }
        header.addView(logo, new LinearLayout.LayoutParams(dp(activity, 46), -1));
        TextView brand = label(activity, "FAW Play", 22, TEXT, true); brand.setTextSize(sp(activity, 22));
        brand.setPadding(dp(activity, 10), 0, 0, 0);
        header.addView(brand, new LinearLayout.LayoutParams(-2, -1));
        TextView carplay = label(activity, "CarPlay", 13, Color.rgb(168, 209, 184), false);
        carplay.setGravity(Gravity.CENTER); carplay.setPadding(dp(activity, 10), 0, dp(activity, 10), 0);
        carplay.setBackground(background(activity, Color.rgb(24, 36, 30), Color.rgb(48, 75, 60)));
        LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(-2, dp(activity, 28)); badge.leftMargin = dp(activity, 12);
        header.addView(carplay, badge); header.addView(new View(activity), new LinearLayout.LayoutParams(0, -1, 1));
        Button back = button(activity, backTitle, false, backAction); back.setBackgroundColor(Color.TRANSPARENT);
        back.setMaxLines(1);
        header.addView(back, new LinearLayout.LayoutParams(dp(activity, 96), -1));
        return header;
    }
    private static void divider(Activity activity, LinearLayout root) {
        View rule = new View(activity); rule.setBackgroundColor(RED); root.addView(rule, new LinearLayout.LayoutParams(-1, dp(activity, 1)));
    }
    private static void goSettings(Activity activity) {
        SELECTED_TAB.put(activity, 0);
        try { Field page = activity.getClass().getDeclaredField("page"); page.setAccessible(true); page.set(activity, "settings"); invoke(activity, "render", new Class<?>[0]); }
        catch (ReflectiveOperationException unavailable) { Log.w("FAWSettings", "Settings navigation unavailable"); }
    }
    public static void showHome(Activity activity) {
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        TextView status = label(activity, "准备连接 iPhone", 20, TEXT, true);
        Button primary = button(activity, "打开 CarPlay", true, () -> openCarPlay(activity));
        primary.setTag("faw_open_carplay");
        primary.setBackground(background(activity, GREEN, GREEN));
        LinearLayout root = landing(activity, "返回车机", () -> open(activity, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)),
                status, primary, null, () -> goSettings(activity));
        activity.setContentView(root); LandscapeSettingsLayout.fullscreen(activity);
        Runnable update = new Runnable() {
            @Override public void run() {
                if (!status.isAttachedToWindow()) return;
                Object error = field(activity, "setupError");
                boolean paired = activity.getSharedPreferences("diplay", 0).getString("phone_address", null) != null;
                status.setText(error instanceof String ? (String) error : active() ? "CarPlay 已连接" : session() ? "正在连接 iPhone…" : paired ? "准备连接 iPhone" : "先配对你的 iPhone");
                status.setTextColor(error != null ? Color.rgb(235, 128, 115) : active() ? Color.rgb(105, 201, 143) : TEXT);
                status.postDelayed(this, 1500);
            }
        };
        status.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { status.post(update); }
            @Override public void onViewDetachedFromWindow(View view) { status.removeCallbacks(update); }
        });
        if (status.isAttachedToWindow()) status.post(update);
    }
    /** Open the projection itself; an in-progress session must not be stopped and restarted. */
    private static void openCarPlay(Activity activity) {
        if (active() || session()) {
            Log.i("FAWSettings", "Opening current CarPlay projection");
            invoke(activity, "openProjection", new Class<?>[0]);
            return;
        }
        String address = activity.getSharedPreferences("diplay", 0).getString("phone_address", null);
        if (address == null || address.trim().isEmpty()) {
            goSettings(activity);
            return;
        }
        Log.i("FAWSettings", "Starting CarPlay projection");
        invoke(activity, "connect", new Class<?>[]{boolean.class}, true);
    }
    /** Restyle only the preparation viewport, retaining the engine's live status and action listeners. */
    public static void stylePreparation(Activity activity) {
        Object existing = field(activity, "connectionPanel");
        if (!(existing instanceof ViewGroup)) return;
        ViewGroup old = (ViewGroup) existing;
        if ("faw_connection_preparation".equals(old.getTag())) {
            View pending = old.findViewWithTag("faw_connection_pending"); Object retry = field(activity, "startupRetryButton");
            if (pending != null && retry instanceof View) pending.setVisibility(((View) retry).getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (retry instanceof View && ((View) retry).getVisibility() == View.VISIBLE) {
                Object currentStage = field(activity, "stageStatusView"), reason = field(activity, "latestStage");
                if (currentStage instanceof TextView && reason instanceof String && !((String) reason).isEmpty())
                    ((TextView) currentStage).setText((String) reason);
            }
            return;
        }
        if (!(old.getParent() instanceof ViewGroup)) return;
        Object liveStage = field(activity, "stageStatusView"), retryValue = field(activity, "startupRetryButton"), recoveryValue = field(activity, "wifiRecoveryButton");
        if (!(liveStage instanceof TextView) || !(retryValue instanceof Button) || !(recoveryValue instanceof Button)) return;
        TextView stage = (TextView) liveStage; Button retry = (Button) retryValue, recovery = (Button) recoveryValue;
        detach(stage); detach(retry); detach(recovery);
        LinearLayout actions = row(activity);
        FrameLayout retrySlot = new FrameLayout(activity);
        Button pending = button(activity, "正在连接…", true, () -> {}); pending.setEnabled(false);
        pending.setTag("faw_connection_pending"); pending.setBackground(background(activity, GREEN, GREEN));
        pending.setVisibility(retry.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        retrySlot.addView(pending, new FrameLayout.LayoutParams(-1, -1));
        retry.setTextColor(TEXT); retry.setTextSize(sp(activity, 14)); retry.setAllCaps(false); retry.setPadding(dp(activity, 6), 0, dp(activity, 6), 0);
        retry.setMinHeight(0); retry.setMinimumHeight(0); retry.setBackground(background(activity, GREEN, GREEN));
        retrySlot.addView(retry, new FrameLayout.LayoutParams(-1, -1));
        actions.addView(retrySlot, new LinearLayout.LayoutParams(0, -1, 1));
        recovery.setTextColor(TEXT); recovery.setTextSize(sp(activity, 13)); recovery.setAllCaps(false); recovery.setPadding(dp(activity, 6), 0, dp(activity, 6), 0);
        recovery.setMinHeight(0); recovery.setMinimumHeight(0); recovery.setBackground(background(activity, SURFACE, BORDER));
        LinearLayout.LayoutParams recoveryParams = new LinearLayout.LayoutParams(0, -1, 1); recoveryParams.leftMargin = dp(activity, 6);
        actions.addView(recovery, recoveryParams);
        LinearLayout screen = landing(activity, "返回首页", () -> invoke(activity, "showDiPlayHome", new Class<?>[]{String.class}, "home"),
                stage, null, actions, () -> invoke(activity, "showDiPlayHome", new Class<?>[]{String.class}, "settings"));
        FrameLayout replacement = new FrameLayout(activity); replacement.setBackgroundColor(BG); replacement.setClickable(true);
        replacement.setTag("faw_connection_preparation"); replacement.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        ViewGroup parent = (ViewGroup) old.getParent(); int index = parent.indexOfChild(old); ViewGroup.LayoutParams params = old.getLayoutParams();
        try {
            Field state = activity.getClass().getDeclaredField("connectionPanel"); state.setAccessible(true); state.set(activity, replacement);
            parent.removeViewAt(index); parent.addView(replacement, index, params);
        } catch (ReflectiveOperationException unavailable) { Log.w("FAWSettings", "Preparation viewport unavailable"); }
    }
    private static Object field(Activity activity, String name) {
        try { Field f = activity.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
        catch (ReflectiveOperationException unavailable) { Log.w("FAWSettings", "Page field unavailable: " + name); return null; }
    }
    private static void detach(View view) { if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view); }
    private static LinearLayout landing(Activity activity, String backTitle, Runnable backAction, TextView status, Button primary, View optionalActions, Runnable settingsAction) {
        LinearLayout root = column(activity); root.setBackgroundColor(BG);
        root.addView(header(activity, backTitle, backAction), new LinearLayout.LayoutParams(-1, dp(activity, 48))); divider(activity, root);
        LinearLayout content = row(activity); content.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        LinearLayout hero = column(activity); hero.setPadding(dp(activity, 14), dp(activity, 16), dp(activity, 14), dp(activity, 12));
        hero.setBackground(background(activity, SURFACE, BORDER));
        TextView title = label(activity, "让熟悉的旅程，更从容", 30, TEXT, true);
        title.setMaxLines(1); title.setAutoSizeTextTypeUniformWithConfiguration(sp(activity, 20), sp(activity, 32), sp(activity, 1), TypedValue.COMPLEX_UNIT_SP);
        hero.addView(title, margin(activity, -1, 40, 0));
        hero.addView(label(activity, "导航、音乐与通话，都在你的车机上。", 14, MUTED, false), margin(activity, -1, -2, 8));
        LinearLayout features = row(activity);
        String[] names = {"导航", "音乐", "通话"}; int[] icons = {2, 7, 8};
        String[] captions = {"熟悉的地图，\n带你去想去的地方。", "你喜欢的音乐，\n一路随行。", "重要的联系，\n安全便捷。"};
        for (int i = 0; i < names.length; i++) {
            LinearLayout feature = column(activity); feature.setGravity(Gravity.CENTER); feature.setPadding(dp(activity, 8), dp(activity, 10), dp(activity, 8), dp(activity, 10));
            feature.setBackground(background(activity, Color.rgb(28, 33, 41), BORDER));
            ImageView icon = new ImageView(activity); icon.setImageDrawable(new Mark(icons[i], RED, dp(activity, 36))); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            feature.addView(icon, new LinearLayout.LayoutParams(-1, dp(activity, 36)));
            TextView name = label(activity, names[i], 18, TEXT, true); name.setGravity(Gravity.CENTER); feature.addView(name, margin(activity, -1, -2, 10));
            TextView caption = label(activity, captions[i], 12, MUTED, false); caption.setGravity(Gravity.CENTER); feature.addView(caption, margin(activity, -1, -2, 8));
            LinearLayout.LayoutParams choice = new LinearLayout.LayoutParams(0, -1, 1); if (i != 0) choice.leftMargin = dp(activity, 8); features.addView(feature, choice);
        }
        hero.addView(features, new LinearLayout.LayoutParams(-1, 0, 1));
        ((LinearLayout.LayoutParams) features.getLayoutParams()).topMargin = dp(activity, 16);
        content.addView(hero, new LinearLayout.LayoutParams(0, -1, 1.6f));
        LinearLayout connection = card(activity, content, "无线 CarPlay", null, 0);
        status.setTextColor(TEXT); status.setTypeface(null, Typeface.BOLD); status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(0, dp(activity, 6), 0, dp(activity, 6)); status.setAutoSizeTextTypeUniformWithConfiguration(sp(activity, 12), sp(activity, 20), sp(activity, 1), TypedValue.COMPLEX_UNIT_SP);
        connection.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
        View line = new View(activity); line.setBackgroundColor(BORDER); connection.addView(line, margin(activity, -1, 1, 4));
        connection.addView(label(activity, "已选择的 iPhone", 12, MUTED, false), margin(activity, -1, -2, 8));
        String phone = activity.getSharedPreferences("diplay", 0).getString("phone_name", null);
        TextView selected = label(activity, phone == null ? "尚未选择 iPhone" : phone, 14, TEXT, true); selected.setMaxLines(1); selected.setEllipsize(android.text.TextUtils.TruncateAt.END);
        connection.addView(selected, margin(activity, -1, -2, 4));
        connection.addView(label(activity, "自动连接已开启", 12, MUTED, false), margin(activity, -1, -2, 8));
        if (primary != null) connection.addView(primary, margin(activity, -1, 48, 10));
        if (optionalActions != null) connection.addView(optionalActions, margin(activity, -1, 48, 10));
        Button settings = button(activity, "设置", false, settingsAction);
        settings.setCompoundDrawablesWithIntrinsicBounds(new Mark(9, TEXT, dp(activity, 20)), null, null, null); settings.setCompoundDrawablePadding(dp(activity, 8));
        connection.addView(settings, margin(activity, -1, 48, 8));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1)); return root;
    }
    private static int selected(Activity activity) {
        Integer value = SELECTED_TAB.get(activity); return value == null ? 0 : value;
    }
    private static View tab(Activity activity, int index, int current) {
        Button control = button(activity, TABS[index], false, () -> {
            SELECTED_TAB.put(activity, index); show(activity, "settings");
        });
        control.setTextSize(sp(activity, 14));
        control.setSelected(index == current);
        control.setBackground(background(activity, index == current ? SELECTED : BG, Color.TRANSPARENT));
        control.setTextColor(index == current ? TEXT : MUTED);
        {
            control.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            control.setCompoundDrawablesWithIntrinsicBounds(new Mark(index, index == current ? RED : MUTED, dp(activity, 20)), null, null, null);
            control.setCompoundDrawablePadding(dp(activity, 8));
        }
        return control;
    }
    private static void connection(Activity activity, LinearLayout parent) {
        LinearLayout pairing = card(activity, parent, "配对与连接", null, 0);
        ((LinearLayout.LayoutParams) pairing.getLayoutParams()).weight = 2;
        TextView status = label(activity, "", 13, MUTED, false); pairing.addView(status, margin(activity, -1, -2, 6));
        pairing.addView(label(activity, "选择 iPhone", 15, TEXT, true), margin(activity, -1, -2, 12));
        List<BluetoothDevice> devices = bonded(activity);
        SharedPreferences prefs = activity.getSharedPreferences("diplay", 0); String address = prefs.getString("phone_address", null);
        LinearLayout phoneChoices = row(activity); phoneChoices.setGravity(Gravity.CENTER_VERTICAL);
        // The HS5's wide, shallow screen fits two phones side by side without
        // consuming a second row or pushing the connection actions below the card.
        boolean inlinePhones = devices.size() <= 2;
        View chosenView = null;
        if (devices.isEmpty()) {
            String name = prefs.getString("phone_name", null);
            phoneChoices.addView(label(activity, name == null ? "暂无已配对的手机" : name + " · 已选择", 14, MUTED, false), margin(activity, -2, 48, 0));
        } else {
            for (BluetoothDevice device : devices) {
                String name = device.getName() == null ? "已配对设备" : device.getName();
                boolean duplicate = false;
                for (BluetoothDevice other : devices) if (other != device && name.equals(other.getName())) duplicate = true;
                String title = name + (duplicate ? " · " + device.getAddress().substring(12) : "");
                boolean chosen = device.getAddress().equals(address);
                Button select = button(activity, title + (chosen ? "  ✓" : ""), false, () -> {
                    prefs.edit().putString("phone_address", device.getAddress()).putString("phone_name", device.getName() == null ? "iPhone" : device.getName()).apply();
                    show(activity, "settings");
                });
                select.setSelected(chosen); select.setMaxLines(2);
                if (chosen) { select.setBackground(background(activity, SELECTED, RED)); chosenView = select; }
                select.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                select.setCompoundDrawablesWithIntrinsicBounds(new Mark(10, TEXT, dp(activity, 20)), null, null, null); select.setCompoundDrawablePadding(dp(activity, 8));
                LinearLayout.LayoutParams item = inlinePhones
                        ? new LinearLayout.LayoutParams(0, dp(activity, 48), 1)
                        : new LinearLayout.LayoutParams(dp(activity, 160), dp(activity, 48));
                if (phoneChoices.getChildCount() != 0) item.leftMargin = dp(activity, 8);
                phoneChoices.addView(select, item);
            }
        }
        if (inlinePhones) {
            pairing.addView(phoneChoices, margin(activity, -1, 48, 8));
        } else {
            HorizontalScrollView phones = new HorizontalScrollView(activity); phones.setFillViewport(false); phones.setHorizontalScrollBarEnabled(false);
            phones.addView(phoneChoices); View selectedPhone = chosenView;
            if (selectedPhone != null) phones.post(() -> phones.scrollTo(Math.max(0, selectedPhone.getRight() - phones.getWidth()), 0));
            pairing.addView(phones, margin(activity, -1, 48, 8));
        }
        spacer(activity, pairing);
        LinearLayout actions = row(activity);
        action(activity, actions, "配对新手机", false, () -> open(activity, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        Button connect = action(activity, actions, "连接手机", true, () -> {
            if (active() || session()) { openCarPlay(activity); return; }
            if (prefs.getString("phone_address", null) == null) { status.setText("请先配对并选择 iPhone"); return; }
            openCarPlay(activity);
        });
        Button disconnect = action(activity, actions, "断开连接", false, () -> stop(activity));
        connect.setBackground(background(activity, GREEN, GREEN));
        pairing.addView(actions, margin(activity, -1, 48, 10));
        Runnable update = new Runnable() {
            @Override public void run() {
                if (!status.isAttachedToWindow()) return;
                boolean running = session(); status.setText(active() ? "CarPlay 已连接" : running ? "正在连接手机…" : "尚未连接");
                status.setTextColor(active() ? Color.rgb(105, 201, 143) : MUTED);
                connect.setText(active() ? "进入 CarPlay" : "连接手机"); disconnect.setEnabled(running); disconnect.setAlpha(running ? 1f : .45f); status.postDelayed(this, 1500);
            }
        };
        status.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { status.post(update); }
            @Override public void onViewDetachedFromWindow(View view) { status.removeCallbacks(update); }
        });
        LinearLayout startup = card(activity, parent, "启动", "打开 FAW Play 后自动连接已选择的 iPhone。", 6);
        toggle(activity, startup, "车机启动后打开", Boolean.TRUE.equals(call("AirPlayPersistence", "loadAutoStartOnBoot", new Class<?>[]{Context.class}, activity)),
                enabled -> call("AirPlayPersistence", "saveAutoStartOnBoot", new Class<?>[]{Context.class, boolean.class}, activity, enabled));
    }
    private static void display(Activity activity, LinearLayout parent) {
        LinearLayout card = card(activity, parent, "画面帧率", "选择画面的流畅度。更改后重新连接生效。", 1);
        int current = FawSystemParameters.fps(activity);
        LinearLayout segmented = row(activity);
        int[] values = {30, 50, 60};
        for (int value : values) {
            Button option = button(activity, value + " 帧", value == current, () -> {
                if (FawSystemParameters.fps(activity) == value) return;
                FawSystemParameters.saveFps(activity, value);
                show(activity, "settings");
            });
            option.setSelected(value == current);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(activity, 48), 1);
            params.setMargins(dp(activity, 3), dp(activity, 12), dp(activity, 3), dp(activity, 4));
            segmented.addView(option, params);
        }
        card.addView(segmented);
        if (session()) card.addView(button(activity, "应用并重新连接", true, () -> invoke(activity, "reconnectForClusterMap", new Class<?>[0])), margin(activity, -1, 48, 10));
    }
    private interface Save { void set(boolean value); }
    private static void navigation(Activity activity, LinearLayout parent) {
        if (com.shilapi.xcertplay.shared.BuildConfig.FAW_NAVIGATION_OUTPUTS_ENABLED) {
            LinearLayout hud = card(activity, parent, "HUD 导航", "在抬头显示中查看导航箭头与距离。", 2);
            SharedPreferences outputs = activity.getSharedPreferences("diplay_byd_outputs", 0);
            toggle(activity, hud, "HUD 导航提示", outputs.getBoolean("navigation_enabled", true),
                    enabled -> outputs.edit().putBoolean("navigation_enabled", enabled).apply());
        }
        LinearLayout gps = card(activity, parent, "定位", "向 iPhone 提供车机位置，帮助导航定位。", 2);
        boolean location = Boolean.TRUE.equals(call("AirPlayPersistence", "loadLocationReportingEnabled", new Class<?>[]{Context.class}, activity));
        toggle(activity, gps, "车机位置上报", location, enabled -> invoke(activity, "onLocationReportingChanged", new Class<?>[]{boolean.class}, enabled));
        if (com.shilapi.xcertplay.shared.BuildConfig.FAW_NAVIGATION_OUTPUTS_ENABLED) {
            LinearLayout gesture = card(activity, parent, "仪表导航手势", "CarPlay 导航时三指左滑开启仪表地图，右滑关闭。", 2);
            gesture.addView(label(activity, "结束导航后，HUD 与仪表自动恢复原厂画面。", 14, MUTED, false), margin(activity, -1, -2, 10));
        }
    }
    private static void permissions(Activity activity, LinearLayout parent) {
        LinearLayout access = card(activity, parent, "应用权限", null, 3);
        access.addView(label(activity, PermissionAccess.status(activity), 13, TEXT, false), margin(activity, -1, -2, 10));
        access.addView(label(activity, "麦克风：" + (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权"), 13, TEXT, false), margin(activity, -1, -2, 8));
        spacer(activity, access);
        access.addView(button(activity, "申请连接权限", true, () -> PermissionAccess.request(activity)), margin(activity, -1, 48, 8));
        access.addView(button(activity, "应用权限", false, () -> PermissionAccess.openAppSettings(activity)), margin(activity, -1, 48, 8));
        LinearLayout system = card(activity, parent, "系统连接", "请保持手机与车机的 Wi-Fi、蓝牙开启。", 3);
        spacer(activity, system);
        system.addView(button(activity, "Wi-Fi 设置", false, () -> PermissionAccess.openWifiSettings(activity)), margin(activity, -1, 48, 8));
        if (com.shilapi.xcertplay.shared.BuildConfig.FAW_NAVIGATION_OUTPUTS_ENABLED)
            system.addView(button(activity, "允许仪表显示", false, () -> open(activity, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.getPackageName())))), margin(activity, -1, 48, 8));
        LinearLayout help = card(activity, parent, "连接帮助", null, 0);
        help.addView(label(activity, "首次使用：系统蓝牙配对 iPhone，再在连接设置选择手机。", 13, MUTED, false), margin(activity, -1, -2, 10));
        help.addView(label(activity, "无法连接时，确认双方 Wi-Fi 与蓝牙开启，再重置连接。", 13, MUTED, false), margin(activity, -1, -2, 8));
        spacer(activity, help);
        help.addView(button(activity, "重置 CarPlay 连接", false,
                () -> invoke(activity, "confirmWirelessReset", new Class<?>[0])), margin(activity, -1, 48, 8));
    }
    private static void diagnostics(Activity activity, LinearLayout parent) {
        LinearLayout logs = card(activity, parent, "诊断日志", "遇到问题时启用日志，再复现问题并保存诊断报告。", 4);
        toggle(activity, logs, "启用调试日志", Boolean.TRUE.equals(call("AirPlayPersistence", "loadDebugLogsEnabled", new Class<?>[]{Context.class}, activity)),
                enabled -> call("AirPlayPersistence", "saveDebugLogsEnabled", new Class<?>[]{Context.class, boolean.class}, activity, enabled));
        logs.addView(button(activity, "保存诊断报告", true, () -> invoke(activity, "exportDiagnostics", new Class<?>[]{Uri.class}, (Object) null)), margin(activity, -1, 48, 10));
        if (com.shilapi.xcertplay.network.WifiP2pChannels.INSTANCE.legacySelectionSupported())
            logs.addView(button(activity, "Wi-Fi 信道实验", false, () -> channelExperiment(activity)), margin(activity, -1, 48, 8));
        logs.addView(button(activity, "选择保存位置", false, () -> invoke(activity, "chooseReportDestination", new Class<?>[0])), margin(activity, -1, 48, 8));
    }
    private static void channelExperiment(Activity activity) {
        int[] values = {0, -1, 36, 40, 44, 48, 149, 153, 157, 161, 165};
        LinearLayout panel = column(activity);
        panel.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
        panel.addView(label(activity, "默认 Auto。实验选择在下次重连生效；以诊断中的实际频率为准。", 13, MUTED, false));
        final android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        LinearLayout line = null;
        for (int index = 0; index < values.length; index++) {
            if (index % 3 == 0) { line = row(activity); panel.addView(line, margin(activity, -1, 48, 6)); }
            final int value = values[index];
            String name = value == 0 ? "Auto" : value == -1 ? "5 GHz 优先" : "信道 " + value;
            line.addView(button(activity, name, false, () -> {
                call("AirPlayPersistence", "saveWifiP2pPreferredChannel", new Class<?>[]{Context.class, int.class}, activity, value);
                dialog[0].dismiss();
                Toast.makeText(activity, "已保存，下次重新连接生效", Toast.LENGTH_SHORT).show();
            }), new LinearLayout.LayoutParams(0, -1, 1));
        }
        dialog[0] = LandscapeSettingsLayout.dialogBuilder(activity).setTitle("Wi-Fi 信道实验")
                .setView(panel).setNegativeButton("取消", null).create();
        dialog[0].show();
    }
    private static void about(Activity activity, LinearLayout parent) {
        LinearLayout info = card(activity, parent, "FAW Play", "红旗 HS5 · 无线 CarPlay", 5);
        try { info.addView(label(activity, "版本 " + activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName, 13, MUTED, false), margin(activity, -1, -2, 12)); }
        catch (PackageManager.NameNotFoundException ignored) {}
        spacer(activity, info);
        info.addView(label(activity, "开源说明", 16, TEXT, true), margin(activity, -1, -2, 12));
        info.addView(label(activity, "基于 DiPlay / xcertplay 开源项目开发。保留原项目及依赖的开源许可。", 13, MUTED, false), margin(activity, -1, -2, 8));
        LinearLayout language = card(activity, parent, "语言", null, 5);
        ((LinearLayout.LayoutParams) language.getLayoutParams()).weight = 1.4f;
        String[] tags = {"system", "zh", "en", "ar", "ru", "es", "uk"};
        String[] names = {"跟随系统", "简体中文", "English", "العربية", "Русский", "Español", "Українська"};
        Object currentLanguage = call("AppLocale", "preference", new Class<?>[]{Context.class}, activity);
        LinearLayout choices = null;
        for (int i = 0; i < tags.length; i++) {
            if (i % 2 == 0) { choices = row(activity); language.addView(choices, margin(activity, -1, 48, 6)); }
            String tag = tags[i];
            Button option = action(activity, choices, names[i], tag.equals(currentLanguage), () -> {
                call("AppLocale", "save", new Class<?>[]{Context.class, String.class}, activity, tag);
                if (Build.VERSION.SDK_INT < 33) activity.recreate(); else show(activity, "settings");
            });
            option.setSelected(tag.equals(currentLanguage));
        }
    }
    private static List<BluetoothDevice> bonded(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return Collections.emptyList();
            BluetoothManager manager = activity.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return Collections.emptyList();
            List<BluetoothDevice> result = new ArrayList<>(adapter.getBondedDevices());
            Collections.sort(result, Comparator.comparing(d -> d.getName() == null ? "" : d.getName()));
            return result;
        } catch (RuntimeException unavailable) { return Collections.emptyList(); }
    }
    private static boolean session() { return Boolean.TRUE.equals(call("CarPlayBackgroundSession", "hasSession", new Class<?>[0])); }
    private static boolean active() { return Boolean.TRUE.equals(call("CarPlayBackgroundSession", "getActive", new Class<?>[0])); }
    private static void stop(Activity activity) {
        call("CarPlayBackgroundSession", "stop", new Class<?>[]{Function0.class}, new Function0<Unit>() {
            @Override public Unit invoke() { activity.runOnUiThread(() -> { if (!activity.isFinishing()) show(activity, "settings"); }); return Unit.INSTANCE; }
        });
    }
    private static Object call(String owner, String name, Class<?>[] types, Object... args) {
        try {
            Class<?> cls = Class.forName("com.shilapi.xcertplay." + owner);
            Method method = cls.getDeclaredMethod(name, types); method.setAccessible(true);
            Object target = Modifier.isStatic(method.getModifiers()) ? null : cls.getField("INSTANCE").get(null);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            Log.w("FAWSettings", "Setting action unavailable: " + name + " (" + unavailable.getClass().getSimpleName() + ")"); return null;
        }
    }
    private static void invoke(Activity activity, String name, Class<?>[] types, Object... args) {
        try { Method method = activity.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(activity, args); }
        catch (ReflectiveOperationException | RuntimeException unavailable) {
            Log.w("FAWSettings", "Page action unavailable: " + name + " (" + unavailable.getClass().getSimpleName() + ")");
            Toast.makeText(activity, "操作暂不可用，请检查权限后重试", Toast.LENGTH_SHORT).show();
        }
    }
    private static void home(Activity activity) {
        try {
            Field page = activity.getClass().getDeclaredField("page"); page.setAccessible(true); page.set(activity, "home");
            invoke(activity, "render", new Class<?>[0]);
        } catch (ReflectiveOperationException unavailable) { activity.onBackPressed(); }
    }
    private static void open(Activity activity, Intent intent) {
        try { activity.startActivity(intent); }
        catch (RuntimeException unavailable) { PermissionAccess.openAppSettings(activity); }
    }
    private static void toggle(Activity activity, LinearLayout parent, String title, boolean checked, Save save) {
        Switch control = new Switch(activity); control.setText(title); control.setTextSize(sp(activity, compact(activity) ? 14 : 16)); control.setTextColor(TEXT);
        control.setGravity(Gravity.CENTER_VERTICAL); control.setPadding(0, 0, 0, 0); control.setMinHeight(dp(activity, 48)); control.setChecked(checked);
        GradientDrawable thumb = new GradientDrawable(); thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(TEXT); thumb.setSize(dp(activity, 24), dp(activity, 24));
        GradientDrawable track = new GradientDrawable(); track.setCornerRadius(dp(activity, 14));
        track.setColor(MUTED); track.setSize(dp(activity, 48), dp(activity, 28));
        control.setThumbDrawable(thumb); control.setTrackDrawable(track); control.setShowText(false);
        control.setSwitchMinWidth(dp(activity, 48)); control.setSwitchPadding(dp(activity, 12));
        ColorStateList colors = new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{RED, MUTED});
        control.setThumbTintList(ColorStateList.valueOf(TEXT)); control.setTrackTintList(colors);
        control.setOnCheckedChangeListener((button, value) -> save.set(value)); parent.addView(control, margin(activity, -1, 48, 10));
    }
    private static LinearLayout card(Activity activity, LinearLayout parent, String title, String hint, int icon) {
        LinearLayout panel = column(activity); panel.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        panel.setBackground(background(activity, SURFACE, BORDER));
        TextView heading = label(activity, title, 20, TEXT, true);
        heading.setCompoundDrawablesWithIntrinsicBounds(new Mark(icon, TEXT, dp(activity, 20)), null, null, null); heading.setCompoundDrawablePadding(dp(activity, 8));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        if (hint != null) panel.addView(label(activity, hint, 13, MUTED, false), margin(activity, -1, -2, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        if (parent.getChildCount() != 0) params.leftMargin = dp(activity, 10);
        parent.addView(panel, params); return panel;
    }
    private static Button action(Activity activity, LinearLayout row, String title, boolean primary, Runnable run) {
        Button button = button(activity, title, primary, run);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() != 0) params.leftMargin = dp(activity, 6);
        row.addView(button, params); return button;
    }
    private static void spacer(Activity activity, LinearLayout parent) { parent.addView(new View(activity), new LinearLayout.LayoutParams(-1, 0, 1)); }
    private static boolean compact(Activity activity) {
        return activity.getResources().getConfiguration().screenHeightDp / uiScale(activity) < 420;
    }
    private static LinearLayout column(Activity activity) { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private static LinearLayout row(Activity activity) { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private static TextView label(Activity activity, String text, int size, int color, boolean bold) {
        TextView v = new TextView(activity); v.setText(text); v.setTextSize(sp(activity, compact(activity) && size >= 16 ? size - 2 : size)); v.setTextColor(color); v.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(null, Typeface.BOLD); return v;
    }
    private static Button button(Activity activity, String title, boolean primary, Runnable action) {
        Button v = new Button(activity); v.setText(title); v.setTextSize(sp(activity, compact(activity) ? 14 : 16)); v.setAllCaps(false); v.setTextColor(TEXT);
        v.setGravity(Gravity.CENTER); v.setPadding(dp(activity, 6), 0, dp(activity, 6), 0);
        v.setMinHeight(0); v.setMinimumHeight(0); v.setMinWidth(0); v.setMinimumWidth(0);
        v.setBackground(background(activity, primary ? RED : SURFACE, primary ? RED : BORDER));
        v.setOnClickListener(view -> action.run()); return v;
    }
    private static Drawable background(Context context, int fill, int stroke) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(fill); bg.setCornerRadius(dp(context, 12)); bg.setStroke(dp(context, 1), stroke); return bg;
    }
    private static LinearLayout.LayoutParams margin(Context c, int width, int height, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width < 0 ? width : dp(c, width), height < 0 ? height : dp(c, height)); p.topMargin = dp(c, top); return p;
    }
    /** Keep the landscape layout at a 360 dp reference height on low-density car displays. */
    private static float uiScale(Context context) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float shortEdgeDp = Math.min(metrics.widthPixels, metrics.heightPixels) / metrics.density;
        return Math.max(1f, shortEdgeDp / 360f);
    }
    private static int sp(Context context, int value) { return Math.max(1, Math.round(value * uiScale(context))); }
    private static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density * uiScale(c)); }
    /** Small deterministic line icons; the generated screenshot is not used as a UI bitmap. */
    private static final class Mark extends Drawable {
        private final int kind, color, size;
        Mark(int kind, int color, int size) { this.kind=kind; this.color=color; this.size=size; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void draw(Canvas canvas) {
            canvas.save(); canvas.translate(getBounds().left, getBounds().top); canvas.scale(getBounds().width()/24f, getBounds().height()/24f);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.7f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            switch(kind) {
                case 0: canvas.drawRoundRect(3, 10, 14, 20, 4, 4, p); canvas.drawRoundRect(10, 3, 21, 13, 4, 4, p); canvas.drawLine(9, 15, 15, 9, p); break;
                case 1: canvas.drawRoundRect(2, 4, 22, 17, 2, 2, p); canvas.drawLine(8, 21, 16, 21, p); canvas.drawLine(12, 17, 12, 21, p); break;
                case 2: path.moveTo(12, 2); path.lineTo(21, 22); path.lineTo(12, 17); path.lineTo(3, 22); path.close(); canvas.drawPath(path,p); break;
                case 3: path.moveTo(12, 2); path.lineTo(21, 6); path.lineTo(19, 16); path.quadTo(17, 21, 12, 23); path.quadTo(7, 21, 5, 16); path.lineTo(3, 6); path.close(); canvas.drawPath(path,p); canvas.drawLine(8,12,11,15,p); canvas.drawLine(11,15,16,9,p); break;
                case 4: canvas.drawLine(4, 20, 17, 7,p); canvas.drawCircle(17,6,4,p); canvas.drawCircle(5,19,2,p); break;
                case 7: canvas.drawLine(9, 5, 9, 18, p); canvas.drawLine(9, 5, 20, 3, p); canvas.drawLine(20, 3, 20, 16, p); canvas.drawOval(3, 16, 9, 21, p); canvas.drawOval(14, 14, 20, 19, p); break;
                case 8: path.moveTo(5, 3); path.lineTo(9, 7); path.lineTo(7, 10); path.quadTo(9, 15, 14, 17); path.lineTo(17, 15); path.lineTo(21, 19); path.quadTo(17, 25, 9, 17); path.quadTo(0, 8, 5, 3); canvas.drawPath(path, p); break;
                case 9: canvas.drawCircle(12, 12, 7, p); canvas.drawCircle(12, 12, 3, p); for (int i=0;i<8;i++) { double angle=i*Math.PI/4; canvas.drawLine(12+(float)Math.cos(angle)*7,12+(float)Math.sin(angle)*7,12+(float)Math.cos(angle)*10,12+(float)Math.sin(angle)*10,p); } break;
                case 10: canvas.drawRoundRect(6, 2, 18, 22, 2, 2, p); canvas.drawLine(10, 19, 14, 19, p); break;
                case 5: canvas.drawCircle(12,12,10,p); canvas.drawPoint(12,7,p); canvas.drawLine(12,11,12,17,p); break;
                default: canvas.drawLine(12,2,12,10,p); canvas.drawArc(3,3,21,21,-55,290,false,p);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
