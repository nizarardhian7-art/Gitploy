package com.gitdeploy.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Foreground Service untuk ZIP upload & Smart Sync.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * BUG FIX: TransactionTooLargeException (root cause crash "terus berhenti")
 * ═══════════════════════════════════════════════════════════════════════
 *
 * PENYEBAB CRASH LAMA:
 *   buildSyncIntent() memasukkan seluruh konten file (base64) ke dalam
 *   Intent.putExtra(EXTRA_SYNC_FILES, raw[]).
 *   Android menggunakan Binder IPC untuk transfer Intent antar komponen.
 *   Binder punya hard limit ~1 MB untuk TOTAL data yang di-pass.
 *   Bahkan 3 file source code Java (masing-masing ~50-200 KB) bisa
 *   melebihi limit ini setelah di-encode base64 (+33%), menyebabkan
 *   TransactionTooLargeException yang crash-kan app secara silent.
 *
 * FIX YANG DITERAPKAN:
 *   Gunakan static volatile in-process data holders:
 *     - sPendingSyncFiles  → List<String[]> file yang akan di-sync
 *     - sPendingZipBytes   → byte[] konten ZIP
 *   Activity dan Service berada di PROSES YANG SAMA, sehingga bisa
 *   berbagi reference Java langsung tanpa melewati Binder sama sekali.
 *   Intent hanya membawa metadata ringan (token, owner, repo, branch).
 *
 *   Pattern ini identik dengan sPendingListener yang sudah benar.
 */
public class UploadForegroundService extends Service {

    private static final String TAG     = "UploadFgSvc";
    private static final String CHANNEL = "gd_upload_fg";
    private static final int    NOTIF_ID = 9001;

    // ── Intent action constants ───────────────────────────────────────────────
    public static final String ACTION_ZIP_UPLOAD = "com.gitdeploy.app.ACTION_ZIP_UPLOAD";
    public static final String ACTION_SMART_SYNC = "com.gitdeploy.app.ACTION_SMART_SYNC";

    // ── Intent extras — hanya metadata RINGAN (tidak ada data file!) ──────────
    public static final String EXTRA_TOKEN      = "token";
    public static final String EXTRA_OWNER      = "owner";
    public static final String EXTRA_REPO       = "repo";
    public static final String EXTRA_BRANCH     = "branch";
    public static final String EXTRA_COMMIT_MSG = "commit_msg";
    public static final String EXTRA_BASE_PATH  = "base_path";

    // ═════════════════════════════════════════════════════════════════════════
    // FIX: Static in-process data holders — TIDAK melewati Binder
    // Diset oleh MainActivity SEBELUM startForegroundService(),
    // dibaca dan di-null-kan oleh Service di onStartCommand().
    // ═════════════════════════════════════════════════════════════════════════

    public static final String EXTRA_TRANSFER_ID = "transfer_id";
    private static final java.util.concurrent.ConcurrentHashMap<String, byte[]>
        sPendingZipMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, List<String[]>>
        sPendingSyncMap = new java.util.concurrent.ConcurrentHashMap<>();
    /** @deprecated use buildZipUploadIntent() */
    @Deprecated public static volatile byte[] sPendingZipBytes;
    /** @deprecated use buildSyncIntent() */
    @Deprecated public static volatile List<String[]> sPendingSyncFiles;

    // ─────────────────────────────────────────────────────────────────────────
    // Progress callback — diset oleh MainActivity saat visible
    // ─────────────────────────────────────────────────────────────────────────
    public interface ProgressListener {
        void onProgress(int current, int total, String currentFile);
        void onComplete(int successCount, int failCount, List<String> failedPaths);
        void onError(String message);
    }

