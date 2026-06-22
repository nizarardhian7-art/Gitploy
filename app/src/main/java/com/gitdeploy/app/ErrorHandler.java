package com.gitdeploy.app;

import android.util.Log;

/**
 * ErrorHandler — pusat penanganan error di seluruh app.
 *
 * Fungsi utama:
 *   1. Kategorisasi error otomatis dari pesan string GHApi
 *      (auth / rate-limit / network / server / generic)
 *   2. Pilih cara tampil yang tepat: toast untuk info ringan,
 *      dialog untuk error yang butuh perhatian user
 *   3. Log error ke Logcat secara konsisten
 *
 * Penggunaan dari callback GHApi:
 * <pre>
 *   GHApi.listRepos(token, (repos, err) -> mHandler.post(() -> {
 *       if (err != null) { ErrorHandler.handle(this, err); return; }
 *       // ... gunakan repos
 *   }));
 * </pre>
 *
 * Atau dengan Resource wrapper:
 * <pre>
 *   Resource<List<GHApi.Repo>> res = Resource.from(repos, err);
 *   if (res.isError()) ErrorHandler.handle(this, res.error);
 * </pre>
 */
public final class ErrorHandler {

    private static final String TAG = "ErrorHandler";

    /** Kategori error — untuk routing tampilan dan logging. */
    public enum Category {
        AUTH,         // 401, 403 — token invalid / expired
        RATE_LIMIT,   // 429 — GitHub rate limit
        NOT_FOUND,    // 404 — repo/file tidak ada
        SERVER,       // 5xx — GitHub server error
        NETWORK,      // timeout, no connection
        GENERIC       // lainnya
    }

    private ErrorHandler() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Handle error dari GHApi callback.
     * Toast untuk error ringan (network/not_found),
     * dialog untuk error yang butuh aksi user (auth/rate_limit).
     *
     * @param activity  MainActivity (butuh mDlg)
     * @param message   error string dari GHApi callback
     */
    public static void handle(MainActivity activity, String message) {
        if (message == null || message.isEmpty()) return;
        Category cat = categorize(message);
        Log.w(TAG, "Error [" + cat + "]: " + message);

        String userMsg = toUserMessage(cat, message);

        switch (cat) {
            case AUTH:
                // Error autentikasi — tampil dialog karena butuh aksi (re-login)
                activity.mDlg.showErr("Authentication Error\n\n" + userMsg);
                break;
            case RATE_LIMIT:
                // Rate limit — dialog karena ada info waktu tunggu
                activity.mDlg.showErr("Rate Limit Reached\n\n" + userMsg);
                break;
            default:
                // Error lain — cukup toast, tidak ganggu flow
                activity.mDlg.toast(userMsg);
                break;
        }
    }

    /**
     * Handle error dengan level toast saja (tidak pernah dialog).
     * Cocok untuk operasi background yang tidak kritis.
     */
    public static void handleSilent(MainActivity activity, String message) {
        if (message == null || message.isEmpty()) return;
        Category cat = categorize(message);
        Log.w(TAG, "Silent error [" + cat + "]: " + message);
        activity.mDlg.toast(toUserMessage(cat, message));
    }

    /**
     * Kategorisasi error dari string pesan GHApi.
     * GHApi format: "HTTP 401: {...}" atau pesan exception biasa.
     */
    public static Category categorize(String message) {
        if (message == null) return Category.GENERIC;
        String m = message.toLowerCase();

        if (m.contains("401") || m.contains("bad credentials")
                || m.contains("token") && m.contains("invalid"))
            return Category.AUTH;

        if (m.contains("403") && (m.contains("forbidden") || m.contains("scope")))
            return Category.AUTH;

        if (m.contains("429") || m.contains("rate limit"))
            return Category.RATE_LIMIT;

        if (m.contains("404") || m.contains("not found"))
            return Category.NOT_FOUND;

        if (m.contains("500") || m.contains("502") || m.contains("503")
                || m.contains("server error"))
            return Category.SERVER;

        if (m.contains("timeout") || m.contains("unable to resolve")
                || m.contains("failed to connect") || m.contains("no address")
                || m.contains("network") || m.contains("socketexception")
                || m.contains("unknownhostexception"))
            return Category.NETWORK;

        return Category.GENERIC;
    }

    /**
     * Terjemahkan pesan teknis GHApi menjadi teks yang ramah user.
     */
    public static String toUserMessage(Category cat, String raw) {
        switch (cat) {
            case AUTH:
                return "Token tidak valid atau tidak punya izin yang diperlukan. "
                     + "Cek token di Settings.";
            case RATE_LIMIT:
                return "GitHub rate limit tercapai. Tunggu beberapa menit lalu coba lagi.";
            case NOT_FOUND:
                return "Resource tidak ditemukan (404). "
                     + "Mungkin sudah dihapus atau tidak punya akses.";
            case SERVER:
                return "GitHub server sedang bermasalah. Coba lagi nanti.";
            case NETWORK:
                return "Tidak bisa terhubung ke GitHub. Cek koneksi internet.";
            default:
                // Return raw message tapi potong jika terlalu panjang
                if (raw != null && raw.length() > 120)
                    return raw.substring(0, 120) + "…";
                return raw != null ? raw : "Terjadi kesalahan tak dikenal.";
        }
    }

    /**
     * Cek apakah error adalah masalah koneksi (bukan logika bisnis).
     * Berguna untuk memutuskan apakah perlu retry.
     */
    public static boolean isRetryable(String message) {
        Category cat = categorize(message);
        return cat == Category.NETWORK || cat == Category.SERVER;
    }
}
