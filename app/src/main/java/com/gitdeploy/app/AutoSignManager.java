package com.gitdeploy.app;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Handles automatic keystore generation and signing setup per-repo.
 * Runs silently every time the repo page opens; skips if already configured.
 */
class AutoSignManager {

    private final MainActivity act;

    AutoSignManager(MainActivity act) {
        this.act = act;
    }

    // =========================================================================
    // AUTO-SIGN — fully automatic, zero user interaction
    // =========================================================================

    void maybeAutoSign(final LinearLayout pageRoot) {
        if (act.mPrefs.isRepoSignConfigured(act.mCurrentOwner, act.mCurrentRepo)) return;

        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // Step 1: Check if this is an Android project
                boolean isAndroid = false;
                try {
                    String resp = GHApi.checkFileExists(act.mPrefs.getToken(),
                        act.mCurrentOwner, act.mCurrentRepo, "app/build.gradle");
                    isAndroid = resp != null;
                } catch (Exception e) { android.util.Log.w("GitDeploy", "ex: " + e.getMessage()); }

                if (!isAndroid) return;

                // Step 2: Check if .signing/release.jks already in repo
                boolean ksExists = false;
                try {
                    String resp = GHApi.checkFileExists(act.mPrefs.getToken(),
                        act.mCurrentOwner, act.mCurrentRepo, ".signing/release.jks");
                    ksExists = resp != null;
                } catch (Exception e) { android.util.Log.w("GitDeploy", "ex: " + e.getMessage()); }

                if (ksExists) {
                    // Keystore already in repo — try to restore credentials from GitHub Variables.
                    // Variables are the SINGLE source of truth; never derive password from token.
                    String varAlias = "";
                    String varPass  = "";
                    try {
                        varAlias = GHApi.getVariableValue(act.mPrefs.getToken(),
                            act.mCurrentOwner, act.mCurrentRepo, "SIGNING_ALIAS");
                        varPass  = GHApi.getVariableValue(act.mPrefs.getToken(),
                            act.mCurrentOwner, act.mCurrentRepo, "SIGNING_STORE_PASS");
                        if (varAlias == null) varAlias = "";
                        if (varPass  == null) varPass  = "";
                    } catch (Exception e) { android.util.Log.w("GitDeploy", "ex: " + e.getMessage()); }

                    if (!varAlias.isEmpty() && !varPass.isEmpty()) {
                        // Validate: try to actually open the keystore with the stored password
                        boolean credentialsValid = false;
                        try {
                            // Download keystore bytes via GitHub Contents API (base64 encoded)
                            String resp = GHApi.getFileContentRaw(act.mPrefs.getToken(),
                                act.mCurrentOwner, act.mCurrentRepo, ".signing/release.jks");
                            if (resp != null) {
                                byte[] ksBytes = android.util.Base64.decode(resp, android.util.Base64.DEFAULT);
                                java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
                                ks.load(new java.io.ByteArrayInputStream(ksBytes),
                                    varPass.toCharArray());
                                credentialsValid = true;
                            }
                        } catch (Exception e) {
                            android.util.Log.w("GitDeploy", "Keystore validation failed: " + e.getMessage());
                            credentialsValid = false;
                        }

                        if (credentialsValid) {
                            // Credentials match keystore — save locally and we're done
                            act.mPrefs.saveRepoSignAlias(act.mCurrentOwner, act.mCurrentRepo, varAlias);
                            act.mPrefs.saveRepoSignPass(act.mCurrentOwner, act.mCurrentRepo, varPass);
                            act.mPrefs.saveSigningAlias(varAlias);
                            act.mPrefs.saveSigningStorePass(varPass);
                            act.mPrefs.saveSigningKeyPass(varPass);
                            act.mPrefs.saveRepoSignConfigured(act.mCurrentOwner, act.mCurrentRepo, true);
                            act.mPrefs.saveSigningEnabled(true);
                            return;
                        }
                        // Credentials in Variables don't match keystore → fall through to regenerate
                        android.util.Log.w("GitDeploy",
                            "Stored credentials don't match keystore — regenerating");
                    }
                    // Variables missing or credentials invalid → regenerate everything
                    act.mHandler.post(() -> showAutoSignBanner(pageRoot,
                        "⚙  Fixing broken signing setup..."));
                }

                // Step 3: Validate it's a buildable Android app module
                boolean hasBuildType = false;
                try {
                    String[] gradleResult = GHApi.getFileContentSync(act.mPrefs.getToken(),
                        act.mCurrentOwner, act.mCurrentRepo, "app/build.gradle");
                    if (gradleResult != null) {
                        String content = gradleResult[0];
                        hasBuildType = content.contains("android {")
                            && (content.contains("applicationId") || content.contains("minSdk"));
                    }
                } catch (Exception e) { android.util.Log.w("GitDeploy", "ex: " + e.getMessage()); }

                if (!hasBuildType) {
                    act.mHandler.post(() -> hideAutoSignBanner(pageRoot));
                    return;
                }

                if (!ksExists) {
                    act.mHandler.post(() -> showAutoSignBanner(pageRoot,
                        "⚙  Setting up auto-sign for this repo..."));
                }

                // Step 4: Generate alias
                String alias = act.mCurrentRepo.toLowerCase().replaceAll("[^a-z0-9]", "") + "_key";
                if (alias.length() > 20) alias = alias.substring(0, 20);
                if (alias.isEmpty()) alias = "release";

                // Step 5: Generate a RANDOM password — NOT derived from token.
                // Using SecureRandom makes this stable regardless of token changes.
                String password = generateSecurePassword();

                String displayName = act.mCurrentOwner + "/" + act.mCurrentRepo;
                final String fAlias = alias;
                final String fPass  = password;

                byte[] ksBytes = GHApi.generateKeystore(fAlias, fPass, displayName);

                // Step 6: Save to internal storage + Downloads backup
                java.io.File signingDir = new java.io.File(act.getFilesDir(), "signing");
                signingDir.mkdirs();
                java.io.File ksFile = new java.io.File(signingDir,
                    act.mCurrentOwner + "_" + act.mCurrentRepo + ".jks");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(ksFile);
                fos.write(ksBytes); fos.close();

                saveKeystoreToDownloads(ksBytes, act.mCurrentRepo + "_autosign.jks");

                // Step 7: Save credentials locally first
                act.mPrefs.saveRepoSignAlias(act.mCurrentOwner, act.mCurrentRepo, fAlias);
                act.mPrefs.saveRepoSignPass(act.mCurrentOwner, act.mCurrentRepo, fPass);
                act.mPrefs.saveSigningAlias(fAlias);
                act.mPrefs.saveSigningStorePass(fPass);
                act.mPrefs.saveSigningKeyPass(fPass);

                // Step 8: Upload keystore + set GitHub Variables + patch gradle/workflows
                act.mHandler.post(() -> showAutoSignBanner(pageRoot,
                    "⬆  Uploading keystore & patching build files..."));

                GHApi.setupSigning(act.mPrefs.getToken(), act.mCurrentOwner, act.mCurrentRepo,
                    act.mCurrentBranch, ksBytes, fAlias, fPass, fPass,
                    (result, err) -> act.mHandler.post(() -> {
                        hideAutoSignBanner(pageRoot);
                        if (err != null) {
                            boolean isPermError = err.contains("403") || err.contains("Resource not accessible")
                                || err.contains("not authorized") || err.contains("Forbidden");
                            String errMsg = isPermError
                                ? "Auto-sign failed — token missing permissions.\n\n"
                                  + "Your GitHub token needs:\n"
                                  + "• repo scope (read & write)\n"
                                  + "• workflow scope (Actions)\n"
                                  + "• Actions Variables: Read & Write\n\n"
                                  + "Go to GitHub → Settings → Developer settings\n"
                                  + "→ Tokens → regenerate with correct scopes.\n\n"
                                  + "Then update your token in GitDeploy."
                                : "Auto-sign setup failed:\n\n" + err
                                  + "\n\nYou can configure it manually via ⋮ → Signing Config.";
                            act.mDlg.showErr(errMsg);
                            return;
                        }
                        act.mPrefs.saveRepoSignConfigured(act.mCurrentOwner, act.mCurrentRepo, true);
                        act.mPrefs.saveSigningEnabled(true);
                        act.mDlg.toast("🔏 Auto-sign configured — builds will be signed automatically");
                    }));

            } catch (Exception e) {
                act.mHandler.post(() -> {
                    hideAutoSignBanner(pageRoot);
                    act.mDlg.showErr("Auto-sign setup failed:\n\n" + e.getMessage());
                });
            }
        });
    }

    /**
     * Generate a secure random password — NOT derived from token or any mutable credential.
     * Format: 8 random hex chars + "Gd!" + 8 more random hex chars = 19 chars total.
     * This is stable per-generation and stored in GitHub Variables as source of truth.
     */
    private String generateSecurePassword() {
        byte[] bytes = new byte[8];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        sb.append("Gd!");
        new java.security.SecureRandom().nextBytes(bytes);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    void showAutoSignBanner(LinearLayout pageRoot, String msg) {
        for (int i = 0; i < pageRoot.getChildCount(); i++) {
            android.view.View v = pageRoot.getChildAt(i);
            if ("autosign_banner".equals(v.getTag())) {
                if (v instanceof TextView) ((TextView) v).setText(msg);
                v.setVisibility(android.view.View.VISIBLE);
                return;
            }
        }
        TextView banner = act.mUi.tv("  " + msg, ThemeManager.CYAN, 10f, false);
        banner.setTag("autosign_banner");
        banner.setBackgroundColor(0xFF020D10);
        banner.setPadding(act.mUi.dp(14), act.mUi.dp(8), act.mUi.dp(14), act.mUi.dp(8));
        banner.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        pageRoot.addView(banner, Math.min(2, pageRoot.getChildCount()));
    }

    void hideAutoSignBanner(LinearLayout pageRoot) {
        for (int i = 0; i < pageRoot.getChildCount(); i++) {
            android.view.View v = pageRoot.getChildAt(i);
            if ("autosign_banner".equals(v.getTag())) {
                v.setVisibility(android.view.View.GONE);
                return;
            }
        }
    }

    void saveKeystoreToDownloads(byte[] ksBytes, String fname) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname);
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri = act.getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    java.io.OutputStream os = act.getContentResolver().openOutputStream(uri);
                    if (os != null) { os.write(ksBytes); os.close(); }
                }
            } else {
                java.io.File dir = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                java.io.FileOutputStream fos2 = new java.io.FileOutputStream(
                    new java.io.File(dir, fname));
                fos2.write(ksBytes); fos2.close();
            }
        } catch (Exception e) { android.util.Log.w("GitDeploy", "ex: " + e.getMessage()); }
    }
}