    public static volatile ProgressListener sListener;
    private static volatile boolean sRunning = false;
    /** True while a foreground upload/sync task is executing. */
    public static boolean isRunning() { return sRunning; }

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sRunning = true;
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }

        // Promote ke foreground SEGERA — sebelum semua pekerjaan async
        startForeground(NOTIF_ID, buildNotification("Mempersiapkan...", 0, 0));

        String action = intent.getAction();
        if (ACTION_ZIP_UPLOAD.equals(action)) {
            handleZipUpload(intent, startId);
        } else if (ACTION_SMART_SYNC.equals(action)) {
            handleSmartSync(intent, startId);
        } else {
            stopSelf(startId);
        }

        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sRunning = false; // Safety net — ensures isRunning() is always false after service dies
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ZIP upload
    // ─────────────────────────────────────────────────────────────────────────
    private void handleZipUpload(final Intent intent, final int startId) {
        final String transferId = intent.getStringExtra(EXTRA_TRANSFER_ID);
        byte[] _zip = (transferId != null) ? sPendingZipMap.remove(transferId) : null;
        if (_zip == null) { _zip = sPendingZipBytes; sPendingZipBytes = null; }
        final byte[] zipBytes = _zip;

        final String token    = intent.getStringExtra(EXTRA_TOKEN);
        final String owner    = intent.getStringExtra(EXTRA_OWNER);
        final String repo     = intent.getStringExtra(EXTRA_REPO);
        final String branch   = intent.getStringExtra(EXTRA_BRANCH);
        final String msg      = intent.getStringExtra(EXTRA_COMMIT_MSG);
        final String basePath = intent.getStringExtra(EXTRA_BASE_PATH);

        if (token == null || zipBytes == null) {
            notifyError("Data upload tidak tersedia — coba lagi");
            stopSelf(startId); return;
        }

        AppExecutors.runOnDisk(() -> {
            // Parse ZIP entries
            List<String[]> files = new ArrayList<>();
            try {
                ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes));
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) { zis.closeEntry(); continue; }
                    String name = entry.getName();
                    if (name.contains("__MACOSX") || name.contains(".DS_Store")
                            || name.startsWith(".git/")) { zis.closeEntry(); continue; }
                    byte[] content = GHApi.readStreamBytes(zis);
                    zis.closeEntry();
                    String base  = (basePath != null ? basePath : "").trim();
                    String rPath = base.isEmpty() ? name : (base + "/" + name);
                    rPath = rPath.replaceAll("/+", "/");
                    if (rPath.startsWith("/")) rPath = rPath.substring(1);
                    files.add(new String[]{rPath,
                        android.util.Base64.encodeToString(content, android.util.Base64.NO_WRAP)});
                }
                zis.close();
            } catch (Exception e) {
                notifyError("Gagal baca ZIP: " + e.getMessage());
                stopSelf(startId); return;
            }

            if (files.isEmpty()) {
                notifyError("ZIP kosong atau tidak valid");
                stopSelf(startId); return;
            }

            pushFiles(token, owner, repo, branch, msg != null ? msg : "Upload via GitDeploy",
                files, startId);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Smart Sync
    // ─────────────────────────────────────────────────────────────────────────
    private void handleSmartSync(final Intent intent, final int startId) {
        final String transferId = intent.getStringExtra(EXTRA_TRANSFER_ID);
        List<String[]> _files = (transferId != null) ? sPendingSyncMap.remove(transferId) : null;
        if (_files == null) { _files = sPendingSyncFiles; sPendingSyncFiles = null; }
        final List<String[]> files = _files;

        final String token  = intent.getStringExtra(EXTRA_TOKEN);
        final String owner  = intent.getStringExtra(EXTRA_OWNER);
        final String repo   = intent.getStringExtra(EXTRA_REPO);
        final String branch = intent.getStringExtra(EXTRA_BRANCH);
        final String msg    = intent.getStringExtra(EXTRA_COMMIT_MSG);

        if (token == null || files == null || files.isEmpty()) {
            notifyError("Data sync tidak tersedia — coba lagi");
            stopSelf(startId); return;
        }

        AppExecutors.runOnDisk(() ->
            pushFiles(token, owner, repo, branch, msg != null ? msg : "Sync via GitDeploy",
                files, startId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core upload loop — dipakai oleh ZIP upload dan Smart Sync
    // ─────────────────────────────────────────────────────────────────────────
    private void pushFiles(String token, String owner, String repo, String branch,
                           String msg, List<String[]> files, int startId) {
        final int total = files.size();

        // ── PRIMARY: GitHub Tree API — 1 commit untuk semua file ──────────────
        // Tidak perlu [skip ci] trick — 1 commit alami = workflow trigger 1x.
        updateNotification("Preparing " + total + " file(s)...", 0, total);

        boolean treeOk = GHApi.uploadFilesViaTreeApi(token, owner, repo, branch, msg, files,
            (done, tot, path) -> {
                String label = path.startsWith("⏳") ? path : new java.io.File(path).getName();
                updateNotification("⬆  " + label, done, tot);
                ProgressListener l = sListener;
                if (l != null) {
                    final int fd = done; final int ft = tot; final String fp = path;
                    AppExecutors.runOnMain(() -> l.onProgress(fd, ft, fp));
                }
            });

        if (treeOk) {
            sRunning = false;
            stopForeground(true);
            cancelNotification();
            ProgressListener l = sListener;
            if (l != null) AppExecutors.runOnMain(() -> l.onComplete(total, 0, new ArrayList<>()));
            stopSelf(startId);
            return;
        }

        // ── FALLBACK: Per-file upload jika Tree API gagal ─────────────────────
        // (misal repo baru / token scope terbatas / server error)
        Log.w(TAG, "Tree API failed, fallback to per-file upload");
        updateNotification("Fallback: uploading " + total + " file(s)...", 0, total);

        int ok = 0, fail = 0;
        List<String> failedPaths = new ArrayList<>();
        final String finalMsg = msg.contains("[skip ci]") ? msg : msg + " [skip ci]";

        List<String[]> failedEntries = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            String rp  = files.get(i)[0];
            byte[] cnt = android.util.Base64.decode(files.get(i)[1], android.util.Base64.NO_WRAP);
            int cur = i + 1;
            updateNotification("Uploading " + new java.io.File(rp).getName(), cur, total);
            final int fCur = cur; final String fRp = rp;
            ProgressListener l = sListener;
            if (l != null) AppExecutors.runOnMain(() -> l.onProgress(fCur, total, fRp));
            boolean success = GHApi.uploadFileSync(token, owner, repo, rp, cnt, finalMsg, branch, 3);
            if (success) { ok++; } else { failedEntries.add(files.get(i)); failedPaths.add(rp); }
            try { Thread.sleep(250); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
        }

        // Retry sekali untuk yang gagal
        if (!failedEntries.isEmpty()) {
            updateNotification("Retrying " + failedEntries.size() + " failed file(s)...", total, total);
            List<String> stillFailed = new ArrayList<>();
            for (String[] entry : failedEntries) {
                String rp  = entry[0];
                byte[] cnt = android.util.Base64.decode(entry[1], android.util.Base64.NO_WRAP);
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                boolean retryOk = GHApi.uploadFileSync(token, owner, repo, rp, cnt, finalMsg, branch, 2);
                if (retryOk) { ok++; } else { stillFailed.add(rp); }
            }
            fail = stillFailed.size();
            failedPaths = stillFailed;
        }

        // Trigger 1 workflow commit setelah fallback selesai
        if (ok > 0) {
            updateNotification("Triggering workflow build...", total, total);
            String triggerMsg = msg.replace(" [skip ci]", "").replace("[skip ci]", "").trim();
            GHApi.triggerWorkflowCommit(token, owner, repo, branch, triggerMsg);
        }

        sRunning = false;
        stopForeground(true);
        cancelNotification();
        final int fOk = ok, fFail = fail;
        final List<String> fFailed = failedPaths;
        ProgressListener l = sListener;
        if (l != null) AppExecutors.runOnMain(() -> l.onComplete(fOk, fFail, fFailed));
        stopSelf(startId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ─────────────────────────────────────────────────────────────────────────
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Upload & Sync", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("GitDeploy file upload and Smart Sync");
            ch.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @SuppressWarnings({"deprecation", "NewApi"})
    private Notification buildNotification(String text, int progress, int max) {
        int piFlags = Build.VERSION.SDK_INT >= 31
            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            : PendingIntent.FLAG_UPDATE_CURRENT;
        Intent cancelIntent = new Intent(this, UploadForegroundService.class);
        cancelIntent.setAction("CANCEL");
        PendingIntent pi = PendingIntent.getService(this, 0, cancelIntent, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);

        b.setSmallIcon(android.R.drawable.ic_popup_sync)
         .setContentTitle("GitDeploy Upload")
         .setContentText(text)
         .setOngoing(true)
         .addAction(android.R.drawable.ic_delete, "Cancel", pi);

        if (max > 0) b.setProgress(max, Math.max(0, progress), false);
        else         b.setProgress(0, 0, true);

        return b.build();
    }

    private void updateNotification(String text, int cur, int total) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text, cur, total));
    }

    private void cancelNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    private void notifyError(String msg) {
        Log.e(TAG, msg);
        sRunning = false;
        stopForeground(true);
        cancelNotification();
        ProgressListener l = sListener;
        if (l != null) AppExecutors.runOnMain(() -> l.onError(msg));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FIX: Static factory — hanya metadata di Intent, data besar via static field
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Build Intent untuk ZIP upload.
     * FIX: zipBytes disimpan di sPendingZipBytes (tidak di Intent extra).
     */
    public static Intent buildZipUploadIntent(Context ctx,
            String token, String owner, String repo, String branch,
            String commitMsg, String basePath, byte[] zipBytes) {
        String tid = java.util.UUID.randomUUID().toString();
        sPendingZipMap.put(tid, zipBytes);
        Intent i = new Intent(ctx, UploadForegroundService.class);
        i.setAction(ACTION_ZIP_UPLOAD);
        i.putExtra(EXTRA_TRANSFER_ID, tid);
        i.putExtra(EXTRA_TOKEN,      token);
        i.putExtra(EXTRA_OWNER,      owner);
        i.putExtra(EXTRA_REPO,       repo);
        i.putExtra(EXTRA_BRANCH,     branch);
        i.putExtra(EXTRA_COMMIT_MSG, commitMsg);
        i.putExtra(EXTRA_BASE_PATH,  basePath != null ? basePath : "");
        // TIDAK ada EXTRA_ZIP_BYTES — data ada di sPendingZipBytes
        return i;
    }

    /**
     * Build Intent untuk Smart Sync.
     * FIX: filesToSync disimpan di sPendingSyncFiles (tidak di Intent extra).
     */
    public static Intent buildSyncIntent(Context ctx,
            String token, String owner, String repo, String branch,
            String commitMsg, List<String[]> filesToSync) {
        String tid = java.util.UUID.randomUUID().toString();
        sPendingSyncMap.put(tid, new ArrayList<>(filesToSync));
        Intent i = new Intent(ctx, UploadForegroundService.class);
        i.setAction(ACTION_SMART_SYNC);
        i.putExtra(EXTRA_TRANSFER_ID, tid);
        i.putExtra(EXTRA_TOKEN,      token);
        i.putExtra(EXTRA_OWNER,      owner);
        i.putExtra(EXTRA_REPO,       repo);
        i.putExtra(EXTRA_BRANCH,     branch);
        i.putExtra(EXTRA_COMMIT_MSG, commitMsg);
        // TIDAK ada EXTRA_SYNC_FILES — data ada di sPendingSyncFiles
        return i;
    }
}
