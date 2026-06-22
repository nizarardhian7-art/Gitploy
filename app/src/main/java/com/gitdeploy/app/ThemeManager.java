package com.gitdeploy.app;

import android.content.Context;

/**
 * ThemeManager — Design Token System v3
 *
 * Filosofi:
 *   - 1 Brand Color untuk semua aksi utama (bukan rainbow of accents)
 *   - Semantic colors HANYA untuk status: hijau=sukses, merah=error, kuning=peringatan
 *   - Teks harus kontras, mudah dibaca, tidak melelahkan mata
 *   - Terinspirasi: GitHub, Vercel, Linear
 */
public final class ThemeManager {

    public static final int THEME_DARK  = 0;
    public static final int THEME_LIGHT = 1;

    // ─────────────────────────────────────────────────────────────────────────
    // Neutral Surface Colors
    // ─────────────────────────────────────────────────────────────────────────
    public static int BG;        // latar belakang halaman
    public static int SURFACE;   // kartu, panel
    public static int SURFACE2;  // input field, elemen sekunder
    public static int BORDER;    // garis pemisah halus
    public static int BORDER2;   // garis pemisah lebih tebal
    public static int TEXT;      // teks utama
    public static int TEXT2;     // teks sekunder
    public static int DIM;       // label, placeholder, caption

    // ─────────────────────────────────────────────────────────────────────────
    // Brand Color — satu warna utama untuk SEMUA tombol aksi
    // Dipakai: primaryBtn, roundBtn untuk aksi positif, iconBtn aktif
    // TIDAK dipakai: status indicators
    // ─────────────────────────────────────────────────────────────────────────
    public static int BRAND;     // warna utama: biru elegan
    public static int BRAND_D;   // versi transparan/muted untuk background chip

    // ─────────────────────────────────────────────────────────────────────────
    // Semantic Colors — HANYA untuk indikator status
    // ─────────────────────────────────────────────────────────────────────────
    public static int SUCCESS;   // operasi berhasil
    public static int SUCCESS_D;
    public static int WARNING;   // peringatan, perlu perhatian
    public static int WARNING_D;
    public static int DANGER;    // error, destruktif, kritis
    public static int DANGER_D;

    // ─────────────────────────────────────────────────────────────────────────
    // Legacy Aliases — agar kode lama tidak break total
    // Dialihkan ke token yang semantically tepat
    // ─────────────────────────────────────────────────────────────────────────
    // Semua alias dihitung dinamis di apply(), jangan set manual
    public static int CYAN;    // → BRAND
    public static int CYAN_D;  // → BRAND_D
    public static int GREEN;   // → SUCCESS
    public static int GREEN_D; // → SUCCESS_D
    public static int AMBER;   // → WARNING
    public static int AMBER_D; // → WARNING_D
    public static int RED;     // → DANGER
    public static int RED_D;   // → DANGER_D
    public static int PURPLE;  // → BRAND (semua purple dialihkan ke brand)
    public static int PURPLE_D;// → BRAND_D

    private static int sCurrentTheme = THEME_DARK;

    // ─────────────────────────────────────────────────────────────────────────
    public static void init(Context ctx) {
        apply(new AppPrefs(ctx).getTheme());
    }

    public static void apply(int theme) {
        sCurrentTheme = theme;

        if (theme == THEME_LIGHT) {
            // ── LIGHT — Soft warm white, easy on the eyes ────────────────────
            BG       = 0xFFF0F2F5;  // warm grey-white, not pure white
            SURFACE  = 0xFFF8F9FB;  // card: slightly off-white
            SURFACE2 = 0xFFE8EBF0;  // input, secondary areas
            BORDER   = 0xFFCDD2DC;  // subtle divider
            BORDER2  = 0xFFB6BCC9;  // stronger border
            TEXT     = 0xFF1A1D23;  // near-black, not harsh
            TEXT2    = 0xFF4A5168;  // medium grey — readable, not heavy
            DIM      = 0xFF8890A4;  // caption, placeholder

            // Brand: softer blue — less electric, more refined
            BRAND    = 0xFF1A6FD4;
            BRAND_D  = 0x1A1A6FD4;

            // Semantic — muted to match soft bg
            SUCCESS  = 0xFF1E7E34;
            SUCCESS_D= 0x1A1E7E34;
            WARNING  = 0xFF8A5C00;
            WARNING_D= 0x1A8A5C00;
            DANGER   = 0xFFBE1E26;
            DANGER_D = 0x1ABE1E26;

        } else {
            // ── DARK — Elegan, low-contrast pada bg, tinggi pada teks ─────────
            BG       = 0xFF0D1117;  // GitHub dark: biru-hitam dalam
            SURFACE  = 0xFF161B22;  // kartu: sedikit lebih terang
            SURFACE2 = 0xFF21262D;  // input, elemen tersier
            BORDER   = 0xFF30363D;  // garis halus
            BORDER2  = 0xFF484F58;  // garis tebal
            TEXT     = 0xFFFFFFFF;  // pure white — max contrast
            TEXT2    = 0xFFB8C4CF;  // light grey — readable secondary
            DIM      = 0xFF6B7885;  // caption, not blending into bg

            // Brand: Biru cerah (terbaca di dark)
            BRAND    = 0xFF58A6FF;  // GitHub dark brand blue
            BRAND_D  = 0x1858A6FF;

            // Semantic
            SUCCESS  = 0xFF3FB950;  // hijau cerah GitHub dark
            SUCCESS_D= 0x183FB950;
            WARNING  = 0xFFD29922;  // kuning-emas
            WARNING_D= 0x18D29922;
            DANGER   = 0xFFF85149;  // merah cerah GitHub dark
            DANGER_D = 0x18F85149;
        }

        // Alias legacy — mapping ke token semantik yang tepat
        CYAN     = BRAND;    CYAN_D   = BRAND_D;
        GREEN    = SUCCESS;  GREEN_D  = SUCCESS_D;
        AMBER    = WARNING;  AMBER_D  = WARNING_D;
        RED      = DANGER;   RED_D    = DANGER_D;
        PURPLE   = BRAND;    PURPLE_D = BRAND_D;  // purple → brand
    }

    public static void save(Context ctx, int theme) {
        new AppPrefs(ctx).saveTheme(theme);
        apply(theme);
    }

    public static int current() { return sCurrentTheme; }

    public static String name(int theme) {
        return theme == THEME_LIGHT ? "Terang" : "Gelap";
    }

    public static String icon(int theme) {
        return theme == THEME_LIGHT ? "☀" : "◐";
    }
}
