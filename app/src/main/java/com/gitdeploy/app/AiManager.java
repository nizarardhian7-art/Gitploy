package com.gitdeploy.app;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * AI Fix + AI Chat Tab + Branch Switcher.
 * API key is user-supplied, stored in SharedPreferences (persists until uninstall).
 */
class AiManager {

    private final MainActivity act;
    io.noties.markwon.Markwon mMarkwon;

    AiManager(MainActivity act) {
        this.act = act;
    }

    // ── Key/model helpers ─────────────────────────────────────────────────────

    private String getSavedKey() {
        return act.mPrefs.getActiveAiKey();
    }

    private String getSavedModel() {
        String m = act.mPrefs.getAiKey("groq_model");
        return m.isEmpty() ? "llama-3.3-70b-versatile" : m;
    }

    private void saveKey(String key, String model) {
        act.mPrefs.saveAiKey("groq", key);
        act.mPrefs.saveAiKey("groq_model",
            model.trim().isEmpty() ? "llama-3.3-70b-versatile" : model.trim());
    }

    private String maskedKey(String key) {
        if (key.length() <= 8) return "••••••••";
        return key.substring(0, 8) + "••••" + key.substring(key.length() - 4);
    }

    private CharSequence renderMarkdown(String text) {
        if (mMarkwon == null)
            mMarkwon = io.noties.markwon.Markwon.builder(act)
                .usePlugin(io.noties.markwon.core.CorePlugin.create()).build();
        return mMarkwon.toMarkdown(text);
    }

    // =========================================================================
    // KEY SETUP — shared between chat + fix
    // =========================================================================

