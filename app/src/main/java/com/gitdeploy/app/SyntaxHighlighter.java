package com.gitdeploy.app;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.widget.EditText;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SyntaxHighlighter — real-time syntax highlighting untuk Code Editor.
 *
 * Cara kerja:
 *  1. {@link #attach(EditText, String)} → pasang TextWatcher ke EditText.
 *  2. Setiap kali user mengetik, highlight di-debounce 400ms agar tidak
 *     memberatkan UI (tidak highlight per-karakter).
 *  3. Highlight hanya dijalankan pada file yang ukurannya ≤ MAX_CHARS
 *     untuk mencegah OOM / ANR pada file sangat besar.
 *
 * Warna:
 *   KEYWORD   → Ungu/Pink  #CC44FF
 *   STRING    → Kuning/Emas #FFB800
 *   COMMENT   → Abu-abu DIM #55556A
 *   NUMBER    → Merah muda  #FF6688
 *   ANNOTATION→ Orange     #FF8855
 *   CLASS     → Biru muda  #88DDFF
 *   DEFAULT   → Hijau      #44FF88
 *
 * Bahasa yang didukung (auto-detect dari ekstensi file):
 *   Java, Kotlin, Groovy/Gradle → keyword + comment + string + class + annotation
 *   JSON   → keys, values, boolean, number
 *   XML    → tags, attributes, values, comments
 *   YAML   → keys, values, boolean, comment
 *   Lainnya → Java mode sebagai fallback
 */
public final class SyntaxHighlighter {

    /** Callback untuk highlightAsync — dipanggil di UI thread. */
    public interface HighlightCallback {
        void onHighlighted(CharSequence result);
    }

    // ── OOM guard ─────────────────────────────────────────────────────────────
    private static final int MAX_CHARS = 60_000;

    // ── Debounce delay (ms) — highlight dipicu setelah user berhenti mengetik ─
    private static final int DEBOUNCE_MS = 400;

    // ── Warna (dark-terminal palette) ─────────────────────────────────────────
    static final int C_DEFAULT  = 0xFF44FF88;   // hijau — teks biasa
    static final int C_KEYWORD  = 0xFFCC44FF;   // ungu/pink — keywords
    static final int C_STRING   = 0xFFFFB800;   // amber/gold — string literals
    static final int C_COMMENT  = 0xFF55556A;   // abu-abu DIM — comments
    static final int C_NUMBER   = 0xFFFF6688;   // merah muda — angka
    static final int C_ANNOT    = 0xFFFF8855;   // orange — @Annotation
    static final int C_CLASS    = 0xFF88DDFF;   // biru muda — ClassName
    static final int C_JSON_KEY = 0xFF00E5FF;   // cyan — JSON keys
    static final int C_XML_TAG  = 0xFF00E5FF;   // cyan — <Tag>
    static final int C_XML_ATTR = 0xFFFFB800;   // amber — attribute="..."
    static final int C_BOOL     = 0xFFCC44FF;   // ungu — true/false/null

    // ── Java/Kotlin keywords ──────────────────────────────────────────────────
    private static final Set<String> JAVA_KW = new HashSet<>(Arrays.asList(
        // Java
        "abstract","assert","boolean","break","byte","case","catch","char","class",
        "const","continue","default","do","double","else","enum","extends","final",
        "finally","float","for","goto","if","implements","import","instanceof","int",
        "interface","long","native","new","package","private","protected","public",
        "return","short","static","strictfp","super","switch","synchronized","this",
        "throw","throws","transient","try","var","void","volatile","while",
        "true","false","null",
        // Kotlin extras
        "fun","val","when","object","companion","data","sealed","suspend","override",
        "open","lateinit","by","in","out","is","as","where","init","constructor",
        "get","set","reified","inline","tailrec","external","actual","expect",
        "crossinline","noinline","it"
    ));

    // ── Pre-compiled patterns — static untuk menghindari recompile tiap keystroke
    private static final Pattern P_BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern P_LINE_COMMENT   = Pattern.compile("//[^\n]*");
    private static final Pattern P_STRING_DQ      = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"");
    private static final Pattern P_STRING_SQ      = Pattern.compile("'([^'\\\\]|\\\\.)*'");
    private static final Pattern P_ANNOTATION     = Pattern.compile("@[A-Z][A-Za-z0-9_]*");
    private static final Pattern P_CLASS_NAME     = Pattern.compile("\\b[A-Z][A-Za-z0-9_]+\\b");
    private static final Pattern P_NUMBER         = Pattern.compile(
            "\\b(0x[0-9A-Fa-f]+[lL]?|\\d+\\.?\\d*[fFdDlL]?)\\b");
    private static final Pattern P_WORD           = Pattern.compile(
            "\\b[a-zA-Z_$][a-zA-Z0-9_$]*\\b");

    private static final Pattern P_JSON_STRING    = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"");
    private static final Pattern P_JSON_KEY       = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"(?=\\s*:)");
    private static final Pattern P_JSON_NUMBER    = Pattern.compile("(?<![\"\\w])-?\\d+\\.?\\d*([eE][+-]?\\d+)?");
    private static final Pattern P_JSON_BOOL      = Pattern.compile("\\b(true|false|null)\\b");

    private static final Pattern P_XML_COMMENT    = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern P_XML_ATTR_VAL   = Pattern.compile("=\"[^\"]*\"");
    private static final Pattern P_XML_TAG        = Pattern.compile("</?[A-Za-z][A-Za-z0-9_.:]*");
    private static final Pattern P_XML_CLOSE_TAG  = Pattern.compile("/>|</[A-Za-z][A-Za-z0-9_.:]*>");
    private static final Pattern P_XML_ATTR_NAME  = Pattern.compile(
            "\\b[a-zA-Z_:][a-zA-Z0-9_:.-]*(?=\\s*=)");

    private static final Pattern P_YAML_COMMENT   = Pattern.compile("#[^\n]*");
    private static final Pattern P_YAML_STRING_DQ = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"");
    private static final Pattern P_YAML_STRING_SQ = Pattern.compile("'[^']*'");
    private static final Pattern P_YAML_KEY       = Pattern.compile(
            "^\\s*[A-Za-z_][A-Za-z0-9_. -]*(?=\\s*:)", Pattern.MULTILINE);
    private static final Pattern P_YAML_BOOL      = Pattern.compile(
            "\\b(true|false|yes|no|null|~)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_YAML_NUMBER    = Pattern.compile("(?<![\"'\\w])-?\\d+\\.?\\d*");

    // ─────────────────────────────────────────────────────────────────────────
    // Static utility — one-shot highlight (untuk load awal)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Versi async dari {@link #highlight}: jalankan di background thread,
     * kirim hasil ke UI thread via callback.
     *
     * Penggunaan:
     * <pre>
     *   SyntaxHighlighter.highlightAsync(code, filename, result ->
     *       editor.setText(result, EditText.BufferType.SPANNABLE));
     * </pre>
     *
     * @param code     source code yang akan di-highlight
     * @param filename nama file (untuk deteksi bahasa)
     * @param callback dipanggil di UI thread dengan hasil CharSequence
     */
    public static void highlightAsync(String code, String filename,
                                      HighlightCallback callback) {
        Handler uiHandler = new Handler(Looper.getMainLooper());
        AppExecutors.getInstance().computation().execute(() -> {
            CharSequence result = highlight(code, filename);
            uiHandler.post(() -> callback.onHighlighted(result));
        });
    }

    /**
     * Highlight {@code code} berdasarkan ekstensi {@code filename}.
     * Returns SpannableStringBuilder siap pakai untuk {@code setText()}.
     * Runs on the calling thread — use {@link #highlightAsync} for UI calls.
     */
    public static CharSequence highlight(String code, String filename) {
        if (code == null || code.isEmpty()) return "";
        if (code.length() > MAX_CHARS) {
            // File terlalu besar — return plain green
            SpannableStringBuilder ssb = new SpannableStringBuilder(code);
            ssb.setSpan(new ForegroundColorSpan(C_DEFAULT), 0, ssb.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return ssb;
        }
        String ext = ext(filename);
        switch (ext) {
            case "json":                      return highlightJson(code);
            case "xml": case "html":
            case "htm": case "svg":           return highlightXml(code);
            case "yml": case "yaml":          return highlightYaml(code);
            default:                          return highlightJava(code);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TextWatcher — real-time (debounced) highlight
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pasang SyntaxHighlighter ke {@code editor}.
     * Setiap kali user mengetik, highlight di-debounce 400 ms sehingga
     * tidak memberatkan typing experience.
     *
     * @param editor   EditText tempat menulis kode
     * @param filename nama file (dipakai untuk deteksi bahasa)
     * @return TextWatcher yang sudah dipasang (bisa dilepas dengan removeTextChangedListener)
     */
    public static TextWatcher attach(EditText editor, String filename) {
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable[] pending = {null};
        boolean[] busy     = {false}; // cegah re-entrancy

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (busy[0]) return; // sedang apply spans → skip
                if (pending[0] != null) handler.removeCallbacks(pending[0]);
                pending[0] = () -> {
                    String text = s.toString();
                    if (text.isEmpty() || text.length() > MAX_CHARS) return;
                    // Jalankan highlight di background, apply spans di UI thread
                    AppExecutors.getInstance().computation().execute(() -> {
                        CharSequence highlighted = highlight(text, filename);
                        handler.post(() -> {
                            if (busy[0]) return;
                            busy[0] = true;
                            try {
                                // Cek panjang masih cocok (user mungkin sudah edit lagi)
                                if (s.length() != text.length()) return;
                                // Hapus spans lama
                                ForegroundColorSpan[] old = s.getSpans(0, s.length(),
                                    ForegroundColorSpan.class);
                                for (ForegroundColorSpan sp : old) s.removeSpan(sp);
                                // Apply spans baru
                                if (highlighted instanceof SpannableStringBuilder) {
                                    SpannableStringBuilder ssb = (SpannableStringBuilder) highlighted;
                                    ForegroundColorSpan[] spans = ssb.getSpans(0, ssb.length(),
                                        ForegroundColorSpan.class);
                                    for (ForegroundColorSpan sp : spans) {
                                        int start2 = ssb.getSpanStart(sp);
                                        int end    = ssb.getSpanEnd(sp);
                                        if (start2 >= 0 && end <= s.length() && start2 < end) {
                                            s.setSpan(new ForegroundColorSpan(sp.getForegroundColor()),
                                                start2, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                                        }
                                    }
                                }
                            } finally {
                                busy[0] = false;
                            }
                        });
                    });
                handler.postDelayed(pending[0], DEBOUNCE_MS);
                };
            }
        };
        editor.addTextChangedListener(watcher);
        return watcher;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Language highlighters
    // ─────────────────────────────────────────────────────────────────────────

    static SpannableStringBuilder highlightJava(String code) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(code);
        paint(ssb, 0, ssb.length(), C_DEFAULT);           // base: semua hijau

        // Urutan penting: comments & strings HARUS di-apply TERAKHIR agar tidak
        // di-override oleh keyword pass yang ada di tengah string/comment.
        applyRe(ssb, P_BLOCK_COMMENT, C_COMMENT);         // /* ... */
        applyRe(ssb, P_LINE_COMMENT,  C_COMMENT);         // // ...
        applyRe(ssb, P_STRING_DQ,     C_STRING);          // "string"
        applyRe(ssb, P_STRING_SQ,     C_STRING);          // 'char'
        applyRe(ssb, P_ANNOTATION,    C_ANNOT);           // @Annotation
        applyKeywords(ssb, JAVA_KW,   C_KEYWORD);         // keywords
        applyRe(ssb, P_CLASS_NAME,    C_CLASS);           // ClassName
        applyRe(ssb, P_NUMBER,        C_NUMBER);          // 0x1F, 3.14f

        // Re-apply comments & strings di atas keyword pass (override precedence)
        applyRe(ssb, P_BLOCK_COMMENT, C_COMMENT);
        applyRe(ssb, P_LINE_COMMENT,  C_COMMENT);
        applyRe(ssb, P_STRING_DQ,     C_STRING);
        applyRe(ssb, P_STRING_SQ,     C_STRING);
        return ssb;
    }

    private static SpannableStringBuilder highlightJson(String code) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(code);
        paint(ssb, 0, ssb.length(), C_DEFAULT);
        applyRe(ssb, P_JSON_STRING, C_STRING);
        applyRe(ssb, P_JSON_KEY,    C_JSON_KEY);     // override string → cyan untuk keys
        applyRe(ssb, P_JSON_BOOL,   C_BOOL);
        applyRe(ssb, P_JSON_NUMBER, C_NUMBER);
        return ssb;
    }

    private static SpannableStringBuilder highlightXml(String code) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(code);
        paint(ssb, 0, ssb.length(), C_DEFAULT);
        applyRe(ssb, P_XML_COMMENT,   C_COMMENT);
        applyRe(ssb, P_XML_ATTR_VAL,  C_STRING);
        applyRe(ssb, P_XML_TAG,       C_XML_TAG);
        applyRe(ssb, P_XML_CLOSE_TAG, C_XML_TAG);
        applyRe(ssb, P_XML_ATTR_NAME, C_XML_ATTR);
        applyRe(ssb, P_XML_COMMENT,   C_COMMENT);   // re-apply supaya menimpa tag
        return ssb;
    }

    private static SpannableStringBuilder highlightYaml(String code) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(code);
        paint(ssb, 0, ssb.length(), C_DEFAULT);
        applyRe(ssb, P_YAML_STRING_DQ, C_STRING);
        applyRe(ssb, P_YAML_STRING_SQ, C_STRING);
        applyRe(ssb, P_YAML_KEY,       C_JSON_KEY);
        applyRe(ssb, P_YAML_BOOL,      C_BOOL);
        applyRe(ssb, P_YAML_NUMBER,    C_NUMBER);
        applyRe(ssb, P_YAML_COMMENT,   C_COMMENT);
        return ssb;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void applyRe(SpannableStringBuilder ssb, Pattern p, int color) {
        Matcher m = p.matcher(ssb);
        while (m.find()) paint(ssb, m.start(), m.end(), color);
    }

    private static void applyKeywords(SpannableStringBuilder ssb, Set<String> kw, int color) {
        Matcher m = P_WORD.matcher(ssb);
        while (m.find()) {
            if (kw.contains(m.group())) paint(ssb, m.start(), m.end(), color);
        }
    }

    private static void paint(SpannableStringBuilder ssb, int s, int e, int color) {
        if (s < 0 || e > ssb.length() || s >= e) return;
        ssb.setSpan(new ForegroundColorSpan(color), s, e,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
