package com.gitdeploy.app;

import android.app.NotificationManager;
import android.os.Build;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Handles system notifications, build polling, and file/size formatting utilities.
 */
class NotificationHelper {

    private final MainActivity act;

    NotificationHelper(MainActivity act) {
        this.act = act;
    }

    // =========================================================================
    // NOTIFICATIONS
    // =========================================================================

    void setupNotifications() {
        act.mNotifMgr = (NotificationManager) act.getSystemService(act.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                MainActivity.NOTIF_CH, "GitDeploy Background Tasks",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Upload & workflow management tasks");
            ch.setSound(null, null);
            act.mNotifMgr.createNotificationChannel(ch);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (act.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 200);
            }
        }
    }

    @SuppressWarnings({"deprecation", "NewApi"})
    void showNotif(int id, String title, String text, int progress, int max) {
        if (act.mNotifMgr == null) return;
        android.app.Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new android.app.Notification.Builder(act, MainActivity.NOTIF_CH);
        } else {
            b = new android.app.Notification.Builder(act);
            b.setPriority(-1);
        }
        b.setSmallIcon(android.R.drawable.ic_popup_sync)
         .setContentTitle(title)
         .setContentText(text)
         .setOngoing(true);
        if (max > 0) b.setProgress(max, Math.max(0, progress), false);
        else         b.setProgress(0, 0, true);
        act.mNotifMgr.notify(id, b.build());
    }

    @SuppressWarnings({"deprecation", "NewApi"})
    void showNotifResult(String title, String text) {
        if (act.mNotifMgr == null) return;
        String tl = title.toLowerCase();
        boolean isError = title.startsWith("✗") || tl.contains("fail") || tl.contains("error");
        boolean isDl    = tl.contains("download") || tl.contains("sync") || tl.contains("upload");
        int icon;
        if (isError)    icon = android.R.drawable.ic_dialog_alert;
        else if (isDl)  icon = android.R.drawable.stat_sys_download_done;
        else            icon = android.R.drawable.ic_menu_send;

        android.app.Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new android.app.Notification.Builder(act, MainActivity.NOTIF_CH);
        } else {
            b = new android.app.Notification.Builder(act);
            b.setPriority(0);
        }
        b.setSmallIcon(icon)
         .setContentTitle(title)
         .setContentText(text)
         .setAutoCancel(true)
         .setOngoing(false);
        act.mNotifMgr.notify(MainActivity.NOTIF_MANAGE + 10, b.build());
    }

    void cancelNotif(int id) {
        if (act.mNotifMgr != null) act.mNotifMgr.cancel(id);
    }

    // =========================================================================
    // BUILD POLLING
    // =========================================================================

    void startBuildPolling() {
        stopBuildPolling();
        final long startTime = System.currentTimeMillis();
        act.mPollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "build-poll");
            t.setDaemon(true);
            return t;
        });

        act.mPollExecutor.scheduleWithFixedDelay(() -> {
            try {
                java.util.List<GHApi.WorkflowRun> runs =
                    GHApi.listRunsSync(act.mPrefs.getToken(), act.mCurrentOwner, act.mCurrentRepo);
                if (runs == null) return;

                boolean stillActive  = false;
                int     activeCount  = 0;
                String  activeRunName = "";

                for (GHApi.WorkflowRun r : runs) {
                    String prev = act.mLastRunStatus.get(r.id);
                    String curr = r.status + "|" + r.conclusion;

                    boolean wasActive = prev != null
                        && (prev.startsWith("in_progress") || prev.startsWith("queued")
                            || prev.startsWith("waiting"));

                    if (wasActive && "completed".equals(r.status)) {
                        boolean ok = "success".equals(r.conclusion);
                        showNotifResult(
                            ok ? "✓ Build Succeeded 🎉" : "✗ Build Failed",
                            r.name + " · " + r.conclusion.toUpperCase()
                        );
                        if (!ok) act.mPrefs.saveLastFailedWorkflowName(r.name);
                        cancelNotif(MainActivity.NOTIF_BUILD_RUNNING);
                    }
                    act.mLastRunStatus.put(r.id, curr);

                    if ("in_progress".equals(r.status) || "queued".equals(r.status)
                            || "waiting".equals(r.status)) {
                        stillActive = true;
                        activeCount++;
                        activeRunName = r.name;
                    }
                }

                if (stillActive) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    String elapsedStr = elapsed < 60
                        ? elapsed + "s" : (elapsed / 60) + "m " + (elapsed % 60) + "s";
                    String notifText = activeCount > 1
                        ? activeCount + " builds running · " + elapsedStr
                        : activeRunName + " · " + elapsedStr;
                    showNotif(MainActivity.NOTIF_BUILD_RUNNING,
                        "⚙ Building " + act.mCurrentRepo, notifText, -1, 0);
                } else {
                    cancelNotif(MainActivity.NOTIF_BUILD_RUNNING);
                }

                final java.util.List<GHApi.WorkflowRun> finalRuns = runs;
                act.mCachedRuns = runs;
                act.mHandler.post(() -> {
                    if (act.mRunListContainer == null) return;
                    act.mRunListContainer.removeAllViews();
                    for (GHApi.WorkflowRun run : finalRuns) {
                        act.mRunListContainer.addView(act.buildRunCard(run));
                        act.mRunListContainer.addView(act.mUi.sp(8));
                    }
                });

                if (!stillActive) stopBuildPolling();
            } catch (Exception e) { android.util.Log.w("GitDeploy", "ex: " + e.getMessage()); }
        }, 2, 15, TimeUnit.SECONDS);
    }

    void stopBuildPolling() {
        cancelNotif(MainActivity.NOTIF_BUILD_RUNNING);
        if (act.mPollExecutor != null) {
            act.mPollExecutor.shutdownNow();
            act.mPollExecutor = null;
        }
    }

    // =========================================================================
    // FILE / SIZE UTILS
    // =========================================================================

    String getFileIcon(String name) {
        name = name.toLowerCase();
        if (name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz")) return "🗜";
        if (name.endsWith(".apk"))  return "📦";
        if (name.endsWith(".java") || name.endsWith(".kt")) return "☕";
        if (name.endsWith(".gradle") || name.endsWith(".xml")) return "⚙";
        if (name.endsWith(".md") || name.endsWith(".txt")) return "📝";
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".svg")) return "🖼";
        if (name.endsWith(".sh") || name.endsWith(".py")) return "📜";
        if (name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".yaml")) return "🔧";
        if (name.endsWith(".jks") || name.endsWith(".keystore")) return "🔑";
        return "📄";
    }

    String fmtSize(long bytes) {
        if (bytes >= 1024L*1024*1024) return String.format("%.1f GB", bytes/(1024.0*1024*1024));
        if (bytes >= 1024L*1024)      return String.format("%.1f MB", bytes/(1024.0*1024));
        if (bytes >= 1024L)           return String.format("%.0f KB", bytes/1024.0);
        return bytes + " B";
    }
}