    /**
     * Full-screen setup shown when no key is saved.
     * No instructions, no links — just ask for the key and model.
     */
    private View buildKeySetupView(final Runnable onDone) {
        // FIX keyboard glitch: wrap semua konten dalam ScrollView
        // supaya keyboard muncul tidak geser/overlap layout
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.setBackgroundColor(ThemeManager.BG);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        sv.setFillViewport(true);

        LinearLayout outer = act.mUi.lv();
        outer.setBackgroundColor(ThemeManager.BG);
        outer.setGravity(Gravity.CENTER);
        outer.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        outer.setPadding(act.mUi.dp(28), act.mUi.dp(32), act.mUi.dp(28), act.mUi.dp(32));

        // Icon
        TextView iconTv = act.mUi.tv("🤖", ThemeManager.BRAND, 36f, false);
        iconTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iLp.bottomMargin = act.mUi.dp(16); iconTv.setLayoutParams(iLp);
        outer.addView(iconTv);

        // Title
        TextView titleTv = act.mUi.tv("AI Assistant", ThemeManager.TEXT, 18f, true);
        titleTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.bottomMargin = act.mUi.dp(6); titleTv.setLayoutParams(tLp);
        outer.addView(titleTv);

        // Subtitle
        TextView subTv = act.mUi.tv("Enter your API key to get started",
            ThemeManager.DIM, 12f, false);
        subTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.bottomMargin = act.mUi.dp(28); subTv.setLayoutParams(sLp);
        outer.addView(subTv);

        // Card
        LinearLayout card = act.mUi.lv();
        card.setBackground(act.mUi.rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        card.setPadding(act.mUi.dp(18), act.mUi.dp(18), act.mUi.dp(18), act.mUi.dp(18));
        card.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // API Key label + input
        TextView keyLabel = act.mUi.tv("API Key", ThemeManager.DIM, 10.5f, false);
        LinearLayout.LayoutParams klLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        klLp.bottomMargin = act.mUi.dp(6); keyLabel.setLayoutParams(klLp);
        card.addView(keyLabel);

        final EditText keyEt = act.mUi.styledInput("Paste your API key here...", true);
        LinearLayout.LayoutParams keLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        keLp.bottomMargin = act.mUi.dp(20); keyEt.setLayoutParams(keLp);
        card.addView(keyEt);

        // Save button
        final TextView saveBtn = act.mUi.primaryBtn("Save & Start", ThemeManager.BRAND);
        saveBtn.setTextColor(ThemeManager.BG);
        saveBtn.setOnClickListener(v -> {
            String key = keyEt.getText().toString().trim();
            if (key.isEmpty()) { act.mDlg.toast("Paste your API key first"); return; }
            saveKey(key, getSavedModel()); // model tetap tersimpan dari nilai sebelumnya
            act.mDlg.toast("✓ Key saved");
            if (onDone != null) {
                onDone.run();
            } else {
                act.navigateTo(() -> act.setContentView(act.buildRepoPageAtTab(4)));
            }
        });
        card.addView(saveBtn);
        outer.addView(card);
        sv.addView(outer);
        return sv;
    }

    /** Dialog to switch/update key & model — shown from "Switch Key" button. */
    private void showSwitchKeyDialog() {
        LinearLayout layout = act.mUi.lv();
        layout.setPadding(act.mUi.dp(18), act.mUi.dp(14), act.mUi.dp(18), act.mUi.dp(8));

        // Current key info
        String curKey = getSavedKey();
        if (!curKey.isEmpty()) {
            TextView curTv = act.mUi.tv(
                "Current: " + maskedKey(curKey),
                ThemeManager.DIM, 10f, false);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cLp.bottomMargin = act.mUi.dp(14); curTv.setLayoutParams(cLp);
            layout.addView(curTv);
        }

        TextView keyLabel = act.mUi.tv("New API Key", ThemeManager.DIM, 10.5f, false);
        LinearLayout.LayoutParams klLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        klLp.bottomMargin = act.mUi.dp(6); keyLabel.setLayoutParams(klLp);
        layout.addView(keyLabel);

        final EditText keyEt = act.mUi.styledInput("Paste new API key...", true);
        layout.addView(keyEt);

        act.mDlg.showViewDialog("Switch API Key", layout, "Save",
            ThemeManager.BRAND, () -> {
                String key = keyEt.getText().toString().trim();
                if (key.isEmpty()) { act.mDlg.toast("Enter a new API key"); return; }
                saveKey(key, getSavedModel()); // pertahankan model yang sudah ada
                act.mDlg.toast("✓ Key updated");
                act.navigateTo(() -> act.setContentView(act.buildRepoPageAtTab(4)));
            });
    }

    /** Simple dialog for AI Fix — no instructions, just input. */
    void showKeyRequiredDialog(Runnable onSaved) {
        LinearLayout layout = act.mUi.lv();
        layout.setPadding(act.mUi.dp(18), act.mUi.dp(14), act.mUi.dp(18), act.mUi.dp(8));

        TextView info = act.mUi.tv("An API key is required to use AI features.",
            ThemeManager.DIM, 11f, false);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iLp.bottomMargin = act.mUi.dp(14); info.setLayoutParams(iLp);
        layout.addView(info);

        TextView keyLabel = act.mUi.tv("API Key", ThemeManager.DIM, 10.5f, false);
        LinearLayout.LayoutParams klLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        klLp.bottomMargin = act.mUi.dp(6); keyLabel.setLayoutParams(klLp);
        layout.addView(keyLabel);

        final EditText keyEt = act.mUi.styledInput("Paste your API key here...", true);
        layout.addView(keyEt);

        act.mDlg.showViewDialog("🤖  AI API Key Required", layout, "Save & Retry",
            ThemeManager.BRAND, () -> {
                String key = keyEt.getText().toString().trim();
                if (key.isEmpty()) { act.mDlg.toast("Enter your API key"); return; }
                saveKey(key, getSavedModel()); // model tetap dari nilai sebelumnya
                act.mDlg.toast("✓ Key saved");
                onSaved.run();
            });
    }

    // Keep old name for backward compat
    void showGroqKeyDialog(Runnable onSaved) { showKeyRequiredDialog(onSaved); }

    // =========================================================================
    // AI FIX
    // =========================================================================

    void startAiFix(final GHApi.ErrorFileRef ref, final TextView aiBtn) {
        if (getSavedKey().isEmpty()) {
            showKeyRequiredDialog(() -> startAiFix(ref, aiBtn));
            return;
        }
        if (aiBtn.getParent() != null) { aiBtn.setEnabled(false); aiBtn.setText("..."); }

        String errLower = ref.errorMsg.toLowerCase();
        boolean isStructural = errLower.contains("end of file") || errLower.contains("reached end")
            || errLower.contains("unclosed") || errLower.contains("class, interface")
            || errLower.contains("illegal start");
        boolean isLikelyLarge = ref.filename.equals("MainActivity.java")
            || ref.filename.equals("GHApi.java");
        if (isStructural && isLikelyLarge) {
            act.mDlg.showConfirm("⚠ Complex Error Detected",
                "This is a structural error (missing brace/bracket) in a large file.\n\n"
                + "AI Fix will attempt a best-effort repair, but accuracy may be limited.\n\n"
                + "Tip: Manual inspection is recommended for large file structural errors.",
                "Try AI Fix Anyway", ThemeManager.WARNING,
                () -> doStartAiFix(ref, aiBtn));
            return;
        }
        doStartAiFix(ref, aiBtn);
    }

    private void doStartAiFix(final GHApi.ErrorFileRef ref, final TextView aiBtn) {
        android.app.Dialog prog = act.mDlg.makeProgressDialog();
        final TextView progTv = (TextView)
            ((LinearLayout) prog.findViewById(android.R.id.message)).getChildAt(0);
        progTv.setText("Reading " + ref.filename + "...");
        prog.show();

        GHApi.getFileContent(act.mPrefs.getToken(), act.mCurrentOwner, act.mCurrentRepo,
            ref.repoPath, (result, err) -> act.mHandler.post(() -> {
                if (err != null) {
                    prog.dismiss();
                    if (aiBtn.getParent() != null) { aiBtn.setEnabled(true); aiBtn.setText("🤖"); }
                    act.mDlg.showErr("Failed to read file:\n" + err);
                    return;
                }
                final String originalContent = result[0];
                final String sha             = result[1];

                GHApi.ErrorType errType = GHApi.classifyError(ref.errorMsg);
                if (errType == GHApi.ErrorType.CROSS_FILE) {
                    String symbolName = GHApi.extractSymbolName(ref.errorMsg);
                    GHApi.ErrorFileRef otherRef = null;
                    if (act.mTrackedErrors != null) {
                        for (GHApi.ErrorFileRef r : act.mTrackedErrors) {
                            if (!r.filename.equals(ref.filename) && r.repoPath != null) {
                                otherRef = r; break;
                            }
                        }
                    }
                    if (otherRef != null && !symbolName.isEmpty()) {
                        final GHApi.ErrorFileRef fOther = otherRef;
                        final String fSymbol = symbolName;
                        progTv.setText("Reading context from " + fOther.filename + "...");
                        GHApi.getFileContent(act.mPrefs.getToken(), act.mCurrentOwner,
                            act.mCurrentRepo, fOther.repoPath,
                            (ctx, ctxErr) -> act.mHandler.post(() -> {
                                String crossCtx = "";
                                if (ctxErr == null && ctx != null && ctx[0] != null) {
                                    String decl = GHApi.extractSymbolDeclaration(ctx[0], fSymbol);
                                    if (!decl.isEmpty())
                                        crossCtx = "// from " + fOther.filename + ":\n" + decl;
                                }
                                runAiFixCall(ref, originalContent, sha, crossCtx, prog, progTv, aiBtn);
                            }));
                        return;
                    }
                }
                runAiFixCall(ref, originalContent, sha, null, prog, progTv, aiBtn);
            }));
    }

    private void runAiFixCall(final GHApi.ErrorFileRef ref,
                               final String originalContent,
                               final String sha,
                               final String crossFileContext,
                               final android.app.Dialog prog,
                               final TextView progTv,
                               final TextView aiBtn) {
        progTv.setText("🤖 AI is analyzing the error...");
        String aiKey = getSavedKey();
        if (aiKey.isEmpty()) {
            prog.dismiss();
            if (aiBtn.getParent() != null) { aiBtn.setEnabled(true); aiBtn.setText("🤖"); }
            showKeyRequiredDialog(() -> startAiFix(ref, aiBtn));
            return;
        }
        GHApi.callGroqFix(aiKey,
            originalContent, ref.filename, ref.lineNum, ref.errorMsg, crossFileContext,
            (fixedContent, fixErr) -> act.mHandler.post(() -> {
                prog.dismiss();
                if (aiBtn.getParent() != null) { aiBtn.setEnabled(true); aiBtn.setText("🤖"); }
                if (fixErr != null) {
                    if (fixErr.contains("401") || fixErr.contains("invalid_api_key")
                            || fixErr.contains("Unauthorized")) {
                        act.mDlg.showConfirm("Invalid API Key",
                            "Your API key was rejected. Would you like to update it?",
                            "Update Key", ThemeManager.BRAND,
                            () -> showKeyRequiredDialog(() -> startAiFix(ref, aiBtn)));
                    } else if (fixErr.contains("too complex") || fixErr.contains("Ask tab")
                            || fixErr.contains("reformatted") || fixErr.contains("full file")) {
                        act.mChatContext = "I have a build error in " + ref.filename
                            + " at line " + ref.lineNum + ":\n\n```\n" + ref.errorMsg + "\n```"
                            + "\n\nAuto-Fix could not handle this. How do I fix it manually?";
                        act.mDlg.showConfirm("🤖 Auto-Fix Limit", fixErr,
                            "Open Ask Tab", ThemeManager.BRAND,
                            () -> act.navigateTo(() -> act.setContentView(act.buildRepoPageAtTab(4))));
                    } else if (fixErr.contains("too large") || fixErr.contains("Request too large")
                            || fixErr.contains("tokens") || fixErr.contains("TPM")) {
                        act.mDlg.showConfirm("Request Too Large",
                            "This method is too large for the selected model.\n\n"
                            + "Use the Ask tab to get manual fix guidance.",
                            "Open Ask Tab", ThemeManager.BRAND,
                            () -> {
                                act.mChatContext = "I have a build error in " + ref.filename
                                    + " at line " + ref.lineNum + ":\n" + ref.errorMsg
                                    + "\n\nHow do I fix this manually?";
                                act.navigateTo(() -> act.setContentView(act.buildRepoPageAtTab(4)));
                            });
                    } else {
                        act.mDlg.showErr("AI Fix failed:\n\n" + fixErr);
                    }
                    return;
                }
                if (fixedContent == null || fixedContent.trim().isEmpty()) {
                    act.mDlg.showErr("AI returned empty content. Please fix manually.");
                    return;
                }
                showAiDiffDialog(ref, originalContent, fixedContent, sha);
            }));
    }

    // =========================================================================
    // AI DIFF DIALOG
    // =========================================================================

    private void showAiDiffDialog(final GHApi.ErrorFileRef ref,
                                   final String original,
                                   final String fixed,
                                   final String sha) {
        android.app.Dialog dialog = new android.app.Dialog(act);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setDimAmount(0.5f);
            dialog.getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setCancelable(true);

        LinearLayout root = act.mUi.lv();
        root.setBackground(act.mUi.rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            (int)(act.getResources().getDisplayMetrics().widthPixels * 0.95f),
            LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout headerRow = act.mUi.lh(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(act.mUi.dp(16), act.mUi.dp(14), act.mUi.dp(14), act.mUi.dp(10));
        headerRow.setLayoutParams(act.mUi.mpWrap());
        TextView titleTv = act.mUi.tv("🤖  AI Fix — " + ref.filename, ThemeManager.TEXT, 14f, true);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(titleTv);
        headerRow.addView(act.mUi.badgeChip(
            "line " + ref.lineNum, ThemeManager.DIM, ThemeManager.SURFACE2));
        root.addView(headerRow);
        root.addView(act.mUi.rowDivider());

        LinearLayout errBar = act.mUi.lh(Gravity.CENTER_VERTICAL);
        errBar.setBackgroundColor(0xFF0F0405);
        errBar.setPadding(act.mUi.dp(12), act.mUi.dp(7), act.mUi.dp(12), act.mUi.dp(7));
        errBar.setLayoutParams(act.mUi.mpWrap());
        TextView errTv = act.mUi.tv("⚠  " + ref.errorMsg, ThemeManager.RED, 9.5f, false);
        errTv.setMaxLines(2);
        errTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        errTv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        errBar.addView(errTv);
        root.addView(errBar);

        String[] origLines  = original.split("\n", -1);
        String[] fixedLines = fixed.split("\n", -1);
        int changed = 0, firstChanged = -1;
        for (int i = 0; i < Math.min(origLines.length, fixedLines.length); i++) {
            if (!origLines[i].equals(fixedLines[i])) {
                if (firstChanged < 0) firstChanged = i;
                changed++;
            }
        }
        if (firstChanged < 0) firstChanged = 0;
        int added   = Math.max(0, fixedLines.length - origLines.length);
        int removed = Math.max(0, origLines.length - fixedLines.length);

        LinearLayout diffSummary = act.mUi.lh(Gravity.CENTER_VERTICAL);
        diffSummary.setPadding(act.mUi.dp(14), act.mUi.dp(8), act.mUi.dp(14), act.mUi.dp(8));
        diffSummary.setBackgroundColor(0xFF080810);
        diffSummary.setLayoutParams(act.mUi.mpWrap());
        diffSummary.addView(act.mUi.tv(
            "AI modified " + changed + " line(s)", ThemeManager.DIM, 10f, false));
        if (added > 0)
            diffSummary.addView(act.mUi.tv("  +" + added, ThemeManager.GREEN, 10f, true));
        if (removed > 0)
            diffSummary.addView(act.mUi.tv("  -" + removed, ThemeManager.RED, 10f, true));
        root.addView(diffSummary);

        final int HARD_CAP = 40;
        final int wStart = Math.max(0, firstChanged - HARD_CAP);
        final int wEndO  = Math.min(origLines.length,  firstChanged + HARD_CAP);
        final int wEndF  = Math.min(fixedLines.length, firstChanged + HARD_CAP);
        final String[] origWindow  =
            java.util.Arrays.copyOfRange(origLines,  wStart, wEndO);
        final String[] fixedWindow =
            java.util.Arrays.copyOfRange(fixedLines, wStart, wEndF);
        final int fWStart = wStart;

        final int[] previewTab = {0};
        LinearLayout tabBar = act.mUi.lh(Gravity.CENTER_VERTICAL);
        tabBar.setBackgroundColor(ThemeManager.SURFACE2);
        tabBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, act.mUi.dp(40)));
        final TextView tabFixed = act.mUi.tv("✓ FIXED", ThemeManager.GREEN, 10f, true);
        tabFixed.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        tabFixed.setGravity(Gravity.CENTER);
        final TextView tabOrig = act.mUi.tv("ORIGINAL", ThemeManager.DIM, 10f, true);
        tabOrig.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        tabOrig.setGravity(Gravity.CENTER);
        tabBar.addView(tabFixed); tabBar.addView(tabOrig);
        root.addView(tabBar);

        ScrollView codeSv = new ScrollView(act);
        codeSv.setBackgroundColor(0xFF050507);
        int previewH =
            (int)(act.getResources().getDisplayMetrics().heightPixels * 0.38f);
        codeSv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, previewH));
        final TextView codeTv = new TextView(act);
        codeTv.setTypeface(Typeface.MONOSPACE);
        codeTv.setTextSize(10f);
        codeTv.setPadding(
            act.mUi.dp(12), act.mUi.dp(12), act.mUi.dp(12), act.mUi.dp(12));
        codeTv.setTextIsSelectable(true);
        new Thread(() -> {
            CharSequence cs = buildDiffHighlight(origWindow, fixedWindow, true, fWStart);
            act.mHandler.post(() -> codeTv.setText(cs));
        }).start();
        codeSv.addView(codeTv);
        root.addView(codeSv);

