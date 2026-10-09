// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Keeps legacy view and dialog callers compatible with the FAW settings renderer. */
public final class LandscapeSettingsLayout {
    private static final String TAG = "DiPlaySettings";
    private static final String PAGE_HOME = "home";
    private static final String PAGE_SETTINGS = "settings";
    private static final String PAGE_CONNECTION = "connection";
    private static final int MIN_LANDSCAPE_WIDTH_DP = 600;
    private static final int DIALOG_MAX_WIDTH_DP = 760;
    private static final int DIALOG_SCREEN_MARGIN_DP = 32;
    private static final int DIALOG_CONTROL_SPACE_DP = 136;
    private static final int DIALOG_MIN_CONTENT_HEIGHT_DP = 96;
    private static final int MIN_TOUCH_HEIGHT_DP = 48;
    private static final int CHOICE_HEIGHT_DP = 52;
    private static final int MIN_INLINE_PANEL_HEIGHT_DP = 200;
    private static final int MIN_SEGMENTED_CHOICES = 2;
    private static final int MAX_SEGMENTED_CHOICES = 4;
    private static final int FIRST_GESTURE_FINGER_COUNT = 2;
    private static final int BODY_TEXT_SP = 16;
    private static final int MIN_DIALOG_TEXT_SP = 14;
    private static final int MAX_TITLE_ROW_CHILDREN = 3;
    private static final int ACTION_HEIGHT_DP = 54;
    private static final int SCROLL_SCREEN_SPACE_DP = 148;
    private static final float DIALOG_WIDTH_FRACTION = 0.8f;
    private static final float DIALOG_DIM_AMOUNT = 0.55f;
    private static final int TITLE_TEXT_SP = 20;
    private static final int MAX_TEXT_LINES = 2;
    private static final int HIDDEN_WINDOW_SIZE_PX = 1;
    private static final int BACKGROUND_COLOR = Color.rgb(12, 17, 27);
    private static final int SURFACE_COLOR = Color.rgb(21, 30, 44);
    private static final int BORDER_COLOR = Color.rgb(42, 56, 75);
    private static final int ACCENT_COLOR = Color.rgb(166, 200, 255);
    private static final int TEXT_COLOR = Color.rgb(241, 245, 252);
    private static final int SELECTED_COLOR = Color.rgb(34, 55, 82);

    private LandscapeSettingsLayout() {}

    public static View wifiDirectStatus(final Activity activity) {
        final TextView status = new TextView(activity);
        status.setText(
                resourceText(activity, "wifi_direct")
                        + "\n"
                        + resourceText(
                                activity,
                                "turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic"));
        status.setTextColor(TEXT_COLOR);
        status.setTextSize(BODY_TEXT_SP);
        status.setPadding(0, dp(activity, 8), 0, dp(activity, 12));
        return status;
    }

    public static void connectionSetup(final Activity activity, final LinearLayout content) {
        final LinearLayout direct = simpleCard(activity, content, "wifi_direct");
        direct.addView(wifiDirectStatus(activity));
        addAction(
                activity,
                direct,
                "open_car_wi_fi_settings",
                new Runnable() {
                    @Override
                    public void run() {
                        PermissionAccess.openWifiSettings(activity);
                    }
                });
        final LinearLayout pairing = simpleCard(activity, content, "s_2_pair_your_iphone");
        final TextView hint = new TextView(activity);
        hint.setText(
                resourceText(
                        activity, "keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car"));
        hint.setTextColor(TEXT_COLOR);
        hint.setTextSize(BODY_TEXT_SP);
        pairing.addView(hint);
        addAction(
                activity,
                pairing,
                "choose_iphone",
                new Runnable() {
                    @Override
                    public void run() {
                        invokeActivityAction(activity, "choosePhone", false);
                    }
                });
        addAction(
                activity,
                pairing,
                "review_app_permissions",
                new Runnable() {
                    @Override
                    public void run() {
                        PermissionAccess.openAppSettings(activity);
                    }
                });
        final LinearLayout connect = simpleCard(activity, content, "s_3_connect");
        addAction(
                activity,
                connect,
                "connect_phone",
                new Runnable() {
                    @Override
                    public void run() {
                        invokeActivityAction(activity, "connect", true);
                    }
                });
        addAction(
                activity,
                connect,
                "reset_carplay_wi_fi",
                new Runnable() {
                    @Override
                    public void run() {
                        invokeActivityAction(activity, "confirmWirelessReset", false);
                    }
                });
    }

