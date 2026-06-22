package com.gitdeploy.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Stateless UI helper — all view-building primitives extracted from MainActivity.
 * Requires a MainActivity reference for Context and nav-stack access.
 */
class UiHelpers {

    final MainActivity act;

    UiHelpers(MainActivity act) {
        this.act = act;
    }

    // =========================================================================
    // PRIMITIVE LAYOUT HELPERS
    // =========================================================================

    LinearLayout lv() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout lh(int gravity) {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(gravity);
        return l;
    }

    TextView tv(String text, int color, float size, boolean bold) {
        TextView v = new TextView(act);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(size);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    View sp(int d) {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(d)));
        return v;
    }

    int dp(int v) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics());
    }

    GradientDrawable rb(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    GradientDrawable rbs(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    LinearLayout.LayoutParams mpWrap() {
        return new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    // =========================================================================
    // RIPPLE EFFECT HELPERS
    // =========================================================================

    /**
     * Applies a Material-style RippleDrawable to view.
     * RippleDrawable requires API 21+ (minSdk is 23, so we're safe).
     *
     * @param cornerDp corner radius in dp (0 = square)
     */
    void applyRipple(View view, int cornerDp) {
        // Use dark ripple on light theme (white ripple is invisible on light bg)
        int rippleColor = (ThemeManager.current() == ThemeManager.THEME_LIGHT)
            ? 0x22000000   // subtle dark ripple for light mode
            : 0x33FFFFFF;  // white ripple for dark mode
        android.content.res.ColorStateList rippleCsl =
            android.content.res.ColorStateList.valueOf(rippleColor);

        android.graphics.drawable.GradientDrawable mask =
            new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(cornerDp));

        android.graphics.drawable.Drawable existingBg = view.getBackground();
        android.graphics.drawable.RippleDrawable ripple =
            new android.graphics.drawable.RippleDrawable(rippleCsl, existingBg, mask);
        view.setBackground(ripple);
        view.setClickable(true);
    }

    /** Zero-radius ripple for list rows with square corners. */
    void applyRipple(View view) { applyRipple(view, 0); }

    /** Ripple for cards/buttons with rounded corners. */
    void applyRippleRounded(View view) { applyRipple(view, 14); }

    // =========================================================================
    // BUTTON / WIDGET FACTORIES
    // =========================================================================

    TextView iconBtn(String label, int color, Runnable onClick) {
    TextView btn = new TextView(act);
    btn.setText(label);
    btn.setTextColor(color);
    btn.setTextSize(16f);
    btn.setGravity(Gravity.CENTER);
    btn.setBackground(rbs(ThemeManager.SURFACE2, 10, ThemeManager.BORDER));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
    lp.leftMargin = dp(6);
    btn.setLayoutParams(lp);
    btn.setClickable(true);
    if (onClick != null) {
        btn.setOnClickListener(v -> {
            if (!btn.isEnabled()) return;
            btn.setEnabled(false);
            onClick.run();
            v.postDelayed(() -> btn.setEnabled(true), 400);
        });
    }
    applyRipple(btn, 10);
    return btn;
}

    TextView primaryBtn(String label, int color) {
    TextView btn = tv(label, color, 13f, true);
    btn.setGravity(Gravity.CENTER);
    btn.setBackground(rb(color, 14));
    btn.setPadding(dp(16), dp(16), dp(16), dp(16));
    btn.setLetterSpacing(0.04f);
    btn.setTextColor(ThemeManager.current() == ThemeManager.THEME_LIGHT
        ? 0xFFFFFFFF : 0xFF000000);
    btn.setLayoutParams(mpWrap());
    btn.setOnClickListener(v -> {
        if (!btn.isEnabled()) return;
        btn.setEnabled(false);
        // Note: original onClick is set outside primaryBtn
        // This only handles the default click behavior
        v.postDelayed(() -> btn.setEnabled(true), 400);
    });
    applyRipple(btn, 14);
    return btn;
}

    TextView ghostBtn(String label) {
        TextView btn = tv(label, ThemeManager.TEXT2, 13f, false);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        btn.setPadding(dp(16), dp(14), dp(16), dp(14));
        btn.setLayoutParams(mpWrap());
        applyRipple(btn, 12);
        return btn;
    }

    TextView roundBtn(String label, int color, int bg) {
        TextView btn = tv(label, color, 11f, true);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(rbs(bg, 10, color));
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        btn.setLetterSpacing(0.04f);
        applyRipple(btn, 10);
        return btn;
    }

    TextView badgeChip(String text, int color, int bg) {
        TextView t = tv(text, color, 10f, true);
        t.setBackground(rbs(bg, 6, color));
        t.setPadding(dp(7), dp(3), dp(7), dp(3));
        t.setLetterSpacing(0.04f);
        applyRipple(t, 6);
        return t;
    }

    // =========================================================================
    // FORM / FIELD HELPERS
    // =========================================================================

    View fieldLabel(String text) {
        TextView t = tv(text, ThemeManager.DIM, 10f, true);
        t.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(5);
        t.setLayoutParams(lp);
        return t;
    }

    TextView secLabel(String text) {
        TextView t = tv(text, ThemeManager.DIM, 9.5f, true);
        t.setLetterSpacing(0.12f);
        return t;
    }

    EditText styledInput(String hint, boolean password) {
        EditText et = new EditText(act);
        et.setHint(hint);
        et.setHintTextColor(ThemeManager.DIM);
        et.setTextColor(ThemeManager.TEXT);
        et.setTextSize(13f);
        et.setBackground(rbs(ThemeManager.SURFACE2, 10, ThemeManager.BORDER));
        et.setPadding(dp(14), dp(13), dp(14), dp(13));
        et.setLayoutParams(mpWrap());
        if (password) et.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return et;
    }

    View rowDivider() {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1));
        v.setBackgroundColor(ThemeManager.BORDER2);
        return v;
    }

    // =========================================================================
    // HEADER BUILDER
    // =========================================================================

    /**
     * Header dengan back button otomatis jika nav stack > 1.
     * Caller ambil LinearLayout actions lewat: (LinearLayout) header.getTag()
     */
    LinearLayout buildHeader2(String title, String sub, Runnable onMenuExtra) {
        LinearLayout wrap = lv();
        wrap.setLayoutParams(mpWrap());

        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(ThemeManager.SURFACE);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        if (act.mNavStack.size() > 1) {
            TextView back = iconBtn("‹", ThemeManager.CYAN, () -> act.onBackPressed());
            back.setTextSize(22f);
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(44), dp(44));
            bLp.rightMargin = dp(8);
            back.setLayoutParams(bLp);
            row.addView(back);
        }

        LinearLayout titles = lv();
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.rightMargin = dp(6);
        titles.setLayoutParams(titlesLp);
        TextView titleTv = tv(title, ThemeManager.TEXT, 15f, true);
        titleTv.setMaxLines(1);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(titleTv);
        if (sub != null && !sub.isEmpty()) {
            TextView subTv = tv(sub, ThemeManager.DIM, 10f, false);
            subTv.setTypeface(Typeface.MONOSPACE);
            subTv.setPadding(0, dp(1), 0, 0);
            subTv.setMaxLines(1);
            subTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titles.addView(subTv);
        }
        row.addView(titles);

        LinearLayout actions = lh(Gravity.CENTER_VERTICAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        // Prevent actions from squeezing title — wrap_content fixed width
        actions.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(actions);
        wrap.addView(row);

        View divider = new View(act);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(ThemeManager.BORDER);
        wrap.addView(divider);

        wrap.setTag(actions);
        return wrap;
    }

    // =========================================================================
    // EXPIRED PAGE
    // =========================================================================

    /** Full-screen page shown when app is past its expiry date. */
    View buildExpiredPage() {
        LinearLayout outer = lv();
        outer.setBackgroundColor(0xFF07070A);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(32), dp(60), dp(32), dp(60));

        // Icon circle
        LinearLayout iconWrap = new LinearLayout(act);
        iconWrap.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iwLp = new LinearLayout.LayoutParams(dp(80), dp(80));
        iwLp.bottomMargin = dp(24);
        iconWrap.setLayoutParams(iwLp);
        iconWrap.setBackground(rb(0xFF1A3A5C, 40));
        iconWrap.addView(tv("G", 0xFF3DBEFF, 32f, true));
        outer.addView(iconWrap);

        // Title
        TextView title = tv("Update Required", 0xFFE0E0F0, 22f, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlLp.bottomMargin = dp(12);
        title.setLayoutParams(tlLp);
        outer.addView(title);

        // Subtitle
        TextView sub = tv(
            "This version of GitDeploy has expired.\nA new version is available.",
            0xFF888899, 13f, false);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(dp(2), 1.3f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dp(24);
        sub.setLayoutParams(subLp);
        outer.addView(sub);

        // Countdown text
        TextView countdown = tv("Redirecting to Telegram in 3...", 0xFF3DBEFF, 12f, false);
        countdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cdLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cdLp.bottomMargin = dp(40);
        countdown.setLayoutParams(cdLp);
        outer.addView(countdown);

        // Countdown animation 3..2..1
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> countdown.setText("Redirecting to Telegram in 2..."), 1000);
        h.postDelayed(() -> countdown.setText("Redirecting to Telegram in 1..."), 2000);
        h.postDelayed(() -> countdown.setText("Opening Telegram..."),             3000);

        // Version
        TextView verTv = tv("GitDeploy v" + BuildConfig.VERSION_NAME, 0xFF444455, 10f, false);
        verTv.setGravity(Gravity.CENTER);
        outer.addView(verTv);

        return outer;
    }
}