        tabFixed.setOnClickListener(v -> {
            if (previewTab[0] == 0) return;
            previewTab[0] = 0;
            tabFixed.setTextColor(ThemeManager.GREEN);
            tabOrig.setTextColor(ThemeManager.DIM);
            new Thread(() -> {
                CharSequence cs = buildDiffHighlight(origWindow, fixedWindow, true, fWStart);
                act.mHandler.post(() -> { codeTv.setText(cs); codeSv.scrollTo(0, 0); });
            }).start();
        });
        tabOrig.setOnClickListener(v -> {
            if (previewTab[0] == 1) return;
            previewTab[0] = 1;
            tabFixed.setTextColor(ThemeManager.DIM);
            tabOrig.setTextColor(ThemeManager.CYAN);
            new Thread(() -> {
                CharSequence cs = buildDiffHighlight(origWindow, fixedWindow, false, fWStart);
                act.mHandler.post(() -> { codeTv.setText(cs); codeSv.scrollTo(0, 0); });
            }).start();
        });

        root.addView(act.mUi.rowDivider());

        LinearLayout btnRow = act.mUi.lh(Gravity.END);
        btnRow.setPadding(
            act.mUi.dp(14), act.mUi.dp(12), act.mUi.dp(14), act.mUi.dp(14));
        btnRow.setLayoutParams(act.mUi.mpWrap());

