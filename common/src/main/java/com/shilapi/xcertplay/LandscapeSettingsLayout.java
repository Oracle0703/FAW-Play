// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared fullscreen behavior and native dialog styling for the Autunn pages. */
public final class LandscapeSettingsLayout {
    private static final int MIN_LANDSCAPE_WIDTH_DP = 600;
    private static final int DIALOG_MAX_WIDTH_DP = 760;
    private static final int DIALOG_SCREEN_MARGIN_DP = 32;
    private static final int DIALOG_CONTROL_SPACE_DP = 136;
    private static final int DIALOG_MIN_CONTENT_HEIGHT_DP = 96;
    private static final int MIN_TOUCH_HEIGHT_DP = 48;
    private static final int CHOICE_HEIGHT_DP = 52;
    private static final int MIN_SEGMENTED_CHOICES = 2;
    private static final int MAX_SEGMENTED_CHOICES = 4;
    private static final int BODY_TEXT_SP = 16;
    private static final int MIN_DIALOG_TEXT_SP = 14;
    private static final int SCROLL_SCREEN_SPACE_DP = 148;
    private static final float DIALOG_WIDTH_FRACTION = 0.8f;
    private static final float DIALOG_DIM_AMOUNT = 0.55f;
    private static final int TITLE_TEXT_SP = 20;
    private static final int MAX_TEXT_LINES = 2;
    private static final int BACKGROUND_COLOR = Color.rgb(12, 17, 27);
    private static final int SURFACE_COLOR = Color.rgb(21, 30, 44);
    private static final int BORDER_COLOR = Color.rgb(42, 56, 75);
    private static final int ACCENT_COLOR = Color.rgb(166, 200, 255);
    private static final int TEXT_COLOR = Color.rgb(241, 245, 252);
    private static final int SELECTED_COLOR = Color.rgb(34, 55, 82);

    private LandscapeSettingsLayout() {}

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

    public static void showDialog(final AlertDialog dialog) {
        dialog.show();
        styleDialog(dialog);
    }

    private static void styleDialog(final AlertDialog dialog) {
        final Context context = dialog.getContext();
        if (context.getResources().getConfiguration().screenWidthDp < MIN_LANDSCAPE_WIDTH_DP
                || context.getResources().getConfiguration().screenWidthDp
                        <= context.getResources().getConfiguration().screenHeightDp)
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
                context.getResources().getDisplayMetrics().heightPixels
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
