package com.gitdeploy.app;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ScrollView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * All custom-styled dialogs, in-app status bar, and feedback helpers.
 * Replaces system AlertDialog throughout the app.
 */
class DialogHelper {

    final MainActivity act;

    DialogHelper(MainActivity act) {
        this.act = act;
    }

    // =========================================================================
    // CUSTOM STYLED DIALOGS
    // =========================================================================

    /** Bottom-sheet style menu — replaces AlertDialog.setItems() */
    void showMenu(String title, String[] items, Runnable[] actions) {
        android.app.Dialog d = new android.app.Dialog(act, android.R.style.Theme_Translucent_NoTitleBar);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            d.getWindow().setDimAmount(0.55f);
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            d.getWindow().setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                                     android.view.WindowManager.LayoutParams.MATCH_PARENT);
            d.getWindow().setGravity(android.view.Gravity.BOTTOM);
        }
        LinearLayout outer = act.mUi.lv();
        outer.setGravity(Gravity.BOTTOM);
        outer.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        outer.setClickable(true);
        outer.setOnClickListener(v -> d.dismiss());

        LinearLayout sheet = act.mUi.lv();
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(ThemeManager.SURFACE);
        sheetBg.setCornerRadii(new float[]{act.mUi.dp(20), act.mUi.dp(20), act.mUi.dp(20), act.mUi.dp(20), 0, 0, 0, 0});
        sheet.setBackground(sheetBg);
        sheet.setPadding(0, act.mUi.dp(6), 0, act.mUi.dp(28));
        sheet.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sheet.setClickable(true);

        View handle = new View(act);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(act.mUi.dp(36), act.mUi.dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = act.mUi.dp(6); hlp.bottomMargin = act.mUi.dp(12);
        handle.setLayoutParams(hlp);
        handle.setBackground(act.mUi.rb(ThemeManager.BORDER2, 4));
        sheet.addView(handle);

        if (title != null && !title.isEmpty()) {
            TextView titleTv = act.mUi.tv(title, ThemeManager.DIM, 10f, true);
            titleTv.setLetterSpacing(0.10f);
            titleTv.setPadding(act.mUi.dp(18), act.mUi.dp(2), act.mUi.dp(18), act.mUi.dp(10));
            sheet.addView(titleTv);
            View div = new View(act);
            div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(ThemeManager.BORDER);
            sheet.addView(div);
        }

        ScrollView itemSv = new ScrollView(act);
        final int maxH = (int)(act.getResources().getDisplayMetrics().heightPixels * 0.60f);
        itemSv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        itemSv.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        LinearLayout itemList = act.mUi.lv();
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            String item = items[i];
            boolean isDanger = item.toLowerCase().contains("delete") || item.toLowerCase().contains("remove");
            TextView row = act.mUi.tv(item, isDanger ? ThemeManager.DANGER : ThemeManager.TEXT, 14f, false);
            row.setPadding(act.mUi.dp(18), act.mUi.dp(15), act.mUi.dp(18), act.mUi.dp(15));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                d.dismiss();
                if (actions != null && idx < actions.length && actions[idx] != null) actions[idx].run();
            });
            act.mUi.applyRipple(row);
            itemList.addView(row);
            if (i < items.length - 1) {
                View sep = new View(act);
                LinearLayout.LayoutParams seplp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
                seplp.leftMargin = act.mUi.dp(18);
                sep.setLayoutParams(seplp);
                sep.setBackgroundColor(ThemeManager.BORDER);
                itemList.addView(sep);
            }
        }
        itemSv.addView(itemList);
        sheet.addView(itemSv);
        sheet.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                sheet.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (itemSv.getHeight() > maxH) {
                    LinearLayout.LayoutParams svLp = (LinearLayout.LayoutParams) itemSv.getLayoutParams();
                    svLp.height = maxH;
                    itemSv.setLayoutParams(svLp);
                }
            }
        });
        outer.addView(sheet);
        d.setContentView(outer);
        d.setCancelable(true);
        d.show();
    }

    /** Styled confirm dialog with positive + cancel buttons */
    void showConfirm(String title, String msg, String posLabel, int posColor, Runnable onYes) {
        android.app.Dialog d = new android.app.Dialog(act);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            d.getWindow().setDimAmount(0.5f);
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        LinearLayout root = act.mUi.lv();
        root.setBackground(act.mUi.rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        root.setPadding(act.mUi.dp(20), act.mUi.dp(20), act.mUi.dp(20), act.mUi.dp(14));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            (int)(act.getResources().getDisplayMetrics().widthPixels * 0.88f),
            LinearLayout.LayoutParams.WRAP_CONTENT));
        if (title != null && !title.isEmpty()) {
            TextView t = act.mUi.tv(title, ThemeManager.TEXT, 15f, true);
            t.setPadding(0, 0, 0, (msg != null && !msg.isEmpty()) ? act.mUi.dp(10) : act.mUi.dp(14));
            root.addView(t);
        }
        if (msg != null && !msg.isEmpty()) {
            TextView m = act.mUi.tv(msg, ThemeManager.TEXT2, 13f, false);
            m.setLineSpacing(act.mUi.dp(2), 1.25f);
            m.setPadding(0, 0, 0, act.mUi.dp(16));
            root.addView(m);
        }
        LinearLayout btns = act.mUi.lh(Gravity.END);
        TextView cancelBtn = act.mUi.tv("Cancel", ThemeManager.TEXT2, 13f, false);
        cancelBtn.setPadding(act.mUi.dp(14), act.mUi.dp(10), act.mUi.dp(14), act.mUi.dp(10));
        cancelBtn.setClickable(true);
        cancelBtn.setOnClickListener(v -> d.dismiss());
        act.mUi.applyRipple(cancelBtn, 8);
        btns.addView(cancelBtn);
        int bgColor = (posColor == ThemeManager.DANGER) ? ThemeManager.DANGER_D : ThemeManager.BRAND_D;
        TextView posBtn = act.mUi.tv(posLabel, posColor, 13f, true);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pblp.leftMargin = act.mUi.dp(4);
        posBtn.setLayoutParams(pblp);
        posBtn.setPadding(act.mUi.dp(14), act.mUi.dp(10), act.mUi.dp(14), act.mUi.dp(10));
        posBtn.setBackground(act.mUi.rbs(bgColor, 8, posColor));
        posBtn.setClickable(true);
        posBtn.setOnClickListener(v -> { d.dismiss(); if (onYes != null) onYes.run(); });
        act.mUi.applyRipple(posBtn, 8);
        btns.addView(posBtn);
        root.addView(btns);
        d.setContentView(root);
        d.setCancelable(true);
        d.show();
    }

    /** Confirm with danger (red) positive button — shortcut */
    void showConfirm(String title, String msg, String posLabel, Runnable onYes) {
        showConfirm(title, msg, posLabel, ThemeManager.DANGER, onYes);
    }

    /** Alert dialog — single OK button */
    void showAlert(String title, String msg) {
        android.app.Dialog d = new android.app.Dialog(act);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            d.getWindow().setDimAmount(0.5f);
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        LinearLayout root = act.mUi.lv();
        root.setBackground(act.mUi.rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        root.setPadding(act.mUi.dp(20), act.mUi.dp(20), act.mUi.dp(20), act.mUi.dp(14));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            (int)(act.getResources().getDisplayMetrics().widthPixels * 0.88f),
            LinearLayout.LayoutParams.WRAP_CONTENT));
        if (title != null && !title.isEmpty()) {
            TextView t = act.mUi.tv(title, ThemeManager.TEXT, 15f, true);
            t.setPadding(0, 0, 0, (msg != null && !msg.isEmpty()) ? act.mUi.dp(10) : act.mUi.dp(14));
            root.addView(t);
        }
        if (msg != null && !msg.isEmpty()) {
            TextView m = act.mUi.tv(msg, ThemeManager.TEXT2, 13f, false);
            m.setLineSpacing(act.mUi.dp(2), 1.25f);
            m.setPadding(0, 0, 0, act.mUi.dp(16));
            root.addView(m);
        }
        LinearLayout btns = act.mUi.lh(Gravity.END);
        TextView okBtn = act.mUi.tv("OK", ThemeManager.BRAND, 13f, true);
        okBtn.setPadding(act.mUi.dp(14), act.mUi.dp(10), act.mUi.dp(14), act.mUi.dp(10));
        okBtn.setBackground(act.mUi.rbs(ThemeManager.BRAND_D, 8, ThemeManager.BRAND));
        okBtn.setClickable(true);
        okBtn.setOnClickListener(v -> d.dismiss());
        act.mUi.applyRipple(okBtn, 8);
        btns.addView(okBtn);
        root.addView(btns);
        d.setContentView(root);
        d.setCancelable(true);
        d.show();
    }

    /** Styled dialog with custom View content + pos/neg buttons */
    void showViewDialog(String title, View content, String posLabel, int posColor, Runnable onYes) {
        android.app.Dialog d = new android.app.Dialog(act);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            d.getWindow().setDimAmount(0.5f);
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        LinearLayout root = act.mUi.lv();
        root.setBackground(act.mUi.rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        root.setPadding(act.mUi.dp(20), act.mUi.dp(20), act.mUi.dp(20), act.mUi.dp(14));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            (int)(act.getResources().getDisplayMetrics().widthPixels * 0.90f),
            LinearLayout.LayoutParams.WRAP_CONTENT));
        if (title != null && !title.isEmpty()) {
            TextView t = act.mUi.tv(title, ThemeManager.TEXT, 15f, true);
            t.setPadding(0, 0, 0, act.mUi.dp(14));
            root.addView(t);
        }
        if (content != null) {
            content.setPadding(0, 0, 0, act.mUi.dp(12));
            root.addView(content);
        }
        LinearLayout btns = act.mUi.lh(Gravity.END);
        TextView cancelBtn = act.mUi.tv("Cancel", ThemeManager.TEXT2, 13f, false);
        cancelBtn.setPadding(act.mUi.dp(14), act.mUi.dp(10), act.mUi.dp(14), act.mUi.dp(10));
        cancelBtn.setClickable(true);
        cancelBtn.setOnClickListener(v -> d.dismiss());
        act.mUi.applyRipple(cancelBtn, 8);
        btns.addView(cancelBtn);
        int bgColor = (posColor == ThemeManager.DANGER) ? ThemeManager.DANGER_D : ThemeManager.BRAND_D;
        TextView posBtn = act.mUi.tv(posLabel, posColor, 13f, true);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pblp.leftMargin = act.mUi.dp(4);
        posBtn.setLayoutParams(pblp);
        posBtn.setPadding(act.mUi.dp(14), act.mUi.dp(10), act.mUi.dp(14), act.mUi.dp(10));
        posBtn.setBackground(act.mUi.rbs(bgColor, 8, posColor));
        posBtn.setClickable(true);
        posBtn.setOnClickListener(v -> { d.dismiss(); if (onYes != null) onYes.run(); });
        act.mUi.applyRipple(posBtn, 8);
        btns.addView(posBtn);
        root.addView(btns);
        d.setContentView(root);
        d.setCancelable(true);
        d.show();
    }

    android.app.Dialog makeProgressDialog() {
        android.app.Dialog d = new android.app.Dialog(act);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            d.getWindow().setDimAmount(0.5f);
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.setCancelable(false);
        LinearLayout root = act.mUi.lv();
        root.setBackground(act.mUi.rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        root.setPadding(act.mUi.dp(24), act.mUi.dp(22), act.mUi.dp(24), act.mUi.dp(22));
        root.setId(android.R.id.message);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            (int)(act.getResources().getDisplayMetrics().widthPixels * 0.82f),
            LinearLayout.LayoutParams.WRAP_CONTENT);
        root.setLayoutParams(rlp);
        TextView tv = act.mUi.tv("Processing...", ThemeManager.CYAN, 12f, false);
        tv.setGravity(Gravity.CENTER);
        tv.setLineSpacing(act.mUi.dp(2), 1.2f);
        root.addView(tv);
        d.setContentView(root);
        return d;
    }

    // =========================================================================
    // IN-APP STATUS BAR
    // =========================================================================

    void showStatusBar(String msg, int color) {
        act.mHandler.post(() -> {
            if (act.mStatusBar == null || act.mStatusBarText == null) return;
            act.mStatusBarText.setText(msg);
            act.mStatusBarText.setTextColor(color);
            if (act.mStatusBar.getChildCount() > 0)
                act.mStatusBar.getChildAt(0).setBackground(act.mUi.rb(color, 4));
            act.mStatusBar.setBackgroundColor(
                color == ThemeManager.GREEN  ? 0xFF040C06 :
                color == ThemeManager.RED    ? 0xFF0C0405 :
                color == ThemeManager.CYAN   ? 0xFF02080C : 0xFF08080F);
            act.mStatusBar.setVisibility(View.VISIBLE);
        });
    }

    void updateStatusBar(String msg) {
        act.mHandler.post(() -> {
            if (act.mStatusBarText != null) act.mStatusBarText.setText(msg);
        });
    }

    void hideStatusBar(long delayMs) {
        act.mHandler.postDelayed(() -> {
            if (act.mStatusBar != null) act.mStatusBar.setVisibility(View.GONE);
        }, delayMs);
    }

    // =========================================================================
    // FEEDBACK HELPERS
    // =========================================================================

    void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * Tampilkan error dengan kategorisasi otomatis via ErrorHandler.
     * Auth error dan rate-limit → dialog. Lainnya → toast.
     */
    void showErr(String msg) {
        if (msg == null || msg.isEmpty()) return;
        ErrorHandler.Category cat = ErrorHandler.categorize(msg);
        String userMsg = ErrorHandler.toUserMessage(cat, msg);
        switch (cat) {
            case AUTH:
                showAlert("Authentication Error", userMsg); break;
            case RATE_LIMIT:
                showAlert("Rate Limit Reached", userMsg); break;
            default:
                showAlert("Error", userMsg); break;
        }
    }

    /** Tampilkan error sebagai toast saja — tidak dialog. */
    void showErrToast(String msg) {
        if (msg == null || msg.isEmpty()) return;
        String userMsg = ErrorHandler.toUserMessage(ErrorHandler.categorize(msg), msg);
        toast(userMsg);
    }

    void showInfo(String title, String m) { showAlert(title, m); }

    /**
     * Dialog error khusus upload/sync — title merah, body monospace,
     * scroll agar list file panjang tetap bisa dibaca.
     */
    void showErrRed(String title, String msg) {
        android.app.Dialog d = new android.app.Dialog(act);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0x00000000));
            d.getWindow().setDimAmount(0.5f);
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        LinearLayout root = act.mUi.lv();
        root.setBackground(act.mUi.rbs(ThemeManager.SURFACE, 16, ThemeManager.RED));
        root.setPadding(act.mUi.dp(20), act.mUi.dp(20), act.mUi.dp(20), act.mUi.dp(14));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            (int)(act.getResources().getDisplayMetrics().widthPixels * 0.88f),
            LinearLayout.LayoutParams.WRAP_CONTENT));

        // Title — merah
        if (title != null && !title.isEmpty()) {
            TextView t = act.mUi.tv(title, ThemeManager.RED, 15f, true);
            t.setPadding(0, 0, 0, act.mUi.dp(10));
            root.addView(t);
        }

        // Body — scrollable supaya list file panjang tetap terbaca
        if (msg != null && !msg.isEmpty()) {
    android.widget.ScrollView sv = new android.widget.ScrollView(act);
    
    // SATU LayoutParams dengan height FIXED 260dp
    LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 
        act.mUi.dp(260)  // ← langsung set height 260dp, bukan WRAP_CONTENT
    );
    svLp.bottomMargin = act.mUi.dp(14);
    sv.setLayoutParams(svLp);

    TextView m = act.mUi.tv(msg, 0xFFDD8888, 12f, false);
    m.setLineSpacing(act.mUi.dp(2), 1.3f);
    sv.addView(m);
    root.addView(sv);
}

        // OK button
        LinearLayout btns = act.mUi.lh(Gravity.END);
        TextView okBtn = act.mUi.tv("OK", ThemeManager.RED, 13f, true);
        okBtn.setPadding(act.mUi.dp(14), act.mUi.dp(10), act.mUi.dp(14), act.mUi.dp(10));
        okBtn.setBackground(act.mUi.rbs(ThemeManager.RED_D, 8, ThemeManager.RED));
        okBtn.setClickable(true);
        okBtn.setOnClickListener(v -> d.dismiss());
        act.mUi.applyRipple(okBtn, 8);
        btns.addView(okBtn);
        root.addView(btns);

        d.setContentView(root);
        d.setCancelable(true);
        if (!act.isFinishing()) d.show();
    }

    View loadingRow() {
    TextView t = act.mUi.tv("Loading...", ThemeManager.DIM, 11f, false);
    t.setGravity(Gravity.CENTER);
    t.setPadding(0, act.mUi.dp(28), 0, act.mUi.dp(28));
    t.setLayoutParams(act.mUi.mpWrap());
    
    // Animasi pulsing (alpha fade in/out)
    android.animation.ObjectAnimator pulseAnim = android.animation.ObjectAnimator.ofFloat(t, "alpha", 0.3f, 1.0f);
    pulseAnim.setDuration(800);
    pulseAnim.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
    pulseAnim.setRepeatMode(android.animation.ObjectAnimator.REVERSE);
    pulseAnim.start();

    // FIX: hentikan animasi saat view di-remove dari window → cegah memory leak
    t.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {}
        @Override public void onViewDetachedFromWindow(View v) {
            pulseAnim.cancel();
        }
    });

    return t;
}

    View errCard(String msg) {
        LinearLayout c = act.mUi.lv();
        c.setLayoutParams(act.mUi.mpWrap());
        c.setBackground(act.mUi.rbs(ThemeManager.RED_D, 12, ThemeManager.RED));
        c.setPadding(act.mUi.dp(14), act.mUi.dp(12), act.mUi.dp(14), act.mUi.dp(12));
        c.addView(act.mUi.tv("Error", ThemeManager.RED, 12f, true));
        c.addView(act.mUi.tv(msg, 0xFFDD8888, 10f, false));
        return c;
    }
}