    private static String resourceText(final Activity activity, final String name) {
        final int id =
                activity.getResources().getIdentifier(name, "string", activity.getPackageName());
        return id == 0 ? "" : activity.getString(id);
    }

    private static LinearLayout simpleCard(
            final Activity activity, final LinearLayout content, final String title) {
        final LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14));
        card.setBackground(background(activity, SURFACE_COLOR, BORDER_COLOR));
        final LinearLayout heading = new LinearLayout(activity);
        final TextView text = new TextView(activity);
        text.setText(resourceText(activity, title));
        text.setTextColor(TEXT_COLOR);
        text.setTextSize(TITLE_TEXT_SP);
        heading.addView(text);
        card.addView(heading);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(activity, 12);
        content.addView(card, params);
        return card;
    }

    private static void addAction(
            final Activity activity,
            final LinearLayout parent,
            final String label,
            final Runnable action) {
        final Button button = new Button(activity);
        button.setText(resourceText(activity, label));
        button.setAllCaps(false);
        button.setTextColor(TEXT_COLOR);
        button.setTextSize(BODY_TEXT_SP);
        button.setBackground(background(activity, SURFACE_COLOR, BORDER_COLOR));
        button.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(final View view) {
                        action.run();
                    }
                });
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, dp(activity, ACTION_HEIGHT_DP));
        params.topMargin = dp(activity, 10);
        parent.addView(button, params);
    }

    private static void invokeActivityAction(
            final Activity activity, final String name, final boolean connect) {
        try {
            final Method method =
                    connect
                            ? activity.getClass().getDeclaredMethod(name, boolean.class)
                            : activity.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            if (connect) {
                method.invoke(activity, true);
            } else {
                method.invoke(activity);
            }
        } catch (final ReflectiveOperationException | SecurityException unavailable) {
            Log.w(TAG, "Connection action unavailable", unavailable);
            Toast.makeText(
                            activity,
                            resourceText(
                                    activity,
                                    "could_not_start_carplay_return_to_diplay_and_check_app_per"),
                            Toast.LENGTH_LONG)
                    .show();
        }
    }

    /** Removes obsolete controls from older APK layouts as well as the current source UI. */
    private static void removeUnusedSettings(final Activity activity, final View view) {
        if (!(view instanceof ViewGroup)) return;
        final ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            final View child = group.getChildAt(i);
            if (child instanceof TextView
                    && hasResourceText(
                            activity,
                            child,
                            "nearby_devices_connects_your_iphone_microphone_enables_sir",
                            "choose_how_to_connect_follow_the_setup_steps_and_save_your",
                            "use_your_last_connection_type_and_selected_iphone")) {
                ((TextView) child)
                        .setText(
                                resourceText(
                                        activity,
                                        "keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car"));
            }
            final boolean audioCard =
                    child instanceof LinearLayout
                            && ((LinearLayout) child).getChildCount() > 0
                            && hasTitleText(
                                    activity,
                                    ((LinearLayout) child).getChildAt(0),
                                    "audio_routing");
            final boolean obsoleteRow =
                    child instanceof LinearLayout
                            && ((LinearLayout) child).getChildCount() > 0
                            && hasTitleText(
                                    activity,
                                    ((LinearLayout) child).getChildAt(0),
                                    "usb_auto_confirm_title",
                                    "advanced_audio_channel_mapping",
                                    "wireless_carplay_2");
            if (audioCard
                    || obsoleteRow
                    || child instanceof TextView
                            && hasResourceText(
                                    activity,
                                    child,
                                    "connect_with_usb",
                                    "plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y",
                                    "usb_auto_confirm_title",
                                    "usb_auto_confirm_subtitle")) {
                group.removeViewAt(i);
            } else removeUnusedSettings(activity, child);
        }
    }

    /** Match only a title or its short text column, never a scroll/container ancestor. */
    private static boolean hasTitleText(
            final Activity activity, final View view, final String... names) {
        if (view instanceof TextView) return hasResourceText(activity, view, names);
        if (!(view instanceof LinearLayout)) return false;
        final LinearLayout row = (LinearLayout) view;
        if (row.getChildCount() == 0 || row.getChildCount() > MAX_TITLE_ROW_CHILDREN) return false;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (!(row.getChildAt(i) instanceof TextView)
                    && !(row.getChildAt(i) instanceof ImageView)) return false;
        }
        return hasResourceText(activity, row, names);
    }

    public static void cleanHostMenu(final Activity activity, final View view) {
        removeUnusedSettings(activity, view);
        removeHostAudioSection(activity, view);
    }

    private static void removeHostAudioSection(final Activity activity, final View view) {
        if (!(view instanceof ViewGroup)) return;
        final ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            final View child = group.getChildAt(i);
            if (child instanceof TextView && hasResourceText(activity, child, "audio"))
                group.removeViewAt(i);
            else removeHostAudioSection(activity, child);
        }
    }

    public static void fullscreen(final Activity activity) {
        final Window window = activity.getWindow();
        final View decor = window.getDecorView();
        final boolean multiWindow =
                android.os.Build.VERSION.SDK_INT >= 24 && activity.isInMultiWindowMode();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!multiWindow)
            flags |=
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decor.setSystemUiVisibility(flags);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(multiWindow);
            final android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                if (multiWindow) controller.show(android.view.WindowInsets.Type.systemBars());
                else controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    public static void apply(final Activity activity, final View original, final String page) {
        FawSystemParameters.enforce(activity);
        if (isSettingsPage(page)) {
            FawSettingsPage.show(activity, page);
            fullscreen(activity);
            return;
        }
        if (PAGE_HOME.equals(page)) {
            FawSettingsPage.showHome(activity);
            PermissionAccess.ensure(activity);
            return;
        }
        removeUnusedSettings(activity, original);
        activity.setContentView(original);
        fullscreen(activity);
    }

    public static void hideSettingsHint(final Activity activity) {
        try {
            final Field field = activity.getClass().getDeclaredField("settingsGestureHint");
            field.setAccessible(true);
            final Object view = field.get(activity);
            if (view instanceof View) ((View) view).setVisibility(View.GONE);
        } catch (final ReflectiveOperationException | SecurityException unavailable) {
            Log.w(TAG, "Settings gesture hint unavailable", unavailable);
        }
    }

    private static boolean isSettingsPage(final String page) {
        return PAGE_SETTINGS.equals(page) || PAGE_CONNECTION.equals(page);
    }

    private static boolean hasResourceText(
            final Activity activity, final View view, final String... names) {
        if (view instanceof TextView) {
            final CharSequence text = ((TextView) view).getText();
            for (final String name : names) {
                final int id =
                        activity.getResources()
                                .getIdentifier(name, "string", activity.getPackageName());
                if (id != 0 && activity.getString(id).contentEquals(text)) return true;
            }
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (hasResourceText(activity, group.getChildAt(i), names)) return true;
        }
        return false;
    }

    /** Compatibility entry for the same renderer in the locally repackaged release APK. */
    public static void apply(final Activity activity, final View original) {
        String page = "";
        try {
            final Field field = activity.getClass().getDeclaredField("page");
            field.setAccessible(true);
            final Object value = field.get(activity);
            if (value instanceof String) page = (String) value;
        } catch (final ReflectiveOperationException | SecurityException unavailable) {
            // Keep the original presentation if this is a different renderer.
        }
        apply(activity, original, page);
    }

    public static AlertDialog.Builder dialogBuilder(final Activity activity) {
        return new AlertDialog.Builder(activity) {
            @Override
            public AlertDialog show() {
                final AlertDialog dialog = super.create();
                showDialog(dialog);
                return dialog;
            }
        };
    }

    public static AlertDialog showDialog(final AlertDialog.Builder builder) {
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        return dialog;
    }

    public static void showDialog(final AlertDialog dialog) {
        showDialog((Dialog) dialog);
    }

    public static void showDialog(final Dialog dialog) {
        final Activity activity = activityFrom(dialog.getContext());
        if (activity != null && showInline(activity, dialog)) return;
        dialog.show();
        if (dialog instanceof AlertDialog) styleDialog((AlertDialog) dialog, false, 0);
    }

    private static final class InlineForm {
        final ScrollView scroll;
        final Activity activity;
        final int previousInputMode;
        final LinearLayout content;
        final ArrayList<View> previous = new ArrayList<>();
        final int previousY;
        final Dialog dialog;
        View panel;
        OnBackPressedCallback back;
        boolean closed;

        InlineForm(
                final Activity activity,
                final ScrollView scroll,
                final LinearLayout content,
                final Dialog dialog,
                final int previousY) {
            this.activity = activity;
            previousInputMode = activity.getWindow().getAttributes().softInputMode;
            this.scroll = scroll;
            this.content = content;
            this.dialog = dialog;
            this.previousY = previousY;
            for (int i = 0; i < content.getChildCount(); i++) previous.add(content.getChildAt(i));
        }

        void restore() {
            if (closed) return;
            closed = true;
            if (back != null) back.remove();
            final InputMethodManager input = activity.getSystemService(InputMethodManager.class);
            if (input != null) input.hideSoftInputFromWindow(scroll.getWindowToken(), 0);
            activity.getWindow().setSoftInputMode(previousInputMode);
            if (scroll.getTag() == this) scroll.setTag(null);
            if (content.getChildCount() != 1 || content.getChildAt(0) != panel) return;
            content.removeAllViews();
            for (final View view : previous) content.addView(view);
            afterLayout(
                    scroll,
                    new Runnable() {
                        @Override
                        public void run() {
                            if (scroll.getTag() != null
                                    || content.getChildCount() != previous.size()) return;
                            for (int i = 0; i < previous.size(); i++)
                                if (content.getChildAt(i) != previous.get(i)) return;
                            scroll.scrollTo(0, previousY);
                        }
                    });
        }
    }

    private static Activity activityFrom(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            final Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private static boolean showInline(final Activity activity, final Dialog dialog) {
        final ScrollView scroll;
        try {
            final Field pageField = activity.getClass().getDeclaredField("page");
            pageField.setAccessible(true);
            final String value = (String) pageField.get(activity);
            if (!isSettingsPage(value)) return false;
            final Field root = activity.getClass().getDeclaredField("rootScroll");
            root.setAccessible(true);
            scroll = (ScrollView) root.get(activity);
        } catch (final ReflectiveOperationException
                | ClassCastException
                | SecurityException unavailable) {
            return false;
        }
        if (scroll == null
                || scroll.getChildCount() != 1
                || !(scroll.getChildAt(0) instanceof LinearLayout)) return false;
        int previousY = scroll.getScrollY();
        if (scroll.getTag() instanceof InlineForm) {
            final InlineForm previous = (InlineForm) scroll.getTag();
            previousY = previous.previousY;
            previous.dialog.dismiss();
        }
        final LinearLayout content = (LinearLayout) scroll.getChildAt(0);
        final InlineForm form = new InlineForm(activity, scroll, content, dialog, previousY);
        final Window window = dialog.getWindow();
        if (window == null) return false;
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        try {
            form.back =
                    new OnBackPressedCallback(true) {
                        @Override
                        public void handleOnBackPressed() {
                            cancelEditor(dialog);
                        }
                    };
            final Object dispatcher =
                    activity.getClass().getMethod("getOnBackPressedDispatcher").invoke(activity);
            dispatcher
                    .getClass()
                    .getMethod("addCallback", OnBackPressedCallback.class)
                    .invoke(dispatcher, form.back);
        } catch (final ReflectiveOperationException | SecurityException unavailable) {
            form.back = null;
        }
        // Keep the original controller and listeners; its window never appears or takes input.
        window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        final WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.alpha = 0f;
        attributes.dimAmount = 0f;
        window.setAttributes(attributes);
        window.setLayout(HIDDEN_WINDOW_SIZE_PX, HIDDEN_WINDOW_SIZE_PX);
        scroll.setTag(form);
        dialog.show();
        window.getDecorView()
                .addOnAttachStateChangeListener(
                        new View.OnAttachStateChangeListener() {
                            @Override
                            public void onViewAttachedToWindow(final View view) {}

                            @Override
                            public void onViewDetachedFromWindow(final View view) {
                                form.restore();
                            }
                        });
        // Let the existing OnShow handlers and custom-panel height fixes finish first.
        window.getDecorView()
                .post(
                        new Runnable() {
                            @Override
                            public void run() {
                                if (form.closed
                                        || !scroll.isAttachedToWindow()
                                        || content.getChildCount() != form.previous.size()) {
                                    dialog.dismiss();
                                    return;
                                }
                                for (int i = 0; i < form.previous.size(); i++) {
                                    if (content.getChildAt(i) != form.previous.get(i)) {
                                        dialog.dismiss();
                                        return;
                                    }
                                }
                                if (dialog instanceof AlertDialog)
                                    styleDialog((AlertDialog) dialog, true, scroll.getHeight());
                                window.setLayout(HIDDEN_WINDOW_SIZE_PX, HIDDEN_WINDOW_SIZE_PX);
                                final View host =
                                        window.getDecorView().findViewById(android.R.id.content);
                                if (!(host instanceof ViewGroup)
                                        || ((ViewGroup) host).getChildCount() != 1) {
                                    dialog.dismiss();
                                    return;
                                }
                                final View body = ((ViewGroup) host).getChildAt(0);
                                ((ViewGroup) host).removeView(body);
                                final LinearLayout panel = new LinearLayout(activity);
                                panel.setOrientation(LinearLayout.VERTICAL);
                                panel.setBackground(
                                        background(activity, SURFACE_COLOR, BORDER_COLOR));
                                final Button back = new Button(activity);
                                back.setText(activity.getString(android.R.string.cancel));
                                back.setTextColor(TEXT_COLOR);
                                back.setTextSize(BODY_TEXT_SP);
                                back.setMinHeight(dp(activity, MIN_TOUCH_HEIGHT_DP));
                                back.setBackground(
                                        background(activity, SURFACE_COLOR, BORDER_COLOR));
                                back.setOnClickListener(
                                        new View.OnClickListener() {
                                            @Override
                                            public void onClick(final View view) {
                                                cancelEditor(dialog);
                                            }
                                        });
                                // The native action row already contains cancel for most editors.
                                final Button cancel =
                                        dialog instanceof AlertDialog
                                                ? ((AlertDialog) dialog)
                                                        .getButton(AlertDialog.BUTTON_NEGATIVE)
                                                : null;
                                if (dialog instanceof AlertDialog
                                        && (cancel == null
                                                || cancel.getVisibility() != View.VISIBLE))
                                    panel.addView(back, new LinearLayout.LayoutParams(-1, -2));
                                panel.addView(
                                        body,
                                        new LinearLayout.LayoutParams(
                                                -1,
                                                dialog instanceof AlertDialog
                                                        ? -2
                                                        : Math.max(
                                                                dp(
                                                                        activity,
                                                                        MIN_INLINE_PANEL_HEIGHT_DP),
                                                                scroll.getHeight())));
                                form.panel = panel;
                                content.removeAllViews();
                                content.addView(panel, new LinearLayout.LayoutParams(-1, -2));
                                panel.addOnAttachStateChangeListener(
                                        new View.OnAttachStateChangeListener() {
                                            @Override
                                            public void onViewAttachedToWindow(final View view) {}

                                            @Override
                                            public void onViewDetachedFromWindow(final View view) {
                                                if (!form.closed) {
                                                    dialog.dismiss();
                                                    form.restore();
                                                }
                                            }
                                        });
                                afterLayout(
                                        scroll,
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                if (!form.closed) scroll.scrollTo(0, 0);
                                            }
                                        });
                            }
                        });
        return true;
    }

    private static void cancelEditor(final Dialog dialog) {
        final Button cancel =
                dialog instanceof AlertDialog
                        ? ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_NEGATIVE)
                        : null;
        if (cancel != null && cancel.getVisibility() == View.VISIBLE) cancel.performClick();
        else dialog.cancel();
    }

    public interface GestureSaver {
        void save(final int fingers);
    }

    public static View gestureControl(
            final Activity activity,
            final String title,
            final String[] labels,
            final int current,
            final GestureSaver saver) {
        final LinearLayout control = new LinearLayout(activity);
        control.setOrientation(LinearLayout.VERTICAL);
        final TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextSize(BODY_TEXT_SP);
        heading.setTextColor(TEXT_COLOR);
        heading.setPadding(0, 0, 0, dp(activity, 12));
        control.addView(heading);
        final RadioGroup choices = new RadioGroup(activity);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        final int[] fingerOptions =
                HongqiWheelControls.supported(activity) ? new int[] {2, 4} : new int[] {2, 3, 4};
        final int normalizedFingers = SettingsGesturePolicy.normalize(activity, current);
        for (int i = 0; i < fingerOptions.length; i++) {
            final RadioButton button = new RadioButton(activity);
            button.setId(View.generateViewId());
            button.setText(labels[fingerOptions[i] - FIRST_GESTURE_FINGER_COUNT]);
            button.setContentDescription(
                    title + " · " + labels[fingerOptions[i] - FIRST_GESTURE_FINGER_COUNT]);
            button.setTextSize(BODY_TEXT_SP);
            button.setTextColor(TEXT_COLOR);
            button.setButtonTintList(ColorStateList.valueOf(ACCENT_COLOR));
            button.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
            button.setMinHeight(dp(activity, CHOICE_HEIGHT_DP));
            button.setMinimumHeight(dp(activity, CHOICE_HEIGHT_DP));
            button.setGravity(Gravity.CENTER_VERTICAL);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) params.setMarginStart(dp(activity, 8));
            choices.addView(button, params);
            button.setChecked(normalizedFingers == fingerOptions[i]);
            button.setBackground(
                    background(
                            activity,
                            button.isChecked() ? SELECTED_COLOR : SURFACE_COLOR,
                            button.isChecked() ? ACCENT_COLOR : BORDER_COLOR));
        }
        choices.setOnCheckedChangeListener(
                new RadioGroup.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(final RadioGroup group, final int id) {
                        for (int i = 0; i < group.getChildCount(); i++) {
                            final RadioButton button = (RadioButton) group.getChildAt(i);
                            final boolean checked = button.getId() == id;
                            button.setBackground(
                                    background(
                                            activity,
                                            checked ? SELECTED_COLOR : SURFACE_COLOR,
                                            checked ? ACCENT_COLOR : BORDER_COLOR));
                            if (checked) saver.save(fingerOptions[i]);
                        }
                    }
                });
        control.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        return control;
    }

    private static void styleDialog(
            final AlertDialog dialog, final boolean inline, final int contentHeight) {
        final Context context = dialog.getContext();
        if (!inline
                && (context.getResources().getConfiguration().screenWidthDp < MIN_LANDSCAPE_WIDTH_DP
                        || context.getResources().getConfiguration().screenWidthDp
                                <= context.getResources().getConfiguration().screenHeightDp))
            return;
        final Window window = dialog.getWindow();
        if (window == null) return;
        final View decor = window.getDecorView();
        window.setBackgroundDrawable(background(context, SURFACE_COLOR, BORDER_COLOR));
        window.setDimAmount(DIALOG_DIM_AMOUNT);
        final int widthDp = context.getResources().getConfiguration().screenWidthDp;
        final int width =
                Math.min(
                        context.getResources().getDisplayMetrics().widthPixels
                                - dp(context, DIALOG_SCREEN_MARGIN_DP),
                        dp(
                                context,
                                Math.min(
                                        DIALOG_MAX_WIDTH_DP,
                                        Math.round(widthDp * DIALOG_WIDTH_FRACTION))));
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        styleDialogText(context, decor);
        final View title =
                decor.findViewById(
                        context.getResources().getIdentifier("alertTitle", "id", "android"));
        if (title instanceof TextView) {
            ((TextView) title).setTextSize(TITLE_TEXT_SP);
            ((TextView) title).setTextColor(TEXT_COLOR);
            ((TextView) title).setMaxLines(MAX_TEXT_LINES);
        }
        final int available =
                contentHeight > 0
                        ? contentHeight
                        : context.getResources().getDisplayMetrics().heightPixels
                                - dp(context, DIALOG_SCREEN_MARGIN_DP);
        compactChoices(context, dialog, available);
        final View custom = decor.findViewById(android.R.id.custom);
        if (custom instanceof FrameLayout && ((FrameLayout) custom).getChildCount() == 1) {
            final FrameLayout frame = (FrameLayout) custom;
            final View fields = frame.getChildAt(0);
            if (!(fields instanceof ScrollView)) {
                frame.removeView(fields);
                final BoundedScrollView scroll =
                        new BoundedScrollView(
                                context,
                                Math.max(
                                        dp(context, DIALOG_MIN_CONTENT_HEIGHT_DP),
                                        available - dp(context, DIALOG_CONTROL_SPACE_DP)));
                scroll.addView(fields, new ScrollView.LayoutParams(-1, -2));
                frame.addView(scroll, new FrameLayout.LayoutParams(-1, -2));
            }
        }
        final int[] actions = {
            AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE
        };
        for (final int which : actions) {
            final Button button = dialog.getButton(which);
            if (button == null) continue;
            button.setAllCaps(false);
            button.setTextSize(BODY_TEXT_SP);
            button.setMaxLines(MAX_TEXT_LINES);
            button.setMinHeight(dp(context, MIN_TOUCH_HEIGHT_DP));
            button.setMinimumHeight(dp(context, MIN_TOUCH_HEIGHT_DP));
            button.setMinWidth(dp(context, 104));
            button.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
            button.setTextColor(
                    which == AlertDialog.BUTTON_POSITIVE ? BACKGROUND_COLOR : TEXT_COLOR);
            button.setBackground(
                    background(
                            context,
                            which == AlertDialog.BUTTON_POSITIVE ? ACCENT_COLOR : SURFACE_COLOR,
                            which == AlertDialog.BUTTON_POSITIVE ? ACCENT_COLOR : BORDER_COLOR));
            button.setStateListAnimator(null);
            final ViewGroup.LayoutParams params = button.getLayoutParams();
            if (params != null) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                if (params instanceof ViewGroup.MarginLayoutParams)
                    ((ViewGroup.MarginLayoutParams) params).setMarginStart(dp(context, 8));
                button.setLayoutParams(params);
            }
        }
        decor.post(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing()) return;
                        final int available =
                                context.getResources().getDisplayMetrics().heightPixels
                                        - dp(context, DIALOG_SCREEN_MARGIN_DP);
                        if (decor.getHeight() > available)
                            window.setLayout(window.getAttributes().width, available);
                    }
                });
    }

    private static void styleDialogText(final Context context, final View view) {
        if (view instanceof TextView) {
            final TextView text = (TextView) view;
            text.setTextColor(TEXT_COLOR);
            text.setTextSize(
                    Math.max(
                            MIN_DIALOG_TEXT_SP,
                            Math.min(
                                    BODY_TEXT_SP,
                                    text.getTextSize()
                                            / context.getResources()
                                                    .getDisplayMetrics()
                                                    .scaledDensity)));
            if (text instanceof EditText)
                ((EditText) text).setHintTextColor(Color.rgb(167, 182, 203));
            if (text instanceof Button && !(text instanceof CompoundButton)) {
                text.setMinHeight(dp(context, MIN_TOUCH_HEIGHT_DP));
                text.setMinimumHeight(dp(context, MIN_TOUCH_HEIGHT_DP));
            }
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                styleDialogText(context, group.getChildAt(i));
        }
    }

    private static void compactChoices(
            final Context context, final AlertDialog dialog, final int availableHeight) {
        final ListView list = dialog.getListView();
        if (list == null || list.getAdapter() == null || !(list.getParent() instanceof ViewGroup))
            return;
        final ListAdapter adapter = list.getAdapter();
        final int count = adapter.getCount();
        if (count < MIN_SEGMENTED_CHOICES
                || count > MAX_SEGMENTED_CHOICES
                || list.getChoiceMode() != ListView.CHOICE_MODE_SINGLE) {
            final ViewGroup.LayoutParams params = list.getLayoutParams();
            params.height =
                    Math.min(
                            Math.max(
                                    dp(context, CHOICE_HEIGHT_DP),
                                    count * dp(context, CHOICE_HEIGHT_DP)),
                            Math.max(
                                    dp(context, DIALOG_MIN_CONTENT_HEIGHT_DP),
                                    availableHeight - dp(context, DIALOG_CONTROL_SPACE_DP)));
            list.setLayoutParams(params);
            list.setOnTouchListener(
                    new View.OnTouchListener() {
                        @Override
                        public boolean onTouch(final View view, final MotionEvent event) {
                            if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                                list.getParent().requestDisallowInterceptTouchEvent(true);
                            else if (event.getActionMasked() == MotionEvent.ACTION_UP
                                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                                list.getParent().requestDisallowInterceptTouchEvent(false);
                            return false;
                        }
                    });
            return;
        }
        final ViewGroup parent = (ViewGroup) list.getParent();
        final RadioGroup choices = new RadioGroup(context);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        choices.setBaselineAligned(false);
        choices.setGravity(Gravity.CENTER_VERTICAL);
        choices.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 12));
        for (int i = 0; i < count; i++) {
            final RadioButton button = new RadioButton(context);
            button.setId(View.generateViewId());
            button.setText(String.valueOf(adapter.getItem(i)));
            button.setContentDescription(button.getText());
            button.setTextColor(TEXT_COLOR);
            button.setTextSize(BODY_TEXT_SP);
            button.setGravity(Gravity.CENTER_VERTICAL);
            button.setMinHeight(dp(context, CHOICE_HEIGHT_DP));
            button.setMinimumHeight(dp(context, CHOICE_HEIGHT_DP));
            button.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
            button.setButtonTintList(ColorStateList.valueOf(ACCENT_COLOR));
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) params.setMarginStart(dp(context, 8));
            choices.addView(button, params);
            if (i == list.getCheckedItemPosition()) button.setChecked(true);
            button.setBackground(
                    background(
                            context,
                            button.isChecked() ? SELECTED_COLOR : SURFACE_COLOR,
                            button.isChecked() ? ACCENT_COLOR : BORDER_COLOR));
        }
        choices.setOnCheckedChangeListener(
                new RadioGroup.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(final RadioGroup group, final int checkedId) {
                        for (int i = 0; i < group.getChildCount(); i++) {
                            final RadioButton button = (RadioButton) group.getChildAt(i);
                            final boolean checked = button.getId() == checkedId;
                            button.setBackground(
                                    background(
                                            context,
                                            checked ? SELECTED_COLOR : SURFACE_COLOR,
                                            checked ? ACCENT_COLOR : BORDER_COLOR));
                            if (checked) {
                                list.setItemChecked(i, true);
                                list.performItemClick(button, i, adapter.getItemId(i));
                            }
                        }
                    }
                });
        list.setVisibility(View.GONE);
        parent.addView(choices, parent.indexOfChild(list), new ViewGroup.LayoutParams(-1, -2));
    }

    private static final class BoundedScrollView extends ScrollView {
        private final int maxHeight;

        BoundedScrollView(final Context context, final int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(final int widthSpec, final int heightSpec) {
            int limit =
                    Math.max(
                            dp(getContext(), DIALOG_MIN_CONTENT_HEIGHT_DP),
                            getResources().getDisplayMetrics().heightPixels
                                    - dp(getContext(), SCROLL_SCREEN_SPACE_DP));
            limit = Math.min(limit, maxHeight);
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
                limit = Math.min(limit, MeasureSpec.getSize(heightSpec));
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }

    // Run after the renderer's full-page scroll restoration, which does not distinguish panes.
    private static void afterLayout(final View view, final Runnable action) {
        view.addOnLayoutChangeListener(
                new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(
                            final View v,
                            final int left,
                            final int top,
                            final int right,
                            final int bottom,
                            final int oldLeft,
                            final int oldTop,
                            final int oldRight,
                            final int oldBottom) {
                        view.removeOnLayoutChangeListener(this);
                        view.post(action);
                    }
                });
        view.requestLayout();
    }

    private static GradientDrawable background(
            final Context activity, final int fill, final int stroke) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(activity, 16));
        drawable.setStroke(dp(activity, 1), stroke);
        return drawable;
    }

    private static int dp(final Context activity, final int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