        TextView discardBtn = act.mUi.roundBtn(
            "✕  Discard", ThemeManager.DIM, ThemeManager.SURFACE2);
        LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, act.mUi.dp(46));
        dbLp.rightMargin = act.mUi.dp(10);
        discardBtn.setPadding(act.mUi.dp(16), 0, act.mUi.dp(16), 0);
        discardBtn.setGravity(Gravity.CENTER);
        discardBtn.setLayoutParams(dbLp);
        discardBtn.setOnClickListener(v -> dialog.dismiss());

        final TextView applyBtn = act.mUi.tv("✓  Apply Fix",
            ThemeManager.current() == ThemeManager.THEME_LIGHT ? 0xFFFFFFFF : 0xFF000000,
            12f, true);
        applyBtn.setBackground(act.mUi.rb(ThemeManager.GREEN, 10));
        applyBtn.setPadding(act.mUi.dp(20), 0, act.mUi.dp(20), 0);
        applyBtn.setGravity(Gravity.CENTER);
        applyBtn.setLetterSpacing(0.04f);
        applyBtn.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, act.mUi.dp(46)));
        applyBtn.setOnClickListener(v -> {
            applyBtn.setEnabled(false);
            applyBtn.setText("Saving...");
            String commitMsg = "🤖 AI fix: "
                + ref.errorMsg.substring(0, Math.min(50, ref.errorMsg.length()));
            byte[] bytes = fixed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            GHApi.getFileContent(act.mPrefs.getToken(), act.mCurrentOwner,
                act.mCurrentRepo, ref.repoPath, (latest, shaErr) -> {
                    final String latestSha =
                        (shaErr == null && latest != null) ? latest[1] : sha;
                    GHApi.uploadFile(act.mPrefs.getToken(), act.mCurrentOwner,
                        act.mCurrentRepo, ref.repoPath, bytes, commitMsg,
                        latestSha, act.mCurrentBranch,
                        (ok, err) -> act.mHandler.post(() -> {
                            dialog.dismiss();
                            if (!ok) { act.mDlg.showErr("Save failed:\n" + err); return; }
                            act.mCachedContent.remove(act.cacheKey(ref.repoPath));
                            act.mCachedFiles.evictAll();
                            if (act.mTrackedErrors != null) act.mTrackedErrors.remove(ref);
                            int left = act.mTrackedErrors == null
                                ? 0 : act.mTrackedErrors.size();
                            act.mDlg.toast("🤖 AI fix applied — " + (left > 0
                                ? left + " error(s) remaining" : "all errors fixed!"));
                            if (left == 0) {
                                while (act.mNavStack.size() > 1) act.mNavStack.pop();
                                act.navigateTo(() ->
                                    act.setContentView(act.buildRepoPageAtTab(1)));
                                act.mHandler.postDelayed(() -> act.offerBuildNow(), 300);
                            } else {
                                act.navigateTo(() ->
                                    act.setContentView(act.buildRepoPageAtTab(3)));
                            }
                        }));
                });
        });

        btnRow.addView(discardBtn);
        btnRow.addView(applyBtn);
        root.addView(btnRow);
        dialog.setContentView(root);
        dialog.show();
    }

    private CharSequence buildDiffHighlight(String[] origLines, String[] fixedLines,
                                             boolean showFixed, int lineOffset) {
        android.text.SpannableStringBuilder ssb =
            new android.text.SpannableStringBuilder();
        String[] displayLines = showFixed ? fixedLines : origLines;
        for (int i = 0; i < displayLines.length; i++) {
            String line = displayLines[i];
            boolean changed = i >= origLines.length || i >= fixedLines.length
                || !origLines[i].equals(fixedLines[i]);
            String lineNum = String.format("%4d  ", lineOffset + i + 1);
            int start = ssb.length();
            ssb.append(lineNum);
            ssb.setSpan(new android.text.style.ForegroundColorSpan(ThemeManager.DIM),
                start, ssb.length(),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            int lineStart = ssb.length();
            ssb.append(line).append("\n");
            int lineEnd = ssb.length();
            if (changed) {
                int bgColor = showFixed ? 0x2200FF44 : 0x22FF2244;
                ssb.setSpan(new android.text.style.BackgroundColorSpan(bgColor),
                    lineStart, lineEnd,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                int fgColor = showFixed ? 0xFF88FFAA : 0xFFFF8899;
                ssb.setSpan(new android.text.style.ForegroundColorSpan(fgColor),
                    lineStart, lineEnd,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                ssb.setSpan(new android.text.style.ForegroundColorSpan(0xFF6B7280),
                    lineStart, lineEnd,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return ssb;
    }

    // =========================================================================
    // AI CHAT TAB
    // =========================================================================

    View buildAiChatTab() {
        // Gate: no key → show setup screen
        if (getSavedKey().isEmpty()) {
            LinearLayout wrapper = act.mUi.lv();
            wrapper.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            wrapper.setBackgroundColor(ThemeManager.BG);
            wrapper.addView(buildKeySetupView(null));
            return wrapper;
        }
        return buildAiChatUI();
    }

    private View buildAiChatUI() {
        act.getWindow().setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        // Root: fills the weight-1f slot given by buildRepoPageNew
        android.widget.FrameLayout root = new android.widget.FrameLayout(act);
        root.setBackgroundColor(ThemeManager.BG);
        root.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT));

        // Inner linear layout for header + messages + input
        LinearLayout frame = act.mUi.lv();
        frame.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        frame.setBackgroundColor(ThemeManager.BG);
        root.addView(frame);

        // ── Context banner ────────────────────────────────────────────────────
        if (!act.mCurrentRepo.isEmpty()) {
            LinearLayout ctxBanner = act.mUi.lh(Gravity.CENTER_VERTICAL);
            ctxBanner.setBackground(act.mUi.rbs(ThemeManager.BRAND_D, 0, ThemeManager.BRAND));
            ctxBanner.setPadding(act.mUi.dp(14), act.mUi.dp(7), act.mUi.dp(14), act.mUi.dp(7));
            ctxBanner.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView ctxTv = act.mUi.tv(
                act.mCurrentOwner + "/" + act.mCurrentRepo + "  ⎇  " + act.mCurrentBranch,
                ThemeManager.BRAND, 10f, false);
            ctxTv.setTypeface(android.graphics.Typeface.MONOSPACE);
            ctxTv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            ctxBanner.addView(ctxTv);
            frame.addView(ctxBanner);
        }

        // ── Key bar: masked key | [Switch Key] [Clear] ───────────────────────
        LinearLayout keyBar = act.mUi.lh(Gravity.CENTER_VERTICAL);
        keyBar.setBackgroundColor(ThemeManager.SURFACE);
        keyBar.setPadding(act.mUi.dp(12), act.mUi.dp(7), act.mUi.dp(8), act.mUi.dp(7));
        keyBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView keyPreview = act.mUi.tv("🤖  " + maskedKey(getSavedKey()),
            ThemeManager.DIM, 10.5f, false);
        keyPreview.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        keyBar.addView(keyPreview);

        TextView switchBtn = act.mUi.tv("Switch Key", ThemeManager.BRAND, 10f, true);
        switchBtn.setBackground(act.mUi.rbs(ThemeManager.BRAND_D, 8, ThemeManager.BRAND));
        switchBtn.setPadding(act.mUi.dp(10), act.mUi.dp(5), act.mUi.dp(10), act.mUi.dp(5));
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        swLp.rightMargin = act.mUi.dp(8);
        switchBtn.setLayoutParams(swLp);
        switchBtn.setClickable(true);
        switchBtn.setOnClickListener(v -> showSwitchKeyDialog());
        act.mUi.applyRipple(switchBtn, 8);
        keyBar.addView(switchBtn);

        final Runnable[] doClearChat = {null};
        TextView clearBtn = act.mUi.tv("Clear", ThemeManager.DIM, 10f, false);
        clearBtn.setPadding(act.mUi.dp(6), act.mUi.dp(5), act.mUi.dp(4), act.mUi.dp(5));
        clearBtn.setOnClickListener(v2 -> act.mDlg.showConfirm(
            "Clear Chat?", "All chat history will be cleared.", "Clear",
            () -> { if (doClearChat[0] != null) doClearChat[0].run(); }));
        keyBar.addView(clearBtn);
        frame.addView(keyBar);

        // Divider
        View kDivider = new View(act);
        kDivider.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1));
        kDivider.setBackgroundColor(ThemeManager.BORDER);
        frame.addView(kDivider);

        // ── Message area (ScrollView fills remaining space) ───────────────────
        // Use weight trick inside a weightSum layout
        LinearLayout msgArea = act.mUi.lv();
        msgArea.setWeightSum(1f);
        msgArea.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        frame.setWeightSum(1f); // Give msgArea the remaining space

        final ScrollView msgSv = new ScrollView(act);
        msgSv.setBackgroundColor(ThemeManager.BG);
        msgSv.setFillViewport(true);
        msgSv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        final LinearLayout msgList = act.mUi.lv();
        msgList.setPadding(act.mUi.dp(14), act.mUi.dp(12), act.mUi.dp(14), act.mUi.dp(16));
        msgSv.addView(msgList);
        msgArea.addView(msgSv);
        frame.addView(msgArea);

        // ── Input bar ─────────────────────────────────────────────────────────
        LinearLayout inputBar = act.mUi.lh(Gravity.BOTTOM);
        inputBar.setBackgroundColor(ThemeManager.SURFACE);
        inputBar.setPadding(act.mUi.dp(10), act.mUi.dp(8), act.mUi.dp(10), act.mUi.dp(8));
        inputBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView attachBtn = new TextView(act);
        attachBtn.setText("📎"); attachBtn.setTextSize(20f);
        attachBtn.setPadding(act.mUi.dp(4), act.mUi.dp(6), act.mUi.dp(8), act.mUi.dp(6));
        attachBtn.setGravity(Gravity.CENTER);

        final EditText inputEt = new EditText(act);
        inputEt.setHint("Message...");
        inputEt.setHintTextColor(ThemeManager.BORDER2 != 0 ? ThemeManager.BORDER2 : 0xFF505060);
        inputEt.setTextColor(ThemeManager.TEXT);
        inputEt.setTextSize(13f);
        inputEt.setBackground(act.mUi.rbs(ThemeManager.SURFACE2, 22, ThemeManager.BORDER));
        inputEt.setPadding(act.mUi.dp(14), act.mUi.dp(10), act.mUi.dp(14), act.mUi.dp(10));
        inputEt.setMaxLines(5);
        inputEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        inputEt.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Send button — circle style
        android.widget.FrameLayout sendWrap = new android.widget.FrameLayout(act);
        LinearLayout.LayoutParams sbwLp = new LinearLayout.LayoutParams(
            act.mUi.dp(40), act.mUi.dp(40));
        sbwLp.leftMargin = act.mUi.dp(8);
        sbwLp.gravity = Gravity.BOTTOM;
        sendWrap.setLayoutParams(sbwLp);
        final TextView sendBtn = new TextView(act);
        sendBtn.setText("➤");
        sendBtn.setTextSize(15f);
        sendBtn.setTextColor(ThemeManager.BG);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setBackground(act.mUi.rb(ThemeManager.BRAND, 20));
        sendBtn.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        sendWrap.addView(sendBtn);

        inputBar.addView(attachBtn);
        inputBar.addView(inputEt);
        inputBar.addView(sendWrap);
        frame.addView(inputBar);

        // ── Scroll-to-bottom FAB (floating over msgArea) ──────────────────────
        final TextView scrollFab = new TextView(act);
        scrollFab.setText("↓");
        scrollFab.setTextSize(16f);
        scrollFab.setTextColor(ThemeManager.TEXT);
        scrollFab.setGravity(Gravity.CENTER);
        scrollFab.setBackground(act.mUi.rbs(ThemeManager.SURFACE, 24, ThemeManager.BORDER));
        android.widget.FrameLayout.LayoutParams fabLp =
            new android.widget.FrameLayout.LayoutParams(
                act.mUi.dp(40), act.mUi.dp(40));
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.rightMargin = act.mUi.dp(12);
        fabLp.bottomMargin = act.mUi.dp(8);
        scrollFab.setLayoutParams(fabLp);
        scrollFab.setVisibility(View.GONE);
        scrollFab.setElevation(act.mUi.dp(4));
        root.addView(scrollFab); // float above everything

        // Scroll helper
        final boolean[] isAtBottom = {true};
        final Runnable[] scrollToBottom = {null};
        scrollToBottom[0] = () -> msgSv.postDelayed(() -> {
            // 300 ms delay: waits for soft-keyboard animation (~250 ms) to finish
            // before scrolling, so the last message is never hidden behind the keyboard.
            msgSv.fullScroll(ScrollView.FOCUS_DOWN);
            isAtBottom[0] = true;
            scrollFab.setVisibility(View.GONE);
        }, 300);

        // FAB click
        scrollFab.setOnClickListener(v -> scrollToBottom[0].run());

        // Detect scroll position to show/hide FAB
        msgSv.getViewTreeObserver().addOnScrollChangedListener(() -> {
            int maxScroll = msgSv.getChildAt(0) == null ? 0 :
                msgSv.getChildAt(0).getHeight() - msgSv.getHeight();
            boolean atBottom = msgSv.getScrollY() >= maxScroll - act.mUi.dp(40);
            isAtBottom[0] = atBottom;
            scrollFab.setVisibility(atBottom ? View.GONE : View.VISIBLE);
        });

        // ── Quick chips ───────────────────────────────────────────────────────
        LinearLayout chipsWrap = act.mUi.lh(Gravity.CENTER_VERTICAL);
        chipsWrap.setBackgroundColor(ThemeManager.BG);
        chipsWrap.setPadding(act.mUi.dp(10), act.mUi.dp(6), act.mUi.dp(10), act.mUi.dp(4));
        chipsWrap.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final LinearLayout chipsRow = chipsWrap;
        chipsRow.setVisibility(act.mChatHistory.isEmpty() ? View.VISIBLE : View.GONE);
        String[][] quickActions = {
            {"Why did my build fail?", "🔴"},
            {"Review my build.gradle", "📋"},
            {"Fix workflow YAML", "⚙"},
            {"Add a dependency", "➕"},
        };
        for (String[] qa : quickActions) {
            TextView chip = act.mUi.tv(qa[1] + " " + qa[0], ThemeManager.TEXT2, 10.5f, false);
            chip.setBackground(act.mUi.rbs(ThemeManager.SURFACE2, 16, ThemeManager.BORDER));
            chip.setPadding(act.mUi.dp(10), act.mUi.dp(5), act.mUi.dp(10), act.mUi.dp(5));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = act.mUi.dp(6);
            chip.setLayoutParams(chipLp);
            chip.setClickable(true);
            final String question = qa[0];
            chip.setOnClickListener(v2 -> {
                inputEt.setText(question);
                inputEt.setSelection(question.length());
                chipsRow.setVisibility(View.GONE);
            });
            act.mUi.applyRipple(chip, 16);
            chipsWrap.addView(chip);
        }
        // Insert chips between msgArea and inputBar
        frame.removeView(inputBar);
        frame.addView(chipsWrap);
        frame.addView(inputBar);

        // ── Bubble builder ────────────────────────────────────────────────────
        final String[][] pendingBubble = {{null, null}};
        final Runnable[] doAddBubble = {null};
        doAddBubble[0] = () -> {
            String[] msgArr = pendingBubble[0];
            boolean isUser = "user".equals(msgArr[0]);

            LinearLayout bubble = act.mUi.lv();
            int screenW = act.getResources().getDisplayMetrics().widthPixels;
            int maxW = (int)(screenW * 0.78f);
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                maxW, LinearLayout.LayoutParams.WRAP_CONTENT);
            bLp.topMargin = act.mUi.dp(4);
            bLp.bottomMargin = act.mUi.dp(2);

            if (isUser) {
                bLp.gravity = Gravity.END;
                bLp.leftMargin = (int)(screenW * 0.22f);
                bubble.setBackground(act.mUi.rb(ThemeManager.BRAND, 18));
                bubble.setPadding(act.mUi.dp(13), act.mUi.dp(10),
                    act.mUi.dp(13), act.mUi.dp(10));
            } else {
                bLp.gravity = Gravity.START;
                bLp.rightMargin = (int)(screenW * 0.10f);
                bubble.setBackground(act.mUi.rbs(ThemeManager.SURFACE, 18, ThemeManager.BORDER));
                bubble.setPadding(act.mUi.dp(13), act.mUi.dp(10),
                    act.mUi.dp(13), act.mUi.dp(10));
            }
            bubble.setLayoutParams(bLp);

            final TextView textTv = new TextView(act);
            textTv.setTextSize(13f);
            textTv.setLineSpacing(act.mUi.dp(2), 1.4f);
            textTv.setTextIsSelectable(true);
            textTv.setTextColor(isUser ? ThemeManager.BG : ThemeManager.TEXT);
            if (isUser) {
                textTv.setText(msgArr[1]);
            } else {
                if (mMarkwon == null)
                    mMarkwon = io.noties.markwon.Markwon.builder(act)
                        .usePlugin(io.noties.markwon.core.CorePlugin.create()).build();
                mMarkwon.setMarkdown(textTv, msgArr[1]);
            }
            textTv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
            bubble.addView(textTv);

            final String msgContent = msgArr[1];
            bubble.setOnLongClickListener(vv -> {
                android.content.ClipboardManager cb =
                    (android.content.ClipboardManager)
                    act.getSystemService(act.CLIPBOARD_SERVICE);
                act.mDlg.showMenu(null,
                    new String[]{"📋  Copy"},
                    new Runnable[]{
                        () -> { cb.setPrimaryClip(android.content.ClipData
                            .newPlainText("msg", msgContent));
                            act.mDlg.toast("Copied!"); }
                    });
                return true;
            });
            msgList.addView(bubble);

            // Auto-scroll if near bottom
            if (isAtBottom[0]) scrollToBottom[0].run();
        };

        doClearChat[0] = () -> {
            act.mChatHistory.clear();
            msgList.removeAllViews();
            pendingBubble[0] = new String[]{"assistant", "Chat cleared. How can I help?"};
            doAddBubble[0].run();
        };

        // Typing indicator
        LinearLayout typingBubble = act.mUi.lv();
        typingBubble.setBackground(
            act.mUi.rbs(ThemeManager.SURFACE, 18, ThemeManager.BORDER));
        typingBubble.setPadding(
            act.mUi.dp(14), act.mUi.dp(10), act.mUi.dp(14), act.mUi.dp(10));
        LinearLayout.LayoutParams typLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        typLp.topMargin = act.mUi.dp(4);
        typLp.gravity = Gravity.START;
        typingBubble.setLayoutParams(typLp);

        // Animated dots
        final TextView dotsTv = new TextView(act);
        dotsTv.setTextSize(18f);
        dotsTv.setTextColor(ThemeManager.DIM);
        dotsTv.setText("●  ●  ●");
        typingBubble.addView(dotsTv);
        typingBubble.setVisibility(View.GONE);
        msgList.addView(typingBubble);

        // Animate typing dots
        final android.os.Handler dotHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
        final String[] dots = {"●  ·  ·", "·  ●  ·", "·  ·  ●"};
        final int[] dotIdx = {0};
        final Runnable[] dotAnim = {null};
        dotAnim[0] = () -> {
            if (typingBubble.getVisibility() == View.VISIBLE) {
                dotsTv.setText(dots[dotIdx[0] % 3]);
                dotIdx[0]++;
                dotHandler.postDelayed(dotAnim[0], 400);
            }
        };

        // Load history or welcome
        if (!act.mChatHistory.isEmpty()) {
            for (String[] m : act.mChatHistory) {
                pendingBubble[0] = m; doAddBubble[0].run();
            }
        } else {
            pendingBubble[0] = new String[]{"assistant",
                "\uD83D\uDC4B Hi! I'm your AI assistant for **"
                + act.mCurrentRepo + "**.\n\n"
                + "I can help you fix build errors, explain code, review workflows, "
                + "and answer Android dev questions.\n\n"
                + "What do you need help with?"};
            doAddBubble[0].run();
        }

        // ── Send logic ────────────────────────────────────────────────────────
        final Runnable[] sendMessage = {null};
        sendMessage[0] = () -> {
            String text = inputEt.getText().toString().trim();
            if (text.isEmpty()) return;

            String aiKey = getSavedKey();
            if (aiKey.isEmpty()) {
                showKeyRequiredDialog(() ->
                    act.navigateTo(() ->
                        act.setContentView(act.buildRepoPageAtTab(4))));
                return;
            }

            inputEt.setText("");
            sendBtn.setEnabled(false);
            sendBtn.setTextColor(ThemeManager.SURFACE2);
            sendBtn.setBackground(act.mUi.rb(ThemeManager.DIM, 20));

            String[] userMsg = {"user", text};
            act.mChatHistory.add(userMsg);
            pendingBubble[0] = userMsg;
            doAddBubble[0].run();
            chipsRow.setVisibility(View.GONE);

            typingBubble.setVisibility(View.VISIBLE);
            msgList.removeView(typingBubble);
            msgList.addView(typingBubble);
            dotHandler.post(dotAnim[0]);
            scrollToBottom[0].run();

            java.util.List<String[]> snapshot =
                new java.util.ArrayList<>(act.mChatHistory);
            final String fKey = aiKey;
            final String fModel = getSavedModel();
            final String richCtx = act.mCurrentOwner + "/" + act.mCurrentRepo
                + "|" + act.mCurrentBranch + "|";

            GHApi.callAiChat(fKey, "groq", fModel, richCtx, snapshot,
                (reply, err) -> act.mHandler.post(() -> {
                    typingBubble.setVisibility(View.GONE);
                    sendBtn.setEnabled(true);
                    sendBtn.setTextColor(ThemeManager.BG);
                    sendBtn.setBackground(act.mUi.rb(ThemeManager.BRAND, 20));

                    if (err != null) {
                        boolean isAuthErr = err.contains("401")
                            || err.contains("invalid_api_key")
                            || err.contains("Invalid API key")
                            || err.contains("Unauthorized")
                            || err.contains("authentication");
                        if (isAuthErr) {
                            pendingBubble[0] = new String[]{"assistant",
                                "\u26A0\uFE0F **Invalid API Key**\n\nYour key was rejected. "
                                + "Tap **Switch Key** above to update it."};
                        } else {
                            pendingBubble[0] = new String[]{"assistant",
                                "⚠️ Error: " + err};
                        }
                        doAddBubble[0].run();
                        return;
                    }
                    String[] aiMsg = {"assistant", reply};
                    act.mChatHistory.add(aiMsg);
                    pendingBubble[0] = aiMsg;
                    doAddBubble[0].run();
                    // Always scroll to bottom when AI replies
                    scrollToBottom[0].run();
                }));
        };

        // ── Attach file ───────────────────────────────────────────────────────
        attachBtn.setOnClickListener(v -> {
            android.app.Dialog prog = act.mDlg.makeProgressDialog();
            ((TextView)((LinearLayout) prog.findViewById(
                android.R.id.message)).getChildAt(0)).setText("Loading file tree...");
            prog.show();
            AppExecutors.getInstance().networkIO().execute(() -> {
                String tree = GHApi.getFileTreeSync(act.mPrefs.getToken(),
                    act.mCurrentOwner, act.mCurrentRepo, act.mCurrentBranch);
                act.mHandler.post(() -> {
                    prog.dismiss();
                    if (tree == null || tree.isEmpty()) {
                        act.mDlg.toast("Failed to load file tree"); return; }
                    java.util.List<String> filePaths = new java.util.ArrayList<>();
                    for (String p : tree.split("\n")) {
                        if (p.isEmpty()) continue;
                        String l = p.toLowerCase();
                        if (l.endsWith(".java") || l.endsWith(".kt")
                            || l.endsWith(".gradle") || l.endsWith(".yml")
                            || l.endsWith(".yaml") || l.endsWith(".xml")
                            || l.endsWith(".json") || l.endsWith(".md")
                            || l.endsWith(".properties"))
                            filePaths.add(p);
                    }
                    if (filePaths.isEmpty()) {
                        act.mDlg.toast("No code files found"); return; }
                    String[] arr = filePaths.toArray(new String[0]);
                    Runnable[] fileActions = new Runnable[arr.length];
                    for (int fi = 0; fi < arr.length; fi++) {
                        final int idx = fi;
                        fileActions[idx] = () -> {
                            String sp = arr[idx];
                            String fname = sp.contains("/")
                                ? sp.substring(sp.lastIndexOf('/') + 1) : sp;
                            android.app.Dialog p2 = act.mDlg.makeProgressDialog();
                            ((TextView)((LinearLayout) p2.findViewById(
                                android.R.id.message)).getChildAt(0))
                                .setText("Loading " + fname + "...");
                            p2.show();
                            GHApi.getFileContent(act.mPrefs.getToken(),
                                act.mCurrentOwner, act.mCurrentRepo, sp,
                                (result, err2) -> act.mHandler.post(() -> {
                                    p2.dismiss();
                                    if (err2 != null) {
                                        act.mDlg.toast("Failed: " + err2); return; }
                                    String[] lines = result[0].split("\n", -1);
                                    String snippet = lines.length > 200
                                        ? String.join("\n",
                                            java.util.Arrays.copyOfRange(lines, 0, 200))
                                            + "\n...(" + (lines.length-200) + " more)"
                                        : result[0];
                                    String cur = inputEt.getText().toString();
                                    inputEt.setText((cur.isEmpty()
                                        ? "Here is `" + fname + "`:\n" : cur + "\n")
                                        + "```\n" + snippet + "\n```");
                                    inputEt.setSelection(inputEt.getText().length());
                                    act.mDlg.toast(fname + " attached");
                                }));
                        };
                    }
                    act.mDlg.showMenu("Attach — " + arr.length + " files", arr, fileActions);
                });
            });
        });

        sendBtn.setOnClickListener(v -> sendMessage[0].run());
        inputEt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage[0].run(); return true;
            }
            return false;
        });

        // Auto-send pending context
        if (!act.mChatContext.isEmpty()) {
            final String ctx = act.mChatContext;
            act.mChatContext = "";
            chipsRow.setVisibility(View.GONE);
            act.mHandler.postDelayed(() -> {
                inputEt.setText(ctx);
                inputEt.setSelection(ctx.length());
                sendMessage[0].run();
            }, 300);
        }

        return root;
    }

    // =========================================================================
    // BRANCH SWITCHER
    // =========================================================================

    void showBranchSwitcher(final int currentTab) {
        GHApi.listBranches(act.mPrefs.getToken(), act.mCurrentOwner, act.mCurrentRepo,
            (branches, err) -> act.mHandler.post(() -> {
                if (err != null || branches == null || branches.isEmpty()) {
                    act.mDlg.toast("Failed to load branches: "
                        + (err != null ? err : "empty"));
                    return;
                }
                String[] names = branches.toArray(new String[0]);
                String[] display = new String[names.length];
                for (int i = 0; i < names.length; i++)
                    display[i] = names[i].equals(act.mCurrentBranch)
                        ? "✓  " + names[i] : "    " + names[i];
                Runnable[] branchActions = new Runnable[display.length];
                for (int bi = 0; bi < display.length; bi++) {
                    final int idx = bi;
                    branchActions[idx] = () -> {
                        if (names[idx].equals(act.mCurrentBranch)) {
                            act.mDlg.toast("Already on this branch."); return;
                        }
                        act.mCurrentBranch = names[idx];
                        act.mCachedFiles.evictAll();
                        act.mCachedContent.evictAll();
                        act.mCachedRuns = null;
                        act.mDlg.toast("Switched to " + act.mCurrentBranch);
                        act.navigateTo(() ->
                            act.setContentView(act.buildRepoPageAtTab(currentTab)));
                    };
                }
                act.mDlg.showMenu("Switch Branch", display, branchActions);
            }));
    }
}
