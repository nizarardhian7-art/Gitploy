package com.gitdeploy.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Smart Sync (folder → GitHub), App-to-GitHub upload, and One-Click APK Install.
 */
class SmartSyncManager {

    private final MainActivity act;

    SmartSyncManager(MainActivity act) {
        this.act = act;
    }

    // =========================================================================
    // SMART SYNC — FOLDER (Fitur 2)
    // =========================================================================

    void handleFolderSync(Uri treeUri) {
        android.app.Dialog prog = act.mDlg.makeProgressDialog();
        final TextView progTv = (TextView)
            ((LinearLayout) prog.findViewById(android.R.id.message)).getChildAt(0);
        progTv.setText("Reading folder...");
        prog.show();

        AppExecutors.getInstance().diskIO().execute(() -> {
            try {
                androidx.documentfile.provider.DocumentFile root =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(act, treeUri);
                if (root == null) {
                    act.mHandler.post(() -> { prog.dismiss(); act.mDlg.showErr("Cannot read folder."); });
                    return;
                }

                java.util.List<String[]> localFiles = new java.util.ArrayList<>();
                collectDocumentFiles(root, "", localFiles, progTv);

                if (localFiles.isEmpty()) {
                    act.mHandler.post(() -> { prog.dismiss(); act.mDlg.showErr("Folder is empty or unreadable."); });
                    return;
                }

                act.mHandler.post(() -> progTv.setText("Fetching file tree from GitHub..."));

                java.util.Map<String, String> remoteTree = new java.util.HashMap<>();
                try {
                    remoteTree = fetchGitTreeMap(act.mCurrentOwner,
                        act.mPendingUploadRepo, act.mPendingUploadBranch);
                } catch (Exception e) { android.util.Log.w("GitDeploy", "ex: " + e.getMessage()); }

                java.util.List<String[]> toUpload = new java.util.ArrayList<>();
                for (String[] f : localFiles) {
                    String path = f[0];
                    byte[] content = android.util.Base64.decode(f[1], android.util.Base64.NO_WRAP);
                    String localSha  = GHApi.computeGitBlobSha(content);
                    String remoteSha = remoteTree.get(path);
                    if (!localSha.equals(remoteSha)) toUpload.add(f);
                }

                final int total   = toUpload.size();
                final int scanned = localFiles.size();

                if (total == 0) {
                    act.mHandler.post(() -> {
                        prog.dismiss();
                        act.mDlg.showInfo("Up to Date",
                            "All " + scanned + " local files are already up to date.\nNothing to upload.");
                    });
                    return;
                }

                act.mHandler.post(() -> {
                    prog.dismiss();
                    act.mDlg.showConfirm("Sync Summary",
                        scanned + " files scanned. " + total + " changed / new.\n\nUpload "
                            + total + " file(s) to " + act.mCurrentBranch + "?",
                        "Upload " + total + " Files", ThemeManager.BRAND, () -> {
                            UploadForegroundService.sListener = new UploadForegroundService.ProgressListener() {
                                @Override public void onProgress(int cur, int tot, String file) {
                                    act.mDlg.updateStatusBar("🔄  " + cur + " / " + tot
                                        + "  — " + new java.io.File(file).getName());
                                }
                                @Override public void onComplete(int ok, int fail, java.util.List<String> fp) {
                                    act.mNotif.cancelNotif(MainActivity.NOTIF_UPLOAD);
                                    act.mCachedFiles.evictAll();
                                    act.mDlg.hideStatusBar(0);
                                    if (fail == 0) {
                                        act.mNotif.showNotifResult("✓ Sync Complete", ok + " file(s) updated");
                                        act.mDlg.showConfirm("Sync Complete",
                                            ok + " file(s) updated.\nBranch: " + act.mCurrentBranch,
                                            "View Files", ThemeManager.BRAND,
                                            () -> act.navigateTo(() -> act.setContentView(act.buildRepoPageAtTab(0))));
                                    } else {
                                        // Tampilkan daftar file gagal warna merah
                                        StringBuilder sb = new StringBuilder();
                                        sb.append(ok).append(" file(s) updated.\n");
                                        sb.append("⚠ ").append(fail).append(" file(s) FAILED:\n");
                                        for (String p : fp)
                                            sb.append("• ").append(new java.io.File(p).getName()).append("\n");
                                        sb.append("\nBranch: ").append(act.mCurrentBranch);
                                        act.mNotif.showNotifResult("⚠ Partial Sync", ok + " ok · " + fail + " failed");
                                        act.mDlg.showErrRed("⚠ Partial Sync", sb.toString().trim());
                                    }
                                    UploadForegroundService.sListener = null;
                                }
                                @Override public void onError(String msg) {
                                    act.mDlg.showErr("Sync error: " + msg);
                                    UploadForegroundService.sListener = null;
                                }
                            };
                            act.mDlg.showStatusBar("🔄  Starting Smart Sync — " + total + " file...", ThemeManager.AMBER);
                            android.content.Intent syncIntent =
                                UploadForegroundService.buildSyncIntent(
                                    act, act.mPrefs.getToken(), act.mCurrentOwner,
                                    act.mPendingUploadRepo, act.mPendingUploadBranch,
                                    act.mPendingUploadMsg, toUpload);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                act.startForegroundService(syncIntent);
                            } else {
                                act.startService(syncIntent);
                            }
                        });
                });

            } catch (Exception e) {
                act.mHandler.post(() -> { prog.dismiss(); act.mDlg.showErr("Sync error: " + e.getMessage()); });
            }
        });
    }

    private void collectDocumentFiles(androidx.documentfile.provider.DocumentFile dir,
                                       String relBase,
                                       java.util.List<String[]> out,
                                       TextView progTv) {
        androidx.documentfile.provider.DocumentFile[] files = dir.listFiles();
        if (files == null) return;
        for (androidx.documentfile.provider.DocumentFile f : files) {
            String name = f.getName();
            if (name == null) continue;
            if (name.equals(".git") || name.equals("build") || name.equals(".gradle")
                || name.equals(".idea") || name.equals(".DS_Store")
                || name.equals("__pycache__") || name.equals("node_modules")
                || name.equals(".dart_tool") || name.equals(".flutter-plugins")
                || (name.startsWith(".") && !name.equals(".github") && !name.equals(".gitignore")
                    && !name.equals(".gitattributes"))) continue;
            String rel = relBase.isEmpty() ? name : relBase + "/" + name;
            if (f.isDirectory()) {
                collectDocumentFiles(f, rel, out, progTv);
            } else {
                try {
                    java.io.InputStream is = act.getContentResolver().openInputStream(f.getUri());
                    if (is == null) continue;
                    byte[] bytes = GHApi.readStreamBytes(is); is.close();
                    out.add(new String[]{rel,
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)});
                    final String relF = rel;
                    act.mHandler.post(() -> progTv.setText("Reading: " + relF + "  (" + out.size() + " file(s))"));
                } catch (Exception e) { android.util.Log.w("GitDeploy", "ex: " + e.getMessage()); }
            }
        }
    }

    private java.util.Map<String, String> fetchGitTreeMap(String owner, String repo,
                                                            String branch) throws Exception {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(
            "https://api.github.com/repos/" + owner + "/" + repo
            + "/git/trees/" + branch + "?recursive=1").openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "token " + act.mPrefs.getToken());
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setConnectTimeout(15000); conn.setReadTimeout(30000);
        if (conn.getResponseCode() == 200) {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            org.json.JSONArray tree = new org.json.JSONObject(sb.toString()).getJSONArray("tree");
            for (int i = 0; i < tree.length(); i++) {
                org.json.JSONObject o = tree.getJSONObject(i);
                if ("blob".equals(o.optString("type")))
                    map.put(o.getString("path"), o.optString("sha", ""));
            }
        }
        return map;
    }

    // =========================================================================
    // APP TO GITHUB (Fitur 5)
    // =========================================================================

    void showAppToGitHubDialog() {
        android.app.Dialog prog = act.mDlg.makeProgressDialog();
        ((TextView)((LinearLayout) prog.findViewById(android.R.id.message)).getChildAt(0))
            .setText("Membaca daftar aplikasi...");
        prog.show();

        AppExecutors.getInstance().networkIO().execute(() -> {
            android.content.pm.PackageManager pm = act.getPackageManager();
            java.util.List<android.content.pm.ApplicationInfo> allApps =
                pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA);
            java.util.List<android.content.pm.ApplicationInfo> userApps = new java.util.ArrayList<>();
            for (android.content.pm.ApplicationInfo ai : allApps) {
                if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                        && ai.sourceDir != null) userApps.add(ai);
            }
            userApps.sort((a, b) -> pm.getApplicationLabel(a).toString()
                .compareToIgnoreCase(pm.getApplicationLabel(b).toString()));

            act.mHandler.post(() -> {
                prog.dismiss();
                if (userApps.isEmpty()) { act.mDlg.toast("No user apps installed."); return; }
                String[] labels = new String[userApps.size()];
                for (int i = 0; i < userApps.size(); i++) {
                    labels[i] = pm.getApplicationLabel(userApps.get(i)).toString()
                        + "\n" + userApps.get(i).packageName;
                }
                Runnable[] actions = new Runnable[userApps.size()];
                for (int i = 0; i < userApps.size(); i++) {
                    final android.content.pm.ApplicationInfo chosen = userApps.get(i);
                    actions[i] = () -> confirmUploadApp(
                        pm.getApplicationLabel(chosen).toString(),
                        chosen.packageName, chosen.sourceDir);
                }
                act.mDlg.showMenu("Select App to Upload", labels, actions);
            });
        });
    }

    private void confirmUploadApp(String label, String pkg, String apkPath) {
        java.io.File apkFile = new java.io.File(apkPath);
        String sizeTxt = act.mNotif.fmtSize(apkFile.length());
        act.mDlg.showConfirm("Upload APK to Repo?",
            "App: " + label + "\nPkg: " + pkg + "\nSize: " + sizeTxt
                + "\n\nWill upload as: " + pkg + ".apk",
            "Upload", ThemeManager.BRAND, () -> {
                android.app.Dialog prog2 = act.mDlg.makeProgressDialog();
                final TextView progTv = (TextView)
                    ((LinearLayout) prog2.findViewById(android.R.id.message)).getChildAt(0);
                progTv.setText("Membaca APK...");
                prog2.show();
                AppExecutors.getInstance().networkIO().execute(() -> {
                    try {
                        java.io.FileInputStream fis = new java.io.FileInputStream(apkPath);
                        byte[] bytes = GHApi.readStreamBytes(fis); fis.close();
                        String repoPath = pkg + ".apk";
                        act.mHandler.post(() -> progTv.setText("Uploading " + repoPath
                            + "\n" + act.mNotif.fmtSize(bytes.length)));
                        boolean ok = GHApi.uploadFileSync(act.mPrefs.getToken(),
                            act.mCurrentOwner, act.mCurrentRepo, repoPath, bytes,
                            "Upload " + label + " APK via GitDeploy", act.mCurrentBranch, 2);
                        act.mHandler.post(() -> {
                            prog2.dismiss();
                            if (ok) offerDecompileWorkflow(label, pkg);
                            else act.mDlg.showErr("Upload failed. File must be under 100MB (GitHub limit).");
                        });
                    } catch (Exception e) {
                        act.mHandler.post(() -> { prog2.dismiss(); act.mDlg.showErr("Error: " + e.getMessage()); });
                    }
                });
            });
    }

    private void offerDecompileWorkflow(String label, String pkg) {
        act.mDlg.showInfo("APK Uploaded",
            label + " (" + pkg + ".apk) uploaded to " + act.mCurrentRepo + ".\n\n"
            + "Next steps:\n"
            + "• Buat workflow Decompile di repo (.github/workflows/decompile.yml)\n"
            + "• Trigger dari tab BUILDS\n"
            + "• GitHub Actions akan jalankan Apktool & push hasilnya");
        GHApi.listWorkflows(act.mPrefs.getToken(), act.mCurrentOwner, act.mCurrentRepo,
            (wfs, err) -> act.mHandler.post(() -> {
                if (wfs == null) return;
                for (GHApi.Workflow wf : wfs) {
                    if (wf.name.toLowerCase().contains("decompile")
                            || wf.path.toLowerCase().contains("decompile")) {
                        act.mDlg.showConfirm("Run Decompile?",
                            "Workflow \"" + wf.name + "\" found. Trigger now?",
                            "Trigger", ThemeManager.BRAND,
                            () -> act.showWorkflowInputsForm(wf, java.util.Collections.emptyList(), false));
                        return;
                    }
                }
            }));
    }

    // =========================================================================
    // ONE-CLICK INSTALL APK (Fitur 4)
    // =========================================================================

    void tryInstallApkFromZip(byte[] zipBytes, String artifactName) {
        AppExecutors.getInstance().diskIO().execute(() -> {
            try {
                // Prepare output dir first
                java.io.File apkDir = new java.io.File(act.getCacheDir(), "apk_install");
                apkDir.mkdirs();
                // Delete any leftover APKs from a previous run
                java.io.File[] old = apkDir.listFiles();
                if (old != null) for (java.io.File f : old) f.delete();

                java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(zipBytes));
                java.util.zip.ZipEntry entry;
                java.util.List<String>       apkNames = new java.util.ArrayList<>();
                java.util.List<java.io.File> apkFiles = new java.util.ArrayList<>();
                byte[] buffer = new byte[16384]; // 16 KB streaming buffer — no OOM
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.isDirectory() && entry.getName().endsWith(".apk")) {
                        String apkName = new java.io.File(entry.getName()).getName();
                        java.io.File dest = new java.io.File(apkDir, apkName);
                        // Stream directly from ZipInputStream → FileOutputStream
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
                        int len;
                        while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                        fos.flush(); fos.close();
                        apkNames.add(apkName);
                        apkFiles.add(dest);
                    }
                    zis.closeEntry();
                }
                zis.close();

                if (apkNames.isEmpty()) {
                    act.mHandler.post(() -> act.mDlg.toast("No .apk found in artifact ZIP"));
                    return;
                }

                // Prioritize: signed > release > any
                int bestIdx = 0;
                for (int i = 0; i < apkNames.size(); i++) {
                    String n = apkNames.get(i).toLowerCase();
                    String b = apkNames.get(bestIdx).toLowerCase();
                    if (n.contains("signed") && !b.contains("signed"))             { bestIdx = i; break; }
                    if (n.contains("release") && !b.contains("release")
                            && !b.contains("signed"))                              { bestIdx = i; }
                }

                final int finalBest              = bestIdx;
                final java.util.List<java.io.File> finalFiles = apkFiles;
                final java.util.List<String>       finalNames = apkNames;

                act.mHandler.post(() -> {
                    if (finalFiles.size() == 1) {
                        doInstallApk(finalFiles.get(0));
                    } else {
                        String[] menuItems = new String[finalNames.size()];
                        for (int i = 0; i < finalNames.size(); i++)
                            menuItems[i] = (i == finalBest ? "★  " : "    ") + finalNames.get(i);
                        Runnable[] actions = new Runnable[finalFiles.size()];
                        for (int i = 0; i < finalFiles.size(); i++) {
                            final java.io.File apk = finalFiles.get(i);
                            actions[i] = () -> doInstallApk(apk);
                        }
                        act.mDlg.showMenu("Choose APK to Install", menuItems, actions);
                    }
                });
            } catch (Exception e) {
                act.mHandler.post(() -> act.mDlg.showErr("APK extract failed:\n" + e.getMessage()));
            }
        });
    }

    private void doInstallApk(java.io.File apkFile) {
        try {
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = androidx.core.content.FileProvider.getUriForFile(
                    act, act.getPackageName() + ".provider", apkFile);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(install);
        } catch (Exception e) {
            act.mDlg.toast("Cannot open installer: " + e.getMessage());
        }
    }
}
