package com.gitdeploy.app;

import android.app.NotificationManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


// IMPROVEMENT #1: EncryptedSharedPreferences (used in AppPrefs)
// IMPROVEMENT #3: ExecutorService pools
// Note: AppExecutors, SyntaxHighlighter, UploadForegroundService are in same package
// — no explicit imports needed for same-package classes in Java.

public class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    // Colors read directly from ThemeManager.

    // ── State ────────────────────────────────────────────────────────────────
    final Handler         mHandler  = new Handler(Looper.getMainLooper());
    final Stack<Runnable> mNavStack = new Stack<>();

    // ── Root container — satu FrameLayout permanen yang tidak pernah diganti.
    // Semua "halaman" di-swap sebagai child-nya sehingga TransitionManager
    // bisa melihat state lama → state baru dan menganimasikannya dengan benar.
    FrameLayout mRootContainer;
    AppPrefs              mPrefs;
    UiHelpers             mUi;
    DialogHelper          mDlg;
    NotificationHelper    mNotif;
    AiManager             mAi;
    AutoSignManager       mAutoSign;
    SmartSyncManager      mSync;

    String  mCurrentRepo   = "";
    String  mCurrentOwner  = "";
    String  mCurrentBranch = "main";

    // ── Scroll-position memory (persists across navigateTo/Back) ─────────────
   // ── Scroll-position memory (persists across navigateTo/Back) ─────────────
// Key examples: "tab_0_0" (Files), "tab_1_0" (Builds), "commit_history"
final java.util.Map<String, Integer> mScrollStates = new java.util.HashMap<>();
// Reference to the active tab's ScrollView in buildRepoPageNew — used by
// child pages (file rows, commit cards) to snapshot the scroll Y before push.
ScrollView mCurrentTabSv = null;
// Reference to the commit-history page ScrollView — used by buildCommitCard.
ScrollView mCommitHistorySv = null;

// ── Double-tap back to exit ─────────────────────────────────────────────
private long mBackPressedTime = 0;

    // ── In-memory cache ───────────────────────────────────────────────────────
    // Repo list — dikelola ViewModel, tahan rotasi layar
    RepoListViewModel mRepoViewModel;
    // File list cache: key = "owner/repo/path"
    final android.util.LruCache<String, java.util.List<GHApi.RepoContent>> mCachedFiles =
        new android.util.LruCache<>(50);
    // File content cache: key = "owner/repo/path"
    final android.util.LruCache<String, String> mCachedContent =
        new android.util.LruCache<>(30);
    // Builds cache: key = "owner/repo"
    java.util.List<GHApi.WorkflowRun>    mCachedRuns      = null;
    String                               mCachedRunsKey   = "";

    String cacheKey(String path) {
        return mCurrentOwner + "/" + mCurrentRepo + "/" + path;
    }
    private void invalidateRepoCache() {
        mCachedFiles.evictAll(); mCachedContent.evictAll(); mCachedRuns = null;
    }

    private static final int REQ_PICK_FILE     = 1001;
    private static final int REQ_PICK_KEYSTORE  = 1002;
    private static final int REQ_PICK_FOLDER    = 1003;
    String  mPendingUploadRepo     = "";
    String  mPendingUploadPath     = "";
    String  mPendingUploadMsg      = "";
    String  mPendingUploadBranch   = "";
    String  mPendingUploadBasePath = "";
    boolean mPendingUploadZipMode  = false;
    private boolean mPendingSyncMode       = false;
    private boolean mPendingRawZipUpload   = false; // upload ZIP as-is, no extract

    // ── Notifications ────────────────────────────────────────────────────────
    static final String NOTIF_CH          = "gd_bg";
    static final int    NOTIF_UPLOAD       = 1001;
    static final int    NOTIF_MANAGE       = 1002;
    static final int    NOTIF_BUILD_RUNNING = 1003;
    NotificationManager mNotifMgr;

    // ── Build Polling ────────────────────────────────────────────────────────
    java.util.concurrent.ScheduledExecutorService mPollExecutor;
    final java.util.Map<Long, String> mLastRunStatus = new java.util.HashMap<>();
    LinearLayout mRunListContainer;

    // ── In-App Status Bar ────────────────────────────────────────────────────
    LinearLayout mStatusBar;
    TextView     mStatusBarText;

    // ── Error Track ──────────────────────────────────────────────────────────
    java.util.List<GHApi.ErrorFileRef> mTrackedErrors = new java.util.ArrayList<>();
    GHApi.WorkflowRun                  mLastFailedRun;

    // ── AI Chat ───────────────────────────────────────────────────────────────
    // Each entry: [role, content] — "user" or "assistant"
    final java.util.List<String[]>     mChatHistory  = new java.util.ArrayList<>();
    String                             mChatContext  = ""; // pre-loaded context (from Track)

    // ── Notifications ─────────────────────────────────────────────────────────
    private int                                    mUnreadNotifCount = 0;
    private TextView                               mNotifBadge;       // home page badge
    private java.util.List<GHApi.GitNotification>  mCachedNotifs = null;
    private long                                   mLastNotifPoll = 0;

    // App expiry: update this date when releasing a new version
    // ── App expiry ─────────────────────────────────────────────────────────
    // Change this number to set how many days the app is valid from BUILD date.
    // BUILD_TIME_MS di-inject otomatis oleh Gradle di build.gradle — tidak perlu edit manual.
    // Examples: 30 = 1 bulan, 90 = 3 bulan, 365 = 1 tahun, 0 = tidak pernah expire
    private static final int  APP_VALID_DAYS = 15;
    private static final long APP_EXPIRY_MS  = BuildConfig.BUILD_TIME_MS
                                               + (long) APP_VALID_DAYS * 24 * 60 * 60 * 1000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ── Setup single persistent root container PERTAMA KALI ──────────────
        // Setelah ini, semua setContentView(view) di-intercept oleh override di bawah
        // dan di-swap sebagai child dari mRootContainer — bukan ganti Activity content.
        mRootContainer = new FrameLayout(this);
        mRootContainer.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        super.setContentView(mRootContainer);

        mUi      = new UiHelpers(this);
        mDlg     = new DialogHelper(this);
        mNotif   = new NotificationHelper(this);
        mAi      = new AiManager(this);
        mAutoSign = new AutoSignManager(this);
        mSync     = new SmartSyncManager(this);
        // Check app expiry before anything else
        if (System.currentTimeMillis() > APP_EXPIRY_MS) {
            setContentView(buildExpiredPage());
            // Auto-buka Telegram setelah 3 detik — tanpa perlu klik
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://t.me/modfreew")));
                } catch (Exception ignored) {}
                finish(); // tutup app setelah redirect
            }, 3000);
            return;
        }
        mPrefs = new AppPrefs(this);
        // FITUR 3: Apply saved theme before any UI is built
        ThemeManager.init(this);
        setupNotifications();
        getWindow().setStatusBarColor(ThemeManager.BG);
        // ViewModel dikelola ViewModelProvider — otomatis survive rotasi
        mRepoViewModel = new androidx.lifecycle.ViewModelProvider(this)
            .get(RepoListViewModel.class);
        if (mPrefs.hasToken()) {
            // Mulai fetch (ViewModel cegah double-fetch otomatis)
            mRepoViewModel.fetchRepos(mPrefs.getToken());
            navigateTo(() -> setContentView(buildHomePage()));
        } else {
            navigateTo(() -> setContentView(buildSetupPage()));
        }
    }

    @Override public void onBackPressed() {
    if (mNavStack.size() > 1) { 
        mNavStack.pop(); 
        mNavStack.peek().run(); 
    }
    else {
        // Double-tap back to exit
        if (mBackPressedTime + 2000 > System.currentTimeMillis()) {
            finish();
        } else {
            mBackPressedTime = System.currentTimeMillis();
            toast("double tab to exit");
        }
    }
}

    @Override
    protected void onPause() {
        super.onPause();
        UploadForegroundService.sListener = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (UploadForegroundService.isRunning()) {
            // Upload still running while we were away — show status bar and
            // re-attach listener so we get the completion callback
            if (UploadForegroundService.sListener == null) {
                showStatusBar("⟳  Upload in progress...", ThemeManager.BRAND);
                UploadForegroundService.sListener = new UploadForegroundService.ProgressListener() {
                    @Override public void onProgress(int cur, int tot, String f) {
                        showStatusBar("⬆  " + cur + "/" + tot + "  " + new java.io.File(f).getName(),
                            ThemeManager.BRAND);
                    }
                    @Override public void onComplete(int ok, int fail, java.util.List<String> failed) {
                        hideStatusBar(0);
                        UploadForegroundService.sListener = null;
                        if (fail == 0) {
                            showNotifResult("✓ Upload Complete", ok + " file(s) uploaded");
                        } else {
                            // Tampilkan daftar file gagal dengan warna merah
                            StringBuilder sb = new StringBuilder();
                            sb.append(ok).append(" file(s) uploaded");
                            if (!failed.isEmpty()) {
                                sb.append("\n\n⚠ ").append(fail).append(" file(s) FAILED:\n");
                                for (String f2 : failed)
                                    sb.append("• ").append(new java.io.File(f2).getName()).append("\n");
                            }
                            mDlg.showErrRed("⚠ Partial Upload", sb.toString().trim());
                        }
                    }
                    @Override public void onError(String msg) {
                        hideStatusBar(0);
                        UploadForegroundService.sListener = null;
                        showNotifResult("✗ Upload Failed", msg);
                    }
                };
            }
        } else {
            // Service not running — make sure status bar is cleared
            // (handles case where upload finished while app was in background)
            hideStatusBar(0);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBuildPolling();
        // Lepas observer supaya tidak ada reference ke Activity yang mati
        if (mRepoViewModel != null) mRepoViewModel.clearObservers();
        // NOTE: AppExecutors adalah singleton process-scoped.
        // JANGAN shutdown() di sini — Activity bisa recreate (rotasi, theme change)
        // dan akan crash dengan RejectedExecutionException karena pool sudah Terminated.
        // Pool hidup selama proses app, dimatikan otomatis saat proses mati.
    }

    void navigateTo(Runnable page) {
        mNavStack.push(page);
        page.run();
    }

    /**
     * Override setContentView — intercept semua page swap dan animasikan.
     *
     * Cara kerja:
     *   - Semua buildXxxPage() mengembalikan View dan dipanggil via setContentView(view).
     *   - Override ini mengarahkan view ke mRootContainer (bukan Activity window).
     *   - TransitionManager bisa lihat state lama → baru karena container-nya tetap.
     *   - Hasilnya: fade animasi 180ms di setiap perpindahan halaman secara otomatis,
     *     tanpa perlu ubah satu pun call-site di seluruh kodebase.
     */
    @Override
    public void setContentView(View view) {
        if (mRootContainer == null) {
            // Fallback safety — seharusnya tidak terjadi
            super.setContentView(view);
            return;
        }
        android.transition.Fade fade = new android.transition.Fade();
        fade.setDuration(170);
        android.transition.TransitionManager.beginDelayedTransition(mRootContainer, fade);
        mRootContainer.removeAllViews();
        mRootContainer.addView(view, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
    }

/**
 * Navigate FROM the More tab — ensures back always returns to More tab
 * instead of whatever group was open when repo page was first entered.
 */
private void navigateFromMore(Runnable page) {
    if (!mNavStack.isEmpty()) mNavStack.pop();
    mNavStack.push(() -> setContentView(buildRepoPageNew(3, 0)));
    navigateTo(page);
}

/** Replace top of nav stack (no new entry) — use for folder drilldown so
 *  back always returns to parent folder rather than growing stack unboundedly. */
private void replaceNavigateTo(Runnable page) {
    if (!mNavStack.isEmpty()) mNavStack.pop();
    mNavStack.push(page);
    page.run();
}

    @Override
    protected void onActivityResult(int req, int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (req == REQ_PICK_FILE)     handleFileSelected(uri);
        if (req == REQ_PICK_KEYSTORE) handleKeystoreSelected(uri);
        if (req == REQ_PICK_FOLDER)   handleFolderSync(uri);
    }

    // =========================================================================
    // SETUP PAGE
    // =========================================================================
    private View buildSetupPage() {
        LinearLayout root = lv();
        root.setBackgroundColor(ThemeManager.BG);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(60), dp(28), dp(40));

        // Logo
        FrameLayout logoWrap = new FrameLayout(this);
        LinearLayout.LayoutParams lwLp = new LinearLayout.LayoutParams(dp(80), dp(80));
        lwLp.gravity = Gravity.CENTER_HORIZONTAL;
        lwLp.bottomMargin = dp(20);
        logoWrap.setLayoutParams(lwLp);
        logoWrap.setBackground(rb(ThemeManager.BRAND, 20));  // Logo: brand color background
        TextView logoEmoji = new TextView(this);
        logoEmoji.setText("↑");
        logoEmoji.setTextSize(30f); logoEmoji.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); logoEmoji.setTextColor(0xFFFFFFFF);
        logoEmoji.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        logoEmoji.setLayoutParams(elp);
        logoWrap.addView(logoEmoji);
        root.addView(logoWrap);

        TextView appName = tv("GitDeploy", ThemeManager.TEXT, 28f, true);
        appName.setGravity(Gravity.CENTER);
        appName.setLetterSpacing(-0.02f);
        root.addView(appName);

        TextView appSub = tv("GitHub CI/CD from your phone.", ThemeManager.DIM, 12f, false);
        appSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4); subLp.bottomMargin = dp(40);
        appSub.setLayoutParams(subLp);
        root.addView(appSub);

        // Card
        LinearLayout card = lv();
        card.setLayoutParams(mpWrap());
        card.setBackground(rbs(ThemeManager.SURFACE, 18, ThemeManager.BORDER));
        card.setPadding(dp(20), dp(24), dp(20), dp(24));

        card.addView(tv("Personal Access Token", ThemeManager.TEXT, 14f, true));
        TextView hint = tv("GitHub → Settings → Developer settings → Tokens (classic). Required scopes: repo · workflow", ThemeManager.DIM, 10f, false);
        hint.setLineSpacing(dp(2), 1.3f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(6); hLp.bottomMargin = dp(16);
        hint.setLayoutParams(hLp);
        card.addView(hint);

        final EditText tokenEt = styledInput("ghp_xxxxxxxxxxxx", true);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.bottomMargin = dp(16);
        tokenEt.setLayoutParams(etLp);
        card.addView(tokenEt);

        final TextView btn = primaryBtn("Connect", ThemeManager.BRAND);
        btn.setOnClickListener(v -> {
            String tok = tokenEt.getText().toString().trim();
            if (tok.isEmpty()) { toast("Enter your token first."); return; }
            btn.setText("Verifying...");
            btn.setEnabled(false);
            GHApi.validateToken(tok, (username, err) -> mHandler.post(() -> {
                btn.setEnabled(true);
                if (err != null) { btn.setText("Connect"); showErr("Invalid token:\n" + err); }
                else {
                    mPrefs.saveToken(tok);
                    mPrefs.saveUsername(username);
                    toast("Connected as @" + username);
                    mNavStack.clear();
                    navigateTo(() -> setContentView(buildHomePage()));
                }
            }));
        });
        card.addView(btn);
        root.addView(card);
        return root;
    }

    // =========================================================================
    // HOME PAGE
    // =========================================================================
    private View buildHomePage() {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);

        // ── Custom Home Header (no truncation, app brand feel) ───────────────
        LinearLayout header = lv();
        header.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout headerRow = lh(Gravity.CENTER_VERTICAL);
        headerRow.setBackgroundColor(ThemeManager.SURFACE);
        headerRow.setPadding(dp(14), dp(11), dp(10), dp(11));
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // App logo — matches launcher icon (dark bg + GitHub octocat emoji)
        android.widget.FrameLayout logoCircle = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        lcLp.rightMargin = dp(10); logoCircle.setLayoutParams(lcLp);
        // Dark background matching icon
        logoCircle.setBackground(rb(0xFF1C2128, 10));
        // GitHub octocat emoji as logo
        TextView logoTv = new TextView(this);
        logoTv.setText("\uD83D\uDC31"); // 🐱 cat = closest to octocat
        logoTv.setTextSize(17f);
        logoTv.setGravity(Gravity.CENTER);
        logoTv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        // Green deploy dot badge (bottom-right)
        android.widget.FrameLayout.LayoutParams dotLp =
            new android.widget.FrameLayout.LayoutParams(dp(10), dp(10));
        dotLp.gravity = Gravity.BOTTOM | Gravity.END;
        android.view.View greenDot = new android.view.View(this);
        greenDot.setLayoutParams(dotLp);
        greenDot.setBackground(rb(ThemeManager.GREEN, 5));
        logoCircle.addView(logoTv);
        logoCircle.addView(greenDot);
        headerRow.addView(logoCircle);

        // App name + username (takes all remaining space)
        LinearLayout titleBlock = lv();
        titleBlock.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView appNameTv = tv("GitDeploy", ThemeManager.TEXT, 15f, true);
        appNameTv.setMaxLines(1);
        titleBlock.addView(appNameTv);
        TextView usernameTv = tv("@" + mPrefs.getUsername(), ThemeManager.DIM, 10f, false);
        usernameTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        usernameTv.setMaxLines(1);
        usernameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBlock.addView(usernameTv);
        headerRow.addView(titleBlock);

       // Action buttons (fixed size, won't steal space from title)
LinearLayout headerActs = lh(Gravity.CENTER_VERTICAL);
headerActs.setLayoutParams(new LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
headerActs.addView(iconBtn("👤", ThemeManager.BRAND, () -> navigateTo(() -> setContentView(buildProfilePage()))));
headerActs.addView(iconBtn("🔑", ThemeManager.BRAND, () -> navigateTo(() -> setContentView(buildTokenPage()))));
headerActs.addView(iconBtn("↻", ThemeManager.BRAND, () -> {
    mRepoViewModel.invalidate(); // clear cache, fetch akan terjadi di buildHomePage
    mLastNotifPoll = 0; // Reset timer notifikasi agar badge langsung ter-update
    mNavStack.clear(); navigateTo(() -> setContentView(buildHomePage()));
}));
headerActs.addView(iconBtn("＋", ThemeManager.GREEN, () -> showCreateRepoDialog()));
        headerRow.addView(headerActs);

        header.addView(headerRow);
        // Divider
        View hDivider = new View(this);
        hDivider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        hDivider.setBackgroundColor(ThemeManager.BORDER);
        header.addView(hDivider);

        // Notification bell with unread badge
        android.widget.FrameLayout bellWrap = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams bellWrapLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        bellWrapLp.leftMargin = dp(6);
        bellWrap.setLayoutParams(bellWrapLp);
        TextView bellBtn = tv("🔔", ThemeManager.DIM, 16f, false);
        bellBtn.setGravity(Gravity.CENTER);
        bellBtn.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        bellBtn.setBackground(rbs(ThemeManager.SURFACE2, 10, ThemeManager.BORDER));
        bellBtn.setClickable(true);
        bellBtn.setOnClickListener(v -> navigateTo(() -> setContentView(buildNotificationsPage())));
        applyRipple(bellBtn, 10);
        bellWrap.addView(bellBtn);

        // Unread badge
        mNotifBadge = tv("", ThemeManager.BG, 7.5f, true);
        mNotifBadge.setGravity(Gravity.CENTER);
        mNotifBadge.setBackground(rb(ThemeManager.DANGER, 8));
        android.widget.FrameLayout.LayoutParams badgeLp = new android.widget.FrameLayout.LayoutParams(dp(16), dp(16));
        badgeLp.gravity = Gravity.TOP | Gravity.END;
        mNotifBadge.setLayoutParams(badgeLp);
        mNotifBadge.setVisibility(mUnreadNotifCount > 0 ? View.VISIBLE : View.GONE);
        if (mUnreadNotifCount > 0) mNotifBadge.setText(mUnreadNotifCount > 9 ? "9+" : String.valueOf(mUnreadNotifCount));
        bellWrap.addView(mNotifBadge);
        headerActs.addView(bellWrap);

        outer.addView(header);

        // Poll unread count silently in background (max once per 5 min)
        long now = System.currentTimeMillis();
        if (now - mLastNotifPoll > 5 * 60 * 1000L) {
            mLastNotifPoll = now;
            GHApi.getUnreadCount(mPrefs.getToken(), (count, err) -> mHandler.post(() -> {
                if (err != null || count == null) return;
                mUnreadNotifCount = count;
                if (mNotifBadge != null) {
                    mNotifBadge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
                    if (count > 0) mNotifBadge.setText(count > 9 ? "9+" : String.valueOf(count));
                }
            }));
        }

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG);
        sv.setLayoutParams(mpWrap());

        LinearLayout c = lv();
        c.setPadding(dp(16), dp(12), dp(16), dp(40));

        // ── Search bar (single entry point to Explore) ───────────────────────
        LinearLayout searchBar = lh(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER2));
        searchBar.setPadding(dp(16), dp(13), dp(16), dp(13));
        searchBar.setClickable(true);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sbLp.bottomMargin = dp(16); searchBar.setLayoutParams(sbLp);
        // Icon
        TextView searchIcon = tv("🔍", ThemeManager.CYAN, 15f, false);
        LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        siLp.rightMargin = dp(10); searchIcon.setLayoutParams(siLp);
        searchBar.addView(searchIcon);
        // Hint text
        LinearLayout searchTexts = lv();
        searchTexts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        searchTexts.addView(tv("Search GitHub", ThemeManager.TEXT, 13f, true));
        searchTexts.addView(tv("Repos, users, trending...", ThemeManager.DIM, 10.5f, false));
        searchBar.addView(searchTexts);
        // Arrow
        TextView searchArrow = tv("›", ThemeManager.CYAN, 20f, false);
        searchBar.addView(searchArrow);
        applyRipple(searchBar, 14);
        searchBar.setOnClickListener(v -> navigateTo(() -> setContentView(buildExplorePage())));
        c.addView(searchBar);

        // ── My Repositories ───────────────────────────────────────────────────
        LinearLayout reposHeader = lh(Gravity.CENTER_VERTICAL);
        reposHeader.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams)reposHeader.getLayoutParams()).bottomMargin = dp(8);
        reposHeader.addView(secLabel("My Repositories"));
        c.addView(reposHeader);

        final LinearLayout repoList = lv();
        repoList.setLayoutParams(mpWrap());
        repoList.setBackground(rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        LinearLayout.LayoutParams rlLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlLp.bottomMargin = dp(20); repoList.setLayoutParams(rlLp);
        TextView loadingTv = tv("Loading repositories...", ThemeManager.DIM, 12f, false);
        loadingTv.setGravity(Gravity.CENTER);
        loadingTv.setPadding(0, dp(32), 0, dp(32));
        repoList.addView(loadingTv);
        c.addView(repoList);

        // ── Starred Repos ─────────────────────────────────────────────────────
        LinearLayout starredHeader = lh(Gravity.CENTER_VERTICAL);
        starredHeader.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams)starredHeader.getLayoutParams()).bottomMargin = dp(8);
        TextView starredLabel = secLabel("★  Starred");
        starredLabel.setTextColor(ThemeManager.AMBER);
        starredLabel.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        starredHeader.addView(starredLabel);
        c.addView(starredHeader);

        final LinearLayout starredList = lv();
        starredList.setLayoutParams(mpWrap());
        starredList.setBackground(rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        LinearLayout.LayoutParams slLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slLp.bottomMargin = dp(20); starredList.setLayoutParams(slLp);
        TextView starLoadingTv = tv("Loading starred repos...", ThemeManager.DIM, 12f, false);
        starLoadingTv.setGravity(Gravity.CENTER);
        starLoadingTv.setPadding(0, dp(24), 0, dp(24));
        starredList.addView(starLoadingTv);
        c.addView(starredList);

        // Load starred repos async
        GHApi.listStarredRepos(mPrefs.getToken(), (starredRepos, starErr) -> mHandler.post(() -> {
            starredList.removeAllViews();
            if (starredRepos == null || starredRepos.isEmpty()) {
                TextView emptyTv = tv(starErr != null ? "Could not load starred repos" : "No starred repos yet",
                    ThemeManager.DIM, 11.5f, false);
                emptyTv.setGravity(Gravity.CENTER);
                emptyTv.setPadding(0, dp(20), 0, dp(20));
                starredList.addView(emptyTv);
                return;
            }
            // Show up to 20 starred repos
            int shown = Math.min(starredRepos.size(), 20);
            for (int i = 0; i < shown; i++) {
                starredList.addView(buildStarredRepoRow(starredRepos.get(i)));
                if (i < shown - 1) starredList.addView(rowDivider());
            }
            if (starredRepos.size() > 20) {
                View div = rowDivider();
                starredList.addView(div);
                TextView moreTv = tv("+" + (starredRepos.size() - 20) + " more — tap Search to view all",
                    ThemeManager.DIM, 11f, false);
                moreTv.setGravity(Gravity.CENTER);
                moreTv.setPadding(0, dp(12), 0, dp(12));
                moreTv.setClickable(true);
                moreTv.setOnClickListener(v -> navigateTo(() -> setContentView(buildExplorePage())));
                applyRipple(moreTv, 0);
                starredList.addView(moreTv);
            }
        }));

        sv.addView(c);
        outer.addView(sv);

        // ── Tampilkan data repo via ViewModel (tahan rotasi) ─────────────────
        // Render data cache yang sudah ada (sync, no flicker)
        List<GHApi.Repo> cachedRepos = mRepoViewModel.getCachedRepos();
        if (cachedRepos != null && !cachedRepos.isEmpty()) {
            repoList.removeAllViews();
            for (int i = 0; i < cachedRepos.size(); i++) {
                repoList.addView(buildRepoRow(cachedRepos.get(i)));
                if (i < cachedRepos.size() - 1) repoList.addView(rowDivider());
            }
        }

        // Set observer sebelum fetch supaya callback langsung ke UI
        final RepoListViewModel.RepoObserver reposObserver = repos -> mHandler.post(() -> {
            if (repos == null || repos.isEmpty()) {
                repoList.removeAllViews();
                TextView empty = tv("No repositories yet.", ThemeManager.DIM, 12f, false);
                empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(32), 0, dp(32));
                repoList.addView(empty);
                return;
            }
            repoList.removeAllViews();
            for (int i = 0; i < repos.size(); i++) {
                repoList.addView(buildRepoRow(repos.get(i)));
                if (i < repos.size() - 1) repoList.addView(rowDivider());
            }
        });

        final RepoListViewModel.ErrorObserver errorObserver = err -> mHandler.post(() -> {
            if (err != null && mRepoViewModel.getCachedRepos() == null) {
                repoList.removeAllViews();
                repoList.setPadding(dp(16), dp(16), dp(16), dp(16));
                repoList.addView(errCard(err));
            }
        });

        mRepoViewModel.setRepoObserver(reposObserver);
        mRepoViewModel.setErrorObserver(errorObserver);

        // Jika cache kosong (habis invalidate) → force fetch, skip TTL check
        boolean forceNetwork = mRepoViewModel.getCachedRepos() == null;
        mRepoViewModel.fetchRepos(mPrefs.getToken(), forceNetwork);

        // Lepas observer saat view ini di-detach — hanya jika masih observer kita
        // (mencegah view lama hapus observer yang sudah di-set view baru)
        outer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                mRepoViewModel.clearObserversIfMatch(reposObserver, errorObserver);
            }
        });

        return outer;
    }

    private View buildRepoRow(final GHApi.Repo repo) {
        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(14), dp(14));
        row.setClickable(true);

        // Color dot
        View dot = new View(this);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dLp.rightMargin = dp(14);
        dot.setLayoutParams(dLp);
        dot.setBackground(rb(repo.isPrivate ? ThemeManager.AMBER : ThemeManager.GREEN, 4));

        LinearLayout info = lv();
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        info.addView(tv(repo.name, ThemeManager.TEXT, 14f, true));
        String desc = repo.description.isEmpty()
            ? (repo.isPrivate ? "Private · " : "Public · ") + repo.defaultBranch
            : repo.description;
        TextView descTv = tv(desc, ThemeManager.DIM, 10.5f, false);
        descTv.setPadding(0, dp(3), 0, 0);
        info.addView(descTv);

        // Badge
        TextView badge = badgeChip(repo.isPrivate ? "PRIVATE" : "PUBLIC",
            repo.isPrivate ? ThemeManager.AMBER : ThemeManager.GREEN,
            repo.isPrivate ? ThemeManager.AMBER_D : ThemeManager.GREEN_D);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(8);
        badge.setLayoutParams(badgeLp);

        TextView arrow = tv("›", ThemeManager.BORDER2, 22f, true);
        arrow.setPadding(dp(6), 0, 0, 0);

        row.addView(dot); row.addView(info); row.addView(badge); row.addView(arrow);

        row.setOnClickListener(v -> {
            stopBuildPolling();           // FIX: hentikan polling repo lama
            mLastRunStatus.clear();       // FIX: reset status map untuk repo baru
            mCurrentRepo   = repo.name;
            // BUG FIX: Pakai owner dari fullName ("owner/repo"), bukan username aktif.
            // Penting untuk repo forked — owner bisa berbeda dengan user login.
            mCurrentOwner  = repo.fullName.contains("/")
                ? repo.fullName.split("/")[0] : mPrefs.getUsername();
            mCurrentBranch = repo.defaultBranch;
            mPrefs.saveLastRepo(repo.name);
            navigateTo(() -> setContentView(buildRepoPage()));
        });
        applyRipple(row); // #5 Ripple effect
        return row;
    }

    /** Compact row for starred repos on home page — taps into explore detail. */
    private View buildStarredRepoRow(final GHApi.SearchRepo repo) {
        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);

        // Lang color dot
        View dot = new View(this);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dLp.rightMargin = dp(12); dot.setLayoutParams(dLp);
        dot.setBackground(rb(repo.language != null && !repo.language.isEmpty()
            ? getLangColor(repo.language) : ThemeManager.AMBER, 5));
        row.addView(dot);

        // Info
        LinearLayout info = lv();
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // owner/name
        LinearLayout nameRow = lh(Gravity.CENTER_VERTICAL);
        TextView ownerTv = tv(repo.owner + " / ", ThemeManager.DIM, 11f, false);
        TextView nameTv  = tv(repo.name, ThemeManager.TEXT, 13f, true);
        nameRow.addView(ownerTv);
        nameRow.addView(nameTv);
        info.addView(nameRow);
        // description
        if (repo.description != null && !repo.description.isEmpty()) {
            TextView descTv = tv(repo.description, ThemeManager.DIM, 10.5f, false);
            descTv.setMaxLines(1);
            descTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            descTv.setPadding(0, dp(2), 0, 0);
            info.addView(descTv);
        }
        row.addView(info);

        // Stars badge
        LinearLayout starBadge = lh(Gravity.CENTER_VERTICAL);
        starBadge.setBackground(rbs(ThemeManager.SURFACE2, 8, ThemeManager.BORDER));
        starBadge.setPadding(dp(7), dp(4), dp(7), dp(4));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sbLp.leftMargin = dp(8); starBadge.setLayoutParams(sbLp);
        starBadge.addView(tv("⭐ " + fmtNum(repo.stars), ThemeManager.AMBER, 10f, true));
        row.addView(starBadge);

        row.addView(tv("›", ThemeManager.DIM, 20f, false));

        row.setOnClickListener(v -> {
            stopBuildPolling();           // FIX: hentikan polling repo lama
            mLastRunStatus.clear();       // FIX: reset status map untuk repo baru
            mCurrentOwner  = repo.owner;
            mCurrentRepo   = repo.name;
            mCurrentBranch = repo.defaultBranch != null && !repo.defaultBranch.isEmpty()
                ? repo.defaultBranch : "main";
            mCachedFiles.evictAll();
            mCachedContent.evictAll();
            mCachedRuns = null;
            navigateTo(() -> setContentView(buildExploreRepoDetailPage(repo)));
        });
        applyRipple(row);
        return row;
    }

    // =========================================================================
    // =========================================================================
    // REPO PAGE — bottom navigation: Code | CI/CD | Collab | More
    // =========================================================================
    private View buildRepoPage() { return buildRepoPageAtTab(0); }

    /**
     * Legacy entry point — maps old 6-tab indices to new bottom-nav groups.
     *   0=Files → Code/Files
     *   1=Builds → CI/CD/Builds
     *   2=Upload → Code/Upload
     *   3=Track  → CI/CD/Track
     *   4=Ask    → Collab/Ask
     *   5=Release→ CI/CD/Release
     */
    View buildRepoPageAtTab(final int legacy) {
        int g = 0, s = 0;
        if (legacy == 1)      { g = 1; s = 0; } // Builds
        else if (legacy == 2) { g = 0; s = 1; } // Upload
        else if (legacy == 3) { g = 1; s = 1; } // Track
        else if (legacy == 4) { g = 2; s = 2; } // Ask (now Collab sub 2)
        else if (legacy == 5) { g = 1; s = 2; } // Release
        return buildRepoPageNew(g, s);
    }

    /**
     * Main repo page.
     * Bottom nav: [Code(0)] [CI/CD(1)] [Collab(2)] [More(3)]
     * Code  : Files(0), Upload(1)
     * CI/CD : Builds(0), Track(1), Release(2)
     * Collab: Ask(0)
     * More  : settings, account, danger zone
     */
    View buildRepoPageNew(final int startGroup, final int startSub) {
        // Root FrameLayout so bottom-nav can float at bottom
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(ThemeManager.BG);

        final int[] activeGroup = {startGroup};
        final int[] activeSub   = {startSub};

        // ── Content area (above bottom nav) ──────────────────────────────────
        LinearLayout contentArea = lv();
        android.widget.FrameLayout.LayoutParams caLp = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        caLp.bottomMargin = dp(58);
        contentArea.setLayoutParams(caLp);
        root.addView(contentArea);

        // Status bar (persistent)
        mStatusBar = lh(Gravity.CENTER_VERTICAL);
        mStatusBar.setBackgroundColor(ThemeManager.SURFACE2);
        mStatusBar.setPadding(dp(14), dp(8), dp(14), dp(8));
        mStatusBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        mStatusBar.setVisibility(View.GONE);
        View sbDot = new View(this);
        LinearLayout.LayoutParams dotLp2 = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp2.rightMargin = dp(10); sbDot.setLayoutParams(dotLp2);
        sbDot.setBackground(rb(ThemeManager.AMBER, 4));
        mStatusBar.addView(sbDot);
        mStatusBarText = tv("", ThemeManager.AMBER, 10.5f, false);
        mStatusBarText.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mStatusBar.addView(mStatusBarText);
        contentArea.addView(mStatusBar);

        // Content holder — rebuilt on every group/sub switch
        final LinearLayout holder = lv();
        holder.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        contentArea.setWeightSum(1f);
        contentArea.addView(holder);

        maybeAutoSign(holder);

        // ── Bottom nav bar ────────────────────────────────────────────────────
        LinearLayout bottomNav = lh(Gravity.CENTER_VERTICAL);
        bottomNav.setBackgroundColor(ThemeManager.SURFACE);
        android.widget.FrameLayout.LayoutParams bnLp = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(58));
        bnLp.gravity = Gravity.BOTTOM;
        bottomNav.setLayoutParams(bnLp);

        View navBorder = new View(this);
        android.widget.FrameLayout.LayoutParams nbLp = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(1));
        nbLp.gravity = Gravity.BOTTOM;
        nbLp.bottomMargin = dp(57);
        navBorder.setLayoutParams(nbLp);
        navBorder.setBackgroundColor(ThemeManager.BORDER);
        root.addView(navBorder);
        root.addView(bottomNav);

        final String[] navIcons  = {"📁", "⚙", "💬", "…"};
        final String[] navLabels = {"Code", "CI/CD", "Collab", "More"};
        final int[]    navCols   = {ThemeManager.CYAN, ThemeManager.GREEN,
                                    ThemeManager.BRAND, ThemeManager.TEXT2};

        // Sub-tab definitions
        final String[][] subLabels = {
            {"Files", "Upload", "Branches"},
            {"Builds", "Track", "Release", "Workflows"},
            {"Issues", "PRs", "Ask"},
            {}
        };
        final int[][] subCols = {
            {ThemeManager.CYAN, ThemeManager.AMBER, ThemeManager.SUCCESS},
            {ThemeManager.GREEN, ThemeManager.CYAN, ThemeManager.SUCCESS, ThemeManager.AMBER},
            {ThemeManager.DANGER, ThemeManager.BRAND, ThemeManager.BRAND},
            {}
        };

        // ── Build content for current group+sub ───────────────────────────────
        final Runnable[] rebuild = {null};
        rebuild[0] = () -> {
            // ── Sync nav stack so back always returns to the CURRENT tab ─────
            // Without this, back from any sub-page (Release form, Settings, etc.)
            // would return to whichever tab was active when the repo was FIRST opened.
            if (!mNavStack.isEmpty()) {
                final int snapG = activeGroup[0];
                final int snapS = activeSub[0];
                mNavStack.pop();
                mNavStack.push(() -> setContentView(buildRepoPageNew(snapG, snapS)));
            }

            holder.removeAllViews();

            if (activeGroup[0] == 3) {
                holder.addView(buildMoreTab());
                return;
            }

            // Header
            LinearLayout header = buildHeader2(
                mCurrentRepo, mCurrentOwner + " · " + mCurrentBranch, null);
            LinearLayout headerActs = (LinearLayout) header.getTag();
headerActs.addView(iconBtn("⎇", ThemeManager.BRAND, () -> {
    // Convert group+sub back to legacy tab for showBranchSwitcher
    int legacyT = 0;
    if (activeGroup[0] == 0 && activeSub[0] == 1) legacyT = 2;
    else if (activeGroup[0] == 1 && activeSub[0] == 0) legacyT = 1;
    else if (activeGroup[0] == 1 && activeSub[0] == 1) legacyT = 3;
    else if (activeGroup[0] == 1 && activeSub[0] == 2) legacyT = 5;
    else if (activeGroup[0] == 2) legacyT = 4;
    showBranchSwitcher(legacyT);
}));
headerActs.addView(iconBtn("🕐", ThemeManager.BRAND,
    () -> navigateTo(() -> setContentView(buildCommitHistoryPage()))));

// Tombol refresh dengan animasi rotasi
final TextView[] refreshBtn = new TextView[1];
refreshBtn[0] = iconBtn("↻", ThemeManager.BRAND, () -> {
    // Nonaktifkan tombol dulu
    refreshBtn[0].setEnabled(false);
    // Jalankan animasi, navigasi dilakukan SETELAH animasi selesai
    refreshBtn[0].animate()
        .rotationBy(360f)
        .setDuration(400)
        .withEndAction(() -> {
            while (mNavStack.size() > 1) mNavStack.pop();
            navigateTo(() -> setContentView(buildRepoPageNew(activeGroup[0], activeSub[0])));
        })
        .start();
    // Aktifkan kembali tombol setelah animasi + navigasi selesai (600ms)
    refreshBtn[0].postDelayed(() -> refreshBtn[0].setEnabled(true), 600);
});
headerActs.addView(refreshBtn[0]);

headerActs.addView(iconBtn("⋮", ThemeManager.TEXT2, () -> showRepoMenu()));
            holder.addView(header);

            // Sub-tab bar (only when group has >1 sub-tabs)
            String[] sLbls = subLabels[activeGroup[0]];
            int[]    sCols  = subCols[activeGroup[0]];
            if (sLbls.length > 1) {
                LinearLayout subBar = lh(Gravity.CENTER_VERTICAL);
                subBar.setBackgroundColor(ThemeManager.SURFACE);
                subBar.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
                final TextView[] subBtns = new TextView[sLbls.length];
                for (int si = 0; si < sLbls.length; si++) {
                    final int sidx = si;
                    final int scol = sCols[si];
                    subBtns[si] = tv(sLbls[si],
                        si == activeSub[0] ? scol : ThemeManager.DIM, 10f, true);
                    subBtns[si].setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
                    subBtns[si].setGravity(Gravity.CENTER);
                    subBtns[si].setOnClickListener(sv -> {
                        if (activeSub[0] == sidx) return;
                        subBtns[activeSub[0]].setTextColor(ThemeManager.DIM);
                        activeSub[0] = sidx;
                        subBtns[sidx].setTextColor(scol);
                        rebuild[0].run();
                    });
                    applyRipple(subBtns[si]);
                    subBar.addView(subBtns[si]);
                }
                View subLine = new View(this);
                subLine.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
                subLine.setBackgroundColor(ThemeManager.BORDER);
                holder.addView(subBar);
                holder.addView(subLine);
            }

            // Tab content
            boolean isAsk = (activeGroup[0] == 2 && activeSub[0] == 2);
            View tabView = buildGroupSubContent(activeGroup[0], activeSub[0]);
            if (isAsk) {
                // AI Chat needs full height via weight
                holder.setWeightSum(1f);
                LinearLayout.LayoutParams askLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
                tabView.setLayoutParams(askLp);
                holder.addView(tabView);
            } else {
                holder.setWeightSum(1f);
                android.widget.FrameLayout tabFrame = new android.widget.FrameLayout(this);
                tabFrame.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
                ScrollView sv2 = new ScrollView(this);
                sv2.setBackgroundColor(ThemeManager.BG);
                sv2.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
                LinearLayout tabContent = lv();
                tabContent.setPadding(dp(16), dp(12), dp(16), dp(80));
                tabContent.addView(tabView);
                sv2.addView(tabContent);
                tabFrame.addView(sv2);
                // ── Scroll-state: track this tab's sv and restore saved position ──
                mCurrentTabSv = sv2;
                final String tabScrollKey = "tab_" + activeGroup[0] + "_" + activeSub[0];
                final int savedTabScroll = mScrollStates.containsKey(tabScrollKey)
                    ? mScrollStates.get(tabScrollKey) : 0;
                if (savedTabScroll > 0) sv2.post(() -> sv2.scrollTo(0, savedTabScroll));
                // Sticky deleteBar for Files
                if (tabView.getTag() instanceof LinearLayout) {
                    LinearLayout delBar = (LinearLayout) tabView.getTag();
                    android.widget.FrameLayout.LayoutParams dbFp =
                        new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
                    dbFp.gravity = Gravity.BOTTOM;
                    delBar.setLayoutParams(dbFp);
                    tabFrame.addView(delBar);
                }
                holder.addView(tabFrame);
            }
        };

        // ── Wire bottom nav items ─────────────────────────────────────────────
        for (int gi = 0; gi < 4; gi++) {
            final int gidx = gi;
            LinearLayout navItem = lv();
            navItem.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            navItem.setGravity(Gravity.CENTER);
            boolean active = (gi == startGroup);
            TextView navIc = tv(navIcons[gi],
                active ? navCols[gi] : ThemeManager.DIM, 17f, false);
            navIc.setGravity(Gravity.CENTER);
            navItem.addView(navIc);
            TextView navLbl = tv(navLabels[gi],
                active ? navCols[gi] : ThemeManager.DIM, 8.5f, active);
            navLbl.setGravity(Gravity.CENTER);
            navItem.addView(navLbl);
            applyRipple(navItem, 0);
            navItem.setOnClickListener(v -> {
                if (activeGroup[0] == gidx) return;
                // Update all nav item colors
                for (int j = 0; j < bottomNav.getChildCount(); j++) {
                    if (!(bottomNav.getChildAt(j) instanceof LinearLayout)) continue;
                    LinearLayout ni = (LinearLayout) bottomNav.getChildAt(j);
                    if (ni.getChildCount() < 2) continue;
                    boolean isActive = (j == gidx);
                    int c = navCols[j];
                    ((TextView) ni.getChildAt(0)).setTextColor(isActive ? c : ThemeManager.DIM);
                    TextView lbl = (TextView) ni.getChildAt(1);
                    lbl.setTextColor(isActive ? c : ThemeManager.DIM);
                    lbl.setTypeface(isActive ?
                        android.graphics.Typeface.DEFAULT_BOLD :
                        android.graphics.Typeface.DEFAULT);
                }
                activeGroup[0] = gidx;
                activeSub[0]   = 0;
                rebuild[0].run();
            });
            bottomNav.addView(navItem);
        }

        rebuild[0].run();
        return root;
    }

    /** Route group+sub to correct tab builder. */
    private View buildGroupSubContent(int group, int sub) {
        // Code: Files(0), Upload(1), Branches(2)
        if (group == 0 && sub == 0) return buildFilesTab();
        if (group == 0 && sub == 1) return buildUploadTab();
        if (group == 0 && sub == 2) return buildBranchesTab();
        // CI/CD: Builds(0), Track(1), Release(2), Workflows(3)
        if (group == 1 && sub == 0) return buildBuildsTab();
        if (group == 1 && sub == 1) return buildTrackTab();
        if (group == 1 && sub == 2) return buildReleaseTab();
        if (group == 1 && sub == 3) return buildWorkflowInspectorTab();
        // Collab: Issues(0), PRs(1), Ask(2)
        if (group == 2 && sub == 0) return buildIssuesTab();
        if (group == 2 && sub == 1) return buildPRsTab();
        if (group == 2 && sub == 2) return buildAiChatTab();
        return buildFilesTab();
    }

    /** Legacy router — kept so old navigateTo(buildRepoPageAtTab(N)) calls still work. */
    private View buildTabContent(int tab) {
        if (tab == 0) return buildGroupSubContent(0, 0);
        if (tab == 1) return buildGroupSubContent(1, 0);
        if (tab == 2) return buildGroupSubContent(0, 1);
        if (tab == 3) return buildGroupSubContent(1, 1);
        if (tab == 5) return buildGroupSubContent(1, 2);
        return buildGroupSubContent(2, 2); // Ask
    }

    // ── MORE TAB ──────────────────────────────────────────────────────────────
    private View buildMoreTab() {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);
        LinearLayout header = buildHeader2(mCurrentRepo,
            mCurrentOwner + " · " + mCurrentBranch, null);
        ((LinearLayout) header.getTag()).addView(
            iconBtn("⋮", ThemeManager.TEXT2, () -> showRepoMenu()));
        outer.addView(header);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout c = lv();
        c.setPadding(dp(16), dp(12), dp(16), dp(80));

        c.addView(buildMoreSection("REPOSITORY", new String[][]{
            {"⚙  Repo Settings",   "name, description, visibility"},
            {"🔑  Signing Config",  "keystore & auto-sign"},
            {"🔒  Secrets & Vars",  "Actions secrets and variables"},
            {"🌿  Commit History",  "browse commit log"},
            {"⬇  Download Repo",   "download as ZIP"},
        }, new Runnable[]{
            () -> navigateFromMore(() -> setContentView(buildRepoSettingsPage())),
            () -> navigateFromMore(() -> setContentView(buildSignConfigPage())),
            () -> navigateFromMore(() -> setContentView(buildSecretsPage())),
            () -> navigateFromMore(() -> setContentView(buildCommitHistoryPage())),
            () -> doDownloadRepo(),
        }));
        c.addView(sp(12));
        c.addView(buildMoreSection("ACCOUNT", new String[][]{
            {"👤  Profile",        "view & switch accounts"},
            {"🔑  Token Manager",  "API tokens & AI keys"},
            {"🔔  Notifications",  "GitHub notifications"},
        }, new Runnable[]{
            () -> navigateFromMore(() -> setContentView(buildProfilePage())),
            () -> navigateFromMore(() -> setContentView(buildTokenPage())),
            () -> navigateFromMore(() -> setContentView(buildNotificationsPage())),
        }));
        c.addView(sp(12));
        c.addView(buildMoreSection("DANGER ZONE", new String[][]{
            {"🗑  Clear Repo Contents", "delete all files, keep CI/CD"},
            {"💣  Delete Repository",   "permanent, cannot be undone"},
        }, new Runnable[]{
            () -> confirmClearRepo(),
            () -> confirmDeleteRepo(),
        }));
        sv.addView(c);
        outer.addView(sv);
        return outer;
    }

    private LinearLayout buildMoreSection(String title, String[][] items, Runnable[] actions) {
        LinearLayout section = lv();
        section.setLayoutParams(mpWrap());
        TextView titleTv = tv(title, ThemeManager.DIM, 9f, true);
        titleTv.setLetterSpacing(0.10f);
        LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ttLp.bottomMargin = dp(6);
        titleTv.setLayoutParams(ttLp);
        section.addView(titleTv);
        LinearLayout card = lv();
        card.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        card.setLayoutParams(mpWrap());
        for (int i = 0; i < items.length; i++) {
            final Runnable action = actions[i];
            boolean isDanger = title.equals("DANGER ZONE");
            LinearLayout row = lh(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setClickable(true);
            LinearLayout texts = lv();
            texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            texts.addView(tv(items[i][0],
                isDanger ? ThemeManager.DANGER : ThemeManager.TEXT, 13f, false));
            if (items[i].length > 1)
                texts.addView(tv(items[i][1], ThemeManager.DIM, 10f, false));
            row.addView(texts);
            row.addView(tv("›", ThemeManager.DIM, 18f, false));
            row.setOnClickListener(vv -> action.run());
            applyRipple(row);
            card.addView(row);
            if (i < items.length - 1) card.addView(rowDivider());
        }
        section.addView(card);
        return section;
    }

    // =========================================================================
    // REPO SETTINGS PAGE
    // =========================================================================
    private View buildRepoSettingsPage() {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);
        LinearLayout header = buildHeader2("Repo Settings", mCurrentRepo, null);
        outer.addView(header);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG);
        sv.setLayoutParams(mpWrap());
        LinearLayout c = lv();
        c.setPadding(dp(16), dp(14), dp(16), dp(40));

        // Load repo info then populate form
        final String[] origName = {mCurrentRepo};
        final String[] origDesc = {""};
        final boolean[] origPriv = {false};

        // Name field
        c.addView(fieldLabel("Repository Name"));
        final EditText nameEt = styledInput(mCurrentRepo, false);
        c.addView(nameEt);

        c.addView(fieldLabel("Description"));
        final EditText descEt = styledInput("Loading...", false);
        descEt.setEnabled(false);
        c.addView(descEt);

        // Visibility toggle
        c.addView(sp(12));
        final boolean[] isPrivate = {false};
        LinearLayout visRow = lh(Gravity.CENTER_VERTICAL);
        visRow.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        visRow.setPadding(dp(14), dp(14), dp(14), dp(14));
        visRow.setLayoutParams(mpWrap());
        LinearLayout visTexts = lv();
        visTexts.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        visTexts.addView(tv("Visibility", ThemeManager.TEXT, 13f, false));
        final TextView visSub = tv("Loading...", ThemeManager.DIM, 10f, false);
        visTexts.addView(visSub);
        visRow.addView(visTexts);
        final TextView visBtn = roundBtn("Toggle", ThemeManager.BRAND, ThemeManager.BRAND_D);
        visBtn.setEnabled(false);
        visRow.addView(visBtn);
        c.addView(visRow);

        c.addView(sp(20));

        // Save button
        final TextView saveBtn = primaryBtn("Save Changes", ThemeManager.BRAND);
        saveBtn.setEnabled(false);
        c.addView(saveBtn);

        sv.addView(c);
        outer.addView(sv);

        // Load current info
        GHApi.getRepoInfo(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            (info, err) -> mHandler.post(() -> {
                if (err != null) { toast("Failed to load: " + err); return; }
                origName[0] = info.name;
                origDesc[0] = info.description;
                origPriv[0] = info.isPrivate;
                isPrivate[0] = info.isPrivate;
                nameEt.setText(info.name);
                descEt.setText(info.description);
                descEt.setEnabled(true);
                visSub.setText(info.isPrivate ? "Private — only you can see this repo"
                    : "Public — anyone can see this repo");
                visBtn.setText(info.isPrivate ? "Make Public" : "Make Private");
                visBtn.setEnabled(true);
                saveBtn.setEnabled(true);

                visBtn.setOnClickListener(v -> {
                    isPrivate[0] = !isPrivate[0];
                    visSub.setText(isPrivate[0]
                        ? "Private — only you can see this repo"
                        : "Public — anyone can see this repo");
                    visBtn.setText(isPrivate[0] ? "Make Public" : "Make Private");
                });

                saveBtn.setOnClickListener(v -> {
                    String newName = nameEt.getText().toString().trim();
                    String newDesc = descEt.getText().toString().trim();
                    if (newName.isEmpty()) { toast("Name cannot be empty"); return; }
                    saveBtn.setText("Saving..."); saveBtn.setEnabled(false);
                    Boolean newVis = (isPrivate[0] != origPriv[0]) ? isPrivate[0] : null;
                    String finalName = newName.equals(origName[0]) ? null : newName;
                    String finalDesc = newDesc.equals(origDesc[0]) ? null : newDesc;
                    if (finalName == null && finalDesc == null && newVis == null) {
                        toast("No changes to save.");
                        saveBtn.setText("Save Changes"); saveBtn.setEnabled(true);
                        return;
                    }
                    GHApi.editRepo(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                        finalName, finalDesc, null, newVis, (updated, editErr) -> mHandler.post(() -> {
                            saveBtn.setText("Save Changes"); saveBtn.setEnabled(true);
                            if (editErr != null) { showErr("Save failed:\n" + editErr); return; }
                            toast("✓ Settings saved");
                            if (updated != null && !updated.name.equals(mCurrentRepo)) {
                                mCurrentRepo = updated.name;
                            }
                            onBackPressed();
                        }));
                });
            }));

        return outer;
    }

    // =========================================================================
    // NOTIFICATIONS PAGE
    // =========================================================================
    private View buildNotificationsPage() {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);

        LinearLayout header = buildHeader2("Notifications", "@" + mPrefs.getUsername(), null);
        LinearLayout headerActs = (LinearLayout) header.getTag();
        TextView markAllBtn = roundBtn("Mark all read", ThemeManager.BRAND, ThemeManager.BRAND_D);
        headerActs.addView(markAllBtn);
        outer.addView(header);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG);
        sv.setLayoutParams(mpWrap());
        final LinearLayout list = lv();
        list.setPadding(dp(16), dp(12), dp(16), dp(40));
        list.addView(loadingRow());
        sv.addView(list);
        outer.addView(sv);

        final Runnable[] loadNotifs = {null};
        loadNotifs[0] = () -> {
            list.removeAllViews();
            list.addView(loadingRow());
            GHApi.listNotifications(mPrefs.getToken(), false, (notifs, err) -> mHandler.post(() -> {
                list.removeAllViews();
                if (err != null) { list.addView(errCard(err)); return; }
                if (notifs == null || notifs.isEmpty()) {
                    TextView empty = tv("🎉  You're all caught up!", ThemeManager.DIM, 13f, false);
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(0, dp(40), 0, dp(40));
                    list.addView(empty);
                    return;
                }
                mCachedNotifs = notifs;
                mUnreadNotifCount = 0;
                for (GHApi.GitNotification n : notifs) if (n.unread) mUnreadNotifCount++;
                // Group by repo
                java.util.LinkedHashMap<String, java.util.List<GHApi.GitNotification>> grouped
                    = new java.util.LinkedHashMap<>();
                for (GHApi.GitNotification n : notifs) {
                    grouped.computeIfAbsent(n.repoFullName, k -> new java.util.ArrayList<>()).add(n);
                }
                for (java.util.Map.Entry<String, java.util.List<GHApi.GitNotification>> e : grouped.entrySet()) {
                    TextView repoLbl = tv(e.getKey(), ThemeManager.DIM, 9f, true);
                    repoLbl.setLetterSpacing(0.08f);
                    LinearLayout.LayoutParams rlLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    rlLp.topMargin = dp(14); rlLp.bottomMargin = dp(5);
                    repoLbl.setLayoutParams(rlLp);
                    list.addView(repoLbl);
                    LinearLayout card = lv();
                    card.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
                    card.setLayoutParams(mpWrap());
                    java.util.List<GHApi.GitNotification> ns = e.getValue();
                    for (int i = 0; i < ns.size(); i++) {
                        GHApi.GitNotification n = ns.get(i);
                        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(14), dp(12), dp(14), dp(12));
                        row.setClickable(true);
                        // Unread dot
                        View dot = new View(this);
                        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(7), dp(7));
                        dLp.rightMargin = dp(10);
                        dot.setLayoutParams(dLp);
                        dot.setBackground(rb(n.unread ? ThemeManager.BRAND : 0, 4));
                        row.addView(dot);
                        // Type icon + title
                        String typeIcon = "issue".equalsIgnoreCase(n.type) ? "⚠" :
                            "pullrequest".equalsIgnoreCase(n.type) ? "↗" :
                            "commit".equalsIgnoreCase(n.type) ? "●" :
                            "release".equalsIgnoreCase(n.type) ? "🏷" : "🔔";
                        LinearLayout texts = lv();
                        texts.setLayoutParams(new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                        texts.addView(tv(typeIcon + "  " + n.title,
                            n.unread ? ThemeManager.TEXT : ThemeManager.TEXT2, 12.5f, n.unread));
                        String reasonStr = n.reason.replace("_", " ");
                        String dateStr = n.updatedAt.length() > 10 ? n.updatedAt.substring(0, 10) : n.updatedAt;
                        texts.addView(tv(reasonStr + "  ·  " + dateStr, ThemeManager.DIM, 9.5f, false));
                        row.addView(texts);
                        row.setOnClickListener(v -> {
                            if (n.unread) GHApi.markNotificationRead(mPrefs.getToken(), n.id,
                                (ok, e2) -> mHandler.post(() -> {
                                    dot.setBackground(rb(0, 4));
                                    n.unread = false;
                                }));
                        });
                        applyRipple(row);
                        card.addView(row);
                        if (i < ns.size() - 1) card.addView(rowDivider());
                    }
                    list.addView(card);
                }
            }));
        };

        markAllBtn.setOnClickListener(v -> {
            markAllBtn.setText("...");
            markAllBtn.setEnabled(false);
            GHApi.markAllNotificationsRead(mPrefs.getToken(), (ok, e) -> mHandler.post(() -> {
                markAllBtn.setText("Mark all read");
                markAllBtn.setEnabled(true);
                mUnreadNotifCount = 0;
                if (mNotifBadge != null) mNotifBadge.setVisibility(View.GONE);
                loadNotifs[0].run();
            }));
        });

        loadNotifs[0].run();
        return outer;
    }


    // =========================================================================
    // ISSUES TAB
    // =========================================================================
    private View buildIssuesTab() {
        LinearLayout c = lv(); c.setLayoutParams(mpWrap());

        // Filter bar: Open / Closed
        final String[] filter = {"open"};
        LinearLayout filterRow = lh(Gravity.CENTER_VERTICAL);
        filterRow.setBackground(rbs(ThemeManager.SURFACE, 10, ThemeManager.BORDER));
        filterRow.setLayoutParams(mpWrap());
        LinearLayout.LayoutParams frLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        frLp.bottomMargin = dp(12); filterRow.setLayoutParams(frLp);
        final TextView openBtn  = tv("Open",   ThemeManager.SUCCESS, 12f, true);
        final TextView closeBtn = tv("Closed", ThemeManager.DIM,     12f, false);
        openBtn.setGravity(Gravity.CENTER);  openBtn.setPadding(0, dp(10), 0, dp(10));
        closeBtn.setGravity(Gravity.CENTER); closeBtn.setPadding(0, dp(10), 0, dp(10));
        openBtn.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        closeBtn.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        filterRow.addView(openBtn); filterRow.addView(closeBtn);
        c.addView(filterRow);

        // New Issue button
        LinearLayout topRow = lh(Gravity.CENTER_VERTICAL);
        topRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams)topRow.getLayoutParams()).bottomMargin = dp(10);
        TextView countTv = tv("", ThemeManager.DIM, 10f, false);
        countTv.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(countTv);
        TextView newBtn = roundBtn("+ New Issue", ThemeManager.SUCCESS, ThemeManager.SUCCESS_D);
        newBtn.setOnClickListener(v -> showNewIssueDialog());
        topRow.addView(newBtn);
        c.addView(topRow);

        final LinearLayout list = lv();
        list.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        list.setLayoutParams(mpWrap());
        list.addView(loadingRow());
        c.addView(list);

        final Runnable[] loadIssues = {null};
        loadIssues[0] = () -> {
            list.removeAllViews();
            list.addView(loadingRow());
            GHApi.listIssues(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                filter[0], (issues, err) -> mHandler.post(() -> {
                    list.removeAllViews();
                    if (err != null) { list.addView(errCard(err)); return; }
                    if (issues == null || issues.isEmpty()) {
                        TextView empty = tv(
                            filter[0].equals("open")
                                ? "🎉  No open issues. All clear!"
                                : "No closed issues found.",
                            ThemeManager.DIM, 12f, false);
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding(0, dp(32), 0, dp(32));
                        list.addView(empty);
                        countTv.setText("0 " + filter[0]);
                        return;
                    }
                    countTv.setText(issues.size() + " " + filter[0]);
                    for (int i = 0; i < issues.size(); i++) {
                        GHApi.Issue iss = issues.get(i);
                        list.addView(buildIssueRow(iss, loadIssues[0]));
                        if (i < issues.size() - 1) list.addView(rowDivider());
                    }
                }));
        };

        openBtn.setOnClickListener(v -> {
            filter[0] = "open";
            openBtn.setTextColor(ThemeManager.SUCCESS);
            openBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            closeBtn.setTextColor(ThemeManager.DIM);
            closeBtn.setTypeface(android.graphics.Typeface.DEFAULT);
            loadIssues[0].run();
        });
        closeBtn.setOnClickListener(v -> {
            filter[0] = "closed";
            closeBtn.setTextColor(ThemeManager.BRAND);
            closeBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            openBtn.setTextColor(ThemeManager.DIM);
            openBtn.setTypeface(android.graphics.Typeface.DEFAULT);
            loadIssues[0].run();
        });

        loadIssues[0].run();
        return c;
    }

    private View buildIssueRow(final GHApi.Issue iss, final Runnable reload) {
        LinearLayout row = lv();
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);

        // Title row
        LinearLayout titleRow = lh(Gravity.CENTER_VERTICAL);
        TextView numTv = badgeChip("#" + iss.number,
            "open".equals(iss.state) ? ThemeManager.SUCCESS : ThemeManager.DIM,
            "open".equals(iss.state) ? ThemeManager.SUCCESS_D : ThemeManager.SURFACE2);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nLp.rightMargin = dp(8); numTv.setLayoutParams(nLp);
        titleRow.addView(numTv);

        TextView titleTv = tv(iss.title, ThemeManager.TEXT, 13f, true);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(titleTv);
        row.addView(titleRow);

        // Meta row
        String date = iss.updatedAt.length() > 10 ? iss.updatedAt.substring(0, 10) : iss.updatedAt;
        String meta = "by " + iss.userLogin + "  ·  " + date;
        if (iss.comments > 0) meta += "  💬 " + iss.comments;
        TextView metaTv = tv(meta, ThemeManager.DIM, 9.5f, false);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = dp(4); metaTv.setLayoutParams(mLp);
        row.addView(metaTv);

        // Labels
        if (!iss.labels.isEmpty()) {
            LinearLayout labelsRow = lh(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lrLp.topMargin = dp(6); labelsRow.setLayoutParams(lrLp);
            for (String lbl : iss.labels) {
                TextView lblChip = badgeChip(lbl, ThemeManager.BRAND, ThemeManager.BRAND_D);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = dp(4); lblChip.setLayoutParams(clp);
                labelsRow.addView(lblChip);
            }
            row.addView(labelsRow);
        }

        row.setOnClickListener(v -> navigateTo(() -> setContentView(buildIssueDetailPage(iss, reload))));
        applyRipple(row);
        return row;
    }

    private View buildIssueDetailPage(final GHApi.Issue iss, final Runnable reload) {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);

        String stateLabel = "open".equals(iss.state) ? "OPEN" : "CLOSED";
        int stateColor = "open".equals(iss.state) ? ThemeManager.SUCCESS : ThemeManager.DIM;
        LinearLayout header = buildHeader2("#" + iss.number + "  " + iss.title,
            mCurrentRepo, null);
        LinearLayout headerActs = (LinearLayout) header.getTag();
        // Close/Reopen button
        String btnLabel = "open".equals(iss.state) ? "Close" : "Reopen";
        int btnColor = "open".equals(iss.state) ? ThemeManager.DANGER : ThemeManager.SUCCESS;
        TextView stateBtn = roundBtn(btnLabel, btnColor,
            "open".equals(iss.state) ? ThemeManager.DANGER_D : ThemeManager.SUCCESS_D);
        stateBtn.setOnClickListener(v -> {
            String newState = "open".equals(iss.state) ? "closed" : "open";
            stateBtn.setText("..."); stateBtn.setEnabled(false);
            GHApi.setIssueState(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                iss.number, newState, (ok, err) -> mHandler.post(() -> {
                    if (!ok) { stateBtn.setEnabled(true); stateBtn.setText(btnLabel);
                        showErr("Failed: " + err); return; }
                    toast("Issue " + newState);
                    iss.state = newState;
                    if (reload != null) reload.run();
                    onBackPressed();
                }));
        });
        headerActs.addView(stateBtn);
        outer.addView(header);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG);
        sv.setLayoutParams(mpWrap());
        LinearLayout c = lv();
        c.setPadding(dp(16), dp(12), dp(16), dp(40));

        // State + meta card
        LinearLayout metaCard = lh(Gravity.CENTER_VERTICAL);
        metaCard.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        metaCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mcLp.bottomMargin = dp(12); metaCard.setLayoutParams(mcLp);
        metaCard.addView(badgeChip(stateLabel, stateColor,
            "open".equals(iss.state) ? ThemeManager.SUCCESS_D : ThemeManager.SURFACE2));
        TextView byTv = tv("  opened by " + iss.userLogin, ThemeManager.DIM, 10f, false);
        byTv.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        metaCard.addView(byTv);
        c.addView(metaCard);

        // Body
        if (iss.body != null && !iss.body.trim().isEmpty()) {
            LinearLayout bodyCard = lv();
            bodyCard.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
            bodyCard.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams bcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bcLp.bottomMargin = dp(16); bodyCard.setLayoutParams(bcLp);
            TextView bodyTv = tv(iss.body, ThemeManager.TEXT2, 12.5f, false);
            bodyTv.setLineSpacing(dp(2), 1.3f);
            bodyCard.addView(bodyTv);
            c.addView(bodyCard);
        }

        // Comments section header
        TextView commentsSec = secLabel("COMMENTS");
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        csLp.bottomMargin = dp(6); commentsSec.setLayoutParams(csLp);
        c.addView(commentsSec);

        final LinearLayout commentsList = lv();
        commentsList.setLayoutParams(mpWrap());
        commentsList.addView(loadingRow());
        c.addView(commentsList);

        // Add comment
        c.addView(sp(12));
        TextView commentSecLabel = secLabel("ADD COMMENT");
        LinearLayout.LayoutParams cslLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cslLp.bottomMargin = dp(6); commentSecLabel.setLayoutParams(cslLp);
        c.addView(commentSecLabel);

        final EditText commentEt = new EditText(this);
        commentEt.setHint("Write a comment...");
        commentEt.setHintTextColor(ThemeManager.DIM);
        commentEt.setTextColor(ThemeManager.TEXT);
        commentEt.setTextSize(12.5f);
        commentEt.setBackground(rbs(ThemeManager.SURFACE, 10, ThemeManager.BORDER));
        commentEt.setPadding(dp(12), dp(10), dp(12), dp(10));
        commentEt.setMinLines(3);
        commentEt.setGravity(Gravity.TOP);
        commentEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams ceLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ceLp.bottomMargin = dp(10); commentEt.setLayoutParams(ceLp);
        c.addView(commentEt);

        final TextView submitBtn = primaryBtn("Submit Comment", ThemeManager.BRAND);
        submitBtn.setOnClickListener(v -> {
            String body = commentEt.getText().toString().trim();
            if (body.isEmpty()) { toast("Write a comment first."); return; }
            submitBtn.setText("Posting..."); submitBtn.setEnabled(false);
            GHApi.addIssueComment(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                iss.number, body, (ok, err) -> mHandler.post(() -> {
                    submitBtn.setText("Submit Comment"); submitBtn.setEnabled(true);
                    if (!ok) { showErr("Failed: " + err); return; }
                    commentEt.setText("");
                    toast("Comment posted!");
                    // Reload comments
                    loadComments(iss.number, commentsList);
                }));
        });
        c.addView(submitBtn);

        sv.addView(c);
        outer.addView(sv);

        loadComments(iss.number, commentsList);
        return outer;
    }

    private void loadComments(int issueNumber, LinearLayout container) {
        container.removeAllViews();
        container.addView(loadingRow());
        GHApi.getIssueComments(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            issueNumber, (comments, err) -> mHandler.post(() -> {
                container.removeAllViews();
                if (err != null) { container.addView(errCard(err)); return; }
                if (comments == null || comments.isEmpty()) {
                    TextView none = tv("No comments yet.", ThemeManager.DIM, 11f, false);
                    none.setPadding(0, dp(8), 0, dp(8));
                    container.addView(none); return;
                }
                for (int i = 0; i < comments.size(); i++) {
                    GHApi.IssueComment cm = comments.get(i);
                    LinearLayout bubble = lv();
                    bubble.setBackground(rbs(ThemeManager.SURFACE, 10, ThemeManager.BORDER));
                    bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
                    LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    bLp.bottomMargin = dp(8); bubble.setLayoutParams(bLp);
                    // Author + date
                    LinearLayout topRow = lh(Gravity.CENTER_VERTICAL);
                    topRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                    ((LinearLayout.LayoutParams)topRow.getLayoutParams()).bottomMargin = dp(6);
                    TextView authorTv = tv(cm.userLogin, ThemeManager.BRAND, 10.5f, true);
                    authorTv.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    topRow.addView(authorTv);
                    String date = cm.createdAt.length() > 10 ? cm.createdAt.substring(0, 10) : cm.createdAt;
                    topRow.addView(tv(date, ThemeManager.DIM, 9f, false));
                    bubble.addView(topRow);
                    TextView bodyTv = tv(cm.body, ThemeManager.TEXT2, 12f, false);
                    bodyTv.setLineSpacing(dp(2), 1.25f);
                    bubble.addView(bodyTv);
                    container.addView(bubble);
                }
            }));
    }

    private void showNewIssueDialog() {
        android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            d.getWindow().setDimAmount(0.5f);
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.setCancelable(true);
        LinearLayout root = lv();
        root.setBackground(rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        root.setPadding(dp(18), dp(18), dp(18), dp(16));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.93f),
            LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(tv("New Issue", ThemeManager.TEXT, 15f, true));
        root.addView(sp(12));
        root.addView(fieldLabel("Title"));
        final EditText titleEt = styledInput("Issue title...", false);
        LinearLayout.LayoutParams teLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        teLp.bottomMargin = dp(10); titleEt.setLayoutParams(teLp);
        root.addView(titleEt);
        root.addView(fieldLabel("Description (optional)"));
        final EditText bodyEt = new EditText(this);
        bodyEt.setHint("Describe the issue...");
        bodyEt.setHintTextColor(ThemeManager.DIM);
        bodyEt.setTextColor(ThemeManager.TEXT);
        bodyEt.setTextSize(12.5f);
        bodyEt.setBackground(rbs(ThemeManager.SURFACE2, 10, ThemeManager.BORDER));
        bodyEt.setPadding(dp(12), dp(10), dp(12), dp(10));
        bodyEt.setMinLines(4); bodyEt.setGravity(Gravity.TOP);
        bodyEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams beLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        beLp.bottomMargin = dp(14); bodyEt.setLayoutParams(beLp);
        root.addView(bodyEt);

        LinearLayout btnRow = lh(Gravity.END);
        TextView cancelBtn = roundBtn("Cancel", ThemeManager.DIM, ThemeManager.SURFACE2);
        cancelBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbLp.rightMargin = dp(8); cancelBtn.setLayoutParams(cbLp);
        cancelBtn.setOnClickListener(v -> d.dismiss());
        btnRow.addView(cancelBtn);
        final TextView submitBtn = tv("Submit", ThemeManager.SUCCESS, 12f, true);
        submitBtn.setBackground(rbs(ThemeManager.SUCCESS_D, 10, ThemeManager.SUCCESS));
        submitBtn.setPadding(dp(18), dp(10), dp(18), dp(10));
        submitBtn.setGravity(Gravity.CENTER);
        submitBtn.setOnClickListener(v -> {
            String title = titleEt.getText().toString().trim();
            if (title.isEmpty()) { toast("Title is required."); return; }
            submitBtn.setText("Creating..."); submitBtn.setEnabled(false);
            d.dismiss();
            GHApi.createIssue(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                title, bodyEt.getText().toString().trim(),
                (iss, err) -> mHandler.post(() -> {
                    if (err != null) { showErr("Failed: " + err); return; }
                    toast("✓ Issue #" + iss.number + " created");
                    while (mNavStack.size() > 1) mNavStack.pop();
                    navigateTo(() -> setContentView(buildRepoPageNew(2, 0)));
                }));
        });
        btnRow.addView(submitBtn);
        root.addView(btnRow);

        d.setContentView(root);
        d.show();
    }

    // =========================================================================
    // BRANCHES TAB
    // =========================================================================
    private View buildBranchesTab() {
        LinearLayout c = lv(); c.setLayoutParams(mpWrap());

        LinearLayout topRow = lh(Gravity.CENTER_VERTICAL);
        topRow.setLayoutParams(mpWrap());
        ((LinearLayout.LayoutParams)topRow.getLayoutParams()).bottomMargin = dp(12);
        TextView lbl = secLabel("Branches");
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(lbl);
        TextView newBtn = roundBtn("+ New Branch", ThemeManager.SUCCESS, ThemeManager.SUCCESS_D);
        newBtn.setOnClickListener(v -> showNewBranchDialog());
        topRow.addView(newBtn);
        c.addView(topRow);

        final LinearLayout list = lv();
        list.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        list.setLayoutParams(mpWrap());
        list.addView(loadingRow());
        c.addView(list);

        GHApi.listBranchesDetail(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            mCurrentBranch, (branches, err) -> mHandler.post(() -> {
                list.removeAllViews();
                if (err != null) { list.addView(errCard(err)); return; }
                if (branches == null || branches.isEmpty()) {
                    list.addView(tv("No branches found.", ThemeManager.DIM, 11f, false));
                    return;
                }
                for (int i = 0; i < branches.size(); i++) {
                    GHApi.Branch br = branches.get(i);
                    list.addView(buildBranchRow(br));
                    if (i < branches.size() - 1) list.addView(rowDivider());
                }
            }));
        return c;
    }

    private View buildBranchRow(final GHApi.Branch br) {
        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);

        LinearLayout info = lv();
        info.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout nameRow = lh(Gravity.CENTER_VERTICAL);
        TextView nameTv = tv("🌿  " + br.name, ThemeManager.TEXT, 13f, br.isDefault);
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        nameRow.addView(nameTv);
        if (br.isDefault) {
            TextView defChip = badgeChip("default", ThemeManager.SUCCESS, ThemeManager.SUCCESS_D);
            LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dcLp.leftMargin = dp(8); defChip.setLayoutParams(dcLp);
            nameRow.addView(defChip);
        }
        if (br.isProtected) {
            TextView protChip = badgeChip("protected", ThemeManager.AMBER, ThemeManager.AMBER_D);
            LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pcLp.leftMargin = dp(6); protChip.setLayoutParams(pcLp);
            nameRow.addView(protChip);
        }
        info.addView(nameRow);
        if (!br.sha.isEmpty()) {
            TextView shaTv = tv(br.sha.substring(0, Math.min(7, br.sha.length())),
                ThemeManager.DIM, 9.5f, false);
            shaTv.setTypeface(android.graphics.Typeface.MONOSPACE);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = dp(3); shaTv.setLayoutParams(sLp);
            info.addView(shaTv);
        }
        row.addView(info);

        // Switch + Delete buttons
        if (!br.name.equals(mCurrentBranch)) {
            TextView switchBtn = roundBtn("Switch", ThemeManager.BRAND, ThemeManager.BRAND_D);
            LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sbLp.rightMargin = dp(6); switchBtn.setLayoutParams(sbLp);
            switchBtn.setOnClickListener(v -> {
                mCurrentBranch = br.name;
                mCachedFiles.evictAll(); mCachedContent.evictAll(); mCachedRuns = null;
                toast("Switched to " + br.name);
                while (mNavStack.size() > 1) mNavStack.pop();
                navigateTo(() -> setContentView(buildRepoPageNew(0, 2)));
            });
            row.addView(switchBtn);
        }

        if (!br.isDefault && !br.isProtected) {
            TextView delBtn = roundBtn("Delete", ThemeManager.DANGER, ThemeManager.DANGER_D);
            delBtn.setOnClickListener(v ->
                showConfirm("Delete branch?", "\"" + br.name + "\" will be permanently deleted.",
                    "Delete", ThemeManager.DANGER, () -> {
                        delBtn.setText("..."); delBtn.setEnabled(false);
                        GHApi.deleteBranch(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                            br.name, (ok, err) -> mHandler.post(() -> {
                                if (!ok) { delBtn.setEnabled(true); delBtn.setText("Delete");
                                    showErr("Failed: " + err); return; }
                                toast("Branch deleted");
                                // Remove row optimistically
                                if (row.getParent() instanceof LinearLayout) {
                                    LinearLayout parent = (LinearLayout) row.getParent();
                                    int idx = parent.indexOfChild(row);
                                    parent.removeView(row);
                                    if (idx > 0) parent.removeViewAt(idx - 1); // divider
                                }
                            }));
                    }));
            row.addView(delBtn);
        }

        applyRipple(row);
        return row;
    }

    private void showNewBranchDialog() {
        LinearLayout layout = lv();
        layout.setPadding(dp(4), dp(4), dp(4), dp(4));
        layout.addView(fieldLabel("Branch name"));
        final EditText nameEt = styledInput("feature/my-branch", false);
        layout.addView(nameEt);
        layout.addView(sp(8));
        layout.addView(fieldLabel("From branch"));
        final EditText fromEt = styledInput(mCurrentBranch, false);
        fromEt.setText(mCurrentBranch);
        layout.addView(fromEt);

        showViewDialog("Create New Branch", layout, "Create", ThemeManager.SUCCESS, () -> {
            String name = nameEt.getText().toString().trim();
            String from = fromEt.getText().toString().trim();
            if (name.isEmpty()) { toast("Branch name required."); return; }
            if (from.isEmpty()) from = mCurrentBranch;
            final String fFrom = from;
            android.app.Dialog prog = makeProgressDialog();
            prog.show();
            GHApi.createBranch(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                name, fFrom, (ok, err) -> mHandler.post(() -> {
                    prog.dismiss();
                    if (!ok) { showErr("Failed: " + err); return; }
                    toast("✓ Branch \"" + name + "\" created from " + fFrom);
                    while (mNavStack.size() > 1) mNavStack.pop();
                    navigateTo(() -> setContentView(buildRepoPageNew(0, 2)));
                }));
        });
    }

    // =========================================================================
    // WORKFLOW INSPECTOR TAB
    // =========================================================================
    private View buildWorkflowInspectorTab() {
        LinearLayout c = lv(); c.setLayoutParams(mpWrap());

        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(12);
        TextView hdr = secLabel("Workflow Inspector");
        hdr.setLayoutParams(hLp);
        c.addView(hdr);

        final LinearLayout list = lv();
        list.setLayoutParams(mpWrap());
        list.addView(loadingRow());
        c.addView(list);

        GHApi.listWorkflowsDetail(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            (workflows, err) -> mHandler.post(() -> {
                list.removeAllViews();
                if (err != null) { list.addView(errCard(err)); return; }
                if (workflows == null || workflows.isEmpty()) {
                    LinearLayout emptyCard = lv();
                    emptyCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
                    emptyCard.setPadding(dp(20), dp(24), dp(20), dp(24));
                    emptyCard.setGravity(Gravity.CENTER);
                    emptyCard.setLayoutParams(mpWrap());
                    TextView emptyTv = tv(
                        "No workflows found.\n\nGo to CI/CD → Builds and tap\n\"Set up CI/CD\" to create one.",
                        ThemeManager.DIM, 12f, false);
                    emptyTv.setGravity(Gravity.CENTER);
                    emptyTv.setLineSpacing(dp(2), 1.3f);
                    emptyCard.addView(emptyTv);
                    list.addView(emptyCard);
                    return;
                }
                for (GHApi.WorkflowDetail wf : workflows) {
                    list.addView(buildWorkflowCard(wf));
                    list.addView(sp(8));
                }
            }));
        return c;
    }

    private View buildWorkflowCard(final GHApi.WorkflowDetail wf) {
        LinearLayout card = lv();
        card.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setLayoutParams(mpWrap());

        // Name + state
        LinearLayout headRow = lh(Gravity.CENTER_VERTICAL);
        headRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams)headRow.getLayoutParams()).bottomMargin = dp(8);
        boolean isActive = "active".equals(wf.state);
        TextView nameTv = tv("⚙  " + wf.name, ThemeManager.TEXT, 13f, true);
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(nameTv);
        headRow.addView(badgeChip(wf.state, isActive ? ThemeManager.SUCCESS : ThemeManager.DIM,
            isActive ? ThemeManager.SUCCESS_D : ThemeManager.SURFACE2));
        card.addView(headRow);

        // Path
        TextView pathTv = tv(wf.path, ThemeManager.DIM, 9.5f, false);
        pathTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ptLp.bottomMargin = dp(10); pathTv.setLayoutParams(ptLp);
        card.addView(pathTv);

        // Trigger chips
        LinearLayout triggerRow = lh(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        trLp.bottomMargin = dp(10); triggerRow.setLayoutParams(trLp);
        TextView trigLbl = tv("Triggers: ", ThemeManager.DIM, 9.5f, false);
        triggerRow.addView(trigLbl);
        if (wf.hasDispatch)    triggerRow.addView(makeTriggerChip("manual",  ThemeManager.SUCCESS));
        if (wf.hasPush)        triggerRow.addView(makeTriggerChip("push",    ThemeManager.BRAND));
        if (wf.hasPullRequest) triggerRow.addView(makeTriggerChip("PR",      ThemeManager.AMBER));
        if (wf.hasSchedule)    triggerRow.addView(makeTriggerChip("schedule",ThemeManager.DIM));
        if (!wf.hasDispatch && !wf.hasPush && !wf.hasPullRequest && !wf.hasSchedule)
            triggerRow.addView(makeTriggerChip("unknown", ThemeManager.DIM));
        card.addView(triggerRow);

        // Warning if no workflow_dispatch
        if (!wf.hasDispatch) {
            LinearLayout warnRow = lh(Gravity.CENTER_VERTICAL);
            warnRow.setBackground(rbs(0xFF1C1400, 8, ThemeManager.AMBER));
            warnRow.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams wrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            wrLp.bottomMargin = dp(10); warnRow.setLayoutParams(wrLp);
            TextView warnTv = tv(
                "⚠  Can\'t trigger manually — missing workflow_dispatch",
                ThemeManager.AMBER, 10f, false);
            warnTv.setLineSpacing(dp(1), 1.2f);
            warnTv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            warnRow.addView(warnTv);
            TextView fixBtn = roundBtn("Fix", ThemeManager.AMBER, ThemeManager.AMBER_D);
            fixBtn.setOnClickListener(v -> showConfirm(
                "Add Manual Trigger?",
                "This will add workflow_dispatch to \"" + wf.name + "\".\n\nYou will then be able to trigger it from the Builds tab.",
                "Add Trigger", ThemeManager.AMBER, () -> {
                    fixBtn.setText("..."); fixBtn.setEnabled(false);
                    GHApi.addWorkflowDispatchTrigger(mPrefs.getToken(), mCurrentOwner,
                        mCurrentRepo, mCurrentBranch, wf.path, (ok, addErr) -> mHandler.post(() -> {
                            if (!ok) { showErr("Failed: " + addErr); return; }
                            toast("✓ Manual trigger added — reload to confirm");
                            wf.hasDispatch = true;
                            warnRow.setVisibility(View.GONE);
                        }));
                }));
            warnRow.addView(fixBtn);
            card.addView(warnRow);
        }

        // Inputs count
        if (!wf.inputs.isEmpty()) {
            card.addView(tv(wf.inputs.size() + " input(s) defined",
                ThemeManager.DIM, 9.5f, false));
        }

        return card;
    }

    private TextView makeTriggerChip(String label, int color) {
        TextView chip = badgeChip(label, color, 0);
        chip.setBackground(rbs(ThemeManager.SURFACE2, 6, color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(5); chip.setLayoutParams(lp);
        return chip;
    }


    // =========================================================================
    // PULL REQUESTS TAB
    // =========================================================================
    private View buildPRsTab() {
        LinearLayout c = lv(); c.setLayoutParams(mpWrap());

        final String[] filter = {"open"};
        LinearLayout filterRow = lh(Gravity.CENTER_VERTICAL);
        filterRow.setBackground(rbs(ThemeManager.SURFACE, 10, ThemeManager.BORDER));
        LinearLayout.LayoutParams frLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        frLp.bottomMargin = dp(12); filterRow.setLayoutParams(frLp);
        final TextView openBtn  = tv("Open",   ThemeManager.BRAND, 12f, true);
        final TextView closeBtn = tv("Closed", ThemeManager.DIM,   12f, false);
        openBtn.setGravity(Gravity.CENTER);  openBtn.setPadding(0, dp(10), 0, dp(10));
        closeBtn.setGravity(Gravity.CENTER); closeBtn.setPadding(0, dp(10), 0, dp(10));
        openBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        closeBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        filterRow.addView(openBtn); filterRow.addView(closeBtn);
        c.addView(filterRow);

        LinearLayout topRow = lh(Gravity.CENTER_VERTICAL);
        topRow.setLayoutParams(mpWrap());
        ((LinearLayout.LayoutParams)topRow.getLayoutParams()).bottomMargin = dp(10);
        final TextView countTv = tv("", ThemeManager.DIM, 10f, false);
        countTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(countTv);
        c.addView(topRow);

        final LinearLayout list = lv();
        list.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        list.setLayoutParams(mpWrap());
        list.addView(loadingRow());
        c.addView(list);

        final Runnable[] loadPRs = {null};
        loadPRs[0] = () -> {
            list.removeAllViews(); list.addView(loadingRow());
            GHApi.listPRs(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                filter[0], (prs, err) -> mHandler.post(() -> {
                    list.removeAllViews();
                    if (err != null) { list.addView(errCard(err)); return; }
                    if (prs == null || prs.isEmpty()) {
                        TextView empty = tv(
                            filter[0].equals("open") ? "No open pull requests." : "No closed pull requests.",
                            ThemeManager.DIM, 12f, false);
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding(0, dp(32), 0, dp(32));
                        list.addView(empty);
                        countTv.setText("0 " + filter[0]);
                        return;
                    }
                    countTv.setText(prs.size() + " " + filter[0]);
                    for (int i = 0; i < prs.size(); i++) {
                        list.addView(buildPRRow(prs.get(i), loadPRs[0]));
                        if (i < prs.size() - 1) list.addView(rowDivider());
                    }
                }));
        };

        openBtn.setOnClickListener(v -> {
            filter[0] = "open";
            openBtn.setTextColor(ThemeManager.BRAND); openBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            closeBtn.setTextColor(ThemeManager.DIM);  closeBtn.setTypeface(android.graphics.Typeface.DEFAULT);
            loadPRs[0].run();
        });
        closeBtn.setOnClickListener(v -> {
            filter[0] = "closed";
            closeBtn.setTextColor(ThemeManager.BRAND); closeBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            openBtn.setTextColor(ThemeManager.DIM);    openBtn.setTypeface(android.graphics.Typeface.DEFAULT);
            loadPRs[0].run();
        });

        loadPRs[0].run();
        return c;
    }

    private View buildPRRow(final GHApi.PullRequest pr, final Runnable reload) {
        LinearLayout row = lv();
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);

        LinearLayout titleRow = lh(Gravity.CENTER_VERTICAL);
        // State chip
        boolean isMerged = pr.mergedAt != null && !pr.mergedAt.isEmpty();
        String stateText  = isMerged ? "merged" : pr.state;
        int    stateColor = isMerged ? ThemeManager.BRAND :
                            "open".equals(pr.state) ? ThemeManager.SUCCESS : ThemeManager.DIM;
        int    stateBg    = isMerged ? ThemeManager.BRAND_D :
                            "open".equals(pr.state) ? ThemeManager.SUCCESS_D : ThemeManager.SURFACE2;
        TextView chip = badgeChip("#" + pr.number, stateColor, stateBg);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = dp(8); chip.setLayoutParams(cLp);
        titleRow.addView(chip);
        if (pr.draft) {
            TextView draftChip = badgeChip("draft", ThemeManager.DIM, ThemeManager.SURFACE2);
            LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dcLp.rightMargin = dp(6); draftChip.setLayoutParams(dcLp);
            titleRow.addView(draftChip);
        }
        TextView titleTv = tv(pr.title, ThemeManager.TEXT, 13f, true);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(titleTv);
        row.addView(titleRow);

        String date = pr.updatedAt.length() > 10 ? pr.updatedAt.substring(0, 10) : pr.updatedAt;
        String meta = pr.headBranch + " → " + pr.baseBranch + "  ·  " + pr.authorLogin + "  ·  " + date;
        if (pr.comments > 0) meta += "  💬 " + pr.comments;
        TextView metaTv = tv(meta, ThemeManager.DIM, 9.5f, false);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = dp(4); metaTv.setLayoutParams(mLp);
        row.addView(metaTv);

        if (pr.changedFiles > 0) {
            LinearLayout statsRow = lh(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            srLp.topMargin = dp(5); statsRow.setLayoutParams(srLp);
            statsRow.addView(tv("+" + pr.additions, ThemeManager.SUCCESS, 9.5f, true));
            statsRow.addView(tv("  -" + pr.deletions, ThemeManager.DANGER, 9.5f, true));
            statsRow.addView(tv("  " + pr.changedFiles + " files", ThemeManager.DIM, 9.5f, false));
            row.addView(statsRow);
        }

        row.setOnClickListener(v -> navigateTo(() -> setContentView(buildPRDetailPage(pr, reload))));
        applyRipple(row);
        return row;
    }

    private View buildPRDetailPage(final GHApi.PullRequest pr, final Runnable reload) {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);

        boolean isMerged = pr.mergedAt != null && !pr.mergedAt.isEmpty();
        LinearLayout header = buildHeader2("#" + pr.number + "  " + pr.title, mCurrentRepo, null);
        LinearLayout headerActs = (LinearLayout) header.getTag();

        // Merge button (only if open and not draft)
        if ("open".equals(pr.state) && !pr.draft && !isMerged) {
            TextView mergeBtn = roundBtn("Merge", ThemeManager.BRAND, ThemeManager.BRAND_D);
            mergeBtn.setOnClickListener(v ->
                showMenu("Merge PR #" + pr.number,
                    new String[]{"Merge commit", "Squash and merge", "Rebase and merge"},
                    new Runnable[]{
                        () -> doMergePR(pr, "merge",  mergeBtn, reload),
                        () -> doMergePR(pr, "squash", mergeBtn, reload),
                        () -> doMergePR(pr, "rebase", mergeBtn, reload),
                    }));
            headerActs.addView(mergeBtn);

            TextView closeBtn2 = roundBtn("Close", ThemeManager.DANGER, ThemeManager.DANGER_D);
            closeBtn2.setOnClickListener(v -> showConfirm("Close PR?",
                "This will close #" + pr.number + " without merging.",
                "Close", ThemeManager.DANGER, () -> {
                    closeBtn2.setText("..."); closeBtn2.setEnabled(false);
                    GHApi.closePR(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                        pr.number, (ok, err) -> mHandler.post(() -> {
                            if (!ok) { closeBtn2.setEnabled(true); closeBtn2.setText("Close");
                                showErr("Failed: " + err); return; }
                            toast("PR closed"); pr.state = "closed";
                            if (reload != null) reload.run();
                            onBackPressed();
                        }));
                }));
            headerActs.addView(closeBtn2);
        }
        outer.addView(header);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG); sv.setLayoutParams(mpWrap());
        LinearLayout c = lv(); c.setPadding(dp(16), dp(12), dp(16), dp(40));

        // Meta card
        boolean mergedFinal = isMerged;
        String stateLabel = mergedFinal ? "MERGED" : "open".equals(pr.state) ? "OPEN" : "CLOSED";
        int stateColor = mergedFinal ? ThemeManager.BRAND :
                         "open".equals(pr.state) ? ThemeManager.SUCCESS : ThemeManager.DIM;
        LinearLayout metaCard = lh(Gravity.CENTER_VERTICAL);
        metaCard.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        metaCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mcLp.bottomMargin = dp(12); metaCard.setLayoutParams(mcLp);
        metaCard.addView(badgeChip(stateLabel, stateColor,
            mergedFinal ? ThemeManager.BRAND_D : "open".equals(pr.state) ? ThemeManager.SUCCESS_D : ThemeManager.SURFACE2));
        TextView byTv = tv("  " + pr.headBranch + " → " + pr.baseBranch + "  ·  by " + pr.authorLogin,
            ThemeManager.DIM, 10f, false);
        byTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        metaCard.addView(byTv);
        c.addView(metaCard);

        // Description
        if (pr.body != null && !pr.body.trim().isEmpty()) {
            LinearLayout bodyCard = lv();
            bodyCard.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
            bodyCard.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams bcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bcLp.bottomMargin = dp(14); bodyCard.setLayoutParams(bcLp);
            bodyCard.addView(tv(pr.body, ThemeManager.TEXT2, 12.5f, false));
            c.addView(bodyCard);
        }

        // Files changed section
        TextView filesSec = secLabel("FILES CHANGED");
        LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fsLp.bottomMargin = dp(6); filesSec.setLayoutParams(fsLp);
        c.addView(filesSec);
        final LinearLayout filesList = lv();
        filesList.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        filesList.setLayoutParams(mpWrap());
        filesList.addView(loadingRow());
        LinearLayout.LayoutParams flLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flLp.bottomMargin = dp(16); filesList.setLayoutParams(flLp);
        c.addView(filesList);

        // Comments section
        TextView commentsSec = secLabel("COMMENTS");
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        csLp.bottomMargin = dp(6); commentsSec.setLayoutParams(csLp);
        c.addView(commentsSec);
        final LinearLayout commentsList = lv();
        commentsList.setLayoutParams(mpWrap());
        commentsList.addView(loadingRow());
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clLp.bottomMargin = dp(14); commentsList.setLayoutParams(clLp);
        c.addView(commentsList);

        // Add comment
        c.addView(secLabel("ADD COMMENT"));
        c.addView(sp(6));
        final EditText commentEt = new EditText(this);
        commentEt.setHint("Write a comment...");
        commentEt.setHintTextColor(ThemeManager.DIM);
        commentEt.setTextColor(ThemeManager.TEXT); commentEt.setTextSize(12.5f);
        commentEt.setBackground(rbs(ThemeManager.SURFACE, 10, ThemeManager.BORDER));
        commentEt.setPadding(dp(12), dp(10), dp(12), dp(10));
        commentEt.setMinLines(3); commentEt.setGravity(Gravity.TOP);
        commentEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams ceLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ceLp.bottomMargin = dp(10); commentEt.setLayoutParams(ceLp);
        c.addView(commentEt);
        final TextView submitBtn = primaryBtn("Submit Comment", ThemeManager.BRAND);
        submitBtn.setOnClickListener(v -> {
            String body = commentEt.getText().toString().trim();
            if (body.isEmpty()) { toast("Write something first."); return; }
            submitBtn.setText("Posting..."); submitBtn.setEnabled(false);
            GHApi.addPRComment(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                pr.number, body, (ok, err) -> mHandler.post(() -> {
                    submitBtn.setText("Submit Comment"); submitBtn.setEnabled(true);
                    if (!ok) { showErr("Failed: " + err); return; }
                    commentEt.setText(""); toast("Comment posted!");
                    loadPRComments(pr.number, commentsList);
                }));
        });
        c.addView(submitBtn);
        sv.addView(c); outer.addView(sv);

        // Load files + comments
        GHApi.getPRFiles(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            pr.number, (files, err) -> mHandler.post(() -> {
                filesList.removeAllViews();
                if (err != null) { filesList.addView(errCard(err)); return; }
                if (files == null || files.isEmpty()) {
                    filesList.addView(tv("No file changes.", ThemeManager.DIM, 11f, false)); return;
                }
                for (int i = 0; i < files.size(); i++) {
                    filesList.addView(buildPRFileRow(files.get(i)));
                    if (i < files.size() - 1) filesList.addView(rowDivider());
                }
            }));
        loadPRComments(pr.number, commentsList);
        return outer;
    }

    private void doMergePR(GHApi.PullRequest pr, String method,
                            TextView mergeBtn, Runnable reload) {
        mergeBtn.setText("Merging..."); mergeBtn.setEnabled(false);
        GHApi.mergePR(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            pr.number, method, (ok, err) -> mHandler.post(() -> {
                mergeBtn.setText("Merge"); mergeBtn.setEnabled(true);
                if (!ok) { showErr("Merge failed: " + (err != null ? err : "unknown")); return; }
                toast("PR #" + pr.number + " merged!");
                if (reload != null) reload.run();
                onBackPressed();
            }));
    }

    private View buildPRFileRow(final GHApi.PRFile file) {
        LinearLayout row = lv();
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setClickable(true);

        LinearLayout topRow = lh(Gravity.CENTER_VERTICAL);
        int statusColor = "added".equals(file.status) ? ThemeManager.SUCCESS :
                          "removed".equals(file.status) ? ThemeManager.DANGER : ThemeManager.AMBER;
        String statusIcon = "added".equals(file.status) ? "+" :
                            "removed".equals(file.status) ? "-" : "~";
        TextView statusChip = badgeChip(statusIcon, statusColor, ThemeManager.SURFACE2);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.rightMargin = dp(8); statusChip.setLayoutParams(scLp);
        topRow.addView(statusChip);
        TextView nameTv = tv(file.filename, ThemeManager.TEXT, 11.5f, false);
        nameTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(nameTv);
        topRow.addView(tv("+" + file.additions + " -" + file.deletions, ThemeManager.DIM, 9.5f, false));
        row.addView(topRow);

        // Show patch on tap (collapsed by default)
        if (file.patch != null && !file.patch.isEmpty()) {
            final boolean[] expanded = {false};
            final LinearLayout patchView = lv();
            patchView.setVisibility(View.GONE);
            patchView.setBackgroundColor(0xFF050507);
            patchView.setPadding(dp(10), dp(8), dp(10), dp(8));
            patchView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            // Build colored diff
            android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder();
            for (String line : file.patch.split("\n")) {
                int color = line.startsWith("+") ? 0xFF33CC66 :
                            line.startsWith("-") ? 0xFFCC3333 :
                            line.startsWith("@@") ? ThemeManager.BRAND : 0xFF6B7280;
                int start = ssb.length();
                ssb.append(line).append("\n");
                ssb.setSpan(new android.text.style.ForegroundColorSpan(color),
                    start, ssb.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            TextView patchTv = new TextView(this);
            patchTv.setText(ssb);
            patchTv.setTypeface(android.graphics.Typeface.MONOSPACE);
            patchTv.setTextSize(9.5f);
            patchTv.setTextIsSelectable(true);
            patchView.addView(patchTv);
            row.addView(patchView);
            row.setOnClickListener(v -> {
                expanded[0] = !expanded[0];
                patchView.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            });
        }
        applyRipple(row);
        return row;
    }

    private void loadPRComments(int prNumber, LinearLayout container) {
        container.removeAllViews(); container.addView(loadingRow());
        GHApi.getPRComments(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            prNumber, (comments, err) -> mHandler.post(() -> {
                container.removeAllViews();
                if (err != null) { container.addView(errCard(err)); return; }
                if (comments == null || comments.isEmpty()) {
                    TextView none = tv("No comments yet.", ThemeManager.DIM, 11f, false);
                    none.setPadding(0, dp(8), 0, dp(8));
                    container.addView(none); return;
                }
                for (GHApi.IssueComment cm : comments) {
                    LinearLayout bubble = lv();
                    bubble.setBackground(rbs(ThemeManager.SURFACE, 10, ThemeManager.BORDER));
                    bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
                    LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    bLp.bottomMargin = dp(8); bubble.setLayoutParams(bLp);
                    LinearLayout topRow = lh(Gravity.CENTER_VERTICAL);
                    topRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                    ((LinearLayout.LayoutParams)topRow.getLayoutParams()).bottomMargin = dp(5);
                    TextView authorTv = tv(cm.userLogin, ThemeManager.BRAND, 10.5f, true);
                    authorTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    topRow.addView(authorTv);
                    String date = cm.createdAt.length() > 10 ? cm.createdAt.substring(0, 10) : cm.createdAt;
                    topRow.addView(tv(date, ThemeManager.DIM, 9f, false));
                    bubble.addView(topRow);
                    TextView bodyTv = tv(cm.body, ThemeManager.TEXT2, 12f, false);
                    bodyTv.setLineSpacing(dp(2), 1.25f);
                    bubble.addView(bodyTv);
                    container.addView(bubble);
                }
            }));
    }

    // =========================================================================
    // COMMIT DETAIL / DIFF
    // =========================================================================

    private View buildCommitDetailPage(final GHApi.CommitEntry commit) {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);
        LinearLayout header = buildHeader2(commit.shortSha, commit.author, null);
        outer.addView(header);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG); sv.setLayoutParams(mpWrap());
        LinearLayout c = lv(); c.setPadding(dp(16), dp(12), dp(16), dp(40));

        // Commit message
        LinearLayout msgCard = lv();
        msgCard.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        msgCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mcLp.bottomMargin = dp(14); msgCard.setLayoutParams(mcLp);
        msgCard.addView(tv(commit.message, ThemeManager.TEXT, 13f, false));
        String meta = commit.author + "  ·  " + (commit.date.length() > 10 ? commit.date.substring(0, 10) : commit.date);
        TextView metaTv = tv(meta, ThemeManager.DIM, 10f, false);
        LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mtLp.topMargin = dp(6); metaTv.setLayoutParams(mtLp);
        msgCard.addView(metaTv);
        // Full SHA
        TextView shaTv = tv(commit.sha, ThemeManager.DIM, 9f, false);
        shaTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams shaLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shaLp.topMargin = dp(4); shaTv.setLayoutParams(shaLp);
        msgCard.addView(shaTv);
        c.addView(msgCard);

        // Files changed
        TextView filesSec = secLabel("FILES CHANGED");
        LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fsLp.bottomMargin = dp(6); filesSec.setLayoutParams(fsLp);
        c.addView(filesSec);

        final LinearLayout filesList = lv();
        filesList.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        filesList.setLayoutParams(mpWrap());
        filesList.addView(loadingRow());
        c.addView(filesList);

        sv.addView(c); outer.addView(sv);

        GHApi.getCommitDiff(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            commit.sha, (files, err) -> mHandler.post(() -> {
                filesList.removeAllViews();
                if (err != null) { filesList.addView(errCard(err)); return; }
                if (files == null || files.isEmpty()) {
                    filesList.addView(tv("No file changes.", ThemeManager.DIM, 11f, false));
                    return;
                }
                // Stats summary
                int totalAdd = 0, totalDel = 0;
                for (GHApi.CommitDiffFile f : files) { totalAdd += f.additions; totalDel += f.deletions; }
                LinearLayout statsRow = lh(Gravity.CENTER_VERTICAL);
                statsRow.setPadding(dp(12), dp(8), dp(12), dp(8));
                statsRow.setBackgroundColor(ThemeManager.SURFACE2);
                statsRow.setLayoutParams(mpWrap());
                statsRow.addView(tv(files.size() + " files", ThemeManager.DIM, 10f, false));
                statsRow.addView(tv("  +" + totalAdd, ThemeManager.SUCCESS, 10f, true));
                statsRow.addView(tv("  -" + totalDel, ThemeManager.DANGER,  10f, true));
                filesList.addView(statsRow);

                for (int i = 0; i < files.size(); i++) {
                    GHApi.CommitDiffFile f = files.get(i);
                    // Reuse PR file row logic via a PRFile wrapper
                    GHApi.PRFile prf = new GHApi.PRFile(f.filename, f.status, f.patch,
                        f.additions, f.deletions, f.additions + f.deletions);
                    filesList.addView(buildPRFileRow(prf));
                    if (i < files.size() - 1) filesList.addView(rowDivider());
                }
            }));
        return outer;
    }

    // =========================================================================
    // UNIVERSAL CI/CD SETUP (replaces Android-only showCiCdSetupDialog)
    // =========================================================================
    private void showUniversalCiCdSetup() {
        android.app.Dialog prog = makeProgressDialog();
        ((TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0))
            .setText("Scanning project...");
        prog.show();

        GHApi.detectProjectContext(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            mCurrentBranch, (ctx, err) -> mHandler.post(() -> {
                prog.dismiss();
                final GHApi.RepoContext finalCtx = (ctx != null) ? ctx : new GHApi.RepoContext();
                final String finalLabel = finalCtx.label;
                final String finalYaml  = GHApi.generateAdaptiveYaml(finalCtx,
                    mPrefs.isSigningEnabled() && mPrefs.hasSigningConfig());

                // Tampilkan detail scan result ke user
                String detailMsg = "Detected: " + finalLabel;
                if (finalCtx.type == GHApi.ProjectType.NODE_JS) {
                    detailMsg += "\n• Package manager: " + finalCtx.nodePackageManager;
                    detailMsg += "\n• Lock file: " + (finalCtx.hasLockFile ? "yes → npm ci" : "no → npm install");
                    detailMsg += "\n• Test script: " + (finalCtx.hasTestScript ? "yes" : "no");
                    detailMsg += "\n• Build script: " + (finalCtx.hasBuildScript ? "yes" : "no");
                } else if (finalCtx.type == GHApi.ProjectType.PYTHON) {
                    detailMsg += "\n• Poetry: " + (finalCtx.hasPoetry ? "yes" : "no");
                    detailMsg += "\n• Pytest: " + (finalCtx.hasPytest ? "yes" : "no");
                } else if (finalCtx.type == GHApi.ProjectType.JAVA_LIB) {
                    detailMsg += finalCtx.hasPomXml ? "\n• Build: Maven" : "\n• Build: Gradle";
                } else if (finalCtx.type == GHApi.ProjectType.ANDROID_NDK) {
                    detailMsg += "\n• CMakeLists.txt: " + (finalCtx.hasCMakeLists ? "yes" : "no");
                    detailMsg += "\n• Android.mk: " + (finalCtx.hasAndroidMk ? "yes" : "no");
                    detailMsg += "\n• NDK version: " + finalCtx.ndkVersion;
                    detailMsg += "\n• C++ standard: " + finalCtx.cppStandard;
                } else if (finalCtx.type == GHApi.ProjectType.CPP_CMAKE) {
                    detailMsg += "\n• Build: CMake standalone";
                    detailMsg += "\n• Output: build/";
                } else if (finalCtx.type == GHApi.ProjectType.CPP_NDKBUILD) {
                    detailMsg += "\n• Android.mk: " + (finalCtx.hasAndroidMk ? "yes" : "no");
                    detailMsg += "\n• Application.mk: " + (finalCtx.hasApplicationMk ? "yes" : "no");
                    detailMsg += "\n• jni/ dir: " + (finalCtx.hasJniDir ? "yes" : "no");
                    detailMsg += "\n• NDK version: " + finalCtx.ndkVersion;
                    detailMsg += "\n• Output: libs/";
                }
                if (err != null) detailMsg += "\n\n⚠ Scan warning: " + err;

                showMenu(detailMsg,
                    new String[]{
                        "✓  Create Workflow  (" + finalLabel + ")",
                        "Preview YAML",
                        "Choose Different Type",
                        "Cancel"
                    },
                    new Runnable[]{
                        () -> doCreateUniversalWorkflow(finalCtx.type, finalYaml),
                        () -> showAlert("Workflow YAML", finalYaml),
                        () -> showProjectTypePicker(),
                        null
                    });
            }));
    }

    private String getProjectTypeLabel(GHApi.ProjectType t) {
        switch (t) {
            case ANDROID:      return "Android";
            case ANDROID_NDK:  return "Android-Native";
            case FLUTTER:      return "Flutter";
            case NODE_JS:      return "Node.js";
            case PYTHON:       return "Python";
            case REACT_NATIVE: return "React-Native";
            case JAVA_LIB:     return "Java";
            case DOTNET:       return "DotNET";
            case RUST:         return "Rust";
            case GO:           return "Go";
            case PHP:          return "PHP";
            case RUBY:         return "Ruby";
            case DOCKER_ONLY:  return "Docker";
            case CPP_CMAKE:    return "C++-CMake";
            case CPP_NDKBUILD: return "C++-NDKBuild";
            default:           return "Generic";
        }
    }

    private void showProjectTypePicker() {
        GHApi.ProjectType[] types = GHApi.ProjectType.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) labels[i] = getProjectTypeLabel(types[i]);
        Runnable[] actions = new Runnable[types.length];
        for (int i = 0; i < types.length; i++) {
            final GHApi.ProjectType t = types[i];
            actions[i] = () -> {
                // Re-scan repo tapi override type sesuai pilihan user
                android.app.Dialog prog = makeProgressDialog();
                ((TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0))
                    .setText("Scanning...");
                prog.show();
                GHApi.detectProjectContext(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    mCurrentBranch, (ctx, e2) -> mHandler.post(() -> {
                        prog.dismiss();
                        GHApi.RepoContext overrideCtx = ctx != null ? ctx : new GHApi.RepoContext();
                        overrideCtx.type  = t;
                        overrideCtx.label = getProjectTypeLabel(t);
                        String yaml = GHApi.generateAdaptiveYaml(overrideCtx,
                            mPrefs.isSigningEnabled() && mPrefs.hasSigningConfig());
                        doCreateUniversalWorkflow(t, yaml);
                    }));
            };
        }
        showMenu("Select Project Type", labels, actions);
    }

    private void doCreateUniversalWorkflow(GHApi.ProjectType type, String yamlContent) {
        android.app.Dialog prog = makeProgressDialog();
        ((TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0))
            .setText("Creating workflow...");
        prog.show();
        String path = ".github/workflows/" + getProjectTypeLabel(type).toLowerCase()
            .replace(" ", "-").replace("/", "-") + "-ci.yml";
        GHApi.uploadFile(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            path, yamlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "Add CI/CD workflow for " + getProjectTypeLabel(type) + " [skip ci]", null,
            mCurrentBranch,
            (ok, err) -> mHandler.post(() -> {
                prog.dismiss();
                if (!ok) { showErr("Failed: " + err); return; }
                toast("✓ Workflow created for " + getProjectTypeLabel(type));
                while (mNavStack.size() > 1) mNavStack.pop();
                navigateTo(() -> setContentView(buildRepoPageNew(1, 3)));
            }));
    }


    // =========================================================================
    // EXPLORE / SEARCH PAGE
    // =========================================================================

    private static final String[] LANGUAGES = {
        "All", "Java", "Kotlin", "Python", "JavaScript", "TypeScript",
        "C++", "C", "Go", "Rust", "Swift", "Dart", "PHP", "Ruby", "Shell"
    };

    private String mExploreQuery    = "";
    private String mExploreLang     = "";
    private int    mExploreGroup    = 0; // 0=Trending 1=Repos 2=Users
    private java.util.List<GHApi.SearchRepo>  mExploreCachedRepos = null;
    private java.util.List<GHApi.SearchUser>  mExploreCachedUsers = null;
    private java.util.List<GHApi.SearchRepo>  mExploreTrending    = null;

    private View buildExplorePage() {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);

        // ── Header ──────────────────────────────────────────────────────────
        LinearLayout header = lh(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(ThemeManager.SURFACE);
        header.setPadding(dp(10), dp(10), dp(10), dp(10));
        header.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Back button
        TextView backBtn = iconBtn("←", ThemeManager.TEXT2, () -> onBackPressed());
        header.addView(backBtn);

        // Search input
        final EditText searchEt = new EditText(this);
        searchEt.setHint("Search repos, users...");
        searchEt.setHintTextColor(ThemeManager.DIM);
        searchEt.setTextColor(ThemeManager.TEXT);
        searchEt.setTextSize(13f);
        searchEt.setSingleLine(true);
        searchEt.setBackground(rbs(ThemeManager.SURFACE2, 10, ThemeManager.BORDER));
        searchEt.setPadding(dp(12), dp(9), dp(12), dp(9));
        searchEt.setText(mExploreQuery);
        searchEt.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etLp.leftMargin = dp(8); etLp.rightMargin = dp(8);
        searchEt.setLayoutParams(etLp);
        header.addView(searchEt);

        // Search button
        TextView goBtn = tv("Search", ThemeManager.BG, 12f, true);
        goBtn.setBackground(rb(ThemeManager.CYAN, 10));
        goBtn.setPadding(dp(12), dp(9), dp(12), dp(9));
        header.addView(goBtn);
        outer.addView(header);

        // ── Tab bar: Trending | Repos | Users ───────────────────────────────
        final int[] activeGroup = {mExploreGroup};
        final String[] tabs = {"🔥 Trending", "📦 Repos", "👤 Users"};
        final int[] tabCols = {ThemeManager.AMBER, ThemeManager.CYAN, ThemeManager.BRAND};
        final TextView[] tabBtns = new TextView[3];

        LinearLayout tabBar = lh(Gravity.CENTER_VERTICAL);
        tabBar.setBackgroundColor(ThemeManager.SURFACE);
        tabBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        final LinearLayout[] resultHolder = {null};

        // ── Language filter bar ──────────────────────────────────────────────
        android.widget.HorizontalScrollView langScroll = new android.widget.HorizontalScrollView(this);
        langScroll.setHorizontalScrollBarEnabled(false);
        langScroll.setBackgroundColor(ThemeManager.SURFACE2);
        langScroll.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        LinearLayout langBar = lh(Gravity.CENTER_VERTICAL);
        langBar.setPadding(dp(8), 0, dp(8), 0);
        final TextView[] langBtns = new TextView[LANGUAGES.length];
        for (int i = 0; i < LANGUAGES.length; i++) {
            final int lidx = i;
            final String lang = LANGUAGES[i];
            boolean isSelected = lang.equals("All") ? mExploreLang.isEmpty()
                : lang.equals(mExploreLang);
            langBtns[i] = tv(lang, isSelected ? ThemeManager.BG : ThemeManager.TEXT2,
                10.5f, isSelected);
            int bg = isSelected ? ThemeManager.CYAN : ThemeManager.SURFACE;
            langBtns[i].setBackground(rbs(bg, 8,
                isSelected ? ThemeManager.CYAN : ThemeManager.BORDER));
            langBtns[i].setPadding(dp(10), dp(6), dp(10), dp(6));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.rightMargin = dp(6); langBtns[i].setLayoutParams(llp);
            langBtns[i].setOnClickListener(v -> {
                // Deselect all
                for (int j = 0; j < langBtns.length; j++) {
                    langBtns[j].setTextColor(ThemeManager.TEXT2);
                    langBtns[j].setBackground(rbs(ThemeManager.SURFACE, 8, ThemeManager.BORDER));
                    langBtns[j].setTypeface(android.graphics.Typeface.DEFAULT);
                }
                langBtns[lidx].setTextColor(ThemeManager.BG);
                langBtns[lidx].setBackground(rbs(ThemeManager.CYAN, 8, ThemeManager.CYAN));
                langBtns[lidx].setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                mExploreLang = lang.equals("All") ? "" : lang;
                mExploreTrending = null; // invalidate cache
                loadExploreContent(activeGroup[0], resultHolder[0]);
            });
            langBar.addView(langBtns[i]);
        }
        langScroll.addView(langBar);
        outer.addView(langScroll);

        // Content holder
        android.widget.FrameLayout contentFrame = new android.widget.FrameLayout(this);
        contentFrame.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.setWeightSum(1f);

        final LinearLayout resultsContainer = lv();
        resultsContainer.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        contentFrame.addView(resultsContainer);
        resultHolder[0] = resultsContainer;

        // Build tabs
        for (int i = 0; i < 3; i++) {
            final int tidx = i;
            final int tcol = tabCols[i];
            tabBtns[i] = tv(tabs[i], i == activeGroup[0] ? tcol : ThemeManager.DIM,
                10.5f, i == activeGroup[0]);
            tabBtns[i].setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            tabBtns[i].setGravity(Gravity.CENTER);
            tabBtns[i].setOnClickListener(v -> {
                for (int j = 0; j < 3; j++) {
                    tabBtns[j].setTextColor(ThemeManager.DIM);
                    tabBtns[j].setTypeface(android.graphics.Typeface.DEFAULT);
                }
                tabBtns[tidx].setTextColor(tcol);
                tabBtns[tidx].setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                activeGroup[0] = tidx;
                mExploreGroup  = tidx;
                loadExploreContent(tidx, resultsContainer);
            });
            applyRipple(tabBtns[i]);
            tabBar.addView(tabBtns[i]);
        }

        // Tab underline
        View tabLine = new View(this);
        tabLine.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        tabLine.setBackgroundColor(ThemeManager.BORDER);

        outer.addView(tabBar);
        outer.addView(tabLine);
        outer.addView(contentFrame);

        // ── Search action ────────────────────────────────────────────────────
        Runnable doSearch = () -> {
            String q = searchEt.getText().toString().trim();
            mExploreQuery = q;
            mExploreCachedRepos = null;
            mExploreCachedUsers = null;
            if (!q.isEmpty()) {
                // Auto-switch: if query looks like user search use Users tab
                activeGroup[0] = mExploreGroup == 2 ? 2 : 1;
                mExploreGroup  = activeGroup[0];
                for (int j = 0; j < 3; j++) {
                    tabBtns[j].setTextColor(ThemeManager.DIM);
                    tabBtns[j].setTypeface(android.graphics.Typeface.DEFAULT);
                }
                tabBtns[activeGroup[0]].setTextColor(tabCols[activeGroup[0]]);
                tabBtns[activeGroup[0]].setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }
            // Hide keyboard
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchEt.getWindowToken(), 0);
            loadExploreContent(activeGroup[0], resultsContainer);
        };

        goBtn.setOnClickListener(v -> doSearch.run());
        searchEt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                doSearch.run(); return true;
            }
            return false;
        });

        // Load initial content
        loadExploreContent(activeGroup[0], resultsContainer);

        // Auto-focus if fresh open
        if (mExploreQuery.isEmpty()) {
            searchEt.requestFocus();
            mHandler.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(searchEt, 0);
            }, 200);
        }

        return outer;
    }

    private void loadExploreContent(int group, LinearLayout container) {
        container.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG);
        sv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout list = lv();
        list.setPadding(dp(16), dp(12), dp(16), dp(60));
        list.addView(loadingRow());
        sv.addView(list);
        container.addView(sv);

        if (group == 0) {
            // Trending
            if (mExploreTrending != null) {
                renderRepoResults(mExploreTrending, list, true);
                return;
            }
            GHApi.getTrendingRepos(mPrefs.getToken(), mExploreLang,
                (repos, err) -> mHandler.post(() -> {
                    list.removeAllViews();
                    if (err != null) { list.addView(errCard(err)); return; }
                    mExploreTrending = repos;
                    renderRepoResults(repos, list, true);
                }));
        } else if (group == 1) {
            // Repo search
            if (mExploreCachedRepos != null && !mExploreQuery.isEmpty()) {
                renderRepoResults(mExploreCachedRepos, list, false);
                return;
            }
            if (mExploreQuery.isEmpty()) {
                list.removeAllViews();
                renderSearchPrompt(list, "📦", "Search Repositories",
                    "Type a name, topic, or description to find repos");
                return;
            }
            GHApi.searchRepos(mPrefs.getToken(), mExploreQuery, mExploreLang, "stars", 1,
                (repos, err) -> mHandler.post(() -> {
                    list.removeAllViews();
                    if (err != null) { list.addView(errCard(err)); return; }
                    mExploreCachedRepos = repos;
                    renderRepoResults(repos, list, false);
                }));
        } else {
            // User search
            if (mExploreCachedUsers != null && !mExploreQuery.isEmpty()) {
                renderUserResults(mExploreCachedUsers, list);
                return;
            }
            if (mExploreQuery.isEmpty()) {
                list.removeAllViews();
                renderSearchPrompt(list, "👤", "Search Users",
                    "Find developers and organizations on GitHub");
                return;
            }
            GHApi.searchUsers(mPrefs.getToken(), mExploreQuery,
                (users, err) -> mHandler.post(() -> {
                    list.removeAllViews();
                    if (err != null) { list.addView(errCard(err)); return; }
                    mExploreCachedUsers = users;
                    renderUserResults(users, list);
                }));
        }
    }

    private void renderSearchPrompt(LinearLayout list, String icon, String title, String sub) {
        LinearLayout card = lv();
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(24), dp(60), dp(24), dp(40));
        card.setLayoutParams(mpWrap());
        TextView iconTv = tv(icon, ThemeManager.DIM, 40f, false);
        iconTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iLp.bottomMargin = dp(12); iconTv.setLayoutParams(iLp);
        card.addView(iconTv);
        TextView t = tv(title, ThemeManager.TEXT, 15f, true);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.bottomMargin = dp(8); t.setLayoutParams(tLp);
        card.addView(t);
        TextView s = tv(sub, ThemeManager.DIM, 11.5f, false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(dp(2), 1.3f);
        card.addView(s);
        list.addView(card);
    }

    private void renderRepoResults(java.util.List<GHApi.SearchRepo> repos, LinearLayout list,
                                    boolean isTrending) {
        if (repos == null || repos.isEmpty()) {
            renderSearchPrompt(list, "🔍", "No results found",
                "Try a different search term or language filter");
            return;
        }
        if (isTrending) {
            TextView hdr = secLabel("TRENDING THIS WEEK");
            hdr.setLetterSpacing(0.08f);
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hLp.bottomMargin = dp(10); hdr.setLayoutParams(hLp);
            list.addView(hdr);
        }
        LinearLayout card = lv();
        card.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        card.setLayoutParams(mpWrap());
        for (int i = 0; i < repos.size(); i++) {
            card.addView(buildExploreRepoRow(repos.get(i)));
            if (i < repos.size() - 1) card.addView(rowDivider());
        }
        list.addView(card);
    }

    private View buildExploreRepoRow(final GHApi.SearchRepo repo) {
        LinearLayout row = lv();
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        row.setClickable(true);

        // Owner/Repo name
        LinearLayout nameRow = lh(Gravity.CENTER_VERTICAL);
        nameRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams)nameRow.getLayoutParams()).bottomMargin = dp(4);

        TextView ownerTv = tv(repo.owner + " / ", ThemeManager.DIM, 12f, false);
        nameRow.addView(ownerTv);
        TextView nameTv = tv(repo.name, ThemeManager.TEXT, 13f, true);
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        nameRow.addView(nameTv);
        if (repo.isFork) {
            nameRow.addView(badgeChip("fork", ThemeManager.DIM, ThemeManager.SURFACE2));
        }
        row.addView(nameRow);

        // Description
        if (repo.description != null && !repo.description.isEmpty()) {
            TextView descTv = tv(repo.description, ThemeManager.TEXT2, 11.5f, false);
            descTv.setMaxLines(2);
            descTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dLp.bottomMargin = dp(6); descTv.setLayoutParams(dLp);
            row.addView(descTv);
        }

        // Stats row: language · stars · forks
        LinearLayout statsRow = lh(Gravity.CENTER_VERTICAL);
        statsRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (repo.language != null && !repo.language.isEmpty()) {
            View langDot = new View(this);
            int langColor = getLangColor(repo.language);
            LinearLayout.LayoutParams ldLp = new LinearLayout.LayoutParams(dp(8), dp(8));
            ldLp.rightMargin = dp(5); langDot.setLayoutParams(ldLp);
            langDot.setBackground(rb(langColor, 4));
            statsRow.addView(langDot);
            TextView langTv = tv(repo.language, ThemeManager.DIM, 10f, false);
            LinearLayout.LayoutParams ltLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ltLp.rightMargin = dp(12); langTv.setLayoutParams(ltLp);
            statsRow.addView(langTv);
        }

        statsRow.addView(tv("⭐ " + fmtNum(repo.stars), ThemeManager.AMBER, 10f, false));
        TextView forksLabel = tv("  🍴 " + fmtNum(repo.forks), ThemeManager.DIM, 10f, false);
        statsRow.addView(forksLabel);

        if (repo.topics != null && !repo.topics.isEmpty()) {
            TextView topicsTv = tv("  · " + repo.topics, ThemeManager.BRAND, 9.5f, false);
            topicsTv.setMaxLines(1);
            topicsTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            topicsTv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            statsRow.addView(topicsTv);
        }
        row.addView(statsRow);

        row.setOnClickListener(v -> openPublicRepo(repo));
        applyRipple(row);
        return row;
    }

    private void renderUserResults(java.util.List<GHApi.SearchUser> users, LinearLayout list) {
        if (users == null || users.isEmpty()) {
            renderSearchPrompt(list, "👤", "No users found", "Try a different username or keyword");
            return;
        }
        LinearLayout card = lv();
        card.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        card.setLayoutParams(mpWrap());
        for (int i = 0; i < users.size(); i++) {
            card.addView(buildExploreUserRow(users.get(i)));
            if (i < users.size() - 1) card.addView(rowDivider());
        }
        list.addView(card);
    }

    private View buildExploreUserRow(final GHApi.SearchUser user) {
        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);

        // Avatar placeholder (initial letter)
        LinearLayout avatar = new LinearLayout(this);
        String initial = user.login.isEmpty() ? "?" : user.login.substring(0, 1).toUpperCase();
        int avatarColor = (user.login.hashCode() & 0xFFFFFF) | 0xFF000000;
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        avLp.rightMargin = dp(12); avatar.setLayoutParams(avLp);
        avatar.setBackground(rb(avatarColor, 20));
        avatar.setGravity(Gravity.CENTER);
        avatar.addView(tv(initial, ThemeManager.BG, 14f, true));
        row.addView(avatar);

        LinearLayout info = lv();
        info.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        info.addView(tv(user.login, ThemeManager.TEXT, 13f, true));
        info.addView(tv(user.type.equals("Organization") ? "Organization" : "User",
            ThemeManager.DIM, 10f, false));
        row.addView(info);
        row.addView(tv("›", ThemeManager.DIM, 18f, false));

        row.setOnClickListener(v -> navigateTo(() -> setContentView(buildUserProfilePage(user.login))));
        applyRipple(row);
        return row;
    }

    /** Open a public repo — reuses the existing repo page seamlessly. */
    private void openPublicRepo(GHApi.SearchRepo repo) {
        stopBuildPolling();           // FIX: hentikan polling repo lama
        mLastRunStatus.clear();       // FIX: reset status map untuk repo baru
        mCurrentOwner  = repo.owner;
        mCurrentRepo   = repo.name;
        mCurrentBranch = repo.defaultBranch != null && !repo.defaultBranch.isEmpty()
            ? repo.defaultBranch : "main";
        mCachedFiles.evictAll();
        mCachedContent.evictAll();
        mCachedRuns = null;
        navigateTo(() -> setContentView(buildExploreRepoDetailPage(repo)));
    }

    /** Explore repo detail — like repo page but with extra public info + star/fork buttons. */
    private View buildExploreRepoDetailPage(final GHApi.SearchRepo repo) {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);

        // ── Header ───────────────────────────────────────────────────────────
        LinearLayout header = buildHeader2(repo.name, repo.owner, null);
        LinearLayout headerActs = (LinearLayout) header.getTag();

        final boolean[] starred = {false};
        final TextView starBtn = roundBtn("⭐ Star", ThemeManager.AMBER, ThemeManager.AMBER_D);
        starBtn.setEnabled(false);
        headerActs.addView(starBtn);

        TextView forkBtn = roundBtn("🍴 Fork", ThemeManager.BRAND, ThemeManager.BRAND_D);
        forkBtn.setOnClickListener(v -> showConfirm("Fork " + repo.fullName + "?",
            "A copy will be created in your account.", "Fork", ThemeManager.BRAND, () -> {
                forkBtn.setText("Forking…"); forkBtn.setEnabled(false);
                GHApi.forkRepo(mPrefs.getToken(), repo.owner, repo.name,
                    (forkedName, err) -> mHandler.post(() -> {
                        forkBtn.setText("🍴 Fork"); forkBtn.setEnabled(true);
                        if (err != null) { showErr("Fork failed: " + err); return; }
                        toast("✓ Forked as " + forkedName);
                    }));
            }));
        headerActs.addView(forkBtn);
        outer.addView(header);

        // Check star status async
        GHApi.isRepoStarred(mPrefs.getToken(), repo.owner, repo.name,
            (isStarred, err) -> mHandler.post(() -> {
                if (err == null && isStarred != null) starred[0] = isStarred;
                starBtn.setText(starred[0] ? "★ Starred" : "⭐ Star");
                starBtn.setTextColor(starred[0] ? ThemeManager.AMBER : ThemeManager.TEXT2);
                starBtn.setEnabled(true);
                starBtn.setOnClickListener(v -> {
                    boolean ns = !starred[0];
                    starBtn.setText("…"); starBtn.setEnabled(false);
                    GHApi.starRepo(mPrefs.getToken(), repo.owner, repo.name, ns,
                        (ok, e2) -> mHandler.post(() -> {
                            if (ok != null && ok) starred[0] = ns;
                            starBtn.setText(starred[0] ? "★ Starred" : "⭐ Star");
                            starBtn.setTextColor(starred[0] ? ThemeManager.AMBER : ThemeManager.TEXT2);
                            starBtn.setEnabled(true);
                        }));
                });
            }));

        // ── Scroll Content ────────────────────────────────────────────────────
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG);
        sv.setLayoutParams(mpWrap());
        LinearLayout c = lv();
        c.setPadding(dp(14), dp(12), dp(14), dp(40));

        // ── About Card ───────────────────────────────────────────────────────
        LinearLayout aboutCard = lv();
        aboutCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        aboutCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        acLp.bottomMargin = dp(12); aboutCard.setLayoutParams(acLp);

        // Description
        if (repo.description != null && !repo.description.isEmpty()) {
            TextView descTv = tv(repo.description, ThemeManager.TEXT, 13f, false);
            descTv.setLineSpacing(dp(2), 1.35f);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dLp.bottomMargin = dp(12); descTv.setLayoutParams(dLp);
            aboutCard.addView(descTv);
        }

        // Stats row: stars · forks · issues · watchers
        LinearLayout statsRow = lh(Gravity.CENTER_VERTICAL);
        statsRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams)statsRow.getLayoutParams()).bottomMargin = dp(10);
        addStatChip(statsRow, "⭐", fmtNum(repo.stars), ThemeManager.AMBER);
        addStatChip(statsRow, "🍴", fmtNum(repo.forks), ThemeManager.CYAN);
        addStatChip(statsRow, "⚠", fmtNum(repo.openIssues), 0xFFE74C3C);
        aboutCard.addView(statsRow);

        // Topics chips (scrollable row)
        if (repo.topics != null && !repo.topics.isEmpty()) {
            android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hLp.bottomMargin = dp(10); hsv.setLayoutParams(hLp);
            LinearLayout topicsRow = lh(Gravity.CENTER_VERTICAL);
            topicsRow.setPadding(0, 0, dp(8), 0);
            for (String topic : repo.topics.split(", ")) {
                if (topic.trim().isEmpty()) continue;
                TextView chip = badgeChip(topic.trim(), ThemeManager.BRAND, ThemeManager.BRAND_D);
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                cLp.rightMargin = dp(6); chip.setLayoutParams(cLp);
                topicsRow.addView(chip);
            }
            hsv.addView(topicsRow);
            aboutCard.addView(hsv);
        }

        // Meta info rows (language, size, updated, homepage)
        final LinearLayout metaRows = lv();
        if (repo.language != null && !repo.language.isEmpty()) {
            metaRows.addView(buildMetaRow("●  Language", repo.language, getLangColor(repo.language)));
        }
        if (repo.size > 0) {
            String sizeStr = repo.size > 1024 ? (repo.size / 1024) + " MB" : repo.size + " KB";
            metaRows.addView(buildMetaRow("🗂  Size", sizeStr, ThemeManager.DIM));
        }
        if (repo.isFork) {
            metaRows.addView(buildMetaRow("🍴  Type", "Forked repository", ThemeManager.DIM));
        }
        if (repo.isPrivate) {
            metaRows.addView(buildMetaRow("🔒  Visibility", "Private", ThemeManager.DIM));
        }
        if (metaRows.getChildCount() > 0) {
            aboutCard.addView(metaRows);
        }
        c.addView(aboutCard);

        // ── Language Bar (loaded async) ───────────────────────────────────────
        final LinearLayout langBarHolder = lv();
        langBarHolder.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams)langBarHolder.getLayoutParams()).bottomMargin = dp(12);
        c.addView(langBarHolder);

        GHApi.getLanguages(mPrefs.getToken(), repo.owner, repo.name,
            (langs, err) -> mHandler.post(() -> {
                if (langs == null || langs.isEmpty()) return;
                langBarHolder.removeAllViews();
                LinearLayout langCard = lv();
                langCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
                langCard.setPadding(dp(14), dp(12), dp(14), dp(12));
                langCard.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                // Calculate total bytes
                long total = 0;
                for (long v : langs.values()) total += v;
                final long totalFinal = total;

                // Colored bar
                LinearLayout bar = lh(Gravity.CENTER_VERTICAL);
                bar.setBackground(rb(ThemeManager.SURFACE2, 4));
                LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
                barLp.bottomMargin = dp(10); bar.setLayoutParams(barLp);
                bar.setClipToOutline(true);
                android.graphics.Outline barOutline = new android.graphics.Outline();
                barOutline.setRoundRect(0, 0, bar.getWidth(), dp(8), dp(4));

                int segIdx = 0;
                java.util.List<String> langKeys = new java.util.ArrayList<>(langs.keySet());
                for (String lang : langKeys) {
                    if (segIdx >= 7) break; // max 7 langs in bar
                    float pct = (float) langs.get(lang) / totalFinal;
                    View seg = new View(this);
                    LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, pct);
                    seg.setLayoutParams(sLp);
                    seg.setBackgroundColor(getLangColor(lang));
                    bar.addView(seg);
                    segIdx++;
                }
                langCard.addView(bar);

                // Language legend
                android.widget.GridLayout grid = new android.widget.GridLayout(this);
                grid.setColumnCount(2);
                grid.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                int li = 0;
                for (String lang : langKeys) {
                    if (li >= 8) break;
                    float pct = (float) langs.get(lang) / totalFinal * 100;
                    LinearLayout item = lh(Gravity.CENTER_VERTICAL);
                    android.widget.GridLayout.Spec rowSpec = android.widget.GridLayout.spec(li / 2);
                    android.widget.GridLayout.Spec colSpec = android.widget.GridLayout.spec(li % 2, 1f);
                    android.widget.GridLayout.LayoutParams gLp = new android.widget.GridLayout.LayoutParams(rowSpec, colSpec);
                    gLp.setMargins(0, dp(4), dp(8), 0);
                    item.setLayoutParams(gLp);
                    // Dot
                    View dot = new View(this);
                    LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
                    dotLp.rightMargin = dp(5); dot.setLayoutParams(dotLp);
                    dot.setBackground(rb(getLangColor(lang), 5));
                    item.addView(dot);
                    // Name + pct
                    item.addView(tv(lang, ThemeManager.TEXT, 11f, true));
                    item.addView(tv(String.format("  %.1f%%", pct), ThemeManager.DIM, 10f, false));
                    grid.addView(item);
                    li++;
                }
                langCard.addView(grid);
                langBarHolder.addView(langCard);
            }));

        // ── Action Buttons ────────────────────────────────────────────────────
        LinearLayout actRow = lh(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arLp.bottomMargin = dp(10); actRow.setLayoutParams(arLp);

        TextView browseBtn = primaryBtn("📁  Files", ThemeManager.CYAN);
        browseBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams)browseBtn.getLayoutParams()).rightMargin = dp(6);
        browseBtn.setOnClickListener(v -> navigateTo(() -> setContentView(buildRepoPage())));
        actRow.addView(browseBtn);

        TextView dlBtn = primaryBtn("⬇  ZIP", ThemeManager.SUCCESS);
        dlBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams)dlBtn.getLayoutParams()).rightMargin = dp(6);
        dlBtn.setOnClickListener(v -> {
            dlBtn.setText("…"); dlBtn.setEnabled(false);
            doDownloadRepoZip(repo.owner, repo.name, mCurrentBranch,
                () -> { dlBtn.setText("⬇  ZIP"); dlBtn.setEnabled(true); });
        });
        actRow.addView(dlBtn);

        TextView webBtn2 = primaryBtn("🌐  GitHub", ThemeManager.DIM);
        webBtn2.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        webBtn2.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://github.com/" + repo.owner + "/" + repo.name))));
        actRow.addView(webBtn2);
        c.addView(actRow);

        // ── Latest Release Card (async) ──────────────────────────────────────
        final LinearLayout releaseHolder = lv();
        releaseHolder.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams)releaseHolder.getLayoutParams()).bottomMargin = dp(12);
        c.addView(releaseHolder);

        GHApi.listReleases(mPrefs.getToken(), repo.owner, repo.name,
            (releases, rErr) -> mHandler.post(() -> {
                if (releases == null || releases.isEmpty()) return;
                GHApi.Release latest = releases.get(0);
                releaseHolder.removeAllViews();
                LinearLayout relCard = lv();
                relCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
                relCard.setPadding(dp(14), dp(12), dp(14), dp(12));
                relCard.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                relCard.setClickable(true);
                applyRipple(relCard, 14);
                relCard.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(latest.htmlUrl))));

                LinearLayout relHeader = lh(Gravity.CENTER_VERTICAL);
                relHeader.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                ((LinearLayout.LayoutParams)relHeader.getLayoutParams()).bottomMargin = dp(4);
                TextView relLabel = tv("🏷  Latest Release", ThemeManager.DIM, 10f, false);
                relLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                relHeader.addView(relLabel);
                if (latest.prerelease) relHeader.addView(badgeChip("pre-release", ThemeManager.AMBER, ThemeManager.AMBER_D));
                relCard.addView(relHeader);

                TextView relName = tv(latest.tagName + (latest.name.isEmpty() ? "" : "  " + latest.name),
                    ThemeManager.TEXT, 13.5f, true);
                LinearLayout.LayoutParams rnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rnLp.bottomMargin = dp(4); relName.setLayoutParams(rnLp);
                relCard.addView(relName);

                if (latest.body != null && !latest.body.isEmpty()) {
                    // Show first 2 lines of release notes
                    String[] noteLines = latest.body.split("\n");
                    StringBuilder preview = new StringBuilder();
                    int shown = 0;
                    for (String line : noteLines) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;
                        // Strip leading markdown chars
                        trimmed = trimmed.replaceAll("^#+\\s*", "").replaceAll("^[-*]\\s*", "• ");
                        preview.append(trimmed).append("\n");
                        if (++shown >= 3) { preview.append("…"); break; }
                    }
                    TextView notesTv = tv(preview.toString().trim(), ThemeManager.TEXT2, 11.5f, false);
                    notesTv.setLineSpacing(dp(2), 1.3f);
                    relCard.addView(notesTv);
                }

                String relDate = latest.createdAt.length() >= 10 ? latest.createdAt.substring(0, 10) : latest.createdAt;
                relCard.addView(tv("  " + relDate + "  ↗", ThemeManager.DIM, 10f, false));
                releaseHolder.addView(relCard);
            }));

        // ── README Section (async) ────────────────────────────────────────────
        LinearLayout readmeCard = lv();
        readmeCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        readmeCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams rcLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rcLp.bottomMargin = dp(12); readmeCard.setLayoutParams(rcLp);

        // README header row
        LinearLayout readmeHeader = lh(Gravity.CENTER_VERTICAL);
        readmeHeader.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams)readmeHeader.getLayoutParams()).bottomMargin = dp(10);
        TextView readmeLabel = tv("📄  README", ThemeManager.TEXT, 13f, true);
        readmeLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        readmeHeader.addView(readmeLabel);
        readmeCard.addView(readmeHeader);

        // Loading indicator
        final TextView readmeLoadingTv = tv("Loading README…", ThemeManager.DIM, 11.5f, false);
        readmeCard.addView(readmeLoadingTv);

        // WebView for rendered README
        final android.webkit.WebView readmeWebView = new android.webkit.WebView(this);
        readmeWebView.setVisibility(View.GONE);
        readmeWebView.setBackgroundColor(ThemeManager.SURFACE);
        android.webkit.WebSettings ws = readmeWebView.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setDefaultFontSize(14);
        ws.setDefaultFixedFontSize(12);
        readmeWebView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        readmeCard.addView(readmeWebView);

        // "Expand/Collapse" toggle
        final boolean[] readmeExpanded = {true};
        final int[] readmeHeight = {0};
        final TextView expandBtn = tv("▲ Collapse", ThemeManager.BRAND, 11f, false);
        expandBtn.setVisibility(View.GONE);
        expandBtn.setPadding(0, dp(8), 0, 0);
        expandBtn.setOnClickListener(v -> {
            readmeExpanded[0] = !readmeExpanded[0];
            if (readmeExpanded[0]) {
                readmeWebView.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                readmeWebView.requestLayout();
                expandBtn.setText("▲ Collapse");
            } else {
                readmeWebView.getLayoutParams().height = dp(280);
                readmeWebView.requestLayout();
                expandBtn.setText("▼ Expand full README");
            }
        });
        readmeCard.addView(expandBtn);
        c.addView(readmeCard);

        // Load README async
        GHApi.getReadme(mPrefs.getToken(), repo.owner, repo.name, mCurrentBranch,
            (mdText, mdErr) -> mHandler.post(() -> {
                readmeCard.removeView(readmeLoadingTv);
                if (mdText == null || mdText.isEmpty()) {
                    readmeCard.addView(tv(mdErr != null ? mdErr : "No README found",
                        ThemeManager.DIM, 11.5f, false));
                    return;
                }
                String html = markdownToHtml(mdText, repo.owner, repo.name, mCurrentBranch);
                readmeWebView.loadDataWithBaseURL(
                    "https://github.com/" + repo.owner + "/" + repo.name + "/",
                    html, "text/html", "UTF-8", null);
                readmeWebView.setVisibility(View.VISIBLE);
                expandBtn.setVisibility(View.VISIBLE);

// Load README async
readmeWebView.setWebViewClient(new android.webkit.WebViewClient() {
    @Override
    public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest request) {
        String url = request.getUrl().toString();
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            startActivity(browserIntent);
        } catch (Exception e) {
            toast("Cannot open link: " + e.getMessage());
        }
        return true; // Prevent WebView from loading the URL internally
    }
    
    @Override
    public void onPageFinished(android.webkit.WebView view, String url) {
        view.evaluateJavascript(
            "(function(){return document.body.scrollHeight;})()",
            value -> mHandler.post(() -> {
                try {
                    int h = Integer.parseInt(value);
                    readmeHeight[0] = h;
                    float density = getResources().getDisplayMetrics().density;
                    int pxH = (int)(h * density);
                    // Cap initial height to ~280dp (readable without full scroll)
                    if (pxH > dp(420)) {
                        readmeWebView.getLayoutParams().height = dp(420);
                        readmeWebView.requestLayout();
                        expandBtn.setText("▼ Expand full README");
                        readmeExpanded[0] = false;
                    } else {
                        readmeWebView.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                        readmeWebView.requestLayout();
                    }
                } catch (Exception ignored) {}
            }));
    }
});
            }));

        sv.addView(c);
        outer.addView(sv);
        return outer;
    }

    /** Minimal Markdown → styled HTML for README WebView (dark theme). */
    private String markdownToHtml(String md, String owner, String repo, String branch) {
        // Replace relative image URLs with absolute GitHub raw URLs
        String base = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch + "/";
        // Process line by line
        String[] lines = md.split("\n", -1);
        StringBuilder html = new StringBuilder();
        boolean inCode = false;
        boolean inList = false;
        boolean inTable = false;

        for (String raw : lines) {
            String line = raw;
            // Fenced code block toggle
            if (line.startsWith("```")) {
                if (!inCode) {
                    if (inList) { html.append("</ul>"); inList = false; }
                    String lang = line.substring(3).trim();
                    html.append("<pre><code class=\"lang-").append(escHtml(lang)).append("\">");
                    inCode = true;
                } else {
                    html.append("</code></pre>");
                    inCode = false;
                }
                continue;
            }
            if (inCode) { html.append(escHtml(line)).append("\n"); continue; }

            // Table detection
            if (line.contains("|") && line.trim().startsWith("|")) {
                if (!inTable) { if (inList) { html.append("</ul>"); inList = false; } html.append("<table>"); inTable = true; }
                if (line.trim().replaceAll("[| :\\-]", "").isEmpty()) { continue; } // separator row
                html.append("<tr>");
                for (String cell : line.trim().replaceAll("^\\|", "").replaceAll("\\|$", "").split("\\|")) {
                    html.append("<td>").append(inlineMarkdown(cell.trim())).append("</td>");
                }
                html.append("</tr>");
                continue;
            } else if (inTable) { html.append("</table>"); inTable = false; }

            // Headings
            if (line.startsWith("######")) { if (inList) { html.append("</ul>"); inList = false; } html.append("<h6>").append(inlineMarkdown(line.substring(6).trim())).append("</h6>"); continue; }
            if (line.startsWith("#####"))  { if (inList) { html.append("</ul>"); inList = false; } html.append("<h5>").append(inlineMarkdown(line.substring(5).trim())).append("</h5>"); continue; }
            if (line.startsWith("####"))   { if (inList) { html.append("</ul>"); inList = false; } html.append("<h4>").append(inlineMarkdown(line.substring(4).trim())).append("</h4>"); continue; }
            if (line.startsWith("###"))    { if (inList) { html.append("</ul>"); inList = false; } html.append("<h3>").append(inlineMarkdown(line.substring(3).trim())).append("</h3>"); continue; }
            if (line.startsWith("##"))     { if (inList) { html.append("</ul>"); inList = false; } html.append("<h2>").append(inlineMarkdown(line.substring(2).trim())).append("</h2>"); continue; }
            if (line.startsWith("# "))     { if (inList) { html.append("</ul>"); inList = false; } html.append("<h1>").append(inlineMarkdown(line.substring(2).trim())).append("</h1>"); continue; }

            // Horizontal rule
            if (line.trim().matches("^([-*_])(\\s*\\1){2,}\\s*$")) {
                if (inList) { html.append("</ul>"); inList = false; }
                html.append("<hr>"); continue;
            }

            // Lists
            if (line.matches("^\\s*[-*+] .+")) {
                if (!inList) { html.append("<ul>"); inList = true; }
                html.append("<li>").append(inlineMarkdown(line.replaceFirst("^\\s*[-*+]\\s+", ""))).append("</li>");
                continue;
            }
            if (line.matches("^\\s*\\d+\\. .+")) {
                if (!inList) { html.append("<ol>"); inList = true; }
                html.append("<li>").append(inlineMarkdown(line.replaceFirst("^\\s*\\d+\\.\\s+", ""))).append("</li>");
                continue;
            }
            if (inList) { html.append(inList ? "</ul>" : ""); inList = false; }

            // Blank line
            if (line.trim().isEmpty()) { html.append("<br>"); continue; }

            // Blockquote
            if (line.startsWith("> ")) {
                html.append("<blockquote>").append(inlineMarkdown(line.substring(2))).append("</blockquote>");
                continue;
            }

            // Normal paragraph
            html.append("<p>").append(inlineMarkdown(line)).append("</p>");
        }
        if (inCode) html.append("</code></pre>");
        if (inList) html.append("</ul>");
        if (inTable) html.append("</table>");

        // Fix relative image/link URLs
        String body = html.toString()
            .replaceAll("src=\"(?!http)((?!data:)[^\"]+)\"",
                "src=\"" + base + "$1\"")
            .replaceAll("href=\"(?!http)(?!#)((?!mailto:)[^\"]+)\"",
                "href=\"https://github.com/" + owner + "/" + repo + "/blob/" + branch + "/$1\"");

        boolean isDark = (ThemeManager.BG & 0xFFFFFF) < 0x808080;
        String bg    = isDark ? "#0d1117" : "#ffffff";
        String text  = isDark ? "#e6edf3" : "#1f2328";
        String text2 = isDark ? "#8d96a0" : "#636c76";
        String codeBg= isDark ? "#161b22" : "#f6f8fa";
        String border= isDark ? "#30363d" : "#d0d7de";
        String linkC = isDark ? "#58a6ff" : "#0969da";
        String h2Col = isDark ? "#58a6ff" : "#0969da";

        return "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>"
            + "body{margin:0;padding:0;background:" + bg + ";color:" + text + ";font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;font-size:14px;line-height:1.6;word-break:break-word}"
            + "h1{font-size:1.6em;border-bottom:1px solid " + border + ";padding-bottom:6px;margin-top:18px;margin-bottom:12px}"
            + "h2{font-size:1.3em;color:" + h2Col + ";border-bottom:1px solid " + border + ";padding-bottom:4px;margin-top:16px;margin-bottom:10px}"
            + "h3{font-size:1.1em;margin-top:14px;margin-bottom:8px}"
            + "h4,h5,h6{font-size:1em;margin-top:12px;margin-bottom:6px}"
            + "p{margin:6px 0}"
            + "a{color:" + linkC + ";text-decoration:none}"
            + "a:hover{text-decoration:underline}"
            + "code{background:" + codeBg + ";border:1px solid " + border + ";border-radius:4px;padding:2px 5px;font-size:0.88em;font-family:'Courier New',monospace}"
            + "pre{background:" + codeBg + ";border:1px solid " + border + ";border-radius:8px;padding:12px;overflow-x:auto;margin:10px 0}"
            + "pre code{background:none;border:none;padding:0;font-size:0.85em}"
            + "blockquote{border-left:3px solid " + border + ";color:" + text2 + ";margin:10px 0;padding:6px 12px}"
            + "img{max-width:100%;height:auto;border-radius:6px}"
            + "table{border-collapse:collapse;width:100%;margin:10px 0}"
            + "td,th{border:1px solid " + border + ";padding:6px 10px;text-align:left}"
            + "tr:nth-child(even){background:" + codeBg + "}"
            + "ul,ol{padding-left:20px;margin:6px 0}"
            + "li{margin:3px 0}"
            + "hr{border:none;border-top:1px solid " + border + ";margin:14px 0}"
            + "br{display:block;content:'';margin:2px 0}"
            + ".lang-bash,.lang-sh,.lang-shell{color:#7ee787}"
            + ".lang-java,.lang-kotlin{color:#f78166}"
            + "</style></head><body>"
            + body + "</body></html>";
    }

    private String inlineMarkdown(String s) {
        if (s == null) return "";
        // Images before links
        s = s.replaceAll("!\\[([^\\]]*)]\\(([^)]+)\\)", "<img alt=\"$1\" src=\"$2\">");
        // Links
        s = s.replaceAll("\\[([^\\]]+)]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        // Bold-italic
        s = s.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "<strong><em>$1</em></strong>");
        // Bold
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("__(.+?)__", "<strong>$1</strong>");
        // Italic
        s = s.replaceAll("\\*(.+?)\\*", "<em>$1</em>");
        s = s.replaceAll("_(.+?)_", "<em>$1</em>");
        // Strikethrough
        s = s.replaceAll("~~(.+?)~~", "<del>$1</del>");
        // Inline code
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        return s;
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    private View buildMetaRow(String label, String value, int valueColor) {
        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.bottomMargin = dp(5); row.setLayoutParams(rLp);
        TextView lTv = tv(label, ThemeManager.DIM, 11f, false);
        lTv.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(lTv);
        row.addView(tv(value, valueColor, 11f, true));
        return row;
    }

    private void addStatChip(LinearLayout parent, String icon, String value, int color) {
        LinearLayout chip = lh(Gravity.CENTER_VERTICAL);
        chip.setBackground(rbs(ThemeManager.SURFACE2, 8, ThemeManager.BORDER));
        chip.setPadding(dp(8), dp(5), dp(8), dp(5));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = dp(8); chip.setLayoutParams(cLp);
        chip.addView(tv(icon + " ", color, 10f, false));
        chip.addView(tv(value, ThemeManager.TEXT, 10.5f, true));
        parent.addView(chip);
    }

    /** User public profile page. */
    private View buildUserProfilePage(final String username) {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);
        LinearLayout header = buildHeader2(username, "GitHub User", null);
        outer.addView(header);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG);
        sv.setLayoutParams(mpWrap());
        LinearLayout c = lv();
        c.setPadding(dp(16), dp(12), dp(16), dp(40));
        c.addView(loadingRow());
        sv.addView(c);
        outer.addView(sv);

        GHApi.getUserProfile(mPrefs.getToken(), username, (user, err) -> mHandler.post(() -> {
            c.removeAllViews();
            if (err != null) { c.addView(errCard(err)); return; }
            if (user == null) { c.addView(errCard("User not found")); return; }

            // Avatar placeholder
            LinearLayout avatarRow = lh(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            arLp.bottomMargin = dp(16); avatarRow.setLayoutParams(arLp);
            String initial = user.login.isEmpty() ? "?" : user.login.substring(0, 1).toUpperCase();
            int avatarColor = (user.login.hashCode() & 0xFFFFFF) | 0xFF000000;
            LinearLayout avatar = new LinearLayout(this);
            LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(56), dp(56));
            avLp.rightMargin = dp(14); avatar.setLayoutParams(avLp);
            avatar.setBackground(rb(avatarColor, 28));
            avatar.setGravity(Gravity.CENTER);
            avatar.addView(tv(initial, ThemeManager.BG, 20f, true));
            avatarRow.addView(avatar);
            LinearLayout nameInfo = lv();
            nameInfo.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            nameInfo.addView(tv(user.login, ThemeManager.TEXT, 16f, true));
            nameInfo.addView(tv(user.type, ThemeManager.DIM, 10.5f, false));
            avatarRow.addView(nameInfo);
            // Open on GitHub
            TextView webBtn2 = roundBtn("GitHub ↗", ThemeManager.DIM, ThemeManager.SURFACE2);
            webBtn2.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/" + user.login))));
            avatarRow.addView(webBtn2);
            c.addView(avatarRow);

            // Bio
            if (user.bio != null && !user.bio.isEmpty()) {
                TextView bioTv = tv(user.bio, ThemeManager.TEXT2, 12.5f, false);
                bioTv.setLineSpacing(dp(2), 1.3f);
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                bLp.bottomMargin = dp(10); bioTv.setLayoutParams(bLp);
                c.addView(bioTv);
            }

            // Meta chips
            LinearLayout metaRow = lh(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mrLp.bottomMargin = dp(16); metaRow.setLayoutParams(mrLp);
            addStatChip(metaRow, "📦", String.valueOf(user.publicRepos) + " repos", ThemeManager.CYAN);
            addStatChip(metaRow, "👥", fmtNum(user.followers) + " followers", ThemeManager.SUCCESS);
            addStatChip(metaRow, "➡", fmtNum(user.following) + " following", ThemeManager.DIM);
            c.addView(metaRow);

            if (user.location != null && !user.location.isEmpty()) {
                c.addView(tv("📍  " + user.location, ThemeManager.DIM, 11f, false));
                c.addView(sp(6));
            }
            if (user.company != null && !user.company.isEmpty()) {
                c.addView(tv("🏢  " + user.company, ThemeManager.DIM, 11f, false));
                c.addView(sp(6));
            }
            if (user.blog != null && !user.blog.isEmpty()) {
                TextView blogTv = tv("🔗  " + user.blog, ThemeManager.BRAND, 11f, false);
                blogTv.setOnClickListener(v -> {
                    String url = user.blog.startsWith("http") ? user.blog : "https://" + user.blog;
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                });
                c.addView(blogTv);
                c.addView(sp(12));
            }

            // Repos section
            TextView repoHdr = secLabel("PUBLIC REPOSITORIES");
            LinearLayout.LayoutParams rhLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rhLp.topMargin = dp(10); rhLp.bottomMargin = dp(8); repoHdr.setLayoutParams(rhLp);
            c.addView(repoHdr);

            final LinearLayout repoList = lv();
            repoList.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
            repoList.setLayoutParams(mpWrap());
            repoList.addView(loadingRow());
            c.addView(repoList);

            GHApi.getUserPublicRepos(mPrefs.getToken(), username,
                (repos, repoErr) -> mHandler.post(() -> {
                    repoList.removeAllViews();
                    if (repoErr != null) { repoList.addView(errCard(repoErr)); return; }
                    if (repos == null || repos.isEmpty()) {
                        repoList.addView(tv("No public repos.", ThemeManager.DIM, 11f, false));
                        return;
                    }
                    for (int i = 0; i < repos.size(); i++) {
                        repoList.addView(buildExploreRepoRow(repos.get(i)));
                        if (i < repos.size() - 1) repoList.addView(rowDivider());
                    }
                }));
        }));

        return outer;
    }

    /** Format large numbers: 1200 → 1.2k, 1500000 → 1.5M */
    private String fmtNum(int n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.getDefault(), "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(java.util.Locale.getDefault(), "%.1fk", n / 1_000f);
        return String.valueOf(n);
    }

    /** Language → distinctive color (GitHub-like palette). */
    private int getLangColor(String lang) {
        if (lang == null) return ThemeManager.DIM;
        switch (lang.toLowerCase()) {
            case "java":        return 0xFFB07219;
            case "kotlin":      return 0xFFA97BFF;
            case "python":      return 0xFF3572A5;
            case "javascript":  return 0xFFF1E05A;
            case "typescript":  return 0xFF2B7489;
            case "c++":         return 0xFFF34B7D;
            case "c":           return 0xFF555555;
            case "go":          return 0xFF00ADD8;
            case "rust":        return 0xFFDEA584;
            case "swift":       return 0xFFFFAC45;
            case "dart":        return 0xFF00B4AB;
            case "ruby":        return 0xFF701516;
            case "php":         return 0xFF4F5D95;
            case "shell":       return 0xFF89E051;
            case "html":        return 0xFFE34C26;
            case "css":         return 0xFF563D7C;
            default:            return ThemeManager.DIM;
        }
    }

    /** Download repo ZIP helper (used by explore detail page). */
    private void doDownloadRepoZip(String owner, String repo, String branch, Runnable onDone) {
        showNotif(NOTIF_UPLOAD, "Downloading", owner + "/" + repo, 0, 100);
        GHApi.downloadRepo(mPrefs.getToken(), owner, repo, branch,
            (dl, tot) -> showNotif(NOTIF_UPLOAD, "Downloading " + repo,
                fmtSize(dl) + (tot > 0 ? " / " + fmtSize(tot) : ""),
                (int)(tot > 0 ? dl * 100 / tot : 0), 100),
            (bytes, err) -> mHandler.post(() -> {
                cancelNotif(NOTIF_UPLOAD);
                if (onDone != null) onDone.run();
                if (err != null) { showErr("Download failed: " + err); return; }
                try {
                    String fname = repo + "-" + branch + ".zip";
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname);
                        cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip");
                        cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS);
                        android.net.Uri fu = getContentResolver().insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        if (fu != null) {
                            java.io.OutputStream os = getContentResolver().openOutputStream(fu);
                            if (os != null) { os.write(bytes); os.close(); }
                        }
                    } else {
                        java.io.File dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                        dir.mkdirs();
                        java.io.FileOutputStream fos =
                            new java.io.FileOutputStream(new java.io.File(dir, fname));
                        fos.write(bytes); fos.close();
                    }
                    showNotifResult("✓ Downloaded", fname + "  " + fmtSize(bytes.length));
                    toast("✓ Saved to Downloads");
                } catch (Exception saveEx) {
                    toast("Saved but couldn't move to Downloads: " + saveEx.getMessage());
                }
            }));
    }


    // ── FILES TAB ─────────────────────────────────────────────────────────────
    private View buildFilesTab() {
        // Wrap filesAt in a FrameLayout so deleteBar can overlay at bottom (always visible)
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View filesView = buildFilesAt("");
        frame.addView(filesView);

        // Retrieve deleteBar stored as tag by buildFilesAt and pin it to bottom
        if (filesView.getTag() instanceof LinearLayout) {
            LinearLayout deleteBar = (LinearLayout) filesView.getTag();
            android.widget.FrameLayout.LayoutParams dbFp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            dbFp.gravity = Gravity.BOTTOM;
            deleteBar.setLayoutParams(dbFp);
            frame.addView(deleteBar);
        }
        return frame;
    }

    private View buildFilesAt(final String path) {
        LinearLayout c = lv(); c.setLayoutParams(mpWrap());

        // ── Multi-select state (defined first so delete bar can reference it) ──
        final java.util.Set<String> selectedPaths = new java.util.LinkedHashSet<>();
        final java.util.Map<String, GHApi.RepoContent> pathToItem = new java.util.HashMap<>();
        final boolean[] selectMode = {false};

        // ── Path header + Add button ───────────────────────────────────────────
        LinearLayout pathRow = lh(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.bottomMargin = dp(10); pathRow.setLayoutParams(prLp);
        TextView pathTv = tv(path.isEmpty() ? "/ root" : "/" + path, ThemeManager.DIM, 10.5f, false);
        pathTv.setTypeface(Typeface.MONOSPACE);
        pathTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pathRow.addView(pathTv);
        TextView addBtn = roundBtn("+ Add", ThemeManager.BRAND, ThemeManager.BRAND_D);
        addBtn.setOnClickListener(v -> showAddFileDialog(path));
        pathRow.addView(addBtn);
        c.addView(pathRow);

        // ── File list ──────────────────────────────────────────────────────────
        final LinearLayout list = lv();
        list.setLayoutParams(mpWrap());
        list.setBackground(rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        list.addView(tv("Loading...", ThemeManager.DIM, 11f, false));
        c.addView(list);

        // ── Delete bar (hidden until select mode active) ───────────────────────
        final LinearLayout deleteBar = lh(Gravity.CENTER_VERTICAL);
        deleteBar.setBackground(rbs(0xFF1A0608, 12, ThemeManager.DANGER));
        deleteBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        deleteBar.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dbLp.topMargin = dp(8); deleteBar.setLayoutParams(dbLp);

        final TextView countTv = tv("0 selected", ThemeManager.RED, 12f, true);
        countTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        deleteBar.addView(countTv);

        // Cancel selection
        TextView cancelSelBtn = roundBtn("Cancel", ThemeManager.DIM, ThemeManager.SURFACE2);
        LinearLayout.LayoutParams csBtnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        csBtnLp.leftMargin = dp(8); cancelSelBtn.setLayoutParams(csBtnLp);

        // Delete selected
        TextView deleteSelBtn = roundBtn("Delete", ThemeManager.DANGER, 0xFF3A0608);
        LinearLayout.LayoutParams dsBtnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dsBtnLp.leftMargin = dp(8); deleteSelBtn.setLayoutParams(dsBtnLp);

        String filesCacheKey = cacheKey(path);

        // Runnable to redraw list rows (used by cancel and after delete)
        Runnable[] redrawRows = {null};

        cancelSelBtn.setOnClickListener(v -> {
            selectMode[0] = false; selectedPaths.clear();
            deleteBar.setVisibility(android.view.View.GONE);
            if (redrawRows[0] != null) redrawRows[0].run();
        });

        deleteSelBtn.setOnClickListener(v -> {
            if (selectedPaths.isEmpty()) return;
            int cnt = selectedPaths.size();
            showConfirm("Delete " + cnt + " item(s)?",
                "Permanently remove " + cnt + " file(s)/folder(s) from the repo.",
                "Delete", ThemeManager.DANGER, () -> {
                    // Optimistic: remove items from list instantly, no progress dialog
                    java.util.List<String> toDelete = new java.util.ArrayList<>(selectedPaths);
                    java.util.List<GHApi.RepoContent> snapshot = new java.util.ArrayList<>();
                    for (String p : toDelete) {
                        GHApi.RepoContent it = pathToItem.get(p);
                        if (it != null) snapshot.add(it);
                    }
                    // Update cached list optimistically
                    java.util.List<GHApi.RepoContent> cached = mCachedFiles.get(filesCacheKey);
                    if (cached != null) {
                        cached.removeIf(ci -> selectedPaths.contains(ci.path));
                        mCachedFiles.put(filesCacheKey, cached);
                    }
                    selectMode[0] = false; selectedPaths.clear();
                    deleteBar.setVisibility(android.view.View.GONE);
                    if (redrawRows[0] != null) redrawRows[0].run();

                    // Delete in background
                    AppExecutors.getInstance().diskIO().execute(() -> {
                        int done = 0, failed = 0;
                        for (GHApi.RepoContent it : snapshot) {
                            final Object lock = new Object();
                            final boolean[] ok = {false};
                            try {
                                if ("dir".equals(it.type)) {
                                    GHApi.deleteFolderRecursive(mPrefs.getToken(), mCurrentOwner,
                                        mCurrentRepo, it.path, mCurrentBranch, (cnt2, err) -> {
                                            ok[0] = err == null;
                                            synchronized(lock) { lock.notifyAll(); }
                                        });
                                } else {
                                    GHApi.deleteFile(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                                        it.path, it.sha, mCurrentBranch, (success, err) -> {
                                            ok[0] = success;
                                            synchronized(lock) { lock.notifyAll(); }
                                        });
                                }
                                synchronized(lock) { try { lock.wait(12000); } catch(Exception ex) { Thread.currentThread().interrupt(); } }
                                if (ok[0]) done++; else failed++;
                            } catch (Exception ex) { failed++; }
                        }
                        final int fd = done, ff = failed;
                        mHandler.post(() -> {
                            mCachedFiles.evictAll(); // force fresh fetch
                            if (ff > 0) {
                                toast(fd + " deleted, " + ff + " failed");
                                // Refresh list to show actual server state
                                GHApi.listContents(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, path,
                                    (contents2, err2) -> mHandler.post(() -> {
                                        if (contents2 != null) {
                                            contents2.sort((a2, b2) -> a2.type.equals(b2.type)
                                                ? a2.name.compareTo(b2.name) : a2.type.equals("dir") ? -1 : 1);
                                            mCachedFiles.put(filesCacheKey, contents2);
                                            if (redrawRows[0] != null) redrawRows[0].run();
                                        }
                                    }));
                            }
                            // All success: already removed optimistically, nothing to do
                        });
                    });
                });
        });

        deleteBar.addView(cancelSelBtn); deleteBar.addView(deleteSelBtn);
        // deleteBar NOT added here — caller retrieves via getTag and places it outside ScrollView
        c.setTag(deleteBar);

        // ── Row builder helper (local) ─────────────────────────────────────────
        // Defined as lambda stored in array so it can self-reference
        final ContentAction[] drawList = new ContentAction[1];
        drawList[0] = (items, fromCache) -> {
            list.removeAllViews();
            if (items == null || items.isEmpty()) {
                TextView empty = tv("This folder is empty.\nTap + Add to create a new file.", ThemeManager.DIM, 11f, false);
                empty.setGravity(Gravity.CENTER); empty.setLineSpacing(dp(2),1.2f);
                empty.setPadding(dp(16),dp(24),dp(16),dp(24));
                list.addView(empty); return;
            }
            for (int i = 0; i < items.size(); i++) {
                GHApi.RepoContent item = items.get(i);
                pathToItem.put(item.path, item);
                list.addView(buildSelectableFileRow(item, path, selectedPaths, selectMode, countTv, deleteBar));
                if (i < items.size()-1) list.addView(rowDivider());
            }
        };

        redrawRows[0] = () -> {
            java.util.List<GHApi.RepoContent> cur = mCachedFiles.get(filesCacheKey);
            if (cur != null) drawList[0].run(cur, true);
        };

        // Show cache instantly
        java.util.List<GHApi.RepoContent> cached = mCachedFiles.get(filesCacheKey);
        if (cached != null && !cached.isEmpty()) {
            drawList[0].run(cached, true);
        }

        // Fetch fresh from API
        GHApi.listContents(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, path,
            (contents, err) -> mHandler.post(() -> {
                if (err != null) {
                    if (mCachedFiles.get(filesCacheKey) == null) {
                        list.removeAllViews();
                        list.setPadding(dp(14),dp(14),dp(14),dp(14));
                        list.addView(errCard(err));
                    }
                    return;
                }
                if (contents == null || contents.isEmpty()) {
                    mCachedFiles.remove(filesCacheKey);
                    drawList[0].run(null, false);
                    return;
                }
                contents.sort((a, b) -> a.type.equals(b.type) ? a.name.compareTo(b.name) : a.type.equals("dir") ? -1 : 1);
                java.util.List<GHApi.RepoContent> prev = mCachedFiles.get(filesCacheKey);
                if (prev != null && contents.size() == prev.size()) {
                    // Update pathToItem map even if size unchanged
                    for (GHApi.RepoContent item : contents) pathToItem.put(item.path, item);
                    return;
                }
                mCachedFiles.put(filesCacheKey, contents);
                drawList[0].run(contents, false);
            }));
        return c;
    }

    private View buildSelectableFileRow(final GHApi.RepoContent item, final String basePath,
            final java.util.Set<String> selectedPaths,
            final boolean[] selectMode,
            final TextView countTv,
            final LinearLayout deleteBar) {

        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        row.setClickable(true);
        boolean isDir = "dir".equals(item.type);

        // Selection indicator (left colored bar)
        final View selBar = new View(this);
        selBar.setLayoutParams(new LinearLayout.LayoutParams(dp(4), dp(40)));
        selBar.setBackground(rb(ThemeManager.BRAND, 2));
        selBar.setVisibility(android.view.View.INVISIBLE);
        row.addView(selBar);

        LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(dp(8), 1);
        View spacer = new View(this); spacer.setLayoutParams(spacerLp);
        row.addView(spacer);

        LinearLayout iconWrap = new LinearLayout(this);
        LinearLayout.LayoutParams iwLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        iwLp.rightMargin = dp(12); iconWrap.setLayoutParams(iwLp);
        iconWrap.setGravity(Gravity.CENTER);
        iconWrap.setBackground(rb(ThemeManager.SURFACE2, 10));
        iconWrap.addView(tv(isDir ? "\ud83d\udcc1" : getFileIcon(item.name), ThemeManager.TEXT, 16f, false));

        LinearLayout info = lv();
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        info.addView(tv(item.name, ThemeManager.TEXT, 13f, isDir));
        if (!isDir && item.size > 0) {
            TextView sizeTv = tv(fmtSize(item.size), ThemeManager.DIM, 10f, false);
            sizeTv.setPadding(0, dp(2), 0, 0); info.addView(sizeTv);
        }

        row.addView(iconWrap); row.addView(info);
        row.addView(tv("\u203a", ThemeManager.DIM, 20f, false));

        // Highlight state
        Runnable[] updateHighlight = {null};
        updateHighlight[0] = () -> {
            boolean sel = selectedPaths.contains(item.path);
            selBar.setVisibility(sel ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
            row.setBackgroundColor(sel ? 0xFF0D1A2E : android.graphics.Color.TRANSPARENT);
            iconWrap.setBackground(rb(sel ? ThemeManager.BRAND_D : ThemeManager.SURFACE2, 10));
        };

        row.setOnClickListener(v -> {
            if (selectMode[0]) {
                // Toggle selection
                if (selectedPaths.contains(item.path)) selectedPaths.remove(item.path);
                else selectedPaths.add(item.path);
                updateHighlight[0].run();
                int cnt = selectedPaths.size();
                countTv.setText(cnt + " selected");
                if (cnt == 0) {
                    selectMode[0] = false;
                    deleteBar.setVisibility(android.view.View.GONE);
                }
            } else {
                // Normal tap
                if (isDir) {
                    LinearLayout pg = lv(); pg.setBackgroundColor(ThemeManager.BG);
                    LinearLayout hdr = buildHeader2("/" + item.name, mCurrentRepo, null);
                    pg.addView(hdr);
                    // Use FrameLayout so deleteBar can be pinned to bottom
                    android.widget.FrameLayout subFrame = new android.widget.FrameLayout(this);
                    subFrame.setLayoutParams(mpWrap());
                    ScrollView sv2 = new ScrollView(this); sv2.setBackgroundColor(ThemeManager.BG);
                    LinearLayout inner = lv(); inner.setPadding(dp(16),dp(12),dp(16),dp(80));
                    View filesView = buildFilesAt(item.path);
                    inner.addView(filesView);
                    sv2.addView(inner); subFrame.addView(sv2);
                    if (filesView.getTag() instanceof LinearLayout) {
                        LinearLayout subDeleteBar = (LinearLayout) filesView.getTag();
                        android.widget.FrameLayout.LayoutParams dbFp2 = new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
                        dbFp2.gravity = Gravity.BOTTOM;
                        subDeleteBar.setLayoutParams(dbFp2);
                        subFrame.addView(subDeleteBar);
                    }
                    pg.addView(subFrame);
                    // Simpan posisi scroll Files tab sebelum masuk subfolder
                    if (mCurrentTabSv != null)
                        mScrollStates.put("tab_0_0", mCurrentTabSv.getScrollY());
                    navigateTo(() -> setContentView(pg));
                } else {
                    // Simpan posisi scroll Files tab sebelum buka file
                    if (mCurrentTabSv != null)
                        mScrollStates.put("tab_0_0", mCurrentTabSv.getScrollY());
                    openFileViewer(item);
                }
            }
        });

        row.setOnLongClickListener(v -> {
            // Activate select mode on first long press
            selectMode[0] = true;
            selectedPaths.add(item.path);
            updateHighlight[0].run();
            countTv.setText("1 selected");
            deleteBar.setVisibility(android.view.View.VISIBLE);
            return true;
        });

        applyRipple(row);
        return row;
    }

    /** Dialog Add File — masukkan nama file + isi konten langsung */
    private void showAddFileDialog(final String basePath) {
    android.app.Dialog dialog = new android.app.Dialog(this);
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
    if (dialog.getWindow() != null) {
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        dialog.getWindow().setDimAmount(0.5f);
        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }
    dialog.setCancelable(true);

    // Root LinearLayout, vertical, agar weightSum berfungsi
    LinearLayout root = lv();
    root.setBackground(rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
    root.setPadding(dp(18), dp(18), dp(18), dp(16));
    root.setLayoutParams(new LinearLayout.LayoutParams(
        (int)(getResources().getDisplayMetrics().widthPixels * 0.92f),
        LinearLayout.LayoutParams.WRAP_CONTENT));
    // Set weightSum agar ScrollView bisa menggunakan weight
    root.setWeightSum(1f);

    // Title (Bagian statis di atas)
    LinearLayout titleRow = lh(Gravity.CENTER_VERTICAL);
    titleRow.setLayoutParams(mpWrap());
    LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    trLp.bottomMargin = dp(14);
    titleRow.setLayoutParams(trLp);
    TextView titleTv = tv("＋ Add File", ThemeManager.TEXT, 15f, true);
    titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    titleRow.addView(titleTv);
    if (!basePath.isEmpty()) {
        titleRow.addView(badgeChip("/" + basePath, ThemeManager.DIM, ThemeManager.SURFACE2));
    }
    root.addView(titleRow);

    // --- Scrollable Content (Input Fields) ---
    ScrollView scrollContainer = new ScrollView(this);
    scrollContainer.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)); // weight = 1
    scrollContainer.setFillViewport(true);
    LinearLayout inputContainer = lv();
    inputContainer.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    inputContainer.setPadding(0, 0, 0, dp(10)); // padding bottom kecil

    // Filename input
    inputContainer.addView(tv("File name", ThemeManager.DIM, 9.5f, true));
    inputContainer.addView(sp(4));
    final EditText nameEt = styledInput("e.g. MainActivity.java", false);
    LinearLayout.LayoutParams neLp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    neLp.bottomMargin = dp(12);
    nameEt.setLayoutParams(neLp);
    inputContainer.addView(nameEt);

    // Content editor
    inputContainer.addView(tv("Content (optional)", ThemeManager.DIM, 9.5f, true));
    inputContainer.addView(sp(4));
    final EditText contentEt = new EditText(this);
    contentEt.setHint("// Write code here...\n// Leave empty to create a blank file.");
    contentEt.setHintTextColor(ThemeManager.DIM);
    contentEt.setTextColor(0xFF44FF88);
    contentEt.setTextSize(11f);
    contentEt.setTypeface(Typeface.MONOSPACE);
    contentEt.setBackground(rbs(0xFF050507, 10, ThemeManager.BORDER));
    contentEt.setPadding(dp(12), dp(12), dp(12), dp(12));
    contentEt.setGravity(Gravity.TOP);
    contentEt.setMinLines(6);
    contentEt.setMaxLines(14);
    LinearLayout.LayoutParams ceLp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    ceLp.bottomMargin = dp(14);
    contentEt.setLayoutParams(ceLp);
    contentEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    inputContainer.addView(contentEt);

    // Commit message
    inputContainer.addView(tv("Commit message", ThemeManager.DIM, 9.5f, true));
    inputContainer.addView(sp(4));
    final EditText msgEt = styledInput("Add " + (basePath.isEmpty() ? "" : basePath + "/") + "...", false);
    LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    msgLp.bottomMargin = dp(14);
    msgEt.setLayoutParams(msgLp);
    inputContainer.addView(msgEt);

    scrollContainer.addView(inputContainer);
    root.addView(scrollContainer);
    // --- End of Scrollable Content ---

    // Buttons (Bagian statis di bawah)
    LinearLayout btnRow = lh(Gravity.END);
    btnRow.setLayoutParams(mpWrap());
    btnRow.setPadding(0, dp(8), 0, 0); // sedikit padding atas
    TextView cancelBtn = roundBtn("Cancel", ThemeManager.DIM, ThemeManager.SURFACE2);
    LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
    cbLp.rightMargin = dp(8); cancelBtn.setLayoutParams(cbLp);
    cancelBtn.setPadding(dp(16), 0, dp(16), 0);
    cancelBtn.setOnClickListener(v -> dialog.dismiss());

    final TextView saveBtn = tv("SAVE", ThemeManager.TEXT, 12f, true);
    saveBtn.setBackground(rb(ThemeManager.CYAN, 10));
    saveBtn.setPadding(dp(18), 0, dp(18), 0);
    saveBtn.setGravity(Gravity.CENTER);
    saveBtn.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)));
    saveBtn.setLetterSpacing(0.04f);

    saveBtn.setOnClickListener(v -> {
        String fname = nameEt.getText().toString().trim();
        if (fname.isEmpty()) { toast("File name is required."); return; }
        String fileContent = contentEt.getText().toString();
        String msg = msgEt.getText().toString().trim();
        if (msg.isEmpty()) msg = "Add " + fname;
        String repoPath = basePath.isEmpty() ? fname : (basePath + "/" + fname);
        byte[] bytes = fileContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final String finalMsg = msg;
        dialog.dismiss();
        android.app.Dialog prog = makeProgressDialog();
        final TextView progTv = (TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0);
        progTv.setText("Uploading " + fname + "...");
        prog.show();
        GHApi.uploadFile(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            repoPath, bytes, finalMsg, null, mCurrentBranch,
            (ok, err) -> mHandler.post(() -> {
                prog.dismiss();
                if (ok) {
                    mCachedFiles.evictAll();
                    toast("" + fname + " added");
                    // Navigate back to the folder where file was added (not stack top)
                    final String targetPath = basePath;
                    if (targetPath.isEmpty()) {
                        // Root — go back to Files tab
                        while (mNavStack.size() > 1) mNavStack.pop();
                        navigateTo(() -> setContentView(buildRepoPageAtTab(0)));
                    } else {
                        // Subfolder — pop until we're at that folder's page or at repo page
                        onBackPressed();
                    }
                }
                else showErr("Failed:\n" + err);
            }));
    });

    btnRow.addView(cancelBtn); btnRow.addView(saveBtn);
    root.addView(btnRow);

    dialog.setContentView(root);
    dialog.show();
}

    private void confirmDeleteFile(final GHApi.RepoContent item) {
        showConfirm("Delete " + item.name + "?", "This file will be permanently deleted from the repo.",
            "Delete", () -> {
                // Optimistic: evict cache + go back instantly
                mCachedFiles.evictAll();
                onBackPressed();
                GHApi.deleteFile(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    item.path, item.sha, mCurrentBranch, (ok, err) -> mHandler.post(() -> {
                        if (!ok) {
                            // File still exists on GitHub — re-enter folder to restore view
                            showErr("Delete failed:\n" + err);
                            mCachedFiles.evictAll();
                        }
                    }));
            });
    }

    private void confirmDeleteFolder(final GHApi.RepoContent folder) {
        showConfirm("Delete folder '" + folder.name + "'?",
            "All files inside will be deleted.\nPath: " + folder.path + "\n\nThis cannot be undone.",
            "Delete All", () -> {
                // Optimistic: go back instantly, delete runs in background via notification
                mCachedFiles.evictAll();
                onBackPressed();
                showNotif(NOTIF_MANAGE, "Deleting " + folder.name, "Running in background...", -1, 0);
                GHApi.deleteFolderRecursive(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    folder.path, mCurrentBranch,
                    (count, err) -> mHandler.post(() -> {
                        cancelNotif(NOTIF_MANAGE);
                        mCachedFiles.evictAll();
                        if (err != null) {
                            showNotifResult("✗ Delete failed", folder.name);
                            showErr("Failed to delete folder: " + err);
                        } else {
                            showNotifResult("✓ Done", count + " file(s) deleted from " + folder.name);
                        }
                        // onBackPressed already called optimistically above — do NOT call again
                    }));
            });
    }

    // ── BUILDS TAB ────────────────────────────────────────────────────────────
    private View buildBuildsTab() {
        LinearLayout c = lv(); c.setLayoutParams(mpWrap());

        // Header row
        LinearLayout topRow = lh(Gravity.CENTER_VERTICAL);
        topRow.setLayoutParams(mpWrap());
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        trLp.bottomMargin = dp(12);
        topRow.setLayoutParams(trLp);
        TextView lbl = secLabel("Workflow Runs");
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(lbl);

        // Sign toggle
        final boolean[] signEnabled = {mPrefs.isSigningEnabled()};
        final TextView signChip = badgeChip(signEnabled[0] ? "Signing On" : "Signing Off",
            signEnabled[0] ? ThemeManager.GREEN : ThemeManager.DIM, signEnabled[0] ? ThemeManager.GREEN_D : ThemeManager.SURFACE2);
        signChip.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.rightMargin = dp(8);
        signChip.setLayoutParams(scLp);
        signChip.setOnClickListener(v -> {
            if (!mPrefs.hasSigningConfig()) {
                showConfirm("Signing Not Configured",
                        "Auto-sign will sign your APK on every build.\n\n"
                        + "Tap ⋮ → Sign Config, upload your keystore (.jks), set alias + password, then save.",
                        "Open Signing Config", ThemeManager.BRAND,
                        () -> navigateTo(() -> setContentView(buildSignConfigPage())));
                return;
            }
            signEnabled[0] = !signEnabled[0];
            mPrefs.saveSigningEnabled(signEnabled[0]);
            signChip.setText(signEnabled[0] ? "Signing On" : "Signing Off");
            signChip.setTextColor(signEnabled[0] ? ThemeManager.GREEN : ThemeManager.DIM);
            signChip.setBackground(rbs(signEnabled[0] ? ThemeManager.GREEN_D : ThemeManager.SURFACE2, 8, signEnabled[0] ? ThemeManager.GREEN : ThemeManager.BORDER));
            toast(signEnabled[0] ? "Signing enabled." : "Signing disabled.");
        });
        topRow.addView(signChip);

        TextView runBtn = roundBtn("Run Build", ThemeManager.BRAND, ThemeManager.BRAND_D);
        runBtn.setOnClickListener(v -> showTriggerDialog(signEnabled[0]));
        topRow.addView(runBtn);
        c.addView(topRow);

        // Cancel/Delete all row
        LinearLayout mgmtRow = lh(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mgmtLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mgmtLp.bottomMargin = dp(12);
        mgmtRow.setLayoutParams(mgmtLp);

        TextView cancelAllBtn = roundBtn("Cancel All", ThemeManager.DANGER, ThemeManager.DANGER_D);
        LinearLayout.LayoutParams caLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        caLp.rightMargin = dp(8); cancelAllBtn.setLayoutParams(caLp);
        cancelAllBtn.setOnClickListener(v -> showRunsManagementDialog());

        mgmtRow.addView(cancelAllBtn);
        c.addView(mgmtRow);

        final LinearLayout list = lv(); list.setLayoutParams(mpWrap());
        list.addView(loadingRow());
        c.addView(list);

        mRunListContainer = list; // for polling updates

        // Show cached runs instantly
        String runsKey = mCurrentOwner + "/" + mCurrentRepo;
        if (mCachedRuns != null && runsKey.equals(mCachedRunsKey) && !mCachedRuns.isEmpty()) {
            list.removeAllViews();
            for (GHApi.WorkflowRun run : mCachedRuns) { list.addView(buildRunCard(run)); list.addView(sp(8)); }
        }
        GHApi.listRuns(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            (runs, err) -> mHandler.post(() -> {
                if (err != null) {
                    if (mCachedRuns == null) { list.removeAllViews(); list.addView(errCard(err)); }
                    return;
                }
                if (runs == null || runs.isEmpty()) {
                    mCachedRuns = null; mCachedRunsKey = "";
                    list.removeAllViews(); list.addView(buildNoBuildCard());
                    return;
                }
                mCachedRuns = runs; mCachedRunsKey = runsKey;
                list.removeAllViews();
                for (GHApi.WorkflowRun run : runs) { list.addView(buildRunCard(run)); list.addView(sp(8)); }

                // Start auto-polling if any active run
                boolean hasActive = false;
                for (GHApi.WorkflowRun r : runs)
                    if ("in_progress".equals(r.status) || "queued".equals(r.status)
                            || "waiting".equals(r.status)) { hasActive = true; break; }
                if (hasActive) {
                    for (GHApi.WorkflowRun r : runs)
                        mLastRunStatus.put(r.id, r.status + "|" + r.conclusion);
                    startBuildPolling();
                }
            }));
        return c;
    }

    // =========================================================================
    // FITUR 1: AUTO CI/CD SETUP — empty-state card
    // =========================================================================

    /**
     * Ditampilkan ketika repo tidak punya workflow sama sekali.
     * Memberikan dua opsi:
     *  • ✨ Setup Android CI/CD → generate .github/workflows/android-build.yml
     *  • ▶ BUILD tetap tampil (mungkin ada workflow via GitHub web)
     */
    private View buildNoBuildCard() {
        LinearLayout card = lv();
        card.setLayoutParams(mpWrap());
        card.setBackground(rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        card.setPadding(dp(22), dp(28), dp(22), dp(28));
        card.setGravity(android.view.Gravity.CENTER);

        // Icon
        TextView icon = tv("↑", ThemeManager.TEXT, 40f, false);
        icon.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.bottomMargin = dp(12);
        icon.setLayoutParams(ilp);
        card.addView(icon);

        // Judul
        TextView title = tv("No CI/CD workflow found", ThemeManager.TEXT, 15f, true);
        title.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(8);
        title.setLayoutParams(tlp);
        card.addView(title);

        // Deskripsi
        TextView desc = tv(
            "This repo has no GitHub Actions workflow yet.\n"
            + "Create a ready-to-use Android CI/CD workflow\n"
            + "in just one tap.",
            ThemeManager.DIM, 11f, false);
        desc.setGravity(android.view.Gravity.CENTER);
        desc.setLineSpacing(dp(2), 1.3f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = dp(22);
        desc.setLayoutParams(dlp);
        card.addView(desc);

        // Tombol utama: Setup CI/CD
        TextView setupBtn = primaryBtn("Set up CI/CD", ThemeManager.BRAND);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(10);
        setupBtn.setLayoutParams(slp);
        setupBtn.setOnClickListener(v -> showUniversalCiCdSetup());
        card.addView(setupBtn);

        // Tombol sekunder: tetap bisa trigger manual
        TextView manualBtn = ghostBtn("▶  Trigger Build Manual");
        manualBtn.setOnClickListener(v -> showTriggerDialog(mPrefs.isSigningEnabled()));
        card.addView(manualBtn);

        return card;
    }

    /**
     * Dialog konfirmasi sebelum membuat workflow.
     * Menampilkan preview YAML + pilihan branch target.
     */
    private void showCiCdSetupDialog() {
        // Cek dulu apakah ini repo Android
        android.app.Dialog prog = makeProgressDialog();
        ((TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0))
            .setText("Checking repo...");
        prog.show();

        GHApi.checkFileExists(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            "app/build.gradle", (exists, err) -> mHandler.post(() -> {
                prog.dismiss();

                boolean isAndroid = exists != null && !exists.isEmpty();
                String warningNote = isAndroid ? "" :
                    "\n\nNote: app/build.gradle not found. "
                    + "This workflow is optimized for Android projects. "
                    + "Make sure your folder structure is correct.";

                String yamlPreview =
                    "name: Android CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "    branches: [ " + mCurrentBranch + " ]\n"
                    + "  workflow_dispatch:\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - uses: actions/checkout@v4\n"
                    + "      - uses: actions/setup-java@v4\n"
                    + "        with:\n"
                    + "          java-version: '17'\n"
                    + "          distribution: 'temurin'\n"
                    + "      - run: chmod +x gradlew\n"
                    + "      - run: ./gradlew assembleRelease\n"
                    + "      - uses: actions/upload-artifact@v4\n"
                    + "        with:\n"
                    + "          name: release-apk\n"
                    + "          path: app/build/outputs/apk/release/\n";

                final String yamlPreviewFinal = yamlPreview;
                showMenu("Set up CI/CD", new String[]{"Create Workflow", "Preview YAML", "Cancel"},
                    new Runnable[]{
                        () -> doCreateCiCdWorkflow(yamlPreviewFinal),
                        () -> showAlert("Preview Workflow YAML", yamlPreviewFinal),
                        null
                    });
            }));
    }

    /**
     * Upload file workflow ke repo via GHApi.uploadFile.
     * Path: .github/workflows/android-build.yml
     */
    private void doCreateCiCdWorkflow(String yamlContent) {
        android.app.Dialog prog = makeProgressDialog();
        final TextView progTv = (TextView)
            ((LinearLayout) prog.findViewById(android.R.id.message)).getChildAt(0);
        progTv.setText("Creating workflow...");
        prog.show();

        // Build full YAML — pakai signing config jika tersedia
        String finalYaml = buildCiCdYaml(mCurrentBranch);
        byte[] yamlBytes = finalYaml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String workflowPath = ".github/workflows/android-build.yml";

        GHApi.uploadFile(
            mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            workflowPath, yamlBytes,
            "ci: add Android CI/CD workflow via GitDeploy",
            null, // sha=null → file baru
            mCurrentBranch,
            (ok, err) -> mHandler.post(() -> {
                prog.dismiss();
                if (!ok) { showErr("Failed to create workflow:\n\n" + err); return; }
                showStatusBar("CI/CD workflow created successfully!", ThemeManager.GREEN);
                hideStatusBar(4000);
                showConfirm("Workflow Created",
                        "Saved at: " + workflowPath + "\n\nRuns automatically on push to \"" + mCurrentBranch + "\".\n"
                        + "You can also trigger it manually from the Builds tab.",
                        "Trigger Now", ThemeManager.BRAND,
                        () -> mHandler.postDelayed(() -> showTriggerDialog(mPrefs.isSigningEnabled()), 1500));
            })
        );
    }

    /**
     * Menghasilkan isi YAML lengkap untuk Android CI/CD workflow.
     * Jika signing sudah dikonfigurasi, otomatis inject signing step.
     */
    private String buildCiCdYaml(String branch) {
        boolean hasSigning = mPrefs.isSigningEnabled() && mPrefs.hasSigningConfig();
        String signingEnvBlock = "";
        String signingStep = "";

        if (hasSigning) {
            signingEnvBlock =
                "        env:\n"
                + "          SIGNING_ALIAS: ${{ vars.SIGNING_ALIAS }}\n"
                + "          SIGNING_STORE_PASS: ${{ vars.SIGNING_STORE_PASS }}\n"
                + "          SIGNING_KEY_PASS: ${{ vars.SIGNING_KEY_PASS }}\n";
            signingStep =
                "\n"
                + "      - name: Sign APK\n"
                + "        run: |\n"
                + "          apksigner sign \\\n"
                + "            --ks .signing/release.jks \\\n"
                + "            --ks-key-alias $SIGNING_ALIAS \\\n"
                + "            --ks-pass pass:$SIGNING_STORE_PASS \\\n"
                + "            --key-pass pass:$SIGNING_KEY_PASS \\\n"
                + "            app/build/outputs/apk/release/app-release-unsigned.apk\n"
                + signingEnvBlock;
        }

        return "name: Android CI\n"
            + "\n"
            + "on:\n"
            + "  push:\n"
            + "    branches: [ \"" + branch + "\" ]\n"
            + "  workflow_dispatch:\n"
            + "\n"
            + "jobs:\n"
            + "  build:\n"
            + "    runs-on: ubuntu-latest\n"
            + "\n"
            + "    steps:\n"
            + "      - name: Checkout repository\n"
            + "        uses: actions/checkout@v4\n"
            + "\n"
            + "      - name: Set up JDK 17\n"
            + "        uses: actions/setup-java@v4\n"
            + "        with:\n"
            + "          java-version: '17'\n"
            + "          distribution: 'temurin'\n"
            + "          cache: gradle\n"
            + "\n"
            + "      - name: Grant execute permission for gradlew\n"
            + "        run: chmod +x gradlew\n"
            + "\n"
            + "      - name: Build Release APK\n"
            + "        run: ./gradlew assembleRelease --no-daemon\n"
            + (hasSigning ? signingStep : "")
            + "\n"
            + "      - name: Upload APK Artifact\n"
            + "        uses: actions/upload-artifact@v4\n"
            + "        with:\n"
            + "          name: release-apk-${{ github.run_number }}\n"
            + "          path: app/build/outputs/apk/release/\n"
            + "          retention-days: 14\n";
    }

    /**
     * "AI Fix" button on a failed run card.
     * Fetches the log, extracts errors, resolves file paths, then opens AI Fix editor.
     * This is the ONE-TAP path: Failed run → AI reads log → suggests fix directly.
     */
    private void launchAiFixFromRun(final GHApi.WorkflowRun run) {
        android.app.Dialog prog = makeProgressDialog();
        ((TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0))
            .setText("Reading build log...");
        prog.show();

        GHApi.getRunLogs(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, run.id,
            (logs, err) -> mHandler.post(() -> {
                prog.dismiss();
                if (err != null || logs == null || logs.trim().isEmpty()) {
                    showErr("Could not fetch build log.\n\n"
                        + (err != null ? err : "Log is empty.")
                        + "\n\nTry opening Logs → Track tab manually.");
                    return;
                }
                // Parse error refs from log
                java.util.List<GHApi.ErrorFileRef> refs = GHApi.parseErrorFiles(logs);
                if (refs.isEmpty()) {
                    // No specific file errors — open Ask tab pre-filled with log summary
                    String rootCause = extractRootCause(logs);
                    mChatContext = "My build failed. Here is the error:\n\n```\n"
                        + (rootCause.isEmpty() ? logs.substring(0, Math.min(logs.length(), 2000)) : rootCause)
                        + "\n```\n\nWhat went wrong and how do I fix it?";
                    navigateTo(() -> setContentView(buildRepoPageAtTab(4)));
                    return;
                }
                // Resolve file paths in repo
                android.app.Dialog prog2 = makeProgressDialog();
                ((TextView)((LinearLayout)prog2.findViewById(android.R.id.message)).getChildAt(0))
                    .setText("Locating files...");
                prog2.show();
                GHApi.resolveFilePaths(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    mCurrentBranch, refs, (resolved, err2) -> mHandler.post(() -> {
                        prog2.dismiss();
                        if (resolved == null || resolved.isEmpty()) {
                            // Fallback to Ask tab
                            String rootCause = extractRootCause(logs);
                            mChatContext = "My build failed. Here is the error:\n\n```\n"
                                + (rootCause.isEmpty() ? logs.substring(0, Math.min(logs.length(), 2000)) : rootCause)
                                + "\n```\n\nWhat went wrong and how do I fix it?";
                            navigateTo(() -> setContentView(buildRepoPageAtTab(4)));
                            return;
                        }
                        mTrackedErrors = resolved;
                        // Auto-trigger AI Fix directly — no extra tap needed
                        startAiFix(resolved.get(0), new TextView(MainActivity.this));
                    }));
            }));
    }

    View buildRunCard(final GHApi.WorkflowRun run) {
        LinearLayout card = lv();
        card.setLayoutParams(mpWrap());
        card.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        card.setPadding(dp(14), dp(14), dp(14), dp(12));
        // Tag for optimistic removal: "runId|status"
        card.setTag(run.id + "|" + run.status);

        // Status badge
        int statusColor; String statusText;
        if ("completed".equals(run.status)) {
            if ("success".equals(run.conclusion))       { statusColor = ThemeManager.GREEN;  statusText = "Success"; }
            else if ("failure".equals(run.conclusion))  { statusColor = ThemeManager.RED;    statusText = "Failed";  }
            else if ("cancelled".equals(run.conclusion)){ statusColor = ThemeManager.DIM;    statusText = "Cancelled"; }
            else                                        { statusColor = ThemeManager.AMBER;  statusText = "~ " + run.conclusion.toUpperCase(); }
        } else if ("in_progress".equals(run.status))   { statusColor = ThemeManager.CYAN;   statusText = "Running"; }
        else                                            { statusColor = ThemeManager.DIM;    statusText = "… " + run.status.toUpperCase(); }

        LinearLayout headRow = lh(Gravity.CENTER_VERTICAL);
        headRow.setLayoutParams(mpWrap());
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLp.bottomMargin = dp(6);
        headRow.setLayoutParams(hrLp);

        TextView badge = badgeChip(statusText, statusColor, 0);
        badge.setBackground(rbs(0xFF0A0A0D, 6, statusColor));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.rightMargin = dp(10); badge.setLayoutParams(bLp);
        headRow.addView(badge);

        TextView nameTv = tv(run.name, ThemeManager.TEXT, 13f, true);
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(nameTv);
        card.addView(headRow);

        String date = run.createdAt.replace("T"," ").replace("Z","");
        TextView meta = tv("Branch: " + run.headBranch + "  ·  " + date, ThemeManager.DIM, 10f, false);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mLp.bottomMargin = dp(10); meta.setLayoutParams(mLp);
        card.addView(meta);

        // Action buttons — bigger, full row
        LinearLayout actRow = lh(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        actRow.setLayoutParams(aLp);
        actRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView logBtn = buildActBtn("Logs", ThemeManager.BRAND, ThemeManager.BRAND_D);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        logLp.rightMargin = dp(8); logBtn.setLayoutParams(logLp);
        logBtn.setOnClickListener(v -> navigateTo(() -> setContentView(buildLogPage(run))));

        TextView artifBtn = buildActBtn("Artifacts", ThemeManager.BRAND, ThemeManager.BRAND_D);
        artifBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        artifBtn.setOnClickListener(v -> navigateTo(() -> setContentView(buildArtifactsPage(run))));

        actRow.addView(logBtn); actRow.addView(artifBtn);
        card.addView(actRow);

        // Per-run cancel (if active) or delete (if completed)
        boolean isActive = "in_progress".equals(run.status) || "queued".equals(run.status)
                        || "waiting".equals(run.status);
        boolean isCompleted = "completed".equals(run.status);

        if (isActive || isCompleted) {
            LinearLayout mgmtRow = lh(Gravity.END);
            LinearLayout.LayoutParams mgmtLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mgmtLp.topMargin = dp(8);
            mgmtRow.setLayoutParams(mgmtLp);

            if (isActive) {
                TextView cancelRunBtn = buildActBtn("Cancel", ThemeManager.DANGER, ThemeManager.DANGER_D);
                LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
                cancelRunBtn.setPadding(dp(14), 0, dp(14), 0);
                cancelRunBtn.setLayoutParams(crLp);
                cancelRunBtn.setOnClickListener(v -> {
                    // Optimistic: update badge + hide cancel button immediately
                    cancelRunBtn.setVisibility(android.view.View.GONE);
                    badge.setText("Cancelling");
                    badge.setTextColor(ThemeManager.DIM);
                    badge.setBackground(rbs(0xFF0A0A0D, 6, ThemeManager.DIM));
                    GHApi.cancelRun(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, run.id,
                        (ok, err) -> mHandler.post(() -> {
                            if (!ok) {
                                // Rollback
                                cancelRunBtn.setVisibility(android.view.View.VISIBLE);
                                badge.setText(statusText);
                                badge.setTextColor(statusColor);
                                badge.setBackground(rbs(0xFF0A0A0D, 6, statusColor));
                                showErr("Cancel failed:\n" + err);
                            }
                            // Success: polling will update status naturally
                        }));
                });
                mgmtRow.addView(cancelRunBtn);
            } else {
                // "AI Fix" button — only on failed runs
                if ("failure".equals(run.conclusion)) {
                    TextView aiFixBtn = buildActBtn("🤖 AI Fix", ThemeManager.WARNING, ThemeManager.WARNING_D);
                    LinearLayout.LayoutParams afLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
                    afLp.rightMargin = dp(8);
                    aiFixBtn.setPadding(dp(14), 0, dp(14), 0);
                    aiFixBtn.setLayoutParams(afLp);
                    aiFixBtn.setOnClickListener(v -> launchAiFixFromRun(run));
                    mgmtRow.addView(aiFixBtn);
                }

                TextView delRunBtn = buildActBtn("Remove", ThemeManager.DANGER, ThemeManager.DANGER_D);
                LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
                delRunBtn.setPadding(dp(14), 0, dp(14), 0);
                delRunBtn.setLayoutParams(drLp);
                delRunBtn.setOnClickListener(v ->
                    showConfirm("Delete this run?", run.name + "\n\nLogs and artifacts will be lost.", "Delete", () -> {
                        // Optimistic: remove card from list instantly, restore on failure
                        if (mRunListContainer != null) {
                            int cardIdx = -1;
                            android.view.View spacer = null;
                            for (int ci = 0; ci < mRunListContainer.getChildCount(); ci++) {
                                android.view.View cv = mRunListContainer.getChildAt(ci);
                                if (cv == card) {
                                    cardIdx = ci;
                                    // spacer is the sp(8) right after card
                                    if (ci + 1 < mRunListContainer.getChildCount())
                                        spacer = mRunListContainer.getChildAt(ci + 1);
                                    break;
                                }
                            }
                            final int fCardIdx = cardIdx;
                            final android.view.View fSpacer = spacer;
                            mRunListContainer.removeView(card);
                            if (fSpacer != null) mRunListContainer.removeView(fSpacer);
                            if (mCachedRuns != null) mCachedRuns.remove(run);
                            GHApi.deleteRun(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, run.id,
                                (ok, err) -> mHandler.post(() -> {
                                    if (!ok) {
                                        // Rollback — re-insert at original position
                                        int insertAt = Math.min(fCardIdx, mRunListContainer.getChildCount());
                                        mRunListContainer.addView(card, insertAt);
                                        if (fSpacer != null) mRunListContainer.addView(fSpacer, insertAt + 1);
                                        if (mCachedRuns != null) mCachedRuns.add(Math.min(fCardIdx / 2, mCachedRuns.size()), run);
                                        delRunBtn.setText("Remove"); delRunBtn.setEnabled(true);
                                        showErr("Failed:\n" + err);
                                    }
                                    // Success: already removed, nothing to do
                                }));
                        } else {
                            // Fallback if container not available
                            delRunBtn.setText("Deleting..."); delRunBtn.setEnabled(false);
                            GHApi.deleteRun(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, run.id,
                                (ok, err) -> mHandler.post(() -> {
                                    if (ok) { mCachedRuns = null; navigateTo(() -> setContentView(buildRepoPageAtTab(1))); }
                                    else { delRunBtn.setText("Remove"); delRunBtn.setEnabled(true); showErr("Failed:\n" + err); }
                                }));
                        }
                    }));
                mgmtRow.addView(delRunBtn);
            }
            card.addView(mgmtRow);
        }

        return card;
    }

    private TextView buildActBtn(String label, int color, int bg) {
        TextView btn = tv(label, color, 11f, true);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(rbs(bg, 10, color));
        btn.setLetterSpacing(0.04f);
        applyRipple(btn, 10); // IMPROVEMENT #5
        return btn;
    }

    private void showTriggerDialog(final boolean withSign) {
        GHApi.listWorkflows(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            (wfs, err) -> mHandler.post(() -> {
                if (err != null || wfs == null || wfs.isEmpty()) {
                    toast("No workflows found in this repo"); return;
                }
                String[] names = new String[wfs.size()];
                for (int i = 0; i < wfs.size(); i++) names[i] = wfs.get(i).name;

                showMenu(withSign ? "▶ Build + Sign APK" : "▶ Trigger Build", names,
                    makeRunnables(names.length, idx -> { GHApi.Workflow wf = wfs.get(idx);
                        // Show progress then load YAML
                        android.app.Dialog prog = makeProgressDialog();
                        ((TextView)((LinearLayout)prog.findViewById(android.R.id.message))
                            .getChildAt(0)).setText("Loading workflow...");
                        prog.show();
                        GHApi.getWorkflowYaml(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                            wf.path, (yaml, yerr) -> mHandler.post(() -> {
                                prog.dismiss();
                                String safeYaml = yaml != null ? yaml : "";
                                boolean hasDispatch = safeYaml.toLowerCase()
                                    .contains("workflow_dispatch");
                                if (!hasDispatch) {
                                    // Workflow tidak punya trigger manual — tawarkan fix
                                    showConfirm(
                                        "⚠ Can't Trigger Manually",
                                        "\"" + wf.name + "\" doesn't have a workflow_dispatch trigger.\n\n"
                                        + "GitHub will reject manual triggers (HTTP 422) for this workflow.\n\n"
                                        + "Add workflow_dispatch trigger automatically?",
                                        "Add Trigger", ThemeManager.AMBER,
                                        () -> {
                                            android.app.Dialog p2 = makeProgressDialog();
                                            ((TextView)((LinearLayout)p2.findViewById(
                                                android.R.id.message)).getChildAt(0))
                                                .setText("Patching workflow...");
                                            p2.show();
                                            GHApi.addWorkflowDispatchTrigger(
                                                mPrefs.getToken(), mCurrentOwner,
                                                mCurrentRepo, mCurrentBranch, wf.path,
                                                (ok, addErr) -> mHandler.post(() -> {
                                                    p2.dismiss();
                                                    if (!ok) {
                                                        showErr("Failed to add trigger: " + addErr);
                                                        return;
                                                    }
                                                    toast("✓ Trigger added — opening form");
                                                    List<GHApi.WorkflowInput> inputs =
                                                        GHApi.parseWorkflowInputs(safeYaml);
                                                    showWorkflowInputsForm(wf, inputs, withSign);
                                                }));
                                        });
                                    return;
                                }
                                List<GHApi.WorkflowInput> inputs =
                                    GHApi.parseWorkflowInputs(safeYaml);
                                showWorkflowInputsForm(wf, inputs, withSign);
                            }));
                    }));
            }));
    }

    /** Dynamically generates a form dialog from parsed WorkflowInput list. */
    void showWorkflowInputsForm(final GHApi.Workflow wf,
                                         final List<GHApi.WorkflowInput> inputs,
                                         final boolean withSign) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setDimAmount(0.5f);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setCancelable(true);

        LinearLayout root = lv();
        root.setBackground(rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.93f),
            LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Header ─────────────────────────────────────────────────────────
        LinearLayout header = lh(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(16), dp(14), dp(12));
        header.setLayoutParams(mpWrap());
        TextView titleTv = tv("▶  " + wf.name, ThemeManager.TEXT, 14f, true);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(titleTv);
        TextView branchChip = badgeChip("⎇ " + mCurrentBranch, ThemeManager.DIM, ThemeManager.SURFACE2);
        header.addView(branchChip);
        root.addView(header);
        root.addView(rowDivider());

        // ── Scrollable form content ─────────────────────────────────────────
        ScrollView sv = new ScrollView(this);
        LinearLayout content = lv();
        content.setPadding(dp(16), dp(8), dp(16), dp(8));

        // Map: key → Object[]{ type, widgetRef }
        // boolean  → boolean[]{ value }
        // choice   → String[]{ selected }
        // string   → EditText
        final java.util.LinkedHashMap<String, Object[]> widgets = new java.util.LinkedHashMap<>();

        if (inputs.isEmpty()) {
            TextView noInput = tv(
                "This workflow has no inputs.\n"
                + "It will be triggered on branch " + mCurrentBranch + ".",
                ThemeManager.DIM, 11f, false);
            noInput.setPadding(0, dp(12), 0, dp(12));
            noInput.setLineSpacing(dp(2), 1.2f);
            content.addView(noInput);
        }

        for (GHApi.WorkflowInput inp : inputs) {
            // Label row: description + key badge
            String labelText = inp.description.isEmpty() ? inp.key : inp.description;

            TextView descTv = tv(labelText, ThemeManager.TEXT2, 11.5f, false);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = dp(14); dLp.bottomMargin = dp(4);
            descTv.setLayoutParams(dLp);
            content.addView(descTv);

            // Key + required badges
            LinearLayout badgeRow = lh(Gravity.CENTER_VERTICAL);
            badgeRow.setLayoutParams(mpWrap());
            LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            brlp.rightMargin = dp(6);
            TextView keyChip = badgeChip(inp.key, ThemeManager.DIM, ThemeManager.SURFACE2);
            keyChip.setLayoutParams(brlp);
            badgeRow.addView(keyChip);
            if (inp.required) {
                badgeRow.addView(badgeChip("REQUIRED", ThemeManager.RED, ThemeManager.RED_D));
            }
            LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            brLp.bottomMargin = dp(6);
            badgeRow.setLayoutParams(brLp);
            content.addView(badgeRow);

            // ── Widget per type ────────────────────────────────────────────
            if ("boolean".equals(inp.type)) {
                final boolean[] val = { "true".equalsIgnoreCase(inp.defaultValue) };
                {
                    LinearLayout toggleRow = lh(Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams tRowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
                    tRowLp.topMargin = dp(2);
                    toggleRow.setLayoutParams(tRowLp);
                    toggleRow.setBackground(rbs(ThemeManager.SURFACE2, 12, ThemeManager.BORDER));
                    toggleRow.setPadding(dp(14), dp(8), dp(14), dp(8));
                    TextView toggleLbl = tv(val[0] ? "Enabled" : "Disabled",
                        val[0] ? ThemeManager.SUCCESS : ThemeManager.TEXT2, 13f, true);
                    toggleLbl.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    TextView pillBtn = tv(val[0] ? "ON" : "OFF", 0xFFFFFFFF, 11f, true);
                    pillBtn.setGravity(Gravity.CENTER);
                    pillBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(28)));
                    pillBtn.setBackground(rb(val[0] ? ThemeManager.SUCCESS : ThemeManager.BORDER2, 14));
                    pillBtn.setLetterSpacing(0.06f);
                    android.view.View.OnClickListener toggleClick = v2 -> {
                        val[0] = !val[0];
                        toggleLbl.setText(val[0] ? "Enabled" : "Disabled");
                        toggleLbl.setTextColor(val[0] ? ThemeManager.SUCCESS : ThemeManager.TEXT2);
                        pillBtn.setText(val[0] ? "ON" : "OFF");
                        pillBtn.setBackground(rb(val[0] ? ThemeManager.SUCCESS : ThemeManager.BORDER2, 14));
                    };
                    pillBtn.setOnClickListener(toggleClick);
                    toggleRow.setOnClickListener(toggleClick);
                    applyRipple(toggleRow, 12);
                    toggleRow.addView(toggleLbl);
                    toggleRow.addView(pillBtn);
                    content.addView(toggleRow);
                }
                widgets.put(inp.key, new Object[]{ "boolean", val });

            } else if ("choice".equals(inp.type) && !inp.options.isEmpty()) {
                // Detect boolean-like choice (options = [true,false] or [false,true])
                boolean isBoolChoice = inp.options.size() == 2
                    && isBooleanOptions(inp.options);

                if (isBoolChoice) {
                    String defVal = inp.defaultValue.isEmpty() ? inp.options.get(0) : inp.defaultValue;
                    final boolean[] val = { "true".equalsIgnoreCase(defVal) };
                    final String[] strVal = { val[0] ? "true" : "false" };
                    {
                        LinearLayout toggleRow = lh(Gravity.CENTER_VERTICAL);
                        LinearLayout.LayoutParams tRowLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
                        tRowLp.topMargin = dp(2);
                        toggleRow.setLayoutParams(tRowLp);
                        toggleRow.setBackground(rbs(ThemeManager.SURFACE2, 12, ThemeManager.BORDER));
                        toggleRow.setPadding(dp(14), dp(8), dp(14), dp(8));
                        TextView toggleLbl = tv(val[0] ? "Enabled" : "Disabled",
                            val[0] ? ThemeManager.SUCCESS : ThemeManager.TEXT2, 13f, true);
                        toggleLbl.setLayoutParams(new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                        TextView pillBtn = tv(val[0] ? "ON" : "OFF", 0xFFFFFFFF, 11f, true);
                        pillBtn.setGravity(Gravity.CENTER);
                        pillBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(28)));
                        pillBtn.setBackground(rb(val[0] ? ThemeManager.SUCCESS : ThemeManager.BORDER2, 14));
                        pillBtn.setLetterSpacing(0.06f);
                        android.view.View.OnClickListener toggleClick = v2 -> {
                            val[0] = !val[0];
                            strVal[0] = val[0] ? "true" : "false";
                            toggleLbl.setText(val[0] ? "Enabled" : "Disabled");
                            toggleLbl.setTextColor(val[0] ? ThemeManager.SUCCESS : ThemeManager.TEXT2);
                            pillBtn.setText(val[0] ? "ON" : "OFF");
                            pillBtn.setBackground(rb(val[0] ? ThemeManager.SUCCESS : ThemeManager.BORDER2, 14));
                        };
                        pillBtn.setOnClickListener(toggleClick);
                        toggleRow.setOnClickListener(toggleClick);
                        applyRipple(toggleRow, 12);
                        toggleRow.addView(toggleLbl);
                        toggleRow.addView(pillBtn);
                        content.addView(toggleRow);
                    }
                    widgets.put(inp.key, new Object[]{ "choice", strVal });

                } else {
                    // Regular dropdown choice
                    String defVal = inp.defaultValue.isEmpty() ? inp.options.get(0) : inp.defaultValue;
                    final String[] selected = { defVal };
                    final TextView choiceBtn = roundBtn("▾  " + selected[0], ThemeManager.TEXT2, ThemeManager.SURFACE2);
                    choiceBtn.setGravity(Gravity.CENTER);
                    choiceBtn.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
                    String[] opts = inp.options.toArray(new String[0]);
                    choiceBtn.setOnClickListener(v -> showMenu(labelText, opts,
                        makeRunnables(opts.length, oi -> { selected[0]=opts[oi]; choiceBtn.setText("\u25be  "+selected[0]); })));
                    content.addView(choiceBtn);
                    widgets.put(inp.key, new Object[]{ "choice", selected });
                }

            } else {
                // string / default
                EditText et = styledInput(
                    inp.defaultValue.isEmpty() ? "Enter value..." : inp.defaultValue, false);
                if (!inp.defaultValue.isEmpty()) et.setText(inp.defaultValue);
                et.setLayoutParams(mpWrap());
                content.addView(et);
                widgets.put(inp.key, new Object[]{ "string", et });
            }
        }

        sv.addView(content);
        // Limit form height to ~55% of screen
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (int)(getResources().getDisplayMetrics().heightPixels * 0.55f));
        sv.setLayoutParams(svLp);
        root.addView(sv);
        root.addView(rowDivider());

        // ── Button row ──────────────────────────────────────────────────────
        LinearLayout btnRow = lh(Gravity.END);
        btnRow.setPadding(dp(14), dp(12), dp(14), dp(14));
        btnRow.setLayoutParams(mpWrap());

        TextView cancelBtn = roundBtn("Cancel", ThemeManager.DIM, ThemeManager.SURFACE2);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(46));
        cbLp.rightMargin = dp(10);
        cancelBtn.setPadding(dp(18), 0, dp(18), 0);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setLayoutParams(cbLp);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        final TextView triggerBtn = tv("▶  TRIGGER", 0xFFFFFFFF, 12f, true);
        triggerBtn.setBackground(rb(ThemeManager.GREEN, 10));
        triggerBtn.setPadding(dp(20), 0, dp(20), 0);
        triggerBtn.setGravity(Gravity.CENTER);
        triggerBtn.setLetterSpacing(0.05f);
        triggerBtn.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)));
        triggerBtn.setOnClickListener(v -> {
            // Collect all input values
            final java.util.Map<String, String> finalInputs = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, Object[]> entry : widgets.entrySet()) {
                String key = entry.getKey();
                Object[] w = entry.getValue();
                String type = (String) w[0];
                String val;
                if ("boolean".equals(type)) {
                    val = String.valueOf(((boolean[]) w[1])[0]);
                } else if ("choice".equals(type)) {
                    val = ((String[]) w[1])[0];
                } else {
                    val = ((EditText) w[1]).getText().toString().trim();
                }
                finalInputs.put(key, val);
            }
            // Inject sign_apk HANYA jika workflow memang punya input sign_apk
            // (Android/React Native only) — jangan inject ke Python/Node/dll
            boolean workflowHasSignInput = false;
            for (GHApi.WorkflowInput wi : inputs) {
                if ("sign_apk".equals(wi.key)) { workflowHasSignInput = true; break; }
            }
            if (workflowHasSignInput && !finalInputs.containsKey("sign_apk")) {
                finalInputs.put("sign_apk", withSign ? "true" : "false");
            }
            dialog.dismiss();
            triggerWithInputs(wf, finalInputs);
        });

        btnRow.addView(cancelBtn);
        btnRow.addView(triggerBtn);
        root.addView(btnRow);

        dialog.setContentView(root);
        dialog.show();
    }

    /**
     * Optimistic UI helper — instantly removes run cards from mRunListContainer.
     * activeOnly=true  → only remove cards for active (running/queued) runs
     * completedOnly=true → only remove cards for completed runs
     * both false → remove all
     */
    private void clearRunCardsOptimistic(boolean activeOnly, boolean completedOnly) {
        if (mRunListContainer == null) return;
        // Collect views to remove (avoid modifying list while iterating)
        java.util.List<android.view.View> toRemove = new java.util.ArrayList<>();
        for (int i = 0; i < mRunListContainer.getChildCount(); i++) {
            android.view.View v = mRunListContainer.getChildAt(i);
            Object tag = v.getTag();
if (tag instanceof String) {
                String[] parts = ((String) tag).split("\\|");
                // tag format: "runId|status"
                if (parts.length >= 2) {
                    String status = parts[1];
                    boolean isActive = "in_progress".equals(status)
                        || "queued".equals(status) || "waiting".equals(status);
                    boolean isCompleted = "completed".equals(status);
                    if (activeOnly && isActive)       toRemove.add(v);
                    else if (completedOnly && isCompleted) toRemove.add(v);
                    else if (!activeOnly && !completedOnly) toRemove.add(v);
                }
            } else if (!activeOnly && !completedOnly) {

                toRemove.add(v);
            }
        }
        for (android.view.View v : toRemove) mRunListContainer.removeView(v);
        if (mCachedRuns != null) {
            mCachedRuns.removeIf(r -> {
                boolean isActive = "in_progress".equals(r.status)
                    || "queued".equals(r.status) || "waiting".equals(r.status);
                if (activeOnly)    return isActive;
                if (completedOnly) return "completed".equals(r.status);
                return true;
            });
        }
    }

        private void showRunsManagementDialog() {
        showMenu("Manage Workflow Runs", new String[]{
            "⏹ Cancel all running builds",
            "🗑 Delete all runs (cancel + delete all)",
            "🗑 Delete completed runs only"
        }, new Runnable[]{
            () -> showConfirm("Cancel all running builds?", "Runs in background — you can hide the app.", "Confirm", () -> {
                // Optimistic: hide all active run cards instantly
                clearRunCardsOptimistic(true, false);
                showNotif(NOTIF_MANAGE, "Cancelling Builds", mCurrentRepo + "...", -1, 0);
                GHApi.cancelAllRuns(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    false, (counts, err) -> mHandler.post(() -> {
                        cancelNotif(NOTIF_MANAGE);
                        if (err != null) {
                            showNotifResult("✗ Error", err); showErr("Error: " + err);
                            // Rollback: rebuild list from scratch on error
                            mCachedRuns = null; navigateTo(() -> setContentView(buildRepoPageAtTab(1))); return;
                        }
                        String r = counts[0] + " run(s) cancelled";
                        showNotifResult("✓ Done", r); toast(r);
                        // Silent: just invalidate cache, list already updated
                        mCachedRuns = null;
                    }));
            }),
            () -> showConfirm("Delete all runs?", "Logs & artifacts will be lost. Runs in background.", "Confirm", () -> {
                // Optimistic: clear ALL run cards instantly
                clearRunCardsOptimistic(false, false);
                showNotif(NOTIF_MANAGE, "Deleting All Runs", mCurrentRepo + "...", -1, 0);
                GHApi.cancelAllRuns(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    true, (counts, err) -> mHandler.post(() -> {
                        cancelNotif(NOTIF_MANAGE);
                        if (err != null) {
                            showNotifResult("✗ Error", err); showErr("Error: " + err);
                            mCachedRuns = null; navigateTo(() -> setContentView(buildRepoPageAtTab(1))); return;
                        }
                        String r = counts[0] + " cancelled, " + counts[1] + " deleted";
                        showNotifResult("✓ Done", r); toast(r);
                        mCachedRuns = null;
                    }));
            }),
            () -> showConfirm("Delete completed runs?", "Runs in background — you can hide the app.", "Confirm", () -> {
                // Optimistic: remove completed run cards instantly
                clearRunCardsOptimistic(false, true);
                showNotif(NOTIF_MANAGE, "Deleting Completed Runs", mCurrentRepo + "...", -1, 0);
                GHApi.deleteCompletedRuns(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    (counts, err) -> mHandler.post(() -> {
                        cancelNotif(NOTIF_MANAGE);
                        if (err != null) {
                            showNotifResult("✗ Error", err); showErr("Error: " + err);
                            mCachedRuns = null; navigateTo(() -> setContentView(buildRepoPageAtTab(1))); return;
                        }
                        String r = counts[1] + " completed run(s) deleted";
                        showNotifResult("✓ Done", r); toast(r);
                        mCachedRuns = null;
                    }));
            })
        });
    }

    private void triggerWithInputs(final GHApi.Workflow wf, final java.util.Map<String,String> inputs) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("ref", mCurrentBranch);
                if (!inputs.isEmpty()) {
                    org.json.JSONObject inp = new org.json.JSONObject();
                    for (java.util.Map.Entry<String,String> e : inputs.entrySet()) {
                        String v = e.getValue();
                        // Always send as string — GitHub Actions workflow_dispatch
                        // accepts strings for all input types including boolean
                        inp.put(e.getKey(), v);
                    }
                    body.put("inputs", inp);
                }
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(
                    "https://api.github.com/repos/" + mCurrentOwner + "/" + mCurrentRepo
                    + "/actions/workflows/" + wf.id + "/dispatches").openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "token " + mPrefs.getToken());
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000); conn.setReadTimeout(30000);
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                // Baca error body — GitHub kasih alasan spesifik di sini
                String ghErrBody = "";
                try {
                    java.io.InputStream errStream = conn.getErrorStream();
                    if (errStream != null) {
                        java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(errStream));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        ghErrBody = sb.toString();
                    }
                } catch (Exception ignored) {}
                final String finalErrBody = ghErrBody;
                mHandler.post(() -> {
                    if (code == 204) {
                        toast("Build triggered: " + wf.name
                            + (inputs.getOrDefault("sign_apk","false").equals("true") ? " + Sign" : ""));
                        mCachedRuns = null;
                        mHandler.postDelayed(() -> {
                            if (!mNavStack.isEmpty()) {
                                navigateTo(() -> setContentView(buildRepoPageAtTab(1)));
                            }
                        }, 2000);
                    } else if (code == 422) {
                        // Parse pesan error dari GitHub untuk tau alasan pastinya
                        String ghMsg = finalErrBody;
                        try { ghMsg = new org.json.JSONObject(finalErrBody).optString("message", finalErrBody); } catch (Exception ignored2) {}
                        final String dispMsg = ghMsg;
                        // Cek apakah ini memang masalah workflow_dispatch atau bukan
                        boolean missingDispatch = dispMsg.toLowerCase().contains("workflow_dispatch")
                            || dispMsg.toLowerCase().contains("no ref found")
                            || dispMsg.isEmpty();
                        if (missingDispatch) {
                        showConfirm(
                            "⚠ Trigger Rejected (HTTP 422)",
                            "GitHub rejected this trigger because \"" + wf.name
                            + "\" doesn't have a workflow_dispatch trigger.\n\n"
                            + "Add it automatically so you can trigger this workflow manually?",
                            "Add Trigger", ThemeManager.AMBER,
                            () -> {
                                android.app.Dialog p3 = makeProgressDialog();
                                ((TextView)((LinearLayout)p3.findViewById(
                                    android.R.id.message)).getChildAt(0))
                                    .setText("Patching workflow...");
                                p3.show();
                                GHApi.addWorkflowDispatchTrigger(
                                    mPrefs.getToken(), mCurrentOwner,
                                    mCurrentRepo, mCurrentBranch, wf.path,
                                    (ok, addErr) -> mHandler.post(() -> {
                                        p3.dismiss();
                                        if (!ok) {
                                            showErr("Failed to add trigger: " + addErr);
                                            return;
                                        }
                                        // Langsung buka form — tidak perlu klik ulang
                                        toast("✓ Trigger added! Opening form...");
                                        GHApi.getWorkflowYaml(mPrefs.getToken(),
                                            mCurrentOwner, mCurrentRepo, wf.path,
                                            (yaml2, yerr2) -> mHandler.post(() -> {
                                                List<GHApi.WorkflowInput> inputs2 =
                                                    GHApi.parseWorkflowInputs(
                                                        yaml2 != null ? yaml2 : "");
                                                showWorkflowInputsForm(wf, inputs2, false);
                                            }));
                                    }));
                            });
                        } else {
                            // 422 bukan karena workflow_dispatch — tampilkan pesan asli GitHub
                            showErr("GitHub error (422):\n" + dispMsg);
                        }
                    } else {
                        toast("Trigger failed (HTTP " + code + "): " + finalErrBody);
                    }
                });
            } catch (Exception e) {
                mHandler.post(() -> toast("Error: " + e.getMessage()));
            }
        });
    }

    // ── UPLOAD TAB — ZIP only ─────────────────────────────────────────────────
    private View buildUploadTab() {
        LinearLayout c = lv(); c.setLayoutParams(mpWrap());

        // Info card
        LinearLayout infoCard = lv();
        infoCard.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        infoCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        icLp.bottomMargin = dp(16);
        infoCard.setLayoutParams(icLp);
        TextView infoTitle = tv("📦 ZIP → Extract & Push", ThemeManager.TEXT, 13f, true);
        infoTitle.setPadding(0, 0, 0, dp(4));
        infoCard.addView(infoTitle);
        TextView infoDesc = tv("Select a ZIP → all contents are extracted and pushed to the repo.\n"
            + "Folder structure preserved.\n\n"
            + "To upload a single file or edit code:\n"
            + "   Open Files tab → tap + Add.", ThemeManager.TEXT2, 10.5f, false);
        infoDesc.setLineSpacing(dp(1), 1.3f);
        infoCard.addView(infoDesc);
        c.addView(infoCard);

        // Settings card
        LinearLayout settCard = lv();
        settCard.setLayoutParams(mpWrap());
        settCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        settCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.bottomMargin = dp(16);
        settCard.setLayoutParams(scLp);

        settCard.addView(fieldLabel("BASE PATH IN REPO (optional)"));
        final EditText baseEt = styledInput("empty = repo root, e.g. app/src", false);
        settCard.addView(baseEt);

        settCard.addView(fieldLabel("Commit message"));
        final EditText msgEt = styledInput("Upload via GitDeploy", false);
        msgEt.setText("Upload via GitDeploy");
        settCard.addView(msgEt);

        settCard.addView(fieldLabel("BRANCH"));
        final EditText branchEt = styledInput(mCurrentBranch, false);
        branchEt.setText(mCurrentBranch);
        settCard.addView(branchEt);
        c.addView(settCard);

        final TextView pickBtn = primaryBtn("📦  Select ZIP", ThemeManager.BRAND);
        pickBtn.setTextColor(ThemeManager.current() == ThemeManager.THEME_LIGHT ? 0xFFFFFFFF : 0xFF000000);
        pickBtn.setOnClickListener(v -> {
            final String basePath = baseEt.getText().toString().trim();
            final String msg = msgEt.getText().toString().trim();
            final String branch = branchEt.getText().toString().trim();
            showMenu("How to upload this ZIP?",
                new String[]{"📂  Extract & Push files", "📦  Upload ZIP as file"},
                new Runnable[]{
                    () -> {
                        mPendingUploadBasePath = basePath;
                        mPendingUploadMsg = msg.isEmpty() ? "Upload via GitDeploy" : msg;
                        mPendingUploadBranch = branch.isEmpty() ? mCurrentBranch : branch;
                        mPendingUploadRepo = mCurrentRepo;
                        mPendingUploadZipMode = true; mPendingSyncMode = false; mPendingRawZipUpload = false;
                        Intent i2 = new Intent(Intent.ACTION_GET_CONTENT);
                        i2.setType("*/*"); i2.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(Intent.createChooser(i2, "Select ZIP"), REQ_PICK_FILE);
                    },
                    () -> {
                        mPendingUploadBasePath = basePath;
                        mPendingUploadMsg = msg.isEmpty() ? "Upload ZIP via GitDeploy" : msg;
                        mPendingUploadBranch = branch.isEmpty() ? mCurrentBranch : branch;
                        mPendingUploadRepo = mCurrentRepo;
                        mPendingUploadZipMode = false; mPendingSyncMode = false; mPendingRawZipUpload = true;
                        Intent i2 = new Intent(Intent.ACTION_GET_CONTENT);
                        i2.setType("*/*"); i2.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(Intent.createChooser(i2, "Select ZIP"), REQ_PICK_FILE);
                    }
                });
        });
        c.addView(pickBtn);

        c.addView(sp(4));

        // Smart Sync button
        LinearLayout syncCard = lv();
        syncCard.setLayoutParams(mpWrap());
        syncCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER2));
        syncCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams syncCardLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        syncCardLp.bottomMargin = dp(8);
        syncCard.setLayoutParams(syncCardLp);
        TextView syncTitle = tv("🔄  Smart Sync — Local Folder", ThemeManager.SUCCESS, 13f, true);
        syncTitle.setPadding(0, 0, 0, dp(4));
        syncCard.addView(syncTitle);
        TextView syncDesc = tv("Select a local folder → only CHANGED files will be pushed.\n"
            + "Uses Git blob SHA — same as a real git push.", ThemeManager.TEXT2, 10.5f, false);
        syncDesc.setLineSpacing(dp(1), 1.3f);
        syncDesc.setPadding(0, 0, 0, dp(10));
        syncCard.addView(syncDesc);
        final TextView syncBtn = primaryBtn("Select Folder & Sync", ThemeManager.BRAND);
        syncBtn.setTextColor(ThemeManager.current() == ThemeManager.THEME_LIGHT ? 0xFFFFFFFF : 0xFF000000);
        syncBtn.setOnClickListener(v -> {
            mPendingUploadMsg    = msgEt.getText().toString().trim();
            mPendingUploadBranch = branchEt.getText().toString().trim();
            mPendingUploadRepo   = mCurrentRepo;
            mPendingSyncMode     = true;
            if (mPendingUploadMsg.isEmpty())    mPendingUploadMsg    = "Sync via GitDeploy";
            if (mPendingUploadBranch.isEmpty()) mPendingUploadBranch = mCurrentBranch;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQ_PICK_FOLDER);
        });
        syncCard.addView(syncBtn);
        c.addView(syncCard);

        return c;
    }

    // =========================================================================
    // RELEASE MANAGER TAB
    // =========================================================================
    private View buildReleaseTab() {
        LinearLayout c = lv(); c.setLayoutParams(mpWrap());

        // Header row with Create Release button
        LinearLayout hdr = lh(Gravity.CENTER_VERTICAL);
        hdr.setLayoutParams(mpWrap());
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(12); hdr.setLayoutParams(hLp);
        TextView lbl = secLabel("GitHub Releases");
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        hdr.addView(lbl);
        TextView createBtn = roundBtn("+ New Release", ThemeManager.BRAND, ThemeManager.BRAND_D);
        createBtn.setOnClickListener(v -> showCreateReleaseFromArtifact());
        hdr.addView(createBtn);
        c.addView(hdr);

        final LinearLayout list = lv(); list.setLayoutParams(mpWrap());
        list.addView(loadingRow());
        c.addView(list);

        GHApi.listReleases(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, (releases, err) -> mHandler.post(() -> {
            list.removeAllViews();
            if (err != null) { list.addView(errCard(err)); return; }
            if (releases == null || releases.isEmpty()) {
                TextView empty = tv("No releases yet.\n\nTap '+ New Release' to create your first release from the latest build artifact.", ThemeManager.DIM, 11f, false);
                empty.setGravity(Gravity.CENTER); empty.setLineSpacing(dp(2), 1.3f);
                empty.setPadding(dp(16), dp(32), dp(16), dp(32));
                list.addView(empty); return;
            }
            for (int i = 0; i < releases.size(); i++) {
                list.addView(buildReleaseCard(releases.get(i)));
                if (i < releases.size()-1) list.addView(sp(8));
            }
        }));
        return c;
    }

    private View buildReleaseCard(final GHApi.Release release) {
        LinearLayout card = lv();
        card.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setLayoutParams(mpWrap());
        card.setClickable(true);
        applyRipple(card);

        // Top row: tag + badges
        LinearLayout topRow = lh(Gravity.CENTER_VERTICAL);
        topRow.setLayoutParams(mpWrap());
        TextView tagTv = tv(release.tagName, ThemeManager.BRAND, 14f, true);
        tagTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(tagTv);
        if (release.prerelease)
            topRow.addView(badgeChip("pre-release", ThemeManager.AMBER, ThemeManager.AMBER_D));
        if (release.draft)
            topRow.addView(badgeChip("draft", ThemeManager.DIM, ThemeManager.SURFACE2));
        card.addView(topRow);

        // Release title
        if (!release.name.isEmpty() && !release.name.equals(release.tagName)) {
            TextView nameTv = tv(release.name, ThemeManager.TEXT, 12f, false);
            nameTv.setPadding(0, dp(2), 0, 0);
            card.addView(nameTv);
        }

        // Date
        String dateStr = release.createdAt.replace("T", "  ").replace("Z", "").substring(0, Math.min(release.createdAt.length(), 16));
        TextView dateTv = tv(dateStr, ThemeManager.DIM, 10f, false);
        dateTv.setPadding(0, dp(3), 0, 0);
        card.addView(dateTv);

        // Release notes preview
        if (!release.body.isEmpty()) {
            String preview = release.body.length() > 120 ? release.body.substring(0, 120) + "..." : release.body;
            TextView bodyTv = tv(preview, ThemeManager.TEXT2, 10.5f, false);
            bodyTv.setLineSpacing(dp(1), 1.3f);
            LinearLayout.LayoutParams bLp = mpWrap();
            ((LinearLayout.LayoutParams)bLp).topMargin = dp(8);
            bodyTv.setLayoutParams(bLp);
            card.addView(bodyTv);
        }

        // Assets area (loaded lazily on tap)
        final LinearLayout assetsArea = lv();
        assetsArea.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams aaLp = mpWrap();
        ((LinearLayout.LayoutParams)aaLp).topMargin = dp(10);
        assetsArea.setLayoutParams(aaLp);
        card.addView(assetsArea);

        // Action buttons row
        LinearLayout actRow = lh(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams arLp = mpWrap();
        ((LinearLayout.LayoutParams)arLp).topMargin = dp(10);
        actRow.setLayoutParams(arLp);

        final TextView expandBtn = roundBtn("Show Assets", ThemeManager.CYAN, ThemeManager.CYAN_D);
        final boolean[] expanded = {false};
        expandBtn.setOnClickListener(v -> {
            if (!expanded[0]) {
                expanded[0] = true;
                expandBtn.setText("Hide Assets");
                assetsArea.setVisibility(android.view.View.VISIBLE);
                assetsArea.removeAllViews();
                assetsArea.addView(tv("Loading assets...", ThemeManager.DIM, 10.5f, false));
                GHApi.listReleaseAssets(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    release.id, (assets, err2) -> mHandler.post(() -> {
                        assetsArea.removeAllViews();
                        if (err2 != null) { assetsArea.addView(tv("Error: " + err2, ThemeManager.DANGER, 10.5f, false)); return; }
                        if (assets == null || assets.isEmpty()) {
                            assetsArea.addView(tv("No assets attached to this release.", ThemeManager.DIM, 10.5f, false)); return;
                        }
                        for (GHApi.ReleaseAsset asset : assets) {
                            assetsArea.addView(buildAssetRow(asset, release));
                        }
                    }));
            } else {
                expanded[0] = false;
                expandBtn.setText("Show Assets");
                assetsArea.setVisibility(android.view.View.GONE);
            }
        });
        actRow.addView(expandBtn);

        LinearLayout.LayoutParams delBtnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        delBtnLp.leftMargin = dp(8);
        TextView delBtn = roundBtn("Delete", ThemeManager.DANGER, 0xFF1A0305);
        delBtn.setLayoutParams(delBtnLp);
        delBtn.setOnClickListener(v -> showConfirm(
            "Delete release " + release.tagName + "?",
            "The release and its tag will be removed from GitHub.\nAssets will be permanently deleted.",
            "Delete", ThemeManager.DANGER, () -> {
                GHApi.deleteRelease(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, release.id,
                    (ok, err2) -> mHandler.post(() -> {
                        if (!ok) { showErr("Delete failed:\n" + err2); return; }
                        // Also delete the Git tag so it doesn't linger
                        GHApi.deleteTag(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, release.tagName,
                            (tagOk, tagErr) -> mHandler.post(() -> {
                                String msg = "Release deleted" + (tagOk ? " (tag removed)" : "");
                                toast(msg);
                                while(mNavStack.size()>1) mNavStack.pop();
                                navigateTo(() -> setContentView(buildRepoPageAtTab(5)));
                            }));
                    }));
            }));
        actRow.addView(delBtn);

        // Open on GitHub
        LinearLayout.LayoutParams webBtnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        webBtnLp.leftMargin = dp(8);
        TextView webBtn = roundBtn("GitHub", ThemeManager.DIM, ThemeManager.SURFACE2);
        webBtn.setLayoutParams(webBtnLp);
        webBtn.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(release.htmlUrl))); }
            catch (Exception e2) { toast("Cannot open browser"); }
        });
        actRow.addView(webBtn);

        card.addView(actRow);
        return card;
    }

    private View buildAssetRow(final GHApi.ReleaseAsset asset, final GHApi.Release release) {
        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
        row.setBackground(rbs(ThemeManager.SURFACE2, 10, ThemeManager.BORDER2));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams rLp = mpWrap();
        ((LinearLayout.LayoutParams)rLp).topMargin = dp(6);
        row.setLayoutParams(rLp);

        // Icon + name
        boolean isApk = asset.name.toLowerCase().endsWith(".apk");
        LinearLayout info = lv();
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        info.addView(tv(asset.name, ThemeManager.TEXT, 11.5f, isApk));
        info.addView(tv(fmtSize(asset.size) + "  " + asset.contentType.replace("application/", ""), ThemeManager.DIM, 9.5f, false));
        row.addView(info);

        // Download button
        final TextView dlBtn = iconBtn("", ThemeManager.CYAN, null);
        dlBtn.setTextSize(13f);
        dlBtn.setOnClickListener(v -> {
            dlBtn.setText("..."); dlBtn.setEnabled(false);
            showNotif(NOTIF_UPLOAD, "Downloading", asset.name + " from " + release.tagName, 0, 100);
            GHApi.downloadReleaseAsset(mPrefs.getToken(), asset.downloadUrl,
                (dl, tot) -> showNotif(NOTIF_UPLOAD, "Downloading " + asset.name,
                    fmtSize(dl) + (tot > 0 ? " / " + fmtSize(tot) : ""),
                    (int)(tot > 0 ? dl * 100 / tot : 0), 100),
                (bytes, err) -> mHandler.post(() -> {
                    cancelNotif(NOTIF_UPLOAD);
                    dlBtn.setEnabled(true);
                    if (err != null) { dlBtn.setText(""); showErr("Download failed:\n" + err); return; }
                    dlBtn.setText(""); dlBtn.setTextColor(ThemeManager.GREEN);
                    showNotifResult("Download Complete", asset.name + "  " + fmtSize(bytes.length));
                    // Save to Downloads
                    try {
                        String ts = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
                        String fname = asset.name.contains(".") ? asset.name : asset.name + "_" + ts;
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            android.content.ContentValues cv = new android.content.ContentValues();
                            cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname);
                            cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, asset.contentType);
                            cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                            android.net.Uri fu = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                            if (fu != null) { java.io.OutputStream os2 = getContentResolver().openOutputStream(fu); if (os2 != null) { os2.write(bytes); os2.close(); } }
                        } else {
                            java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                            dir.mkdirs();
                            FileOutputStream fos = new FileOutputStream(new java.io.File(dir, fname));
                            fos.write(bytes); fos.close();
                        }
                    } catch (Exception saveEx) { android.util.Log.w("GitDeploy", "Save to downloads failed: " + saveEx.getMessage()); }
                    // Universal: offer actions based on file type
                    String nameLower = asset.name.toLowerCase();
                    boolean isApkFile   = nameLower.endsWith(".apk");
                    boolean isZipFile   = nameLower.endsWith(".zip") || nameLower.endsWith(".tar.gz") || nameLower.endsWith(".tgz");
                    boolean isAarFile   = nameLower.endsWith(".aar");
                    boolean isJarFile   = nameLower.endsWith(".jar");
                    boolean isTxtFile   = nameLower.endsWith(".txt") || nameLower.endsWith(".log") || nameLower.endsWith(".md");

                    if (isApkFile) {
                        final byte[] apkBytes = bytes;
                        showMenu("Downloaded: " + asset.name,
                            new String[]{"📲  Install APK", "📤  Share", "✓  Done"},
                            new Runnable[]{
                                () -> tryInstallApkFromBytes(apkBytes, asset.name),
                                () -> shareFile(bytes, asset.name, asset.contentType),
                                null
                            });
                    } else if (isZipFile || isAarFile || isJarFile) {
                        showMenu("Downloaded: " + asset.name,
                            new String[]{"📤  Share File", "✓  Done"},
                            new Runnable[]{
                                () -> shareFile(bytes, asset.name, asset.contentType),
                                null
                            });
                    } else if (isTxtFile) {
                        final String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        showMenu("Downloaded: " + asset.name,
                            new String[]{"📋  View Contents", "📤  Share", "✓  Done"},
                            new Runnable[]{
                                () -> showAlert(asset.name, text.length() > 4000
                                    ? text.substring(0, 4000) + "\n\n[truncated...]" : text),
                                () -> shareFile(bytes, asset.name, "text/plain"),
                                null
                            });
                    } else {
                        showMenu("Downloaded: " + asset.name,
                            new String[]{"📤  Share File", "✓  Done"},
                            new Runnable[]{
                                () -> shareFile(bytes, asset.name, asset.contentType),
                                null
                            });
                    }
                }));
        });
        row.addView(dlBtn);

        // Delete asset button
        TextView assetDelBtn = iconBtn("", ThemeManager.DANGER, null);
        assetDelBtn.setTextSize(11f);
        LinearLayout.LayoutParams adbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        adbLp.leftMargin = dp(6); assetDelBtn.setLayoutParams(adbLp);
        assetDelBtn.setOnClickListener(v -> showConfirm("Delete " + asset.name + "?",
            "This asset will be permanently removed from the release.",
            "Delete", ThemeManager.DANGER, () ->
                GHApi.deleteReleaseAsset(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, asset.id,
                    (ok, err2) -> mHandler.post(() -> {
                        if (ok) { toast("Asset deleted"); while(mNavStack.size()>1)mNavStack.pop(); navigateTo(() -> setContentView(buildRepoPageAtTab(5))); }
                        else showErr("Delete failed:\n" + err2);
                    }))));
        row.addView(assetDelBtn);
        return row;
    }

    /** Install APK directly from byte array. */
    private void tryInstallApkFromBytes(byte[] apkBytes, String apkName) {
        try {
            java.io.File tmp = new java.io.File(getCacheDir(), apkName);
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(apkBytes); fos.close();
            android.net.Uri uri;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".provider", tmp);
            } else {
                uri = android.net.Uri.fromFile(tmp);
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (Exception e) {
            showErr("Install failed:\n" + e.getMessage());
        }
    }

    /** Create release from latest successful build artifact. */
    private void showCreateReleaseFromArtifact() {
        // First check if there are recent builds
        android.app.Dialog prog = makeProgressDialog();
        ((TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0))
            .setText("Finding latest build...");
        prog.show();

        GHApi.listRuns(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, (runs, err) -> mHandler.post(() -> {
            prog.dismiss();
            if (err != null || runs == null || runs.isEmpty()) {
                showPublishReleaseDialogStandalone(null, null);
                return;
            }
            GHApi.WorkflowRun lastSuccess = null;
            for (GHApi.WorkflowRun r : runs) {
                if ("success".equals(r.conclusion)) { lastSuccess = r; break; }
            }
            if (lastSuccess == null) {
                showPublishReleaseDialogStandalone(null, null);
                return;
            }
            final GHApi.WorkflowRun run = lastSuccess;
            GHApi.listArtifacts(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, run.id,
                (artifacts, err2) -> mHandler.post(() -> {
                    GHApi.Artifact best = null;
                    if (artifacts != null) {
                        for (GHApi.Artifact a : artifacts) {
                            if (!a.expired && (a.name.toLowerCase().contains("signed") || a.name.toLowerCase().contains("release") || a.name.toLowerCase().contains("apk")))
                                { best = a; break; }
                        }
                        if (best == null) for (GHApi.Artifact a : artifacts) { if (!a.expired) { best = a; break; } }
                    }
                    showPublishReleaseDialogStandalone(run, best);
                }));
        }));
    }

    private void showPublishReleaseDialogStandalone(final GHApi.WorkflowRun run, final GHApi.Artifact artifact) {
        // ── Full-page New Release (not a dialog) ─────────────────────────────
        LinearLayout outer = lv(); outer.setBackgroundColor(ThemeManager.BG);
        outer.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        // Header
        String sub = (artifact != null && run != null)
            ? artifact.name + "  \u00b7  build #" + run.id : "no artifact found";
        LinearLayout header = buildHeader2("New Release", sub, null);
        try {
            LinearLayout hr = (LinearLayout) header.getChildAt(0);
            if (hr != null && hr.getChildCount() > 0)
                hr.getChildAt(0).setOnClickListener(bv -> onBackPressed());
        } catch (Exception ignored2) {}
        outer.addView(header);

        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(ThemeManager.BG);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.setWeightSum(1f);
        LinearLayout c = lv(); c.setPadding(dp(16), dp(14), dp(16), dp(32));
        sv.addView(c);
        outer.addView(sv);

        // ── Source card ───────────────────────────────────────────────────────
        if (artifact != null && run != null) {
            LinearLayout srcCard = lh(Gravity.CENTER_VERTICAL);
            srcCard.setBackground(rbs(ThemeManager.BRAND_D, 12, ThemeManager.BRAND));
            srcCard.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams scLp = mpWrap();
            ((LinearLayout.LayoutParams) scLp).bottomMargin = dp(16);
            srcCard.setLayoutParams(scLp);
            View dot = new View(this);
            dot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
            dot.setBackground(rb(ThemeManager.BRAND, 4));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotLp.rightMargin = dp(12); dot.setLayoutParams(dotLp);
            srcCard.addView(dot);
            LinearLayout srcInfo = lv();
            srcInfo.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            srcInfo.addView(tv("Build artifact", ThemeManager.BRAND, 10f, true));
            String apkLabel = artifact.name + "  \u2022  " + fmtSize(artifact.sizeInBytes);
            srcInfo.addView(tv(apkLabel, ThemeManager.TEXT2, 11f, false));
            srcCard.addView(srcInfo);
            c.addView(srcCard);
        } else {
            LinearLayout noSrc = lh(Gravity.CENTER_VERTICAL);
            noSrc.setBackground(rbs(ThemeManager.AMBER_D, 12, ThemeManager.AMBER));
            noSrc.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams nsLp = mpWrap();
            ((LinearLayout.LayoutParams) nsLp).bottomMargin = dp(16);
            noSrc.setLayoutParams(nsLp);
            noSrc.addView(tv("No build artifact found \u2014 a tag-only release will be created.", ThemeManager.AMBER, 11f, false));
            c.addView(noSrc);
        }

        // ── Tag field ─────────────────────────────────────────────────────────
        // Tag row with version preset button
        LinearLayout tagHdr = lh(Gravity.CENTER_VERTICAL); tagHdr.setLayoutParams(mpWrap());
        TextView tagLbl = secLabel("Tag");
        tagLbl.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tagHdr.addView(tagLbl);
        TextView tagPresetBtn = roundBtn("Version presets", ThemeManager.DIM, ThemeManager.SURFACE2);
        tagHdr.addView(tagPresetBtn);
        c.addView(tagHdr);
        c.addView(sp(4));
        final EditText tagEt = styledInput("v1.0.0", false);
        tagEt.setTypeface(android.graphics.Typeface.MONOSPACE);
        tagEt.setTextColor(ThemeManager.BRAND);
        tagEt.setTextSize(15f);
        LinearLayout.LayoutParams tagLp = mpWrap();
        ((LinearLayout.LayoutParams) tagLp).bottomMargin = dp(14);
        tagEt.setLayoutParams(tagLp);
        c.addView(tagEt);

        // ── Title field ───────────────────────────────────────────────────────
        c.addView(secLabel("Release title"));
        c.addView(sp(4));
        final EditText titleEt = styledInput("Version 1.0.0", false);
        LinearLayout.LayoutParams titleLp = mpWrap();
        ((LinearLayout.LayoutParams) titleLp).bottomMargin = dp(14);
        titleEt.setLayoutParams(titleLp);
        c.addView(titleEt);

        // Version preset logic (shared by tag + notes presets)
        // Get current app version from build config if possible, else use incremented defaults
        final String[] versionPresets = {"v1.0.0", "v1.1.0", "v1.2.0", "v2.0.0", "v1.0.1", "v1.0.2", "v1.1.1"};
        final String[] versionLabels  = {"v1.0.0  — Initial", "v1.1.0  — Minor update", "v1.2.0  — Feature update", "v2.0.0  — Major release", "v1.0.1  — Patch", "v1.0.2  — Patch 2", "v1.1.1  — Hotfix"};
        Runnable[] versionActions = new Runnable[versionPresets.length];
        for (int vi = 0; vi < versionPresets.length; vi++) {
            final String tag2 = versionPresets[vi];
            final String ver  = tag2.startsWith("v") ? tag2.substring(1) : tag2;
            versionActions[vi] = () -> {
                tagEt.setText(tag2);
                titleEt.setText("Version " + ver);
            };
        }
        tagPresetBtn.setOnClickListener(v -> showMenu("Choose version", versionLabels, versionActions));

        // ── Release notes field ───────────────────────────────────────────────
        LinearLayout notesHeader = lh(Gravity.CENTER_VERTICAL);
        notesHeader.setLayoutParams(mpWrap());
        TextView notesLabel = secLabel("Release notes");
        notesLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        notesHeader.addView(notesLabel);
        // Preset button
        TextView presetBtn = roundBtn("Presets", ThemeManager.DIM, ThemeManager.SURFACE2);
        notesHeader.addView(presetBtn);
        c.addView(notesHeader);
        c.addView(sp(4));

        final EditText notesEt = new EditText(this);
        notesEt.setHint("Describe what changed in this release...");
        notesEt.setHintTextColor(ThemeManager.DIM);
        notesEt.setTextColor(ThemeManager.TEXT);
        notesEt.setTextSize(12.5f);
        notesEt.setBackground(rbs(ThemeManager.SURFACE2, 10, ThemeManager.BORDER));
        notesEt.setPadding(dp(14), dp(12), dp(14), dp(12));
        notesEt.setMinLines(4); notesEt.setMaxLines(10); notesEt.setGravity(Gravity.TOP);
        notesEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams notesLp = mpWrap();
        ((LinearLayout.LayoutParams) notesLp).bottomMargin = dp(14);
        notesEt.setLayoutParams(notesLp);
        c.addView(notesEt);

        // Preset templates
        presetBtn.setOnClickListener(v -> {
            String[] presetNames = {
                "Bug Fixes",
                "New Features",
                "Performance Update",
                "Initial Release",
                "Maintenance Release"
            };
            String[] presetBodies = {
                "## Bug Fixes\n- Fixed crash on startup\n- Resolved layout issues on small screens\n- Improved stability",
                "## What's New\n- Added [feature name]\n- Improved [area]\n- New [functionality]\n\n## Bug Fixes\n- Fixed [issue]",
                "## Performance Improvements\n- Faster app startup\n- Reduced memory usage\n- Smoother animations\n\n## Bug Fixes\n- Minor stability fixes",
                "## Initial Release\n\nFirst public release of " + mCurrentRepo + ".\n\n## Features\n- [Feature 1]\n- [Feature 2]\n- [Feature 3]",
                "## Maintenance\n- Updated dependencies\n- Code cleanup\n- Minor fixes and improvements"
            };
            Runnable[] actions = new Runnable[presetNames.length];
            for (int i = 0; i < presetNames.length; i++) {
                final String body = presetBodies[i];
                actions[i] = () -> {
                    String cur = notesEt.getText().toString();
                    if (cur.isEmpty()) {
                        notesEt.setText(body);
                    } else {
                        notesEt.setText(cur + "\n\n" + body);
                    }
                    notesEt.setSelection(notesEt.getText().length());
                };
            }
            showMenu("Choose a template", presetNames, actions);
        });

        // Auto-fill tag → title sync
        tagEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c2, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c2) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                String tag = e.toString().trim();
                if (titleEt.getText().toString().isEmpty() || titleEt.getText().toString().startsWith("Version ")) {
                    String clean = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                    if (!clean.isEmpty()) titleEt.setText("Version " + clean);
                }
            }
        });

        // ── Pre-release toggle ────────────────────────────────────────────────
        final boolean[] isPre = {false};
        LinearLayout toggleRow = lh(Gravity.CENTER_VERTICAL);
        toggleRow.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        toggleRow.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams trLp = mpWrap();
        ((LinearLayout.LayoutParams) trLp).bottomMargin = dp(20);
        toggleRow.setLayoutParams(trLp);
        toggleRow.setClickable(true);
        LinearLayout toggleInfo = lv();
        toggleInfo.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        toggleInfo.addView(tv("Mark as pre-release", ThemeManager.TEXT, 13f, false));
        toggleInfo.addView(tv("Not ready for production", ThemeManager.DIM, 10f, false));
        toggleRow.addView(toggleInfo);
        // Toggle pill
        final LinearLayout togglePill = new LinearLayout(this);
        togglePill.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(26)));
        togglePill.setBackground(rb(ThemeManager.SURFACE2, 13));
        togglePill.setGravity(Gravity.CENTER_VERTICAL);
        togglePill.setPadding(dp(3), 0, dp(3), 0);
        final View toggleThumb = new View(this);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        toggleThumb.setLayoutParams(thumbLp);
        toggleThumb.setBackground(rb(ThemeManager.DIM, 10));
        togglePill.addView(toggleThumb);
        toggleRow.addView(togglePill);
        toggleRow.setOnClickListener(v -> {
            isPre[0] = !isPre[0];
            if (isPre[0]) {
                togglePill.setBackground(rb(ThemeManager.AMBER_D, 13));
                toggleThumb.setBackground(rb(ThemeManager.AMBER, 10));
                LinearLayout.LayoutParams lp2 = (LinearLayout.LayoutParams) toggleThumb.getLayoutParams();
                lp2.leftMargin = dp(18); toggleThumb.setLayoutParams(lp2);
                ((TextView) toggleInfo.getChildAt(0)).setTextColor(ThemeManager.AMBER);
            } else {
                togglePill.setBackground(rb(ThemeManager.SURFACE2, 13));
                toggleThumb.setBackground(rb(ThemeManager.DIM, 10));
                LinearLayout.LayoutParams lp2 = (LinearLayout.LayoutParams) toggleThumb.getLayoutParams();
                lp2.leftMargin = 0; toggleThumb.setLayoutParams(lp2);
                ((TextView) toggleInfo.getChildAt(0)).setTextColor(ThemeManager.TEXT);
            }
        });
        c.addView(toggleRow);

        // ── Action buttons ────────────────────────────────────────────────────
        LinearLayout btnRow = lh(Gravity.CENTER_VERTICAL);
        btnRow.setLayoutParams(mpWrap());
        TextView cancelBtn = roundBtn("Cancel", ThemeManager.DIM, ThemeManager.SURFACE2);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        cbLp.rightMargin = dp(10); cancelBtn.setLayoutParams(cbLp);
        cancelBtn.setPadding(dp(20), 0, dp(20), 0);
        cancelBtn.setOnClickListener(v -> onBackPressed());

        final TextView publishBtn = tv("Publish Release", ThemeManager.TEXT, 13f, true);
        publishBtn.setBackground(rb(ThemeManager.BRAND, 12));
        publishBtn.setGravity(Gravity.CENTER);
        publishBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        publishBtn.setLetterSpacing(0.02f);

        publishBtn.setOnClickListener(v -> {
            String tag   = tagEt.getText().toString().trim();
            String title = titleEt.getText().toString().trim();
            String notes = notesEt.getText().toString().trim();
            if (tag.isEmpty()) { toast("Tag is required (e.g. v1.0.0)"); return; }

            // ── Progress dialog with Hide button ─────────────────────────────
            android.app.Dialog prog = makeProgressDialog();
            final TextView progTv = (TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0);
            progTv.setText("Creating release " + tag + "...");

            // Hide button — lets user navigate away while publish continues in background
            TextView hideBtn = roundBtn("Hide (run in background)", ThemeManager.DIM, ThemeManager.SURFACE2);
            LinearLayout.LayoutParams hideLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hideLp.topMargin = dp(12);
            hideBtn.setLayoutParams(hideLp);
            hideBtn.setOnClickListener(hv -> {
                prog.dismiss();
                showNotif(NOTIF_MANAGE, "Publishing Release", tag + " — uploading APK...", -1, 0);
            });
            ((LinearLayout)prog.findViewById(android.R.id.message)).addView(hideBtn);
            prog.show();

            // Helper: update progress text regardless of dialog state
            StringAction updateProg = msg -> mHandler.post(() -> {
                if (prog.isShowing()) progTv.setText(msg);
                else showNotif(NOTIF_MANAGE, "Publishing Release", msg, -1, 0);
            });

            // Helper: finish — cancel notif, navigate to Release tab
            Runnable onDone = () -> mHandler.post(() -> {
                if (prog.isShowing()) prog.dismiss();
                cancelNotif(NOTIF_MANAGE);
                while (mNavStack.size() > 1) mNavStack.pop();
                navigateTo(() -> setContentView(buildRepoPageAtTab(5)));
            });

            // Helper: fatal error
            StringAction onErr = msg -> mHandler.post(() -> {
                if (prog.isShowing()) prog.dismiss();
                cancelNotif(NOTIF_MANAGE);
                showNotifResult("\u2717 Publish Failed", tag + " \u2014 " + msg);
                showErr("Publish failed:\n" + msg);
            });

            GHApi.createRelease(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                tag, title.isEmpty() ? tag : title, notes, isPre[0],
                (release, relErr) -> {
                    if (relErr != null) { onErr.run(relErr); return; }
                    if (artifact == null) {
                        showNotifResult("\u2713 Release Published", tag + " created");
                        onDone.run();
                        return;
                    }
                    updateProg.run("Checking artifact cache...");
                    AppExecutors.getInstance().diskIO().execute(() -> {
                        // Check internal cache first — universal (bukan hanya APK)
                        byte[] cachedAsset = null; String cachedName = null;
                        try {
                            java.io.File cDir2 = new java.io.File(getCacheDir(), "artifacts");
                            if (!cDir2.exists()) cDir2 = new java.io.File(getCacheDir(), "apk"); // backward compat
                            if (cDir2.exists()) {
                                java.io.File[] files = cDir2.listFiles();
                                if (files != null && files.length > 0) {
                                    java.util.Arrays.sort(files, (a3,b3)->Long.compare(b3.lastModified(),a3.lastModified()));
                                    String[] pref = {".apk",".aab",".jar",".whl",".exe",".bin"};
                                    java.io.File best2 = files[0];
                                    outer2:
                                    for (String ext : pref) {
                                        for (java.io.File f3 : files) {
                                            if (f3.getName().toLowerCase().endsWith(ext)) { best2=f3; break outer2; }
                                        }
                                    }
                                    java.io.FileInputStream fis = new java.io.FileInputStream(best2);
                                    cachedAsset = GHApi.readStreamBytes(fis); fis.close();
                                    cachedName = best2.getName();
                                }
                            }
                        } catch (Exception ce) { android.util.Log.w("GitDeploy","cache: "+ce.getMessage()); }

                        if (cachedAsset != null) {
                            final byte[] fa2 = cachedAsset; final String fn2 = cachedName;
                            updateProg.run("Uploading " + fn2 + " (from cache)...");
                            GHApi.uploadReleaseAsset(mPrefs.getToken(), release.uploadUrl, fn2, fa2,
                                (url2, upErr2) -> {
                                    if (upErr2 != null) { showNotifResult("\u2713 Release Created", tag+" \u2014 asset upload failed"); mHandler.post(()->showErr("Upload failed:\n"+upErr2)); onDone.run(); }
                                    else { showNotifResult("\u2713 Release Published", tag+" with "+fn2); onDone.run(); }
                                });
                        } else {
                            // No cache — download artifact first
                            updateProg.run("Downloading artifact (" + fmtSize(artifact.sizeInBytes) + ")...");
                            GHApi.downloadArtifact(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                                artifact.id, null,
                                (zipBytes, dlErr) -> {
                                    if (dlErr != null) { showNotifResult("\u2717 Download Failed", tag); onErr.run("Artifact download failed: "+dlErr); return; }
                                    updateProg.run("Extracting asset from artifact...");
                                    AppExecutors.getInstance().diskIO().execute(() -> {
                                        try {
                                            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes));
                                            java.util.zip.ZipEntry ze;
                                            java.util.Map<String,byte[]> foundFiles = new java.util.LinkedHashMap<>();
                                            while ((ze = zis.getNextEntry()) != null) {
                                                if (!ze.isDirectory()) foundFiles.put(ze.getName(), GHApi.readStreamBytes(zis));
                                                zis.closeEntry();
                                            }
                                            zis.close();
                                            byte[] assetBytes = null; String assetName = null;
                                            String[] prefExt = {".apk",".aab",".jar",".whl",".exe",".bin",".ipa"};
                                            outer3:
                                            for (String ext : prefExt) {
                                                for (java.util.Map.Entry<String,byte[]> fe : foundFiles.entrySet()) {
                                                    if (fe.getKey().toLowerCase().endsWith(ext)) {
                                                        assetBytes = fe.getValue();
                                                        assetName  = new java.io.File(fe.getKey()).getName();
                                                        break outer3;
                                                    }
                                                }
                                            }
                                            if (assetBytes == null && !foundFiles.isEmpty()) {
                                                java.util.Map.Entry<String,byte[]> first = foundFiles.entrySet().iterator().next();
                                                assetBytes = first.getValue(); assetName = new java.io.File(first.getKey()).getName();
                                            }
                                            if (assetBytes == null) { assetBytes = zipBytes; assetName = artifact.name + ".zip"; }
                                            // Save to cache
                                            try {
                                                java.io.File cDir3=new java.io.File(getCacheDir(),"artifacts"); cDir3.mkdirs();
                                                java.io.File[] oldC=cDir3.listFiles(); if(oldC!=null) for(java.io.File od:oldC) od.delete();
                                                new java.io.FileOutputStream(new java.io.File(cDir3,assetName)).write(assetBytes);
                                            } catch (Exception se) { android.util.Log.w("GitDeploy","cache save: "+se.getMessage()); }
                                            final byte[] fa=assetBytes; final String fn=assetName;
                                            updateProg.run("Uploading " + fn + " to release...");
                                            GHApi.uploadReleaseAsset(mPrefs.getToken(), release.uploadUrl, fn, fa,
                                                (url3, upErr3) -> {
                                                    if (upErr3 != null) { showNotifResult("\u2713 Release Created", tag+" \u2014 asset upload failed"); mHandler.post(()->showErr("Upload failed:\n"+upErr3)); onDone.run(); }
                                                    else { showNotifResult("\u2713 Release Published", tag+" with "+fn); onDone.run(); }
                                                });
                                        } catch (Exception e2) { onErr.run(e2.getMessage()); }
                                    });
                                });
                        }
                    });
                });
        });

        btnRow.addView(cancelBtn);
        btnRow.addView(publishBtn);
        c.addView(btnRow);

        navigateTo(() -> setContentView(outer));
    }



    private CharSequence buildStepsLog(String log) {
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder();
        String[] lines = log.split("\n");
        String currentStep = null;
        long stepStartMs = -1;
        StringBuilder stepOutput = new StringBuilder();
        java.util.List<String[]> steps = new java.util.ArrayList<>();
        for (String rawLine : lines) {
            String ts   = extractTimestamp(rawLine);
            String line = stripGhTimestamp(rawLine);
            String t    = line.trim();
            if (t.startsWith("##[group]")) {
                if (currentStep != null)
                    steps.add(new String[]{currentStep, formatStepDuration(stepStartMs, ts), stepOutput.toString().trim(), "ok"});
                currentStep = t.substring("##[group]".length()).trim();
                stepStartMs = parseTimestampMs(ts);
                stepOutput  = new StringBuilder();
            } else if (t.startsWith("##[endgroup]")) {
                if (currentStep != null) {
                    // Cek [ERR] (dari ##[error]) DAN kata "error" / "failed" / "exit code"
                    String stepOut = stepOutput.toString();
                    String stepLow = stepOut.toLowerCase();
                    boolean stepFailed = stepLow.contains("[err]") || stepLow.contains("error")
                        || stepLow.contains("failed") || stepLow.contains("exit code 1")
                        || stepLow.contains("process completed with exit code");
                    String st = stepFailed ? "err" : "ok";
                    steps.add(new String[]{currentStep, formatStepDuration(stepStartMs, ts), stepOut.trim(), st});
                    currentStep = null; stepOutput = new StringBuilder();
                }
            } else if (t.startsWith("##[error]"))   { stepOutput.append("[ERR] ").append(t.substring(9)).append("\n"); }
            else if (t.startsWith("##[warning]")) { stepOutput.append("[WARN] ").append(t.substring(11)).append("\n"); }
            else if (!t.startsWith("##[") && !t.isEmpty()) { stepOutput.append(line).append("\n"); }
        }
        if (currentStep != null)
            steps.add(new String[]{currentStep, formatStepDuration(stepStartMs, null), stepOutput.toString().trim(), "ok"});
        if (steps.isEmpty()) { ssb.append("No step groups found.\nSwitch to All Logs to see raw output."); return ssb; }
        for (String[] step : steps) {
            boolean isErr = "err".equals(step[3]);
            int hCol=isErr?0xFFFF6666:0xFF44FF88, bgCol=isErr?0xFF120404:0xFF040C06;
            ssb.append("\n");
            String header=(isErr?"[FAIL]  ":"[ OK ]  ")+step[0]+(step[1].isEmpty()?"":" ("+step[1]+")");
            int hStart=ssb.length(); ssb.append(header).append("\n");
            ssb.setSpan(new android.text.style.ForegroundColorSpan(hCol),hStart,ssb.length(),33);
            ssb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),hStart,ssb.length(),33);
            ssb.setSpan(new android.text.style.BackgroundColorSpan(bgCol),hStart,ssb.length(),33);
            if (!step[2].isEmpty()) {
                for (String ol : step[2].split("\n")) {
                    if (ol.trim().isEmpty()) continue;
                    String lo=ol.toLowerCase();
                    boolean isEL=lo.contains("error")||ol.startsWith("[ERR");
                    boolean isWL=lo.contains("warn")||ol.startsWith("[WARN");
                    if (!isEL&&!isWL) continue;
                    int oStart=ssb.length(); ssb.append("  ").append(ol.trim()).append("\n");
                    ssb.setSpan(new android.text.style.ForegroundColorSpan(isEL?0xFFFF8888:0xFFFFCC44),oStart,ssb.length(),33);
                }
            }
        }
        return ssb;
    }

    private String extractTimestamp(String line) {
        if (line.length()>20&&line.charAt(4)=='-'&&line.charAt(10)=='T') {
            int z=line.indexOf('Z'); if(z>10&&z<35) return line.substring(0,z+1);
        }
        return "";
    }

    private long parseTimestampMs(String ts) {
        if (ts==null||ts.isEmpty()) return -1;
        try {
            java.text.SimpleDateFormat sdf=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return sdf.parse(ts.substring(0,Math.min(ts.length(),19))).getTime();
        } catch(Exception e){return -1;}
    }

    private String formatStepDuration(long startMs, String endTs) {
        if (startMs<0) return "";
        long endMs=parseTimestampMs(endTs);
        if (endMs<=startMs) return "";
        long sec=(endMs-startMs)/1000;
        return sec<60?sec+"s":(sec/60)+"m "+(sec%60)+"s";
    }

    private void handleFileSelected(Uri uri) {
        android.app.Dialog prog = makeProgressDialog();
        final TextView progTv = (TextView) ((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0);
        prog.show();

        AppExecutors.getInstance().diskIO().execute(() -> {  // #3 disk I/O
            try {
                ContentResolver cr = getContentResolver();
                InputStream is = cr.openInputStream(uri);
                if (is == null) { mHandler.post(() -> { prog.dismiss(); showErr("Cannot read file"); }); return; }

                String filename = getFilenameFromUri(cr, uri);
                byte[] bytes = GHApi.readStreamBytes(is); is.close();

                if (mPendingRawZipUpload) {
                    // Upload ZIP file as-is, no extraction
                    mPendingRawZipUpload = false;
                    final String basePath = mPendingUploadBasePath.isEmpty() ? "" : mPendingUploadBasePath + "/";
                    final String repoPath = basePath + filename;
                    final byte[] zipBytesRef = bytes; // simpan ref untuk YAML extraction
                    mHandler.post(() -> progTv.setText("Uploading " + filename + " as file..."));
                    GHApi.getFileContent(mPrefs.getToken(), mCurrentOwner, mPendingUploadRepo,
                        repoPath, (existing, ignored2) -> {
                            String sha = (existing != null) ? existing[1] : null;
                            GHApi.uploadFile(mPrefs.getToken(), mCurrentOwner, mPendingUploadRepo,
                                repoPath, bytes, mPendingUploadMsg, sha, mPendingUploadBranch,
                                (ok, err) -> mHandler.post(() -> {
                                    prog.dismiss();
                                    mCachedFiles.evictAll();
                                    if (ok) {
                                        // ── ZIP Deploy: tawarkan auto-setup CI/CD ──────────
                                        final String finalRepoPath = repoPath;
                                        final String finalBranch   = mPendingUploadBranch;
                                        final String finalRepo     = mPendingUploadRepo;
                                        showConfirm(
                                            "📦 ZIP Deploy — Setup CI/CD?",
                                            filename + " berhasil diupload ke repo.\n\n" +
                                            "Mau otomatis setup build.yml?\n" +
                                            "App akan extract YAML dari ZIP (jika ada) " +
                                            "atau buat baru dengan step unzip otomatis.",
                                            "Setup CI/CD", ThemeManager.BRAND,
                                            () -> {
                                                showStatusBar("⚙  Setting up CI/CD...", ThemeManager.AMBER);
                                                GHApi.setupYamlForZipDeploy(
                                                    mPrefs.getToken(), mCurrentOwner,
                                                    finalRepo, finalBranch,
                                                    finalRepoPath, zipBytesRef,
                                                    (patchOk, patchErr) -> mHandler.post(() -> {
                                                        hideStatusBar(0);
                                                        if (Boolean.TRUE.equals(patchOk)) {
                                                            toast("✓ build.yml siap — workflow akan extract ZIP saat build");
                                                        } else {
                                                            showErr("Gagal setup build.yml:\n" + patchErr);
                                                        }
                                                        navigateTo(() -> setContentView(buildRepoPageAtTab(0)));
                                                    }));
                                            });
                                    } else showErr("Upload failed:\n\n" + err);
                                }));
                        });
                } else if (mPendingUploadZipMode) {
                    mHandler.post(() -> progTv.setText("Reading ZIP..."));
                    handleZipExtractAndPush(bytes, prog, progTv);
                } else {
                    final String repoPath = mPendingUploadPath.isEmpty() ? filename : mPendingUploadPath;
                    mHandler.post(() -> progTv.setText("Uploading " + filename + "\n→ " + repoPath));

                    String sha = getExistingSha(repoPath);
                    GHApi.uploadFile(mPrefs.getToken(), mCurrentOwner, mPendingUploadRepo,
                        repoPath, bytes, mPendingUploadMsg, sha, mPendingUploadBranch,
                        (ok, err) -> mHandler.post(() -> {
                            prog.dismiss();
                            if (ok) { showInfo("✓ Upload Successful", repoPath); navigateTo(() -> setContentView(buildRepoPage())); }
                            else showErr("Upload failed:\n\n" + err);
                        }));
                }
            } catch (Exception e) {
                mHandler.post(() -> { prog.dismiss(); showErr("Error: " + e.getMessage()); });
            }
        });
    }

    /**
     * IMPROVEMENT #3 — FOREGROUND SERVICE INTEGRATION
     *
     * Old approach: raw Thread inside Activity.  If the user minimised the app
     * while 200 files were uploading, Android 8+ killed the thread mid-way,
     * leaving the repo in a partially-uploaded, broken state.
     *
     * New approach: delegate the entire upload to UploadForegroundService which
     * holds a persistent "Uploading…" notification that keeps the process alive
     * even when the app goes to the background for 10+ minutes.
     *
     * MainActivity registers a ProgressListener before launching the service and
     * clears it in onPause, so UI updates only happen while the Activity is visible.
     */
    private void handleZipExtractAndPush(byte[] zipBytes, android.app.Dialog prog, TextView progTv) {
        // Dismiss the small "reading ZIP" progress dialog — status bar takes over
        mHandler.post(prog::dismiss);
        showStatusBar("⬆  Handing off to background service...", ThemeManager.AMBER);

        // Register listener to receive progress callbacks from the service
        UploadForegroundService.sListener = new UploadForegroundService.ProgressListener() {
            @Override
            public void onProgress(int current, int total, String currentFile) {
                int pct = (int)(current * 100.0 / total);
                updateStatusBar("⬆  " + current + " / " + total
                    + "  (" + pct + "%)  — " + new java.io.File(currentFile).getName());
            }
            @Override
            public void onComplete(int ok, int fail, java.util.List<String> failedPaths) {
                cancelNotif(NOTIF_UPLOAD);
                String baseLbl = mPendingUploadBasePath.isEmpty()
                    ? "(root)" : mPendingUploadBasePath;
                if (fail == 0) {
                    mCachedFiles.evictAll();
                    hideStatusBar(0);
                    showNotifResult("✓ Upload Complete", ok + " file(s) → " + mPendingUploadRepo);
                    showConfirm("Upload Complete", ok + " file(s) pushed to " + mPendingUploadRepo
                            + "\nBase path: " + baseLbl,
                        "View Files", ThemeManager.BRAND,
                        () -> navigateTo(() -> setContentView(buildRepoPageAtTab(0))));
                } else {
                    hideStatusBar(0);
                    showNotifResult("⚠ Partial Upload", ok + " ok · " + fail + " failed");
                    StringBuilder sb = new StringBuilder();
                    sb.append(ok).append(" file(s) succeeded.\n")
                      .append(fail).append(" file(s) FAILED:\n");
                    for (String p : failedPaths) sb.append("  • ").append(p).append("\n");
                    sb.append("\nBase path: ").append(baseLbl);
                    showInfo("Partial Upload", sb.toString());
                    if (ok > 0) navigateTo(() -> setContentView(buildRepoPageAtTab(0)));
                }
                UploadForegroundService.sListener = null;
            }
            @Override
            public void onError(String message) {
                hideStatusBar(0);
                showErr("Upload error:\n" + message);
                UploadForegroundService.sListener = null;
            }
        };

        // Launch Foreground Service
        android.content.Intent svcIntent = UploadForegroundService.buildZipUploadIntent(
            this,
            mPrefs.getToken(),
            mCurrentOwner,
            mPendingUploadRepo,
            mPendingUploadBranch,
            mPendingUploadMsg,
            mPendingUploadBasePath,
            zipBytes
        );
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }
    }

    private String getExistingSha(String repoPath) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL("https://api.github.com/repos/"
                    + mCurrentOwner + "/" + mPendingUploadRepo
                    + "/contents/" + repoPath).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "token " + mPrefs.getToken());
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
            if (conn.getResponseCode() == 200) {
                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                return new org.json.JSONObject(sb.toString()).optString("sha", null);
            }
        } catch (Exception e) { android.util.Log.w("GitDeploy", "ex: " + e.getMessage()); }
        return null;
    }

    private String getFilenameFromUri(ContentResolver cr, Uri uri) {
        try {
            android.database.Cursor cursor = cr.query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) { String n = cursor.getString(idx); cursor.close(); return n; }
                cursor.close();
            }
        } catch (Exception e) { android.util.Log.w("GitDeploy", "ex: " + e.getMessage()); }
        return "upload";
    }

    // =========================================================================
    // LOG PAGE — 3-mode filter: Root Cause / Errors+Warnings / All
    // =========================================================================
    private View buildLogPage(final GHApi.WorkflowRun run) {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);
        outer.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        // Header
        String statusLabel = "completed".equals(run.status)
            ? run.conclusion.toUpperCase() : run.status.toUpperCase();
        LinearLayout header = buildHeader2("Build Logs", run.headBranch + " · " + statusLabel, null);
        // Override back button: always return to Builds tab
        try {
            LinearLayout logRow = (LinearLayout) header.getChildAt(0);
            if (logRow != null && logRow.getChildCount() > 0)
                logRow.getChildAt(0).setOnClickListener(bv -> {
                    while (mNavStack.size() > 1) mNavStack.pop();
                    navigateTo(() -> setContentView(buildRepoPageAtTab(1)));
                });
        } catch (Exception ignored2) {}
        LinearLayout ha = (LinearLayout) header.getTag();

        // 3-mode cycle button: 0=ROOT_CAUSE, 1=ERRORS, 2=ALL
        final int[] logMode = {0}; // default = root cause
        final TextView filterBtn = iconBtn("🎯", ThemeManager.RED, null);
        final TextView copyBtn   = iconBtn("📋", ThemeManager.TEXT2, null);
        final TextView trackBtn  = iconBtn("📁", ThemeManager.DIM, null); // shown only when errors exist
        trackBtn.setVisibility(View.GONE);
        ha.addView(trackBtn);
        ha.addView(filterBtn);
        ha.addView(copyBtn);
        outer.addView(header);

        // Mode label bar
        final TextView modeLbl = tv("", ThemeManager.AMBER, 9.5f, true);
        modeLbl.setPadding(dp(14), dp(7), dp(14), dp(7));
        modeLbl.setLetterSpacing(0.03f);
        outer.addView(modeLbl);

        // Log TextView
        final TextView logTv = new TextView(this);
        logTv.setTextSize(10.5f);
        logTv.setTypeface(Typeface.MONOSPACE);
        logTv.setPadding(dp(12), dp(12), dp(12), dp(40));
        logTv.setTextIsSelectable(true);
        logTv.setTextColor(0xFF44FF88);

        final ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF050507);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.setWeightSum(1f);
        sv.addView(logTv);
        outer.addView(sv);

        final String[] rawLog = {""};

        // Helper to apply current mode
        Runnable applyMode = () -> {
            if (rawLog[0].isEmpty()) return;
            switch (logMode[0]) {
                case 0: // Root Cause Only
                    filterBtn.setText("🎯"); filterBtn.setTextColor(ThemeManager.RED);
                    filterBtn.setBackground(rbs(ThemeManager.RED_D, 10, ThemeManager.RED));
                    modeLbl.setBackgroundColor(0xFF0F0507);
                    modeLbl.setTextColor(ThemeManager.RED);
                    modeLbl.setText("Root Cause  ·  tap to cycle");
                    String rootCause = extractRootCause(rawLog[0]);
                    boolean hasErrors = !rootCause.isEmpty();
                    trackBtn.setVisibility(hasErrors ? View.VISIBLE : View.GONE);
                    if (hasErrors) {
                        trackBtn.setTextColor(ThemeManager.AMBER);
                        trackBtn.setBackground(rbs(ThemeManager.AMBER_D, 10, ThemeManager.AMBER));
                    }
                    logTv.setText(hasErrors
                        ? buildColoredLog(rootCause, true)
                        : buildColoredLog("No errors found — build is clean.", false));
                    break;
                case 1: // Errors + Warnings
                    filterBtn.setText("⚠"); filterBtn.setTextColor(ThemeManager.AMBER);
                    filterBtn.setBackground(rbs(ThemeManager.AMBER_D, 10, ThemeManager.AMBER));
                    modeLbl.setBackgroundColor(0xFF100A00);
                    modeLbl.setTextColor(ThemeManager.AMBER);
                    modeLbl.setText("Errors & Warnings  ·  tap to cycle");
                    String errors = extractErrorLines(rawLog[0]);
                    logTv.setText(errors.isEmpty()
                        ? buildColoredLog("No errors or warnings found.", false)
                        : buildColoredLog(errors, true));
                    break;
                default: // All logs
                    filterBtn.setText("📄"); filterBtn.setTextColor(ThemeManager.GREEN);
                    filterBtn.setBackground(rbs(ThemeManager.GREEN_D, 10, ThemeManager.GREEN));
                    modeLbl.setBackgroundColor(0xFF030A05);
                    modeLbl.setTextColor(ThemeManager.GREEN);
                    modeLbl.setText("All Logs  ·  tap to cycle");
                    logTv.setText(buildColoredLog(rawLog[0], false));
                    break;
                case 3: // Steps view
                    filterBtn.setText("\u2630"); filterBtn.setTextColor(ThemeManager.CYAN);
                    filterBtn.setBackground(rbs(ThemeManager.CYAN_D, 10, ThemeManager.CYAN));
                    modeLbl.setBackgroundColor(0xFF020A10);
                    modeLbl.setTextColor(ThemeManager.CYAN);
                    modeLbl.setText("Steps View  \u00b7  tap to cycle");
                    logTv.setText(buildStepsLog(rawLog[0]));
                    break;
            }
            sv.post(() -> sv.scrollTo(0, 0));
        };

        filterBtn.setOnClickListener(v -> {
            logMode[0] = (logMode[0] + 1) % 4;
            applyMode.run();
        });

        trackBtn.setOnClickListener(v -> {
            // Parse error files, store in mTrackedErrors, navigate to Track tab
            String rootCause = extractRootCause(rawLog[0]);
            java.util.List<GHApi.ErrorFileRef> refs = GHApi.parseErrorFiles(rootCause);
            if (refs.isEmpty()) { toast("No specific error files found in root cause"); return; }
            mTrackedErrors = refs;
            mLastFailedRun = run;
            // Save workflow name for "Build Now" button
            mPrefs.saveLastFailedWorkflowName(run.name);
            trackBtn.setEnabled(false);
            trackBtn.setText("...");
            // Resolve paths via git tree, then navigate
            GHApi.resolveFilePaths(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                mCurrentBranch, refs, (resolved, err) -> mHandler.post(() -> {
                    mTrackedErrors = resolved;
                    // Pop log page, go to repo page at Track tab
                    while (mNavStack.size() > 1) mNavStack.pop();
                    navigateTo(() -> setContentView(buildRepoPageAtTab(3)));
                }));
        });

        copyBtn.setOnClickListener(v -> {
            if (rawLog[0].isEmpty()) { toast("No log to copy"); return; }
            String toCopy;
            switch (logMode[0]) {
                case 0: toCopy = extractRootCause(rawLog[0]); break;
                case 1: toCopy = extractErrorLines(rawLog[0]); break;
                default: toCopy = rawLog[0]; break;
            }
            if (toCopy.isEmpty()) toCopy = rawLog[0];
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", toCopy));
            String[] modeNames = {"Root cause", "Errors", "Full", "Steps"};
            toast("" + modeNames[Math.min(logMode[0], 3)] + " log copied to clipboard");
        });

        if ("in_progress".equals(run.status) || "queued".equals(run.status)
                || "waiting".equals(run.status)) {
            logTv.setTextColor(ThemeManager.CYAN);
            logTv.setText("⟳ Build is running...\n\n"
                + "Status  : " + run.status + "\n"
                + "Branch  : " + run.headBranch + "\n"
                + "Started : " + run.createdAt.replace("T"," ").replace("Z","") + "\n\n"
                + "Logs will be available once the build completes.\n"
                + "You\'ll receive a notification when done.");
            modeLbl.setBackgroundColor(ThemeManager.CYAN_D);
            modeLbl.setTextColor(ThemeManager.CYAN);
            modeLbl.setText("⟳  BUILD IN PROGRESS — auto-refresh every 15s");
        } else {
            modeLbl.setBackgroundColor(ThemeManager.SURFACE);
            modeLbl.setTextColor(ThemeManager.DIM);
            modeLbl.setText("  Fetching logs...");
            GHApi.getRunLogs(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, run.id,
                (logs, err) -> mHandler.post(() -> {
                    if (err != null) {
                        logTv.setTextColor(ThemeManager.RED);
                        logTv.setText("Failed to fetch logs:\n\n" + err
                            + "\n\nLogs are only available for 90 days after a build.");
                        modeLbl.setBackgroundColor(ThemeManager.RED_D);
                        modeLbl.setTextColor(ThemeManager.RED);
                        modeLbl.setText("⚠  FETCH ERROR");
                    } else if (logs == null || logs.trim().isEmpty()) {
                        logTv.setTextColor(ThemeManager.AMBER);
                        logTv.setText("Log is empty.");
                        modeLbl.setBackgroundColor(ThemeManager.AMBER_D);
                        modeLbl.setTextColor(ThemeManager.AMBER);
                        modeLbl.setText("  No log output");
                    } else {
                        // Cap at 300k chars to prevent TextView OOM on huge logs
                        String safeLogs = (logs.length() > 300000)
                            ? "⚠ Log truncated — showing last 300k chars\n\n"
                              + logs.substring(logs.length() - 300000)
                            : logs;
                        rawLog[0] = safeLogs;
                        String rootCause = extractRootCause(safeLogs);
                        if ("success".equals(run.conclusion) || "skipped".equals(run.conclusion)) {
                            logMode[0] = 3; // Steps view for successful builds
                        } else if (!rootCause.isEmpty()) {
                            logMode[0] = 0; // Root Cause saat ada error yang dikenali
                        } else {
                            // Build gagal tapi pattern tidak dikenali → Errors & Warnings
                            // Jangan pakai All Logs (hijau semua) untuk build gagal
                            logMode[0] = 1;
                        }
                        applyMode.run();
                    }
                }));
        }

        return outer;
    }

    /** Color-code log lines. errOnly = show only error lines. */
    private CharSequence buildColoredLog(String log, boolean errOnly) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        for (String rawLine : log.split("\n")) {
            String raw  = stripGhTimestamp(rawLine);
            // Penting: strip ##[error]/##[warning] PREFIX sebelum isNoiseLine
            // supaya line error tidak dibuang sebagai noise
            boolean isGhError   = raw.startsWith("##[error]");
            boolean isGhWarning = raw.startsWith("##[warning]");
            String line;
            if (isGhError)        line = raw.substring(9).trim();
            else if (isGhWarning) line = raw.substring(11).trim();
            else                  line = raw;
            if (isNoiseLine(line) || line.isEmpty()) continue;
            String lower = line.toLowerCase();
            boolean isErr  = isGhError  || isErrorLine(lower);
            boolean isWarn = isGhWarning || lower.contains("warning") || lower.contains("warn:");
            boolean isOk   = lower.contains("build successful") || lower.contains("up-to-date")
                          || lower.startsWith("> task");
            if (errOnly && !isErr && !isGhError) continue;
            int start = ssb.length();
            ssb.append(line).append("\n");
            int end = ssb.length();
            if (isErr)       ssb.setSpan(new ForegroundColorSpan(ThemeManager.RED),    start, end, 0);
            else if (isWarn) ssb.setSpan(new ForegroundColorSpan(ThemeManager.AMBER),  start, end, 0);
            else if (isOk)   ssb.setSpan(new ForegroundColorSpan(ThemeManager.GREEN),  start, end, 0);
            else             ssb.setSpan(new ForegroundColorSpan(0xFF44FF88),           start, end, 0);
        }
        return ssb;
    }

    /**
     * Extract ONLY lines that are root causes of the build failure:
     * Caused by, error:, cannot find symbol, duplicate class, etc.
     * Skips generic "FAILED" task lines and long stack traces (at com.xxx).
     * Returns a compact, readable error summary — usually 3-15 lines.
     */
    private String extractRootCause(String log) {
        StringBuilder sb = new StringBuilder();
        String[] lines = log.split("\n");
        boolean inWhatWentWrong = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = stripGhTimestamp(lines[i]);
            String line = raw.startsWith("##[error]") ? raw.substring(9).trim() : raw;
            if (isNoiseLine(line) || line.isEmpty()) continue;
            String lower = line.toLowerCase().trim();
            if (lower.equals("* what went wrong:")) {
                inWhatWentWrong = true; sb.append(line.trim()).append("\n"); continue;
            }
            if (inWhatWentWrong) {
                if (lower.startsWith("* try:") || lower.startsWith("* get more") || lower.equals("* exception is:"))
                    { inWhatWentWrong = false; continue; }
                if (!lower.startsWith("\tat ") && !lower.startsWith("at com.")
                        && !lower.startsWith("at org.") && !lower.startsWith("at java."))
                    sb.append(line.trim()).append("\n");
                continue;
            }
            boolean isRoot =
                // Gradle / Java / Android
                lower.startsWith("caused by:") || lower.startsWith("failure:")
                || lower.startsWith("error:")
                || (lower.contains("error:") && !lower.contains("note:"))
                || lower.contains("cannot find symbol") || lower.contains("incompatible types")
                || lower.contains("duplicate class") || lower.contains("could not resolve")
                || lower.contains("could not find method") || lower.contains("unresolved reference")
                || lower.contains("compilation failed") || lower.contains("build failed")
                || lower.contains("execution failed for task") || lower.contains("a problem occurred")
                || lower.contains("package does not exist")
                || (lower.contains("method") && lower.contains("not found"))
                || lower.contains("symbol:") || lower.contains("location:")
                || lower.contains("compilesdkversion is not specified")
                || lower.contains("invalid flag:")
                // GitHub Actions ##[error] (sudah di-strip prefix-nya)
                || lower.contains("process completed with exit code")
                || (lower.contains("exit code") && !lower.contains("exit code 0"))
                // Python errors
                || lower.contains("modulenotfounderror") || lower.contains("importerror")
                || lower.contains("syntaxerror:") || lower.contains("nameerror:")
                || lower.contains("typeerror:") || lower.contains("valueerror:")
                || lower.contains("attributeerror:") || lower.contains("assertionerror")
                || lower.contains("traceback (most recent")
                || (lower.contains("error") && lower.contains(".py:"))
                // Node / npm errors
                || lower.startsWith("npm err!") || lower.contains("npm error")
                || lower.contains("cannot find module") || lower.contains("module not found")
                || lower.contains("syntaxerror") && lower.contains(".js")
                // CMake / C++ errors
                || lower.contains("cmake error") || lower.contains("cmake warning")
                || lower.contains("ld: error:") || lower.contains("linker command failed")
                || lower.contains("undefined symbol") || lower.contains("undefined reference")
                || lower.contains("no such file or directory") && lower.contains(".cpp")
                // ndk-build errors
                || lower.contains("android.mk") && lower.contains("no rule")
                || lower.contains("make: ***")
                // Flutter / Dart errors
                || lower.contains("dart compile") && lower.contains("error")
                || lower.contains("flutter build failed") || lower.contains("gradle task")
                || lower.contains("unable to determine flutter")
                || lower.contains("unsupported gradle project")
                // .NET errors
                || lower.contains("build engine") && lower.contains("error")
                || (lower.contains("cs") && lower.contains("error cs"))
                // Rust errors
                || lower.contains("error[e") // error[E0xxx]
                // Go errors
                || lower.contains("./...") && lower.contains("error")
                // General CI
                || lower.contains("fatal error:") || lower.contains("fatal:")
                || lower.contains("permission denied") || lower.contains("command not found")
                || lower.contains("no such file or directory")
                || lower.contains("task failed") || lower.contains("step failed");
            boolean isNoise =
                lower.startsWith("\tat ") || lower.startsWith("at com.")
                || lower.startsWith("at org.") || lower.startsWith("at java.")
                || lower.startsWith("at sun.") || lower.startsWith("at android.")
                || lower.contains("taskexecutionexception") || lower.contains("buildoperationrunner")
                || lower.startsWith("> run with ") || lower.startsWith("> use ")
                || lower.startsWith("> get more");
            if (isRoot && !isNoise) sb.append(line.trim()).append("\n");
        }
        return sb.toString().trim();
    }

    /** Extract all error + warning lines (more than root cause, less than all). */
    private String extractErrorLines(String log) {
        StringBuilder sb = new StringBuilder();
        for (String rawLine : log.split("\n")) {
            String raw  = stripGhTimestamp(rawLine);
            boolean isGhError   = raw.startsWith("##[error]");
            boolean isGhWarning = raw.startsWith("##[warning]");
            String line;
            if (isGhError)        line = "[ERROR] " + raw.substring(9).trim();
            else if (isGhWarning) line = "[WARN] " + raw.substring(11).trim();
            else                  line = raw;
            if (isNoiseLine(line) || line.isEmpty()) continue;
            String lower = line.toLowerCase();
            if (isGhError || isGhWarning || isErrorLine(lower)
                    || lower.contains("warning") || lower.contains("warn:"))
                sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Strip GitHub Actions log timestamp prefix.
     * Format: "2026-03-19T05:30:59.3318783Z " or "2026-03-19T05:30:59Z "
     */
    private String stripGhTimestamp(String line) {
        // Match ISO-8601 timestamp at start: YYYY-MM-DDTHH:MM:SS[.nnnnnnn]Z<space>
        if (line.length() > 20 && line.charAt(4) == '-' && line.charAt(7) == '-'
                && line.charAt(10) == 'T' && line.contains("Z ")) {
            int zIdx = line.indexOf("Z ");
            if (zIdx > 10 && zIdx < 35) return line.substring(zIdx + 2);
        }
        return line;
    }

    /**
     * Returns true for lines that are noise and should be skipped:
     * - GitHub Actions group markers (##[group], ##[endgroup], ##[error])
     * - SDK license text (long prose sentences)
     * - Empty ANSI escape sequences
     * - Shell metadata lines
     */
    private boolean isNoiseLine(String line) {
        if (line.isEmpty()) return false;
        String t = line.trim();
        // GitHub Actions markers
        if (t.startsWith("##[")) return true;
        // ANSI color escape sequences only
        if (t.matches("\\[\\d+[;m].*") && t.length() < 15) return true;
        // Shell/env metadata
        if (t.startsWith("shell:") || t.startsWith("env:")) return true;
        // SDK license legal text — heuristic: very long sentence without gradle keywords
        if (t.length() > 200 && t.contains(" the ") && !t.contains(":app:")) return true;
        // Gradle progress dots line
        if (t.matches("\\.+\\d+%.*")) return true;
        // Download progress lines
        if (t.startsWith("Downloading https://") || t.startsWith("Checking the license")
                || t.startsWith("Preparing \"Install") || t.startsWith("Installing Android")
                || t.startsWith("\"Install ") || t.startsWith("License for package")) return true;
        return false;
    }

    private boolean isErrorLine(String lower) {
        return lower.contains("error:") || lower.contains("exception")
            || lower.contains("build failed") || lower.contains("cannot find symbol")
            || lower.contains("incompatible types") || lower.contains("compilation failed")
            || lower.contains("duplicate class") || lower.contains("could not resolve")
            || lower.contains("unresolved reference")
            || (lower.contains("failed") && (lower.contains("task") || lower.contains("execution")))
            || lower.startsWith("\tat ")
            || lower.startsWith("npm err!") || lower.contains("npm error")
            || lower.contains("cannot find module") || lower.contains("modulenotfounderror")
            || lower.contains("cmake error") || lower.contains("ld: error:")
            || lower.contains("undefined symbol") || lower.contains("undefined reference")
            || lower.contains("make: ***") || lower.contains("linker command failed")
            || lower.contains("fatal error:") || lower.contains("fatal:")
            || lower.contains("traceback (most recent")
            || lower.contains("process completed with exit code")
            || (lower.contains("exit code") && !lower.contains("exit code 0"))
            || lower.contains("permission denied") || lower.contains("command not found");
    }

    // =========================================================================
    // ARTIFACTS PAGE
    // =========================================================================

    /** Share any file via Android share sheet. */
    private void shareFile(byte[] bytes, String fileName, String mimeType) {
        try {
            java.io.File tmp = new java.io.File(getCacheDir(), fileName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            fos.write(bytes); fos.close();
            android.net.Uri uri;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".provider", tmp);
            } else {
                uri = android.net.Uri.fromFile(tmp);
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mimeType == null || mimeType.isEmpty()
                ? "application/octet-stream" : mimeType);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, fileName);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share " + fileName));
        } catch (Exception e) {
            toast("Share failed: " + e.getMessage());
        }
    }

    private View buildArtifactsPage(final GHApi.WorkflowRun run) {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);
        LinearLayout artHdr = buildHeader2("Artifacts", run.name + " · " + (run.conclusion.isEmpty() ? run.status : run.conclusion), null);
        try {
            LinearLayout artRow = (LinearLayout) artHdr.getChildAt(0);
            if (artRow != null && artRow.getChildCount() > 0)
                artRow.getChildAt(0).setOnClickListener(bv -> {
                    while (mNavStack.size() > 1) mNavStack.pop();
                    navigateTo(() -> setContentView(buildRepoPageAtTab(1)));
                });
        } catch (Exception ignored2) {}
        outer.addView(artHdr);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG); sv.setLayoutParams(mpWrap());
        LinearLayout c = lv(); c.setPadding(dp(16), dp(14), dp(16), dp(40));

        final LinearLayout list = lv(); list.setLayoutParams(mpWrap());
        list.addView(loadingRow());
        c.addView(list);
        sv.addView(c); outer.addView(sv);

        GHApi.listArtifacts(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, run.id,
            (artifacts, err) -> mHandler.post(() -> {
                list.removeAllViews();
                if (err != null) { list.addView(errCard(err)); return; }
                if (artifacts == null || artifacts.isEmpty()) {
                    list.addView(tv("No artifacts found.\nMake sure your workflow uses actions/upload-artifact.", ThemeManager.DIM, 11f, false)); return;
                }
                for (GHApi.Artifact a : artifacts) { list.addView(buildArtifactCard(a)); list.addView(sp(8)); }
            }));
        return outer;
    }

    private View buildArtifactCard(final GHApi.Artifact a) {
        LinearLayout card = lh(Gravity.CENTER_VERTICAL);
        card.setLayoutParams(mpWrap());
        card.setBackground(rbs(ThemeManager.SURFACE, 14, a.expired ? ThemeManager.BORDER : ThemeManager.BORDER2));
        card.setPadding(dp(14), dp(16), dp(12), dp(16));

        LinearLayout iconWrap = new LinearLayout(this);
        LinearLayout.LayoutParams iwLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        iwLp.rightMargin = dp(12); iconWrap.setLayoutParams(iwLp);
        iconWrap.setGravity(Gravity.CENTER);
        iconWrap.setBackground(rb(ThemeManager.SURFACE2, 12));
        iconWrap.addView(tv(a.name.endsWith(".apk") ? "📦" : "🗜", ThemeManager.TEXT, 20f, false));

        LinearLayout info = lv();
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        info.addView(tv(a.name, a.expired ? ThemeManager.DIM : ThemeManager.TEXT, 13f, true));
        info.addView(tv(fmtSize(a.sizeInBytes) + (a.expired ? " · EXPIRED" : " · " + a.createdAt.substring(0,10)), ThemeManager.DIM, 10f, false));

        card.addView(iconWrap); card.addView(info);

        if (!a.expired) {
            // Download button
            final TextView dlBtn = tv("↓  Download", ThemeManager.BRAND, 11f, true);
            dlBtn.setBackground(rbs(ThemeManager.BRAND_D, 10, ThemeManager.BRAND));
            dlBtn.setPadding(dp(12), dp(9), dp(12), dp(9));
            dlBtn.setGravity(Gravity.CENTER);
            dlBtn.setLetterSpacing(0.02f);
            LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlLp.rightMargin = dp(6);
            dlBtn.setLayoutParams(dlLp);
            dlBtn.setOnClickListener(v -> { dlBtn.setText("..."); dlBtn.setEnabled(false); downloadArtifact(a, dlBtn); });
            applyRipple(dlBtn, 10);
            card.addView(dlBtn);

            // Publish Release button
            final TextView pubBtn = tv("↑  Publish", ThemeManager.SUCCESS, 11f, true);
            pubBtn.setBackground(rbs(ThemeManager.SUCCESS_D, 10, ThemeManager.SUCCESS));
            pubBtn.setPadding(dp(12), dp(9), dp(12), dp(9));
            pubBtn.setGravity(Gravity.CENTER);
            pubBtn.setLetterSpacing(0.02f);
            pubBtn.setOnClickListener(v -> showPublishReleaseDialog(a));
            applyRipple(pubBtn, 10);
            card.addView(pubBtn);
        }
        return card;
    }

    // =========================================================================
    // FITUR 1: GITHUB RELEASES — Publish APK
    // =========================================================================

    /**
     * Dialog untuk mengisi tag, nama, dan release notes sebelum publish.
     * Alur lengkap: Download Artifact ZIP → ekstrak APK → Buat Release → Upload Asset
     */
    private void showPublishReleaseDialog(final GHApi.Artifact artifact) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setDimAmount(0.5f);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setCancelable(true);

        LinearLayout root = lv();
        root.setBackground(rbs(ThemeManager.SURFACE, 18, ThemeManager.BORDER));
        root.setPadding(dp(20), dp(22), dp(20), dp(20));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.92f),
            LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Header ────────────────────────────────────────────────────────────
        LinearLayout headerRow = lh(Gravity.CENTER_VERTICAL);
        headerRow.setLayoutParams(mpWrap());
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLp.bottomMargin = dp(16); headerRow.setLayoutParams(hrLp);
        TextView titleTv = tv("🚀  Publish GitHub Release", ThemeManager.TEXT, 15f, true);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(titleTv);
        headerRow.addView(badgeChip(artifact.name, ThemeManager.DIM, ThemeManager.SURFACE2));
        root.addView(headerRow);

        // ── Tag name ──────────────────────────────────────────────────────────
        root.addView(secLabel("Tag name")); root.addView(sp(5));
        final EditText tagEt = styledInput("e.g. v1.0.0", false);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tagLp.bottomMargin = dp(12); tagEt.setLayoutParams(tagLp);
        root.addView(tagEt);

        // ── Release title ─────────────────────────────────────────────────────
        root.addView(secLabel("Title (optional)")); root.addView(sp(5));
        final EditText titleEt = styledInput("e.g. Version 1.0.0 — Initial Release", false);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlLp.bottomMargin = dp(12); titleEt.setLayoutParams(tlLp);
        root.addView(titleEt);

        // ── Release notes ─────────────────────────────────────────────────────
        root.addView(secLabel("RELEASE NOTES (Markdown)")); root.addView(sp(5));
        final EditText notesEt = new EditText(this);
        notesEt.setHint("## What's new\n- Added ...\n- Fixed ...");
        notesEt.setHintTextColor(0xFF404050);
        notesEt.setTextColor(ThemeManager.TEXT);
        notesEt.setTextSize(12f);
        notesEt.setBackground(rbs(ThemeManager.SURFACE2, 10, ThemeManager.BORDER));
        notesEt.setPadding(dp(12), dp(10), dp(12), dp(10));
        notesEt.setGravity(Gravity.TOP);
        notesEt.setMinLines(4);
        notesEt.setMaxLines(8);
        notesEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams notesLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        notesLp.bottomMargin = dp(12); notesEt.setLayoutParams(notesLp);
        root.addView(notesEt);

        // ── Pre-release toggle ────────────────────────────────────────────────
        LinearLayout preRow = lh(Gravity.CENTER_VERTICAL);
        preRow.setLayoutParams(mpWrap());
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.bottomMargin = dp(18); preRow.setLayoutParams(prLp);
        final boolean[] isPre = {false};
        final TextView preChip = badgeChip("Pre-release", ThemeManager.DIM, ThemeManager.SURFACE2);
        preChip.setPadding(dp(12), dp(8), dp(12), dp(8));
        preChip.setOnClickListener(v -> {
            isPre[0] = !isPre[0];
            preChip.setText(isPre[0] ? "Pre-release" : "Pre-release");
            preChip.setTextColor(isPre[0] ? ThemeManager.AMBER : ThemeManager.DIM);
            preChip.setBackground(rbs(isPre[0] ? ThemeManager.AMBER_D : ThemeManager.SURFACE2, 6, isPre[0] ? ThemeManager.AMBER : ThemeManager.DIM));
        });
        preRow.addView(preChip);
        root.addView(preRow);

        // ── Buttons ───────────────────────────────────────────────────────────
        LinearLayout btnRow = lh(Gravity.END);
        btnRow.setLayoutParams(mpWrap());
        TextView cancelBtn = roundBtn("Cancel", ThemeManager.DIM, ThemeManager.SURFACE2);
        LinearLayout.LayoutParams cbLp2 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        cbLp2.rightMargin = dp(8); cancelBtn.setLayoutParams(cbLp2);
        cancelBtn.setPadding(dp(16), 0, dp(16), 0);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        final TextView publishBtn = tv("Publish Release", 0xFFFFFFFF, 12f, true);
        publishBtn.setBackground(rb(ThemeManager.GREEN, 10));
        publishBtn.setPadding(dp(18), 0, dp(18), 0);
        publishBtn.setGravity(Gravity.CENTER);
        publishBtn.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
        publishBtn.setLetterSpacing(0.04f);

        publishBtn.setOnClickListener(v -> {
            String tag   = tagEt.getText().toString().trim();
            String title = titleEt.getText().toString().trim();
            String notes = notesEt.getText().toString().trim();
            if (tag.isEmpty()) { toast("Isi tag name dulu (e.g. v1.0.0)"); return; }
            if (!tag.startsWith("v") && !tag.matches("[0-9].*")) {
                toast("Tags usually start with 'v', e.g. v1.0.0"); }
            dialog.dismiss();
            doPublishRelease(artifact, tag, title.isEmpty() ? tag : title,
                notes, isPre[0]);
        });

        btnRow.addView(cancelBtn); btnRow.addView(publishBtn);
        root.addView(btnRow);
        dialog.setContentView(root);
        dialog.show();
    }

    /**
     * Alur lengkap publish release:
     * 1) Download artifact ZIP (sudah berisi APK)
     * 2) Buat GitHub Release baru
     * 3) Upload APK sebagai release asset
     */
    private void doPublishRelease(final GHApi.Artifact artifact,
                                   final String tag, final String title,
                                   final String notes, final boolean prerelease) {
        android.app.Dialog prog = makeProgressDialog();
        final TextView progTv = (TextView)
            ((LinearLayout) prog.findViewById(android.R.id.message)).getChildAt(0);
        progTv.setText("Downloading artifact...");
        prog.show();
        showStatusBar("🚀  Publishing release " + tag + "...", ThemeManager.CYAN);

        // Step 1: Download artifact
        GHApi.downloadArtifact(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            artifact.id,
            (dl, total) -> mHandler.post(() ->
                progTv.setText("⬇  " + fmtSize(dl)
                    + (total > 0 ? " / " + fmtSize(total) : ""))),
            (zipBytes, dlErr) -> mHandler.post(() -> {
                if (dlErr != null) {
                    prog.dismiss(); hideStatusBar(0);
                    showErr("Artifact download failed:\n\n" + dlErr); return;
                }

                // Step 2: Buat Release
                progTv.setText("Creating release " + tag + "...");
                GHApi.createRelease(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    tag, title, notes, prerelease,
                    (release, relErr) -> mHandler.post(() -> {
                        if (relErr != null) {
                            prog.dismiss(); hideStatusBar(0);
                            showErr("Failed to create release:\n\n" + relErr
                                + "\n\nPastikan tag \"" + tag + "\" is already taken."); return;
                        }

                        // Step 3: Upload APK dari ZIP artifact
                        progTv.setText("Uploading asset...");
                        AppExecutors.getInstance().diskIO().execute(() -> {
                            try {
                                // Ekstrak file output dari artifact ZIP — universal
                                java.util.zip.ZipInputStream zis =
                                    new java.util.zip.ZipInputStream(
                                        new java.io.ByteArrayInputStream(zipBytes));
                                java.util.zip.ZipEntry entry;
                                byte[] assetBytesRaw = null;
                                String assetNameRaw = null;
                                // Prioritas: apk > jar > whl > exe > binary > ambil file pertama
                                String[] preferred = {".apk",".aab",".jar",".whl",".exe",".bin",".ipa"};
                                java.util.Map<String,byte[]> found = new java.util.LinkedHashMap<>();
                                while ((entry = zis.getNextEntry()) != null) {
                                    if (!entry.isDirectory())
                                        found.put(entry.getName(), GHApi.readStreamBytes(zis));
                                    zis.closeEntry();
                                }
                                zis.close();
                                // Pilih file terbaik
                                outer:
                                for (String ext : preferred) {
                                    for (java.util.Map.Entry<String,byte[]> fe : found.entrySet()) {
                                        if (fe.getKey().toLowerCase().endsWith(ext)) {
                                            assetBytesRaw = fe.getValue();
                                            assetNameRaw  = new java.io.File(fe.getKey()).getName();
                                            break outer;
                                        }
                                    }
                                }
                                if (assetBytesRaw == null && !found.isEmpty()) {
                                    java.util.Map.Entry<String,byte[]> first = found.entrySet().iterator().next();
                                    assetBytesRaw = first.getValue();
                                    assetNameRaw  = new java.io.File(first.getKey()).getName();
                                }
                                // Jika ZIP kosong atau tidak ada file cocok, upload ZIP langsung
                                final byte[] assetBytes = assetBytesRaw != null ? assetBytesRaw : zipBytes;
                                final String assetName  = assetNameRaw  != null ? assetNameRaw  : artifact.name + ".zip";

                                GHApi.uploadReleaseAsset(
                                    mPrefs.getToken(), release.uploadUrl,
                                    assetName, assetBytes,
                                    (downloadUrl, upErr) -> mHandler.post(() -> {
                                        prog.dismiss();
                                        hideStatusBar(0);
                                        if (upErr != null) {
                                            showErr("Release created but asset upload failed:\n"
                                                + upErr + "\n\nUpload manual di:\n"
                                                + release.htmlUrl); return;
                                        }
                                        showStatusBar("✅  Release " + tag + " published successfully!", ThemeManager.GREEN);
                                        hideStatusBar(5000);
                                        showConfirm("Release Published",
                                            "Tag: " + tag + "\nAsset: " + assetName
                                                + "\nSize: " + fmtSize(assetBytes.length) + "\n\n" + release.htmlUrl,
                                            "Open in Browser", ThemeManager.BRAND, () -> {
                                                try {
                                                    startActivity(new Intent(Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(release.htmlUrl)));
                                                } catch (Exception ex) { toast("Cannot open browser"); }
                                            });
                                    })
                                );
                            } catch (Exception e) {
                                mHandler.post(() -> {
                                    prog.dismiss(); hideStatusBar(0);
                                    showErr("Error extracting APK:\n" + e.getMessage());
                                });
                            }
                        });
                    }));
            }));
    }

    private void downloadArtifact(final GHApi.Artifact a, final TextView btn) {
        showNotif(NOTIF_UPLOAD, "Downloading", a.name + " — starting...", 0, 100);
        GHApi.downloadArtifact(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            a.id,
            (dl, total) -> {
                String sizeStr = fmtSize(dl) + (total > 0 ? " / " + fmtSize(total) : "");
                int pct = (int)(total > 0 ? dl * 100 / total : 0);
                showNotif(NOTIF_UPLOAD, "Downloading: " + a.name, sizeStr, pct, 100);
            },
            (bytes, err) -> mHandler.post(() -> {
                cancelNotif(NOTIF_UPLOAD);
                if (err != null) { btn.setText("⬇"); btn.setEnabled(true); showErr("Download failed:\n\n" + err); return; }
                try {
                    String ts = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
                    String fname = a.name + "_" + ts + ".zip";
                    if (Build.VERSION.SDK_INT >= 29) {
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname);
                        cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip");
                        cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        android.net.Uri fu = getContentResolver().insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        if (fu == null) throw new Exception("MediaStore insert failed");
                        java.io.OutputStream os = getContentResolver().openOutputStream(fu);
                        if (os == null) throw new Exception("Cannot open stream");
                        os.write(bytes); os.close();
                    } else {
                        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        dir.mkdirs();
                        FileOutputStream fos = new FileOutputStream(new File(dir, fname));
                        fos.write(bytes); fos.close();
                    }
                    cancelNotif(NOTIF_UPLOAD);
                    showNotifResult("✓ Download Complete", a.name + "  —  " + fmtSize(bytes.length));
                    btn.setText("✓"); btn.setTextColor(ThemeManager.GREEN);
                    // Save extracted APKs to internal cache — Release publish can reuse without re-download
                    final byte[] fz = bytes;
                    AppExecutors.getInstance().diskIO().execute(() -> {
                        try {
                            java.util.zip.ZipInputStream czis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(fz));
                            java.util.zip.ZipEntry cze;
                            java.io.File cDir = new java.io.File(getCacheDir(), "apk"); cDir.mkdirs();
                            for (java.io.File od : cDir.listFiles()!=null?cDir.listFiles():new java.io.File[0]) { if(od.getName().endsWith(".apk"))od.delete(); }
                            while ((cze = czis.getNextEntry()) != null) {
                                if (cze.getName().endsWith(".apk")) {
                                    byte[] cb2 = GHApi.readStreamBytes(czis);
                                    java.io.FileOutputStream cfos = new java.io.FileOutputStream(new java.io.File(cDir, new java.io.File(cze.getName()).getName()));
                                    cfos.write(cb2); cfos.close();
                                }
                                czis.closeEntry();
                            }
                            czis.close();
                        } catch (Exception ex2) { android.util.Log.w("GitDeploy","Cache save: "+ex2.getMessage()); }
                    });
                    final byte[] finalBytes = bytes;
                    // Dialog dengan opsi Buka Downloads + Install APK
                    boolean isApk = a.name.toLowerCase().contains("apk")
                            || a.name.toLowerCase().contains("release")
                            || a.name.toLowerCase().contains("debug")
                            || a.name.toLowerCase().contains("signed");
                    java.util.List<String> dlgItems = new java.util.ArrayList<>();
                    java.util.List<Runnable> dlgActions = new java.util.ArrayList<>();
                    if (isApk) { dlgItems.add("📦 Install APK"); dlgActions.add(() -> tryInstallApkFromZip(finalBytes, a.name)); }
                    dlgItems.add("Open Downloads"); dlgActions.add(() -> {
                        try { startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)); }
                        catch (Exception ex) { toast("Cannot open file manager."); }
                    });
                    dlgItems.add("Done"); dlgActions.add(null);
                    showMenu("Download Complete — Downloads/" + fname,
                        dlgItems.toArray(new String[0]), dlgActions.toArray(new Runnable[0]));
                } catch (Exception e) { btn.setText("⬇"); btn.setEnabled(true); showErr("Failed to save:\n" + e.getMessage()); }
            }));
    }

    // =========================================================================
    // FITUR 2: COMMIT HISTORY PAGE
    // =========================================================================

    private View buildCommitHistoryPage() {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);

        LinearLayout header = buildHeader2("Commit History",
            mCurrentOwner + "/" + mCurrentRepo + " · " + mCurrentBranch, null);
        LinearLayout ha = (LinearLayout) header.getTag();
        ha.addView(iconBtn("↻", ThemeManager.BRAND, () ->
            navigateTo(() -> setContentView(buildCommitHistoryPage()))));
        outer.addView(header);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG); sv.setLayoutParams(mpWrap());
        LinearLayout c = lv(); c.setPadding(dp(16), dp(12), dp(16), dp(40));

        final LinearLayout list = lv(); list.setLayoutParams(mpWrap());
        list.addView(loadingRow());
        c.addView(list);
        sv.addView(c); outer.addView(sv);
        // Scroll-state: simpan referensi sv dan restore posisi tersimpan
        mCommitHistorySv = sv;
        final int savedCommitScroll = mScrollStates.containsKey("commit_history")
            ? mScrollStates.get("commit_history") : 0;
        if (savedCommitScroll > 0) sv.post(() -> sv.scrollTo(0, savedCommitScroll));

        // State untuk pagination
        final int[] page = {1};
        final boolean[] hasMore = {true};
        final boolean[] loading = {false};

        // Load halaman pertama
        loadCommitPage(list, page, hasMore, loading, c);
        return outer;
    }

    private void loadCommitPage(final LinearLayout list,
                                 final int[] page,
                                 final boolean[] hasMore,
                                 final boolean[] loading,
                                 final LinearLayout container) {
        if (loading[0] || !hasMore[0]) return;
        loading[0] = true;

        GHApi.listCommits(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            mCurrentBranch, page[0],
            (commits, err) -> mHandler.post(() -> {
                loading[0] = false;
                // Hapus loading row / error card lama di akhir list
                if (list.getChildCount() > 0) {
                    android.view.View last = list.getChildAt(list.getChildCount() - 1);
                    if ("loading_row".equals(last.getTag())
                            || "load_more_btn".equals(last.getTag()))
                        list.removeView(last);
                }

                if (err != null) {
                    list.addView(errCard(err)); return;
                }
                if (commits == null || commits.isEmpty()) {
                    if (page[0] == 1)
                        list.addView(tv("No commits on this branch yet.", ThemeManager.DIM, 11f, false));
                    hasMore[0] = false; return;
                }

                // Render commit cards
                for (int i = 0; i < commits.size(); i++) {
                    list.addView(buildCommitCard(commits.get(i)));
                    if (i < commits.size() - 1) list.addView(rowDivider());
                }
                list.setBackground(rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));

                page[0]++;
                // Jika hasil < 30, tidak ada halaman berikutnya
                if (commits.size() < 30) {
                    hasMore[0] = false;
                    // Footer info total
                    TextView footer = tv("— " + ((page[0]-1) * 30 - (30 - commits.size()))
                        + " commits loaded —", ThemeManager.DIM, 10f, false);
                    footer.setGravity(Gravity.CENTER);
                    footer.setPadding(0, dp(14), 0, dp(14));
                    container.addView(footer);
                } else {
                    // Tombol Load More
                    TextView loadMore = roundBtn("Load more", ThemeManager.BRAND, ThemeManager.BRAND_D);
                    loadMore.setTag("load_more_btn");
                    LinearLayout.LayoutParams lmLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lmLp.topMargin = dp(12); loadMore.setLayoutParams(lmLp);
                    loadMore.setPadding(0, dp(12), 0, dp(12));
                    loadMore.setOnClickListener(v -> {
                        container.removeView(loadMore);
                        TextView loadingTv = tv("Loading...", ThemeManager.DIM, 11f, false);
                        loadingTv.setTag("loading_row");
                        loadingTv.setGravity(Gravity.CENTER);
                        loadingTv.setPadding(0, dp(12), 0, dp(12));
                        list.addView(rowDivider());
                        list.addView(loadingTv);
                        loadCommitPage(list, page, hasMore, loading, container);
                    });
                    container.addView(loadMore);
                }
            }));
    }

    /** Card untuk satu commit entry. */
    private View buildCommitCard(final GHApi.CommitEntry commit) {
        LinearLayout row = lh(Gravity.TOP);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setClickable(true);

        // SHA badge
        LinearLayout shaWrap = new LinearLayout(this);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(52), dp(52));
        swLp.rightMargin = dp(12); shaWrap.setLayoutParams(swLp);
        shaWrap.setGravity(Gravity.CENTER);
        shaWrap.setBackground(rb(ThemeManager.SURFACE2, 12));

        TextView shaTv = tv(commit.shortSha, ThemeManager.AMBER, 10f, true);
        shaTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        shaTv.setGravity(Gravity.CENTER);
        shaWrap.addView(shaTv);

        // Info column
        LinearLayout info = lv();
        info.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Commit message — ambil baris pertama saja
        String firstLine = commit.message.contains("\n")
            ? commit.message.substring(0, commit.message.indexOf('\n'))
            : commit.message;
        TextView msgTv = tv(firstLine, ThemeManager.TEXT, 12.5f, true);
        msgTv.setMaxLines(2);
        msgTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.bottomMargin = dp(5); msgTv.setLayoutParams(msgLp);
        info.addView(msgTv);

        // Meta row: author · date
        String dateStr = commit.date.length() >= 10 ? commit.date.substring(0, 10) : commit.date;
        TextView metaTv = tv(commit.author + "  ·  " + dateStr, ThemeManager.DIM, 10f, false);
        metaTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        info.addView(metaTv);

        // Full SHA (jika message multi-line, tampilkan sebagai hint)
        if (commit.message.contains("\n")) {
            String rest = commit.message.substring(commit.message.indexOf('\n')).trim();
            if (!rest.isEmpty()) {
                TextView bodyTv = tv(rest.length() > 80 ? rest.substring(0, 80) + "…" : rest,
                    0xFF606070, 10f, false);
                bodyTv.setPadding(0, dp(3), 0, 0);
                info.addView(bodyTv);
            }
        }

        row.addView(shaWrap); row.addView(info);

        // Tap → open commit detail / diff
        row.setOnClickListener(v -> {
            // Simpan posisi scroll commit history sebelum navigate
            if (mCommitHistorySv != null)
                mScrollStates.put("commit_history", mCommitHistorySv.getScrollY());
            navigateTo(() -> setContentView(buildCommitDetailPage(commit)));
        });
        // Long press → copy SHA
        row.setOnLongClickListener(v -> {
            android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("SHA", commit.sha));
                toast("SHA copied: " + commit.shortSha);
            }
            return true;
        });
        applyRipple(row);
        return row;
    }

    // =========================================================================
    // TOKEN PAGE
    // =========================================================================
    View buildTokenPage() {
        LinearLayout outer = lv(); outer.setBackgroundColor(ThemeManager.BG);
        outer.addView(buildHeader2("Token Manager", "GitHub Personal Access Token", null));
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(ThemeManager.BG); sv.setLayoutParams(mpWrap());
        LinearLayout c = lv(); c.setPadding(dp(16), dp(12), dp(16), dp(40));

        // Current token info
        c.addView(secLabel("Active Token"));
        c.addView(sp(8));

        LinearLayout tokenCard = lv();
        tokenCard.setLayoutParams(mpWrap());
        tokenCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        tokenCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout tokenRow = lh(Gravity.CENTER_VERTICAL);
        tokenRow.setLayoutParams(mpWrap());
        View dot = new View(this);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
        dot.setBackground(rb(ThemeManager.GREEN, 4));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(10); dot.setLayoutParams(dotLp);
        tokenRow.addView(dot);
        TextView nameTv = tv("@" + mPrefs.getUsername(), ThemeManager.TEXT, 14f, true);
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tokenRow.addView(nameTv);
        tokenRow.addView(badgeChip("ACTIVE", ThemeManager.DIM, ThemeManager.SURFACE2));
        tokenCard.addView(tokenRow);

        // Token preview
        String tok = mPrefs.getToken();
        String preview = tok.length() > 12 ? tok.substring(0, 8) + "••••••••" + tok.substring(tok.length()-4) : "••••••••";
        TextView tokPreview = tv(preview, ThemeManager.DIM, 12f, false);
        tokPreview.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tpLp.topMargin = dp(10); tpLp.bottomMargin = dp(12);
        tokPreview.setLayoutParams(tpLp);
        tokenCard.addView(tokPreview);

        // Scope guide
        String[][] scopes = {
            {"repo", "Upload, buat, lihat repo", "WAJIB", "ok"},
            {"workflow", "Upload .yml, trigger build", "WAJIB", "ok"},
            {"delete_repo", "Delete repository", "OPSIONAL", "warn"}
        };
        LinearLayout scopeRow = lh(Gravity.CENTER_VERTICAL);
        scopeRow.setLayoutParams(mpWrap());
        for (String[] s : scopes) {
            int col = s[3].equals("ok") ? ThemeManager.DIM : ThemeManager.AMBER;
            int bg  = s[3].equals("ok") ? ThemeManager.SURFACE2 : ThemeManager.AMBER_D;
            TextView chip = badgeChip((s[3].equals("ok")?"✓ ":"! ") + s[0], col, bg);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cLp.rightMargin = dp(6);
            chip.setLayoutParams(cLp);
            scopeRow.addView(chip);
        }
        tokenCard.addView(scopeRow);

        // Tombol buka halaman token di browser
        LinearLayout.LayoutParams editBtnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        editBtnLp.topMargin = dp(12);
        TextView editTokenBtn = roundBtn("Edit Scopes on GitHub →", ThemeManager.BRAND, ThemeManager.BRAND_D);
        editTokenBtn.setLayoutParams(editBtnLp);
        editTokenBtn.setGravity(Gravity.CENTER);
        editTokenBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        editTokenBtn.setOnClickListener(v -> {
            // Coba buka halaman edit token yang spesifik
            // Token format: ghp_xxxx → tidak ada ID dari token string itu sendiri
            // Arahkan ke halaman daftar token supaya user bisa pilih yang mana
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/settings/tokens"));
                startActivity(i);
            } catch (Exception e) { toast("Cannot open browser."); }
        });
        tokenCard.addView(editTokenBtn);

        // Info scope tidak bisa diubah lewat API
        TextView scopeInfo = tv("ⓘ  Token scopes cannot be changed via API — this is a GitHub security restriction. Tap the button above to edit scopes in your browser.", ThemeManager.DIM, 10f, false);
        scopeInfo.setLineSpacing(dp(1), 1.3f);
        LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        siLp.topMargin = dp(10);
        scopeInfo.setLayoutParams(siLp);
        tokenCard.addView(scopeInfo);

        c.addView(tokenCard);
        c.addView(sp(20));

        // Update token
        c.addView(secLabel("Update Token"));
        c.addView(sp(8));
        LinearLayout updateCard = lv();
        updateCard.setLayoutParams(mpWrap());
        updateCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        updateCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView upHint = tv("Replace the active token. Required scopes: repo, workflow, delete_repo.", ThemeManager.DIM, 10.5f, false);
        upHint.setLineSpacing(dp(2), 1.2f);
        LinearLayout.LayoutParams uhLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        uhLp.bottomMargin = dp(12);
        upHint.setLayoutParams(uhLp);
        updateCard.addView(upHint);

        final EditText newTokEt = styledInput("ghp_your_new_token...", true);
        LinearLayout.LayoutParams netLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        netLp.bottomMargin = dp(12);
        newTokEt.setLayoutParams(netLp);
        updateCard.addView(newTokEt);

        final TextView updateBtn = primaryBtn("Update Token", ThemeManager.BRAND);
        updateBtn.setTextColor(ThemeManager.current() == ThemeManager.THEME_LIGHT ? 0xFFFFFFFF : 0xFF000000);
        updateBtn.setOnClickListener(v -> {
            String newTok = newTokEt.getText().toString().trim();
            if (newTok.isEmpty()) { toast("Enter a new token"); return; }
            updateBtn.setText("Verifying...");
            updateBtn.setEnabled(false);
            GHApi.validateToken(newTok, (username, err) -> mHandler.post(() -> {
                updateBtn.setEnabled(true);
                if (err != null) { updateBtn.setText("🔄  UPDATE TOKEN"); showErr("Invalid token:\n" + err); }
                else {
                    mPrefs.saveToken(newTok);
                    mPrefs.saveUsername(username);
                    showInfo("✓ Token Updated", "Signed in as @" + username + "\n\nToken saved successfully.");
                    mRepoViewModel.invalidate(); // token baru = repo list berbeda
                    mNavStack.clear();
                    navigateTo(() -> setContentView(buildHomePage()));
                }
            }));
        });
        updateCard.addView(updateBtn);
        c.addView(updateCard);
        c.addView(sp(16));

        sv.addView(c); outer.addView(sv);
        return outer;
    }

    // ── PROFILE PAGE: AI Assistant + Appearance + Accounts ───────────────────
    private View buildProfilePage() {
        LinearLayout outer = lv(); outer.setBackgroundColor(ThemeManager.BG);
        outer.addView(buildHeader2("Profile & Settings", "Appearance · Community", null));
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(ThemeManager.BG); sv.setLayoutParams(mpWrap());
        LinearLayout c = lv(); c.setPadding(dp(16), dp(12), dp(16), dp(40));

        // ── AI Fix Section ────────────────────────────────────────────────────
        // ── FITUR 3: THEME ENGINE ─────────────────────────────────────────────
        c.addView(secLabel("Appearance"));
        c.addView(sp(8));

        LinearLayout themeCard = lv();
        themeCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        themeCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tcLp.bottomMargin = dp(20);
        themeCard.setLayoutParams(tcLp);

        String curName = ThemeManager.icon(ThemeManager.current())
            + "  " + ThemeManager.name(ThemeManager.current());
        TextView themeDesc = tv("Active theme: " + curName, ThemeManager.DIM, 10.5f, false);
        LinearLayout.LayoutParams tdescLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tdescLp.bottomMargin = dp(12);
        themeDesc.setLayoutParams(tdescLp);
        themeCard.addView(themeDesc);

        LinearLayout themeRow = lh(Gravity.CENTER_VERTICAL);
        themeRow.setLayoutParams(mpWrap());
        // Hanya 2 tema: Dark dan Light (AMOLED dihapus)
        String[] themeNames = {"Dark", "Light"};
        int[] themeIds = {ThemeManager.THEME_DARK, ThemeManager.THEME_LIGHT};
        for (int ti = 0; ti < 2; ti++) {
            final int tid = themeIds[ti];
            boolean active = ThemeManager.current() == tid;
            TextView tBtn = tv(themeNames[ti], active ? ThemeManager.CYAN : ThemeManager.DIM, 12f, active);
            tBtn.setBackground(rbs(active ? ThemeManager.CYAN_D : ThemeManager.SURFACE2, 12, active ? ThemeManager.CYAN : ThemeManager.BORDER));
            tBtn.setGravity(Gravity.CENTER);
            tBtn.setPadding(dp(8), dp(14), dp(8), dp(14));
            LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (ti < 1) tbLp.rightMargin = dp(8);
            tBtn.setLayoutParams(tbLp);
            tBtn.setOnClickListener(v -> {
                if (ThemeManager.current() == tid) return; // tidak perlu ganti
                // ── FIX: Jangan recreate() — rebuild in-place ─────────────────
                // recreate() menghancurkan Activity + mNavStack sehingga app
                // "keluar". Solusi: sync warna lalu rebuild halaman aktif.
                ThemeManager.save(this, tid);
                getWindow().setStatusBarColor(ThemeManager.BG);
                toast("Theme changed to " + ThemeManager.name(tid) + ".");
                // Rebuild Settings page dengan warna baru.
                // After theme change, reload profilePage to reflect new colors
                if (mNavStack.size() > 1) mNavStack.pop();
                navigateTo(() -> setContentView(buildProfilePage()));
            });
            applyRipple(tBtn, 12);
            themeRow.addView(tBtn);
        }
        themeCard.addView(themeRow);
        c.addView(themeCard);

        // ── FITUR 2: MULTI-ACCOUNT — manage accounts section ─────────────────
        // ── Community & Updates ───────────────────────────────────────────────
        c.addView(sp(20));
        c.addView(secLabel("Community & Updates"));
        c.addView(sp(8));

        LinearLayout communityCard = lv();
        communityCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        communityCard.setLayoutParams(mpWrap());

        // Telegram row
        LinearLayout tgRow = lh(Gravity.CENTER_VERTICAL);
        tgRow.setPadding(dp(14), dp(14), dp(14), dp(14));
        tgRow.setClickable(true); applyRipple(tgRow);
        android.widget.ImageView tgImg = new android.widget.ImageView(this);
        android.view.ViewGroup.MarginLayoutParams tgImgLp = new android.view.ViewGroup.MarginLayoutParams(dp(40), dp(40));
        tgImgLp.rightMargin = dp(12); tgImg.setLayoutParams(tgImgLp);
        tgImg.setImageDrawable(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_telegram));
        tgImg.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        tgRow.addView(tgImg);
        LinearLayout tgInfo = lv();
        tgInfo.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tgInfo.addView(tv("Telegram Channel", ThemeManager.TEXT, 13f, true));
        tgInfo.addView(tv("Updates, announcements & support", ThemeManager.DIM, 10.5f, false));
        tgRow.addView(tgInfo);
        tgRow.addView(tv("\u203a", ThemeManager.DIM, 20f, false));
        tgRow.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/modfreew"))); }
            catch (Exception e) { toast("Cannot open browser."); }
        });
        communityCard.addView(tgRow);
        communityCard.addView(rowDivider());

        // Discord row
        LinearLayout dcRow = lh(Gravity.CENTER_VERTICAL);
        dcRow.setPadding(dp(14), dp(14), dp(14), dp(14));
        dcRow.setClickable(true); applyRipple(dcRow);
        android.widget.ImageView dcImg = new android.widget.ImageView(this);
        android.view.ViewGroup.MarginLayoutParams dcImgLp = new android.view.ViewGroup.MarginLayoutParams(dp(40), dp(40));
        dcImgLp.rightMargin = dp(12); dcImg.setLayoutParams(dcImgLp);
        dcImg.setImageDrawable(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_discord));
        dcImg.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        dcRow.addView(dcImg);
        LinearLayout dcInfo = lv();
        dcInfo.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        dcInfo.addView(tv("Discord Server", ThemeManager.TEXT, 13f, true));
        dcInfo.addView(tv("Coming soon", ThemeManager.DIM, 10.5f, false));
        dcRow.addView(dcInfo);
        dcRow.addView(tv("\u203a", ThemeManager.DIM, 20f, false));
        dcRow.setOnClickListener(v -> toast("Discord server is not available yet."));
        communityCard.addView(dcRow);

        c.addView(communityCard);
        c.addView(sp(16));

        // ── Accounts ─────────────────────────────────────────────────────────
        c.addView(sp(20));
        c.addView(secLabel("Accounts"));
        c.addView(sp(8));
        final LinearLayout accountsCard = lv();
        accountsCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        LinearLayout.LayoutParams acLp2 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        acLp2.bottomMargin = dp(12);
        accountsCard.setLayoutParams(acLp2);
        buildAccountsList(accountsCard);
        c.addView(accountsCard);

        // Sign Out
        TextView logoutBtn2 = ghostBtn("Sign Out");
        logoutBtn2.setOnClickListener(v2 ->
            showConfirm("Sign Out?", "All accounts and settings will be cleared.", "Sign Out", () -> {
                    mPrefs.clear(); mNavStack.clear();
                    navigateTo(() -> setContentView(buildSetupPage()));
                }));
        c.addView(logoutBtn2);
        c.addView(sp(16));

        sv.addView(c); outer.addView(sv);
        return outer;
    }

    // =========================================================================
    // FITUR 2: MULTI-ACCOUNT SUPPORT — UI helpers
    // =========================================================================

    /**
     * Render daftar semua akun tersimpan ke dalam card container.
     * Setiap baris: avatar-initial · @username · [AKTIF] · [Switch] [Hapus]
     */
    private void buildAccountsList(LinearLayout container) {
        container.removeAllViews();
        java.util.List<AppPrefs.Account> accounts = mPrefs.getAccounts();
        int activeIdx = mPrefs.getActiveAccountIndex();

        if (accounts.isEmpty()) {
            TextView empty = tv("No accounts saved.", ThemeManager.DIM, 11f, false);
            empty.setPadding(dp(14), dp(14), dp(14), dp(14));
            container.addView(empty);
        } else {
            for (int i = 0; i < accounts.size(); i++) {
                AppPrefs.Account acc = accounts.get(i);
                final int idx = i;
                boolean isActive = (i == activeIdx);
                container.addView(buildAccountRow(acc, isActive, idx, container));
                if (i < accounts.size() - 1) container.addView(rowDivider());
            }
        }

        // Tombol tambah akun
        container.addView(rowDivider());
        TextView addBtn = tv("＋  Add New Account", ThemeManager.CYAN, 12f, true);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setPadding(dp(14), dp(14), dp(14), dp(14));
        addBtn.setOnClickListener(v -> showAddAccountDialog(container));
        applyRipple(addBtn);
        container.addView(addBtn);
    }

    private View buildAccountRow(AppPrefs.Account acc, boolean isActive,
                                   int idx, LinearLayout container) {
        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));

        // Avatar initial
        LinearLayout avatarWrap = new LinearLayout(this);
        LinearLayout.LayoutParams awLp = new LinearLayout.LayoutParams(dp(38), dp(38));
        awLp.rightMargin = dp(12); avatarWrap.setLayoutParams(awLp);
        avatarWrap.setGravity(Gravity.CENTER);
        avatarWrap.setBackground(rb(isActive ? ThemeManager.CYAN_D : ThemeManager.SURFACE2, 19));
        String initials = acc.username.isEmpty() ? "?" :
            String.valueOf(Character.toUpperCase(acc.username.charAt(0)));
        avatarWrap.addView(tv(initials, isActive ? ThemeManager.CYAN : ThemeManager.DIM, 16f, true));

        // Info
        LinearLayout info = lv();
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout nameRow = lh(Gravity.CENTER_VERTICAL);
        nameRow.setLayoutParams(mpWrap());
        TextView userTv = tv("@" + acc.username, isActive ? ThemeManager.TEXT : ThemeManager.TEXT2, 13f, isActive);
        userTv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        nameRow.addView(userTv);
        if (isActive) {
            TextView activeBadge = badgeChip("AKTIF", ThemeManager.GREEN, ThemeManager.GREEN_D);
            LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            abLp.leftMargin = dp(6); activeBadge.setLayoutParams(abLp);
            nameRow.addView(activeBadge);
        }
        info.addView(nameRow);
        String tokPreview = acc.token.length() > 10
            ? acc.token.substring(0, 6) + "••••" + acc.token.substring(acc.token.length() - 4)
            : "••••••";
        info.addView(tv(tokPreview, ThemeManager.DIM, 10f, false));

        row.addView(avatarWrap); row.addView(info);

        if (!isActive) {
            // Tombol switch
            TextView switchBtn = roundBtn("Switch", ThemeManager.BRAND, ThemeManager.BRAND_D);
            LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sbLp.rightMargin = dp(6); switchBtn.setLayoutParams(sbLp);
            switchBtn.setOnClickListener(v -> {
                mPrefs.switchToAccount(idx);
                toast("Switched to @" + acc.username);
                mRepoViewModel.invalidate(); // akun berbeda = repo list berbeda
                // Reload home page dengan token baru
                mNavStack.clear();
                navigateTo(() -> setContentView(buildHomePage()));
            });
            row.addView(switchBtn);
        }

        // Tombol hapus (jangan hapus akun satu-satunya)
        TextView removeBtn = tv("✕", ThemeManager.RED, 16f, true);
        removeBtn.setPadding(dp(10), dp(6), dp(6), dp(6));
        removeBtn.setOnClickListener(v -> {
            showConfirm("Remove @" + acc.username + "?", "This account will be removed from the list.", "Remove", () -> {
                    mPrefs.removeAccount(acc.username);
                    buildAccountsList(container);
                    if (isActive) {
                        mNavStack.clear();
                        if (mPrefs.hasToken())
                            navigateTo(() -> setContentView(buildHomePage()));
                        else
                            navigateTo(() -> setContentView(buildSetupPage()));
                    }
                });
        });
        row.addView(removeBtn);
        applyRipple(row);
        return row;
    }

    /** Dialog tambah akun baru — validasi token via GitHub API. */
    private void showAddAccountDialog(final LinearLayout container) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setDimAmount(0.5f);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setCancelable(true);

        LinearLayout root = lv();
        root.setBackground(rbs(ThemeManager.SURFACE, 18, ThemeManager.BORDER));
        root.setPadding(dp(20), dp(22), dp(20), dp(20));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.90f),
            LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(tv("Add Account", ThemeManager.TEXT, 15f, true));
        root.addView(sp(14));
        root.addView(tv("GitHub Token", ThemeManager.DIM, 9.5f, true));
        root.addView(sp(5));

        final EditText tokenEt = styledInput("ghp_xxxxxxxxxxxx", true);
        LinearLayout.LayoutParams teLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        teLp.bottomMargin = dp(16); tokenEt.setLayoutParams(teLp);
        root.addView(tokenEt);

        LinearLayout btnRow = lh(Gravity.END);
        btnRow.setLayoutParams(mpWrap());
        TextView cancelBtn = roundBtn("Cancel", ThemeManager.DIM, ThemeManager.SURFACE2);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        cbLp.rightMargin = dp(8); cancelBtn.setLayoutParams(cbLp);
        cancelBtn.setPadding(dp(16), 0, dp(16), 0);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        final TextView addBtn2 = tv("Connect", ThemeManager.current() == ThemeManager.THEME_LIGHT ? 0xFFFFFFFF : 0xFF000000, 12f, true);
        addBtn2.setBackground(rb(ThemeManager.CYAN, 10));
        addBtn2.setPadding(dp(18), 0, dp(18), 0);
        addBtn2.setGravity(Gravity.CENTER);
        addBtn2.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));

        addBtn2.setOnClickListener(v -> {
            String tok = tokenEt.getText().toString().trim();
            if (tok.isEmpty()) { toast("Enter a token first."); return; }
            addBtn2.setText("Verifying..."); addBtn2.setEnabled(false);
            GHApi.validateToken(tok, (username, err) -> mHandler.post(() -> {
                addBtn2.setEnabled(true); addBtn2.setText("Connect");
                if (err != null) { showErr("Invalid token:\n" + err); return; }
                AppPrefs.Account newAcc = new AppPrefs.Account(tok, username, "");
                mPrefs.addOrUpdateAccount(newAcc);
                dialog.dismiss();
                toast("Account @" + username + " ditambahkan");
                buildAccountsList(container);
            }));
        });

        btnRow.addView(cancelBtn); btnRow.addView(addBtn2);
        root.addView(btnRow);
        dialog.setContentView(root);
        dialog.show();
    }

    /**
     * Dialog "Switch Account" yang muncul saat user tap username di Home.
     * Menampilkan daftar akun + tombol Add New.
     */
    private void showSwitchAccountDialog() {
        java.util.List<AppPrefs.Account> accounts = mPrefs.getAccounts();
        if (accounts.size() <= 1) {
            // Single account — open Profile page to manage accounts
            replaceNavigateTo(() -> setContentView(buildProfilePage()));
            return;
        }

        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setDimAmount(0.5f);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setCancelable(true);

        LinearLayout root = lv();
        root.setBackground(rbs(ThemeManager.SURFACE, 20, ThemeManager.BORDER));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.88f),
            LinearLayout.LayoutParams.WRAP_CONTENT));

        // Header
        LinearLayout hdr = lh(Gravity.CENTER_VERTICAL);
        hdr.setBackgroundColor(ThemeManager.SURFACE2);
        hdr.setPadding(dp(18), dp(14), dp(18), dp(14));
        hdr.addView(tv("👤  Switch Account", ThemeManager.TEXT, 15f, true));
        root.addView(hdr);
        root.addView(rowDivider());

        int activeIdx = mPrefs.getActiveAccountIndex();
        for (int i = 0; i < accounts.size(); i++) {
            AppPrefs.Account acc = accounts.get(i);
            final int idx = i;
            boolean isActive = (i == activeIdx);

            LinearLayout row = lh(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(14), dp(18), dp(14));

            // Checkmark aktif atau avatar
            TextView check = tv(isActive ? "✓" : "  ", isActive ? ThemeManager.GREEN : ThemeManager.DIM, 16f, true);
            check.setMinWidth(dp(24));
            row.addView(check);

            LinearLayout info = lv();
            info.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            info.addView(tv("@" + acc.username, isActive ? ThemeManager.TEXT : ThemeManager.TEXT2, 14f, isActive));
            String tPrev = acc.token.length() > 10
                ? acc.token.substring(0, 6) + "••••" + acc.token.substring(acc.token.length()-4)
                : "••••••";
            info.addView(tv(tPrev, ThemeManager.DIM, 10f, false));
            row.addView(info);

            if (!isActive) {
                row.setOnClickListener(v -> {
                    mPrefs.switchToAccount(idx);
                    dialog.dismiss();
                    toast("Switched to @" + acc.username);
                    mNavStack.clear();
                    navigateTo(() -> setContentView(buildHomePage()));
                });
                applyRipple(row);
            }

            root.addView(row);
            if (i < accounts.size() - 1) root.addView(rowDivider());
        }

        // Footer: Manage accounts
        root.addView(rowDivider());
        TextView manageBtn = tv("Manage Accounts", ThemeManager.CYAN, 12f, true);
        manageBtn.setGravity(Gravity.CENTER);
        manageBtn.setPadding(dp(18), dp(14), dp(18), dp(14));
        manageBtn.setOnClickListener(v -> {
            dialog.dismiss();
            replaceNavigateTo(() -> setContentView(buildProfilePage()));
        });
        applyRipple(manageBtn);
        root.addView(manageBtn);

        dialog.setContentView(root);
        dialog.show();
    }

    // =========================================================================
    // SIGN CONFIG PAGE — two tabs: Generate New / Use Existing
    // =========================================================================
    private View buildSignConfigPage() {
        LinearLayout outer = lv(); outer.setBackgroundColor(ThemeManager.BG);
        outer.addView(buildHeader2("Signing Config", mCurrentRepo + " · auto-sign", null));

        // ── Check repo for existing signing config ────────────────────────────
        final LinearLayout statusCard = lv();
        statusCard.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        statusCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.leftMargin = dp(16); scLp.rightMargin = dp(16); scLp.topMargin = dp(10);
        scLp.bottomMargin = dp(4); statusCard.setLayoutParams(scLp);
        final TextView statusTv = tv("  Checking signing status...", ThemeManager.DIM, 10.5f, false);
        statusCard.addView(statusTv);
        outer.addView(statusCard);

        // Check GitHub for existing keystore async
        GHApi.getFileContent(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            ".signing/release.jks",
            (result, err) -> mHandler.post(() -> {
                if (err == null && result != null) {
                    // Keystore already exists in repo
                    statusCard.setBackground(rbs(ThemeManager.GREEN_D, 12, 0x3000FF88));
                    statusTv.setTextColor(ThemeManager.GREEN);
                    statusTv.setText("✓  Signing already configured for this repo.\n"
                        + "Alias: " + (mPrefs.getSigningAlias().isEmpty()
                            ? "(check GitHub Variables → SIGNING_ALIAS)"
                            : mPrefs.getSigningAlias())
                        + "\n\nEvery build already produces a signed APK.\n"
                        + "Use 📂 USE EXISTING tab to re-link if you reinstalled.");
                } else {
                    statusCard.setBackground(rbs(ThemeManager.AMBER_D, 12, 0x30FFB800));
                    statusTv.setTextColor(ThemeManager.AMBER);
                    statusTv.setText("⚠  No signing config found in this repo yet.\n"
                        + "Use ✨ GENERATE NEW to set it up automatically.");
                }
            }));

        // Tab bar
        final int[] activeTab = {0};
        LinearLayout tabBar = lh(Gravity.CENTER_VERTICAL);
        tabBar.setBackgroundColor(ThemeManager.SURFACE);
        tabBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        final TextView tabGen = tv("✨ GENERATE NEW", ThemeManager.GREEN, 11f, true);
        tabGen.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        tabGen.setGravity(Gravity.CENTER); tabGen.setLetterSpacing(0.03f);

        final TextView tabUse = tv("📂 USE EXISTING", ThemeManager.DIM, 11f, true);
        tabUse.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        tabUse.setGravity(Gravity.CENTER); tabUse.setLetterSpacing(0.03f);

        tabBar.addView(tabGen); tabBar.addView(tabUse);
        outer.addView(tabBar);

        View tabLine = new View(this);
        tabLine.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        tabLine.setBackgroundColor(ThemeManager.BORDER);
        outer.addView(tabLine);

        final LinearLayout content = lv(); content.setLayoutParams(mpWrap());

        tabGen.setOnClickListener(v -> {
            if (activeTab[0] == 0) return;
            activeTab[0] = 0; tabGen.setTextColor(ThemeManager.GREEN); tabUse.setTextColor(ThemeManager.DIM);
            content.removeAllViews(); content.addView(buildSignGenerateTab());
        });
        tabUse.setOnClickListener(v -> {
            if (activeTab[0] == 1) return;
            activeTab[0] = 1; tabGen.setTextColor(ThemeManager.DIM); tabUse.setTextColor(ThemeManager.CYAN);
            content.removeAllViews(); content.addView(buildSignUseExistingTab());
        });

        content.addView(buildSignGenerateTab());
        outer.addView(content);
        return outer;
    }

    // ── Save keystore bytes to Downloads folder ───────────────────────────────

        private View buildSignGenerateTab() {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(ThemeManager.BG); sv.setLayoutParams(mpWrap());
        LinearLayout c = lv(); c.setPadding(dp(16), dp(14), dp(16), dp(40));

        // Info card
        LinearLayout infoCard = lv();
        infoCard.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        infoCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        icLp.bottomMargin = dp(16); infoCard.setLayoutParams(icLp);
        TextView infoTv = tv(
            "✨  Automatically generates a new signing keystore.\n"
            + "RSA-2048, SHA256withRSA, valid for 25 years.\n"
            + "Keystore uploaded to repo + all credentials saved.\n"
            + "Every future build will produce a signed APK.",
            ThemeManager.TEXT2, 10.5f, false);
        infoTv.setLineSpacing(dp(2), 1.2f); infoCard.addView(infoTv);
        c.addView(infoCard);

        // Alias
        c.addView(secLabel("Key Alias"));
        c.addView(sp(6));
        final EditText aliasEt = styledInput("e.g. release", false);
        String savedAlias = mPrefs.getSigningAlias();
        aliasEt.setText(savedAlias.isEmpty() ? "release" : savedAlias);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aLp.bottomMargin = dp(10); aliasEt.setLayoutParams(aLp); c.addView(aliasEt);

        // Password
        c.addView(secLabel("Keystore Password"));
        c.addView(sp(6));
        final EditText passEt = styledInput("Choose a strong password (min. 6 chars)", true);
        String savedPass = mPrefs.getSigningStorePass();
        if (!savedPass.isEmpty()) passEt.setText(savedPass);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLp.bottomMargin = dp(4); passEt.setLayoutParams(pLp); c.addView(passEt);
        TextView passHint = tv("ⓘ  Key password = Keystore password (kept simple).", ThemeManager.DIM, 9.5f, false);
        LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        phLp.bottomMargin = dp(14); passHint.setLayoutParams(phLp); c.addView(passHint);

        // Display name
        c.addView(secLabel("Display Name (optional — shown on certificate)"));
        c.addView(sp(6));
        final EditText nameEt = styledInput("e.g. My App or your name", false);
        nameEt.setText(mPrefs.getUsername().isEmpty() ? "GitDeploy App" : mPrefs.getUsername());
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nLp.bottomMargin = dp(16); nameEt.setLayoutParams(nLp); c.addView(nameEt);

        // Warning
        LinearLayout warnCard = lv();
        warnCard.setBackground(rbs(ThemeManager.AMBER_D, 12, 0x30FFB800));
        warnCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams wcLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wcLp.bottomMargin = dp(20); warnCard.setLayoutParams(wcLp);
        TextView warnTv = tv("⚠  Save your alias and password somewhere safe!\nIf lost, you cannot sign future updates with the same key.", ThemeManager.AMBER, 10f, false);
        warnTv.setLineSpacing(dp(2), 1.2f); warnCard.addView(warnTv); c.addView(warnCard);

        // Generate button
        final TextView genBtn = primaryBtn("Generate & Upload Keystore", ThemeManager.BRAND);
        genBtn.setTextColor(ThemeManager.current() == ThemeManager.THEME_LIGHT ? 0xFFFFFFFF : 0xFF000000);
        genBtn.setOnClickListener(v -> {
            String alias = aliasEt.getText().toString().trim();
            String pass  = passEt.getText().toString();
            String name  = nameEt.getText().toString().trim();
            if (alias.isEmpty()) { toast("Key alias is required."); return; }
            if (pass.length() < 6) { showErr("Password must be at least 6 characters."); return; }
            if (name.isEmpty()) name = "GitDeploy App";
            final String fAlias = alias, fPass = pass, fName = name;
            genBtn.setText("Generating..."); genBtn.setEnabled(false);
            android.app.Dialog prog = makeProgressDialog();
            final TextView progTv = (TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0);
            progTv.setText("Generating RSA-2048 keystore..."); prog.show();
            AppExecutors.getInstance().networkIO().execute(() -> {
                try {
                    byte[] ksBytes = GHApi.generateKeystore(fAlias, fPass, fName);
                    mHandler.post(() -> progTv.setText("Uploading to repo..."));
                    mPrefs.saveSigningAlias(fAlias);
                    mPrefs.saveSigningStorePass(fPass);
                    mPrefs.saveSigningKeyPass(fPass);

                    // Auto-save keystore to Downloads so it survives uninstall
                    final String ksFname = fAlias + "_release.jks";
                    mAutoSign.saveKeystoreToDownloads(ksBytes, ksFname);

                    GHApi.setupSigning(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                        mCurrentBranch, ksBytes, fAlias, fPass, fPass,
                        (result, err) -> mHandler.post(() -> {
                            prog.dismiss();
                            genBtn.setEnabled(true);
                            genBtn.setText("✨  GENERATE & UPLOAD KEYSTORE");
                            if (err != null) { showErr("Failed:\n\n" + err); return; }
                            mPrefs.saveSigningEnabled(true);
                            showConfirm("Keystore Created",
                                    "Uploaded and saved successfully.\n\n"
                                    + "Alias: " + fAlias + "  Password: " + fPass + "\n"
                                    + "Saved to Downloads/" + ksFname + "\n\n"
                                    + "Keep this file + password safe. Every build will produce a signed APK.",
                                    "Got it", ThemeManager.BRAND, () -> onBackPressed());
                        }));
                } catch (Exception e) {
                    mHandler.post(() -> {
                        prog.dismiss();
                        genBtn.setEnabled(true);
                        genBtn.setText("✨  GENERATE & UPLOAD KEYSTORE");
                        showErr("Keystore generation failed:\n\n" + e.getMessage());
                    });
                }
            });
        });
        c.addView(genBtn);
        sv.addView(c); return sv;
    }

    private View buildSignUseExistingTab() {
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(ThemeManager.BG); sv.setLayoutParams(mpWrap());
        LinearLayout c = lv(); c.setPadding(dp(16), dp(14), dp(16), dp(40));

        LinearLayout infoBox = lv();
        infoBox.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        infoBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ibLp.bottomMargin = dp(16); infoBox.setLayoutParams(ibLp);
        TextView infoTv = tv("Use your existing .jks or .keystore file.\nKeystore will be uploaded to .signing/release.jks\nand build.gradle + workflow yml will be auto-patched.", ThemeManager.TEXT2, 10.5f, false);
        infoTv.setLineSpacing(dp(2), 1.2f); infoBox.addView(infoTv); c.addView(infoBox);

        c.addView(secLabel("KEYSTORE FILE")); c.addView(sp(8));
        final TextView ksBtn = ghostBtn("📂  Select keystore (.jks / .keystore)");
        final byte[][] ksBuf = {null};
        ksBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "Select keystore"), REQ_PICK_KEYSTORE);
        });
        c.addView(ksBtn);
        final TextView ksStatus = tv("No keystore selected", ThemeManager.DIM, 10f, false);
        ksStatus.setPadding(dp(4), dp(6), 0, 0);
        LinearLayout.LayoutParams ksStLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ksStLp.bottomMargin = dp(14); ksStatus.setLayoutParams(ksStLp); c.addView(ksStatus);

        mKeystoreSelectedCallback = (bytes, fname) -> {
            ksBuf[0] = bytes;
            mHandler.post(() -> {
                ksBtn.setText("✓ " + fname); ksBtn.setTextColor(ThemeManager.GREEN);
                ksStatus.setText(fmtSize(bytes.length) + " · keystore ready"); ksStatus.setTextColor(ThemeManager.GREEN);
            });
        };

        c.addView(secLabel("Key Alias")); c.addView(sp(6));
        final EditText aliasEt = styledInput("e.g. my_key", false);
        aliasEt.setText(mPrefs.getSigningAlias());
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aLp.bottomMargin = dp(10); aliasEt.setLayoutParams(aLp); c.addView(aliasEt);

        c.addView(secLabel("Keystore Password")); c.addView(sp(6));
        final EditText storePassEt = styledInput("Keystore password", true);
        storePassEt.setText(mPrefs.getSigningStorePass());
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spLp.bottomMargin = dp(10); storePassEt.setLayoutParams(spLp); c.addView(storePassEt);

        c.addView(secLabel("Key Password")); c.addView(sp(6));
        final EditText keyPassEt = styledInput("Leave blank if same as above", true);
        keyPassEt.setText(mPrefs.getSigningKeyPass());
        LinearLayout.LayoutParams kpLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        kpLp.bottomMargin = dp(20); keyPassEt.setLayoutParams(kpLp); c.addView(keyPassEt);

        final TextView saveBtn = primaryBtn("Save & Upload to Repo", ThemeManager.BRAND);
        saveBtn.setTextColor(ThemeManager.current() == ThemeManager.THEME_LIGHT ? 0xFFFFFFFF : 0xFF000000);
        saveBtn.setOnClickListener(v -> {
            String alias   = aliasEt.getText().toString().trim();
            String storePwd = storePassEt.getText().toString();
            String keyPwd  = keyPassEt.getText().toString();
            if (ksBuf[0] == null)   { showErr("Please select a keystore file first"); return; }
            if (alias.isEmpty())    { showErr("Enter key alias"); return; }
            if (storePwd.isEmpty()) { showErr("Enter keystore password"); return; }
            mPrefs.saveSigningAlias(alias);
            mPrefs.saveSigningStorePass(storePwd);
            mPrefs.saveSigningKeyPass(keyPwd.isEmpty() ? storePwd : keyPwd);
            saveBtn.setText("Uploading..."); saveBtn.setEnabled(false);
            GHApi.setupSigning(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                mCurrentBranch, ksBuf[0], alias, storePwd,
                keyPwd.isEmpty() ? storePwd : keyPwd,
                (result, err) -> mHandler.post(() -> {
                    saveBtn.setEnabled(true); saveBtn.setText("💾  SAVE & UPLOAD TO REPO");
                    if (err != null) { showErr("Setup failed:\n\n" + err); return; }
                    mPrefs.saveSigningEnabled(true);
                    showInfo("✓ Auto-Sign Setup Complete",
                        "Keystore uploaded → .signing/release.jks\n"
                        + "Credentials saved as GitHub Actions Variables.\n"
                        + "build.gradle and workflow yml patched.\n\n"
                        + "Every future build will produce a signed APK.");
                    onBackPressed();
                }));
        });
        c.addView(saveBtn);
        sv.addView(c); return sv;
    }

    // Callback untuk keystore file picker
    private interface KeystoreCallback { void onSelected(byte[] bytes, String filename); }
    private KeystoreCallback mKeystoreSelectedCallback;

    private void handleKeystoreSelected(Uri uri) {
        if (mKeystoreSelectedCallback == null) return;
        AppExecutors.getInstance().diskIO().execute(() -> {  // #3 disk I/O
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) { mHandler.post(() -> toast("Cannot read file")); return; }
                byte[] bytes = GHApi.readStreamBytes(is); is.close();
                String fname = getFilenameFromUri(getContentResolver(), uri);
                KeystoreCallback cb = mKeystoreSelectedCallback;
                mKeystoreSelectedCallback = null; // clear after use
                cb.onSelected(bytes, fname);
            } catch (Exception e) { mHandler.post(() -> toast("Error: " + e.getMessage())); }
        });
    }

    // =========================================================================
    // REPO MENU
    // =========================================================================
    // =========================================================================
    // SECRETS & VARIABLES PAGE
    // =========================================================================
    private View buildSecretsPage() {
        LinearLayout outer = lv(); outer.setBackgroundColor(ThemeManager.BG);
        outer.addView(buildHeader2("Secrets & Variables", mCurrentRepo, null));
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(ThemeManager.BG); sv.setLayoutParams(mpWrap());
        LinearLayout c = lv(); c.setPadding(dp(16), dp(14), dp(16), dp(40));

        // Info
        LinearLayout infoBox = lv();
        infoBox.setBackground(rbs(ThemeManager.SURFACE, 10, ThemeManager.BORDER));
        infoBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ibLp.bottomMargin = dp(16); infoBox.setLayoutParams(ibLp);
        TextView infoTv = tv("Variables: values stored as plain text, visible in workflow logs.\n"
            + "Secrets: nilai terenkripsi, tidak bisa dibaca — hanya nama yang ditampilkan.\n\n"
            + "Both can be used in workflows: ${{ vars.NAME }} or ${{ secrets.NAME }}", ThemeManager.TEXT2, 10f, false);
        infoTv.setLineSpacing(dp(2), 1.2f); infoBox.addView(infoTv);
        c.addView(infoBox);

        // ── Variables section ────────────────────────────────────────────────
        LinearLayout varHeader = lh(Gravity.CENTER_VERTICAL);
        varHeader.setLayoutParams(mpWrap());
        LinearLayout.LayoutParams vhLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vhLp.bottomMargin = dp(8); varHeader.setLayoutParams(vhLp);
        TextView varLabel = secLabel("Variables");
        varLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        varHeader.addView(varLabel);
        TextView addVarBtn = roundBtn("+ Add", ThemeManager.BRAND, ThemeManager.BRAND_D);
        addVarBtn.setOnClickListener(v -> showAddSecretDialog(false, () ->
            replaceNavigateTo(() -> setContentView(buildSecretsPage()))));
        varHeader.addView(addVarBtn);
        c.addView(varHeader);

        final LinearLayout varList = lv();
        varList.setLayoutParams(mpWrap());
        varList.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        varList.addView(loadingRow());
        c.addView(varList);

        // ── Secrets section ──────────────────────────────────────────────────
        c.addView(sp(16));
        LinearLayout secHeader = lh(Gravity.CENTER_VERTICAL);
        secHeader.setLayoutParams(mpWrap());
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shLp.bottomMargin = dp(8); secHeader.setLayoutParams(shLp);
        TextView secLabel2 = secLabel("Secrets (encrypted, value not visible)");
        secLabel2.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        secHeader.addView(secLabel2);
        TextView addSecBtn = roundBtn("+ Add", ThemeManager.BRAND, ThemeManager.BRAND_D);
        addSecBtn.setOnClickListener(v -> showAddSecretDialog(true, () ->
            replaceNavigateTo(() -> setContentView(buildSecretsPage()))));
        secHeader.addView(addSecBtn);
        c.addView(secHeader);

        final LinearLayout secList = lv();
        secList.setLayoutParams(mpWrap());
        secList.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        secList.addView(loadingRow());
        c.addView(secList);

        sv.addView(c); outer.addView(sv);

        // Load Variables
        GHApi.listVariables(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            (vars, err) -> mHandler.post(() -> {
                varList.removeAllViews();
                if (err != null) { varList.setPadding(dp(12),dp(12),dp(12),dp(12)); varList.addView(errCard(err)); return; }
                if (vars == null || vars.isEmpty()) {
                    TextView empty = tv("No variables defined.", ThemeManager.DIM, 11f, false);
                    empty.setGravity(Gravity.CENTER); empty.setPadding(0,dp(20),0,dp(20));
                    varList.addView(empty); return;
                }
                for (int i = 0; i < vars.size(); i++) {
                    String[] v = vars.get(i);
                    varList.addView(buildSecretRow(v[0], v[1], false,
                        () -> replaceNavigateTo(() -> setContentView(buildSecretsPage()))));
                    if (i < vars.size()-1) varList.addView(rowDivider());
                }
            }));

        // Load Secrets
        GHApi.listSecrets(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            (secrets, err) -> mHandler.post(() -> {
                secList.removeAllViews();
                if (err != null) { secList.setPadding(dp(12),dp(12),dp(12),dp(12)); secList.addView(errCard(err)); return; }
                if (secrets == null || secrets.isEmpty()) {
                    TextView empty = tv("No secrets defined.", ThemeManager.DIM, 11f, false);
                    empty.setGravity(Gravity.CENTER); empty.setPadding(0,dp(20),0,dp(20));
                    secList.addView(empty); return;
                }
                for (int i = 0; i < secrets.size(); i++) {
                    GHApi.Secret s = secrets.get(i);
                    secList.addView(buildSecretRow(s.name, "••••••••", true,
                        () -> replaceNavigateTo(() -> setContentView(buildSecretsPage()))));
                    if (i < secrets.size()-1) secList.addView(rowDivider());
                }
            }));

        return outer;
    }

    private View buildSecretRow(final String name, final String value,
                                  final boolean isSecret, final Runnable onDelete) {
        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));

        LinearLayout iconWrap = new LinearLayout(this);
        LinearLayout.LayoutParams iwLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        iwLp.rightMargin = dp(10); iconWrap.setLayoutParams(iwLp);
        iconWrap.setGravity(Gravity.CENTER);
        iconWrap.setBackground(rb(ThemeManager.SURFACE2, 8));
        iconWrap.addView(tv(isSecret ? "🔐" : "📝", ThemeManager.TEXT, 14f, false));

        LinearLayout info = lv();
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        info.addView(tv(name, ThemeManager.TEXT, 12.5f, true));
        TextView valTv = tv(value, ThemeManager.DIM, 10f, false);
        valTv.setTypeface(Typeface.MONOSPACE);
        valTv.setPadding(0, dp(2), 0, 0);
        info.addView(valTv);

        row.addView(iconWrap); row.addView(info);

        // Edit button (Variables only)
        if (!isSecret) {
            TextView editBtn = tv("✏", ThemeManager.CYAN, 16f, false);
            editBtn.setPadding(dp(10), dp(6), dp(6), dp(6));
            editBtn.setOnClickListener(v -> showEditVariableDialog(name, value, onDelete));
            row.addView(editBtn);
        }

        // Delete button
        TextView delBtn = tv("🗑", ThemeManager.RED, 16f, false);
        delBtn.setPadding(dp(6), dp(6), dp(2), dp(6));
        delBtn.setOnClickListener(v -> {
            showConfirm("Delete " + (isSecret ? "secret" : "variable") + "?",
                name + "\n\nThis cannot be undone.", "Delete", () -> {
                    if (isSecret) {
                        GHApi.deleteSecret(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                            name, (ok, err) -> mHandler.post(() -> {
                                if (ok) { toast("Secret deleted"); onDelete.run(); }
                                else showErr("Failed:\n" + err);
                            }));
                    } else {
                        GHApi.deleteVariable(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                            name, (ok, err) -> mHandler.post(() -> {
                                if (ok) { toast("Variable deleted"); onDelete.run(); }
                                else showErr("Failed:\n" + err);
                            }));
                    }
                });
        });
        row.addView(delBtn);
        return row;
    }

    private void showAddSecretDialog(final boolean isSecret, final Runnable onSaved) {
        LinearLayout layout = lv(); layout.setPadding(dp(20), dp(16), dp(20), dp(8));

        layout.addView(tv(isSecret ? "Secret Name" : "Variable Name", ThemeManager.DIM, 10f, true));
        layout.addView(sp(4));
        final EditText nameEt = styledInput("NAME_IN_UPPERCASE", false);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nLp.bottomMargin = dp(10); nameEt.setLayoutParams(nLp);
        layout.addView(nameEt);

        layout.addView(tv("Value", ThemeManager.DIM, 10f, true));
        layout.addView(sp(4));
        final EditText valEt = styledInput("value...", isSecret);
        layout.addView(valEt);

        if (isSecret) {
            TextView note = tv("ⓘ Secrets are stored as Variables (plain text). Avoid storing highly sensitive data here.", ThemeManager.DIM, 9.5f, false);
            note.setLineSpacing(dp(1), 1.2f);
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            noteLp.topMargin = dp(8); note.setLayoutParams(noteLp);
            layout.addView(note);
        }

        showViewDialog((isSecret ? "Add Secret" : "Add Variable"), layout, "Save", ThemeManager.BRAND, () -> {
                String name = nameEt.getText().toString().trim().toUpperCase().replace(" ","_");
                String val  = valEt.getText().toString();
                if (name.isEmpty()) { toast("Enter a name"); return; }
                GHApi.setVariable(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    name, val, (ok, err) -> mHandler.post(() -> {
                        if (ok) { toast("Saved: " + name); onSaved.run(); }
                        else showErr("Failed:\n" + err);
                    }));
            });
    }

    private void showEditVariableDialog(final String name, final String currentValue,
                                         final Runnable onSaved) {
        LinearLayout layout = lv(); layout.setPadding(dp(20), dp(16), dp(20), dp(8));
        layout.addView(tv("New value for: " + name, ThemeManager.DIM, 11f, true));
        layout.addView(sp(8));
        final EditText valEt = styledInput("New value...", false);
        valEt.setText(currentValue);
        valEt.setSelectAllOnFocus(true);
        layout.addView(valEt);

        showViewDialog("Edit Variable", layout, "Save", ThemeManager.BRAND, () -> {
                String newVal = valEt.getText().toString();
                GHApi.setVariable(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    name, newVal, (ok, err) -> mHandler.post(() -> {
                        if (ok) { toast(name + " updated"); onSaved.run(); }
                        else showErr("Failed:\n" + err);
                    }));
            });
    }

    private void showRepoMenu() {
        // First fetch current visibility
        GHApi.getRepoVisibility(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            (vis, err) -> mHandler.post(() -> {
                boolean isPrivate = "private".equals(vis);
                String visLabel = isPrivate ? "🌐 Make Public" : "🔒 Make Private";
                final boolean isFinalPrivate = isPrivate;
                showMenu(mCurrentRepo + (isPrivate ? "  🔒" : "  🌐"),
                    new String[]{"Open on GitHub","Download as ZIP","Upload APK to Repo",
                        "Signing Config","Secrets & Variables", visLabel,
                        "🗑 Clear Repo Contents", "Delete Repository"},
                    new Runnable[]{
                        () -> startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/" + mCurrentOwner + "/" + mCurrentRepo))),
                        () -> doDownloadRepo(),
                        () -> showAppToGitHubDialog(),
                        () -> navigateTo(() -> setContentView(buildSignConfigPage())),
                        () -> replaceNavigateTo(() -> setContentView(buildSecretsPage())),
                        () -> confirmChangeVisibility(isFinalPrivate),
                        () -> confirmClearRepo(),
                        () -> confirmDeleteRepo()
                    });
            }));
    }

    private void confirmChangeVisibility(boolean currentlyPrivate) {
        String action = currentlyPrivate ? "Make Public" : "Make Private";
        String msg = currentlyPrivate
            ? "This will make the repo publicly visible to everyone."
            : "This will hide the repo. GitHub Actions may require paid plan for private repos.";
        showConfirm(action + "?", msg, action, ThemeManager.WARNING, () -> {
                GHApi.setRepoVisibility(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    !currentlyPrivate,
                    (ok, err) -> mHandler.post(() -> {
                        if (ok) toast("Repo is now " + (currentlyPrivate ? "public" : "private"));
                        else showErr("Failed:\n" + err);
                    }));
            });
    }

    private void confirmClearRepo() {
        showConfirm("🗑 Clear Repo Contents?",
            "This will delete ALL files in \"" + mCurrentRepo + "\" EXCEPT .github/ workflows.\n\n"
            + "Secrets, signing config, and CI/CD setup will be preserved.\n"
            + "Use this to reuse the repo for a new project.",
            "Clear Contents", ThemeManager.DANGER, () -> {
                android.app.Dialog prog = makeProgressDialog();
                final TextView progTv = (TextView)
                    ((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0);
                progTv.setText("Fetching file list...");
                prog.show();

                TextView hideProgBtn = roundBtn("Hide (run in background)", ThemeManager.DIM, ThemeManager.SURFACE2);
                LinearLayout.LayoutParams hideLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                hideLp.topMargin = dp(10);
                hideProgBtn.setLayoutParams(hideLp);
                hideProgBtn.setOnClickListener(hv -> { prog.dismiss(); showNotif(NOTIF_MANAGE, "Clearing Repo", mCurrentRepo + " — deleting...", -1, 0); });
                ((LinearLayout) prog.findViewById(android.R.id.message)).addView(hideProgBtn);

                GHApi.clearRepoContents(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                    mCurrentBranch, new GHApi.ClearRepoProgress() {
                        @Override public void onProgress(int deleted, int total, String path) {
                            mHandler.post(() -> {
                                String m2 = "Deleting " + deleted + " / " + total + "\n" + path;
                                if (prog.isShowing()) progTv.setText(m2);
                                else showNotif(NOTIF_MANAGE, "Clearing Repo", deleted + " / " + total, deleted, total);
                            });
                        }
                        @Override public void onComplete(int deleted, String err) {
                            mHandler.post(() -> {
                                if (prog.isShowing()) prog.dismiss();
                                cancelNotif(NOTIF_MANAGE);
                                mCachedFiles.evictAll(); mCachedContent.evictAll();
                                if (err != null) { showNotifResult("✗ Clear Failed", err); showErr("Clear failed:\n" + err); }
                                else { showNotifResult("✓ Repo Cleared", deleted + " file(s) deleted"); toast("✓ " + deleted + " file(s) deleted — repo is clean"); navigateTo(() -> setContentView(buildRepoPageAtTab(0))); }
                            });
                        }
                    });
            });
    }

    private void confirmDeleteRepo() {
        showConfirm("Delete " + mCurrentRepo + "?",
            "Permanent. All code, history, and issues will be deleted.\nYour token must have the delete_repo scope.",
            "Delete", () -> GHApi.deleteRepo(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                (ok, err) -> mHandler.post(() -> {
                    if (ok) { toast("Repo deleted"); mRepoViewModel.invalidate(); mNavStack.clear(); navigateTo(() -> setContentView(buildHomePage())); }
                    else if (err != null && err.contains("403"))
                        showErr("Access denied (403). Your token needs delete_repo scope.\nGo to Settings → update your token.");
                    else showErr("Failed:\n" + err);
                })));
    }

    private void showCreateRepoDialog() {
        android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            d.getWindow().setDimAmount(0.5f);
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.setCancelable(true);

        // Root card
        LinearLayout root = lv();
        root.setBackground(rbs(ThemeManager.SURFACE, 16, ThemeManager.BORDER));
        root.setPadding(dp(20), dp(20), dp(20), dp(16));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            (int)(getResources().getDisplayMetrics().widthPixels * 0.90f),
            LinearLayout.LayoutParams.WRAP_CONTENT));

        // Title
        TextView titleTv = tv("New Repository", ThemeManager.TEXT, 15f, true);
        titleTv.setPadding(0, 0, 0, dp(14));
        root.addView(titleTv);

        // Name input
        root.addView(tv("Repository name", ThemeManager.DIM, 10f, true));
        root.addView(sp(4));
        final EditText nameEt = styledInput("my-android-app", false);
        root.addView(nameEt);
        root.addView(sp(10));

        // Private/Public toggle
        final boolean[] priv = {true};
        final TextView privBtn = roundBtn("🔒 Private", ThemeManager.TEXT2, ThemeManager.SURFACE2);
        privBtn.setOnClickListener(v -> {
            priv[0] = !priv[0];
            privBtn.setText(priv[0] ? "🔒 Private" : "🌐 Public");
            privBtn.setTextColor(priv[0] ? ThemeManager.TEXT2 : ThemeManager.BRAND);
            privBtn.setBackground(rbs(priv[0] ? ThemeManager.SURFACE2 : ThemeManager.BRAND_D,
                10, priv[0] ? ThemeManager.BORDER : ThemeManager.BRAND));
        });
        root.addView(privBtn);
        root.addView(sp(14));

        // Divider
        View divLine = new View(this);
        divLine.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divLine.setBackgroundColor(ThemeManager.BORDER);
        root.addView(divLine);
        root.addView(sp(10));

        // CI/CD row — single-line layout to avoid wrapping
        final boolean[] addCiCd = {true};
        LinearLayout ciRow = lh(Gravity.CENTER_VERTICAL);
        ciRow.setBackground(rbs(ThemeManager.BRAND_D, 10, ThemeManager.BRAND));
        ciRow.setPadding(dp(12), dp(12), dp(12), dp(12));
        ciRow.setLayoutParams(mpWrap());

        TextView ciIcon = tv("⚙", ThemeManager.BRAND, 14f, false);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        icLp.rightMargin = dp(8); ciIcon.setLayoutParams(icLp);

        // Title on one line, no description text that can wrap
        TextView ciLabel = tv("CI/CD workflow", ThemeManager.BRAND, 12f, true);
        ciLabel.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ciLabel.setSingleLine(true);
        ciLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView ciPill = tv("ON", 0xFFFFFFFF, 11f, true);
        ciPill.setGravity(Gravity.CENTER);
        ciPill.setMinWidth(dp(44));
        ciPill.setPadding(dp(8), dp(4), dp(8), dp(4));
        ciPill.setBackground(rb(ThemeManager.BRAND, 12));
        ciPill.setLetterSpacing(0.06f);

        android.view.View.OnClickListener ciToggle = v2 -> {
            addCiCd[0] = !addCiCd[0];
            ciPill.setText(addCiCd[0] ? "ON" : "OFF");
            ciPill.setBackground(rb(addCiCd[0] ? ThemeManager.BRAND : ThemeManager.BORDER2, 12));
            ciRow.setBackground(rbs(addCiCd[0] ? ThemeManager.BRAND_D : ThemeManager.SURFACE2,
                10, addCiCd[0] ? ThemeManager.BRAND : ThemeManager.BORDER));
        };
        ciRow.addView(ciIcon); ciRow.addView(ciLabel); ciRow.addView(ciPill);
        ciRow.setOnClickListener(ciToggle);
        applyRipple(ciRow, 10);
        root.addView(ciRow);
        root.addView(sp(16));

        // Buttons row — always at bottom
        LinearLayout btns = lh(Gravity.END);
        TextView cancelBtn = tv("Cancel", ThemeManager.TEXT2, 13f, false);
        cancelBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        cancelBtn.setOnClickListener(v -> d.dismiss());
        applyRipple(cancelBtn, 8);

        TextView createBtn = tv("Create", ThemeManager.BRAND, 13f, true);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbLp.leftMargin = dp(4); createBtn.setLayoutParams(cbLp);
        createBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        createBtn.setBackground(rbs(ThemeManager.BRAND_D, 8, ThemeManager.BRAND));
        createBtn.setOnClickListener(v -> {
            String name = nameEt.getText().toString().trim();
            if (name.isEmpty()) { toast("Repository name is required."); return; }
            d.dismiss();

            android.app.Dialog prog = makeProgressDialog();
            ((TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0))
                .setText("Creating repository...");
            prog.show();

            GHApi.createRepo(mPrefs.getToken(), name, priv[0],
                (repo, err) -> mHandler.post(() -> {
                    if (err != null) { prog.dismiss(); showErr("Failed:\n" + err); return; }
                    mRepoViewModel.invalidate(); // paksa refresh list setelah repo baru dibuat

                    if (!addCiCd[0]) {
                        prog.dismiss();
                        toast("Repo created: " + repo.name);
                        mNavStack.clear(); navigateTo(() -> setContentView(buildHomePage()));
                        return;
                    }

                    ((TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0))
                        .setText("Setting up CI/CD...");

                    final String owner = mPrefs.getUsername();
                    final String repoName = repo.name;
                    final String branch = "main";
                    final String yamlContent = buildCiCdYaml(branch);

                    AppExecutors.getInstance().networkIO().execute(() -> {
                        boolean yamlOk = GHApi.uploadFileSync(mPrefs.getToken(), owner,
                            repoName, ".github/workflows/android-build.yml",
                            yamlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "ci: add Android CI/CD workflow", branch, 2);

                        String readme = "# " + repoName + "\n\nAndroid project. Built with [GitDeploy](https://github.com).\n";
                        GHApi.uploadFileSync(mPrefs.getToken(), owner, repoName,
                            "README.md", readme.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "docs: add README", branch, 2);

                        mHandler.post(() -> {
                            prog.dismiss();
                            toast("✓ Repo created" + (yamlOk ? " + CI/CD added" : ""));
                            mCurrentRepo = repoName;
                            mCurrentOwner = owner;
                            mCurrentBranch = branch;
                            mNavStack.clear();
                            navigateTo(() -> setContentView(buildRepoPageAtTab(1)));
                        });
                    });
                }));
        });
        applyRipple(createBtn, 8);
        btns.addView(cancelBtn); btns.addView(createBtn);
        root.addView(btns);

        d.setContentView(root);
        d.show();
    }

    // =========================================================================
    // BUILD POLLING + NOTIFICATIONS — implementations in NotificationHelper.java
    // =========================================================================
    private void startBuildPolling()  { mNotif.startBuildPolling(); }
    private void stopBuildPolling()   { mNotif.stopBuildPolling(); }

        // =========================================================================
    // FILE VIEWER / EDITOR
    // =========================================================================

    private void openFileViewer(final GHApi.RepoContent item) {
        if (item.size > 1024 * 1024) {
            showErr("File too large (>1 MB). GitHub does not support previewing files over 1 MB.");
            return;
        }
        String contentKey = cacheKey(item.path);
        String cachedContent = mCachedContent.get(contentKey);

        // Helper to open with content (avoids duplicate code)
        StringPairAction openWith = (content, sha) -> {
            boolean binary = false;
            for (int i = 0; i < Math.min(content.length(), 512); i++)
                if (content.charAt(i) == 0) { binary = true; break; }
            if (binary) { showInfo("Binary File", item.name + "\nBinary file — cannot be opened as text."); return; }
            String nameLower = item.name.toLowerCase();
            if (nameLower.endsWith(".md") || nameLower.endsWith(".markdown")) {
                navigateTo(() -> setContentView(buildMarkdownViewerPage(item, content, sha)));
            } else {
                navigateTo(() -> setContentView(buildFileEditorPage(item, content, sha)));
            }
        };

        if (cachedContent != null) {
            // Open instantly from cache
            openWith.run(cachedContent, item.sha);
            // Silently re-fetch in background to detect remote changes
            GHApi.getFileContent(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, item.path,
                (result, err) -> mHandler.post(() -> {
                    if (err == null && result != null && !result[0].equals(cachedContent)) {
                        mCachedContent.put(contentKey, result[0]);
                    }
                }));
            return;
        }

        android.app.Dialog prog = makeProgressDialog();
        ((TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0))
            .setText("Reading " + item.name + "...");
        prog.show();

        GHApi.getFileContent(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, item.path,
            (result, err) -> mHandler.post(() -> {
                prog.dismiss();
                if (err != null) { showErr("Failed to read file:\n" + err); return; }
                String content = result[0];
                String sha     = result[1];
                mCachedContent.put(contentKey, content); // cache for next open
                openWith.run(content, sha);
            }));
    }

    // =========================================================================
    // FITUR 3: MARKDOWN VIEWER PAGE
    // =========================================================================

    /**
     * Menampilkan file .md yang dirender menggunakan Markwon.
     * Inisialisasi Markwon sepenuhnya via Java — tidak ada XML/layout resource.
     *
     * Tombol ✏ Edit di header memungkinkan user membuka file yang sama
     * di Code Editor jika ingin mengedit.
     */
    private View buildMarkdownViewerPage(final GHApi.RepoContent item,
                                          final String content,
                                          final String sha) {
        LinearLayout outer = lv();
        outer.setBackgroundColor(ThemeManager.BG);
        outer.setWeightSum(1f);

        // ── Header ────────────────────────────────────────────────────────────
        LinearLayout header = buildHeader2(item.name, item.path, null);
        LinearLayout ha = (LinearLayout) header.getTag();

        // Tombol Edit — buka Code Editor untuk file yang sama
        TextView editBtn = iconBtn("✏", ThemeManager.BRAND, null);
        editBtn.setOnClickListener(v ->
            navigateTo(() -> setContentView(buildFileEditorPage(item, content, sha))));
        ha.addView(editBtn);

        // Badge "MARKDOWN"
        TextView mdBadge = badgeChip("MD", ThemeManager.BRAND, ThemeManager.BRAND_D);
        LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mbLp.leftMargin = dp(4); mdBadge.setLayoutParams(mbLp);
        ha.addView(mdBadge);

        outer.addView(header);

        // ── ScrollView dengan rendered Markdown ───────────────────────────────
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ThemeManager.BG);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        final TextView mdTv = new TextView(this);
        mdTv.setPadding(dp(18), dp(16), dp(18), dp(48));
        mdTv.setTextColor(ThemeManager.TEXT);
        mdTv.setTextSize(13.5f);
        mdTv.setLineSpacing(dp(3), 1.3f);
        mdTv.setTextIsSelectable(true);

        // ── Markwon inisialisasi Java murni ───────────────────────────────────
        try {
            io.noties.markwon.Markwon markwon = io.noties.markwon.Markwon.builder(this)
                .usePlugin(io.noties.markwon.core.CorePlugin.create())
                .build();

            // Kustomisasi visual agar cocok dengan dark theme
            // Heading: besar + tebal + cyan
            android.text.SpannableStringBuilder rendered =
                (android.text.SpannableStringBuilder) markwon.toMarkdown(content);
            markwon.setParsedMarkdown(mdTv, rendered);

        } catch (Exception e) {
            // Fallback: plain text jika Markwon gagal
            mdTv.setText(content);
            mdTv.setTextColor(ThemeManager.TEXT2);
            mdTv.setTypeface(android.graphics.Typeface.MONOSPACE);
            mdTv.setTextSize(11.5f);
        }

        sv.addView(mdTv);
        outer.addView(sv);
        return outer;
    }


    // =========================================================================
    // SHARED FIND/REPLACE HELPER — used by both editor pages
    // =========================================================================

    interface FindBarHost {
        EditText getEditor();
        ScrollView getScrollView();
    }

    /**
     * Builds a Find/Replace bar and wires all logic:
     *  - Real-time highlight of all matches as user types
     *  - Auto-scroll to first match on type
     *  - ▶ / ◀ next/prev with scroll
     *  - "N / M" counter badge
     *  - Replace current / Replace All
     * Returns the bar View (GONE by default).
     */
    private LinearLayout buildFindBar(final EditText editor, final ScrollView sv) {
        final LinearLayout findBar = lh(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(ThemeManager.SURFACE2);
        findBar.setPadding(dp(8), dp(5), dp(8), dp(5));
        findBar.setVisibility(android.view.View.GONE);
        findBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Find input
        final EditText findEt = new EditText(this);
        findEt.setHint("Find...");
        findEt.setHintTextColor(0xFF404050);
        findEt.setTextColor(ThemeManager.TEXT);
        findEt.setTextSize(11f);
        findEt.setBackground(rbs(ThemeManager.SURFACE, 6, ThemeManager.BORDER));
        findEt.setPadding(dp(8), dp(5), dp(8), dp(5));
        findEt.setSingleLine(true);
        findEt.setLayoutParams(new LinearLayout.LayoutParams(0, dp(34), 1.3f));
        findEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        // Replace input
        final EditText replaceEt = new EditText(this);
        replaceEt.setHint("Replace...");
        replaceEt.setHintTextColor(0xFF404050);
        replaceEt.setTextColor(ThemeManager.TEXT);
        replaceEt.setTextSize(11f);
        replaceEt.setBackground(rbs(ThemeManager.SURFACE, 6, ThemeManager.BORDER));
        replaceEt.setPadding(dp(8), dp(5), dp(8), dp(5));
        replaceEt.setSingleLine(true);
        LinearLayout.LayoutParams reLp2 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        reLp2.leftMargin = dp(5); replaceEt.setLayoutParams(reLp2);
        replaceEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        // Counter label "3/12"
        final TextView counterTv = tv("", ThemeManager.DIM, 9.5f, false);
        counterTv.setMinWidth(dp(36));
        counterTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cntLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cntLp.leftMargin = dp(4); counterTv.setLayoutParams(cntLp);

        // ◀ prev
        TextView prevBtn = roundBtn("◀", ThemeManager.BRAND, ThemeManager.BRAND_D);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        pbLp.leftMargin = dp(4); prevBtn.setLayoutParams(pbLp); prevBtn.setGravity(Gravity.CENTER);

        // ▶ next
        TextView nextBtn2 = roundBtn("▶", ThemeManager.BRAND, ThemeManager.BRAND_D);
        LinearLayout.LayoutParams nbLp2 = new LinearLayout.LayoutParams(dp(34), dp(34));
        nbLp2.leftMargin = dp(3); nextBtn2.setLayoutParams(nbLp2); nextBtn2.setGravity(Gravity.CENTER);

        // Replace one
        TextView replBtn2 = roundBtn("↻", ThemeManager.AMBER, ThemeManager.AMBER_D);
        LinearLayout.LayoutParams rb2Lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        rb2Lp.leftMargin = dp(4); replBtn2.setLayoutParams(rb2Lp); replBtn2.setGravity(Gravity.CENTER);

        // Replace all
        TextView replAllBtn2 = roundBtn("All", ThemeManager.GREEN, ThemeManager.GREEN_D);
        LinearLayout.LayoutParams ra2Lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        ra2Lp.leftMargin = dp(3); replAllBtn2.setLayoutParams(ra2Lp); replAllBtn2.setGravity(Gravity.CENTER);

        // Close
        TextView closeFindBtn2 = tv("✕", ThemeManager.DIM, 15f, false);
        closeFindBtn2.setPadding(dp(8), dp(2), dp(4), dp(2));
        closeFindBtn2.setOnClickListener(v -> {
            findBar.setVisibility(android.view.View.GONE);
            // Clear all find highlights when closing
            android.text.Editable ed = editor.getText();
            android.text.style.BackgroundColorSpan[] spans = ed.getSpans(0, ed.length(),
                android.text.style.BackgroundColorSpan.class);
            for (android.text.style.BackgroundColorSpan sp : spans) {
                if (sp.getBackgroundColor() == 0xFFFFD700 || sp.getBackgroundColor() == 0xFFFF8C00)
                    ed.removeSpan(sp);
            }
        });

        findBar.addView(findEt);
        findBar.addView(replaceEt);
        findBar.addView(counterTv);
        findBar.addView(prevBtn);
        findBar.addView(nextBtn2);
        findBar.addView(replBtn2);
        findBar.addView(replAllBtn2);
        findBar.addView(closeFindBtn2);

        // ── State ─────────────────────────────────────────────────────────
        final int[] matchOffsets = {0}; // packed: stores flat int array via wrapper
        final java.util.List<Integer> matches = new java.util.ArrayList<>();
        final int[] currentMatch = {-1};

        // ── Scroll helper ─────────────────────────────────────────────────
        Runnable[] scrollToCurrentMatch = {null};
        scrollToCurrentMatch[0] = () -> {
            if (currentMatch[0] < 0 || currentMatch[0] >= matches.size()) return;
            int offset = matches.get(currentMatch[0]);
            String query = findEt.getText().toString();
            editor.setSelection(offset, offset + query.length());
            // Use Layout to get Y position — reliable even inside ScrollView
            sv.post(() -> {
                android.text.Layout layout = editor.getLayout();
                if (layout == null) return;
                int line = layout.getLineForOffset(offset);
                int lineTop = layout.getLineTop(line);
                int scrollTarget = Math.max(0, lineTop - sv.getHeight() / 3);
                sv.smoothScrollTo(0, scrollTarget);
            });
        };

        // ── Highlight all matches ─────────────────────────────────────────
        Runnable[] highlightAll = {null};
        highlightAll[0] = () -> {
            String query = findEt.getText().toString();
            android.text.Editable ed = editor.getText();
            // Remove previous find highlights
            android.text.style.BackgroundColorSpan[] old = ed.getSpans(0, ed.length(),
                android.text.style.BackgroundColorSpan.class);
            for (android.text.style.BackgroundColorSpan sp : old) {
                int c = sp.getBackgroundColor();
                if (c == 0xFFFFD700 || c == 0xFFFF8C00) ed.removeSpan(sp);
            }
            matches.clear();
            currentMatch[0] = -1;
            counterTv.setText("");
            if (query.isEmpty()) return;
            String text = ed.toString();
            int idx = 0;
            while ((idx = text.indexOf(query, idx)) >= 0) {
                matches.add(idx);
                idx += query.length();
            }
            // Highlight all matches in gold
            for (int mi = 0; mi < matches.size(); mi++) {
                int s2 = matches.get(mi);
                int e2 = s2 + query.length();
                ed.setSpan(new android.text.style.BackgroundColorSpan(0xFFFFD700),
                    s2, e2, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (!matches.isEmpty()) {
                currentMatch[0] = 0;
                counterTv.setText("1/" + matches.size());
                // Highlight current match in orange
                int cs = matches.get(0);
                int ce = cs + query.length();
                ed.setSpan(new android.text.style.BackgroundColorSpan(0xFFFF8C00),
                    cs, ce, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                scrollToCurrentMatch[0].run();
            } else {
                counterTv.setText("0/0");
            }
        };

        // ── Re-highlight current match in orange ──────────────────────────
        Runnable[] highlightCurrent = {null};
        highlightCurrent[0] = () -> {
            if (matches.isEmpty()) return;
            android.text.Editable ed = editor.getText();
            // Reset all to gold first
            android.text.style.BackgroundColorSpan[] spans = ed.getSpans(0, ed.length(),
                android.text.style.BackgroundColorSpan.class);
            String query = findEt.getText().toString();
            for (android.text.style.BackgroundColorSpan sp : spans) {
                if (sp.getBackgroundColor() == 0xFFFF8C00) {
                    int s2 = ed.getSpanStart(sp); ed.removeSpan(sp);
                    if (s2 >= 0 && s2 + query.length() <= ed.length())
                        ed.setSpan(new android.text.style.BackgroundColorSpan(0xFFFFD700),
                            s2, s2 + query.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            // Highlight current in orange
            if (currentMatch[0] >= 0 && currentMatch[0] < matches.size()) {
                int cs = matches.get(currentMatch[0]);
                int ce = cs + query.length();
                if (ce <= ed.length()) {
                    ed.setSpan(new android.text.style.BackgroundColorSpan(0xFFFF8C00),
                        cs, ce, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                counterTv.setText((currentMatch[0] + 1) + "/" + matches.size());
            }
        };

        // ── Real-time search as user types ────────────────────────────────
        final Handler findHandler = new Handler(Looper.getMainLooper());
        final Runnable[] findPending = {null};
        findEt.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (findPending[0] != null) findHandler.removeCallbacks(findPending[0]);
                findPending[0] = highlightAll[0];
                findHandler.postDelayed(findPending[0], 250);
            }
            public void afterTextChanged(android.text.Editable s) {}
        });

        // ── Prev / Next ───────────────────────────────────────────────────
        prevBtn.setOnClickListener(v -> {
            if (matches.isEmpty()) { highlightAll[0].run(); return; }
            currentMatch[0] = (currentMatch[0] - 1 + matches.size()) % matches.size();
            highlightCurrent[0].run();
            scrollToCurrentMatch[0].run();
        });
        nextBtn2.setOnClickListener(v -> {
            if (matches.isEmpty()) { highlightAll[0].run(); return; }
            currentMatch[0] = (currentMatch[0] + 1) % matches.size();
            highlightCurrent[0].run();
            scrollToCurrentMatch[0].run();
        });

        // ── Replace one ───────────────────────────────────────────────────
        replBtn2.setOnClickListener(v -> {
            String query = findEt.getText().toString();
            String replacement = replaceEt.getText().toString();
            if (query.isEmpty() || matches.isEmpty() || currentMatch[0] < 0) return;
            int pos = matches.get(currentMatch[0]);
            if (pos + query.length() <= editor.getText().length()) {
                editor.getText().replace(pos, pos + query.length(), replacement);
                toast("Replaced 1 match.");
            }
            highlightAll[0].run();
        });

        // ── Replace all ───────────────────────────────────────────────────
        replAllBtn2.setOnClickListener(v -> {
            String query = findEt.getText().toString();
            String replacement = replaceEt.getText().toString();
            if (query.isEmpty()) return;
            int count = matches.size();
            if (count == 0) { toast("No matches found."); return; }
            showConfirm("Replace All?",
                "Replace " + count + " occurrence(s) of \"" + query + "\" with \"" + replacement + "\"?",
                "Replace " + count, ThemeManager.BRAND, () -> {
                    String replaced = editor.getText().toString().replace(query, replacement);
                    editor.setText(replaced);
                    editor.setSelection(Math.min(editor.getText().length(), replaced.length()));
                    toast("Replaced " + count + " instance(s).");
                    matches.clear(); counterTv.setText(""); currentMatch[0] = -1;
                });
        });

        return findBar;
    }

    /** Scrolls editor to a specific line number (1-based) inside a ScrollView. */
    private void scrollEditorToLine(final EditText editor, final ScrollView sv, final int lineNum) {
        if (lineNum <= 1) return;
        // Use ViewTreeObserver so we wait until layout is truly complete
        editor.getViewTreeObserver().addOnGlobalLayoutListener(
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    editor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    try {
                        android.text.Layout layout = editor.getLayout();
                        if (layout == null) return;
                        int target = Math.min(lineNum - 1, layout.getLineCount() - 1);
                        int lineTop = layout.getLineTop(target);
                        sv.scrollTo(0, Math.max(0, lineTop - dp(80)));
                    } catch (Exception ignored) {}
                }
            });
    }

    private View buildFileEditorPage(final GHApi.RepoContent item,
                                      final String content, final String sha) {
        LinearLayout outer = lv();
        outer.setBackgroundColor(0xFF050507);
        outer.setWeightSum(1f);

        // ── FITUR 1: Undo/Redo state ──────────────────────────────────────────
        final java.util.Stack<String> undoStack = new java.util.Stack<>();
        final java.util.Stack<String> redoStack = new java.util.Stack<>();
        final int MAX_HISTORY = 50;
        final boolean[] trackingChanges = {true}; // cegah rekursi saat apply undo/redo

        // ── Header ────────────────────────────────────────────────────────────
        LinearLayout header = buildHeader2(item.name, item.path, null);
        LinearLayout ha = (LinearLayout) header.getTag();
        final TextView undoBtn = iconBtn("↩", ThemeManager.TEXT2, null);
        final TextView redoBtn = iconBtn("↪", ThemeManager.TEXT2, null);
        final TextView findBtn = iconBtn("🔍", ThemeManager.BRAND, null);
        final TextView hlBtn   = iconBtn("🎨", ThemeManager.BRAND, null);
        final TextView saveBtn = iconBtn("💾", ThemeManager.GREEN, null);
        undoBtn.setAlpha(0.4f); // disabled awalnya
        redoBtn.setAlpha(0.4f);
        ha.addView(undoBtn); ha.addView(redoBtn); ha.addView(findBtn);
        ha.addView(hlBtn);   ha.addView(saveBtn);
        outer.addView(header);

        // ── Commit message row ────────────────────────────────────────────────
        LinearLayout msgRow = lh(Gravity.CENTER_VERTICAL);
        msgRow.setBackgroundColor(ThemeManager.SURFACE);
        msgRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        msgRow.setLayoutParams(mpWrap());
        msgRow.addView(tv("Msg: ", ThemeManager.DIM, 10f, false));
        final EditText msgEt = new EditText(this);
        msgEt.setHint("Edit " + item.name);
        msgEt.setHintTextColor(0xFF404050);
        msgEt.setTextColor(ThemeManager.TEXT2);
        msgEt.setTextSize(11f);
        msgEt.setSingleLine(true);
        msgEt.setBackground(null);
        msgEt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        msgRow.addView(msgEt);
        outer.addView(msgRow);

        // ── Code editor (created first so findBar can ref it) ─────────────────
        final EditText editor = new EditText(this);
        // Set plain teks dulu agar editor langsung tampil, highlight menyusul
        editor.setText(content);
        SyntaxHighlighter.highlightAsync(content, item.name, result ->
            editor.setText(result, EditText.BufferType.SPANNABLE));
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextColor(0xFF44FF88);
        editor.setBackgroundColor(0xFF050507);
        editor.setTextSize(11.5f);
        editor.setPadding(dp(12), dp(12), dp(12), dp(60));
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setScrollContainer(false);

        final ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF050507);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        sv.addView(editor);

        // ── Shared Find/Replace bar ───────────────────────────────────────────
        final LinearLayout findBar = buildFindBar(editor, sv);
        outer.addView(findBar);

        // ── Divider ───────────────────────────────────────────────────────────
        View div = new View(this); div.setBackgroundColor(ThemeManager.BORDER);
        div.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        outer.addView(div);

        // ── Cursor status bar: "Line X / Y · Col Z  [Go to Line]" ────────────
        final LinearLayout statusBar = lh(Gravity.CENTER_VERTICAL);
        statusBar.setBackgroundColor(ThemeManager.SURFACE);
        statusBar.setPadding(dp(12), dp(4), dp(12), dp(4));
        statusBar.setLayoutParams(mpWrap());
        final TextView statusTv = tv("", ThemeManager.DIM, 9f, false);
        statusTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView goToLineBtn = tv("⇒ Go to Line", ThemeManager.BRAND, 9f, false);
        goToLineBtn.setPadding(dp(8), dp(2), dp(4), dp(2));
        statusBar.addView(statusTv);
        statusBar.addView(goToLineBtn);
        outer.addView(statusBar);

        outer.addView(sv);

        // ── Init status bar ───────────────────────────────────────────────────
        int initLines = 1;
        for (int i = 0; i < content.length(); i++) if (content.charAt(i) == '\n') initLines++;
        final int[] totalLines = {initLines};
        statusTv.setText("Line 1 / " + initLines + " · Col 1  ·  " + content.length() + " chars");

        // ── Undo/Redo setup ───────────────────────────────────────────────────
        undoStack.push(content);
        final Handler undoHandler = new Handler(Looper.getMainLooper());
        final Runnable[] reHighlightPending = {null};

        editor.addTextChangedListener(new android.text.TextWatcher() {
            private String before = "";
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                if (trackingChanges[0]) before = s.toString();
            }
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                // Update cursor status
                int sel = editor.getSelectionStart();
                String txt = s.toString();
                int line = 1, col = 1, cnt = 1;
                for (int i2 = 0; i2 < txt.length(); i2++) {
                    if (i2 == sel) { line = cnt; col = i2 - (txt.lastIndexOf('\n', i2 - 1)) ; break; }
                    if (txt.charAt(i2) == '\n') cnt++;
                }
                int totalL = 1;
                for (int i2 = 0; i2 < txt.length(); i2++) if (txt.charAt(i2) == '\n') totalL++;
                totalLines[0] = totalL;
                statusTv.setText("Line " + line + " / " + totalL + " · " + txt.length() + " chars");

                if (!trackingChanges[0]) return;
                String now = txt;
                if (now.equals(before)) return;
                if (!undoStack.isEmpty() && now.equals(undoStack.peek())) return;
                if (undoStack.size() >= MAX_HISTORY) undoStack.remove(0);
                undoStack.push(now);
                redoStack.clear();
                undoBtn.setAlpha(undoStack.size() > 1 ? 1f : 0.4f);
                redoBtn.setAlpha(0.4f);
            }
        });

        SyntaxHighlighter.attach(editor, item.name);

        // ── Undo — set plain text then defer re-highlight to avoid UI freeze ──
        undoBtn.setOnClickListener(v -> {
            if (undoStack.size() <= 1) return;
            String current = undoStack.pop();
            redoStack.push(current);
            String prev = undoStack.peek();
            trackingChanges[0] = false;
            int cursor = Math.min(editor.getSelectionStart(), prev.length());
            editor.setText(prev);
            editor.setSelection(cursor);
            trackingChanges[0] = true;
            undoBtn.setAlpha(undoStack.size() > 1 ? 1f : 0.4f);
            redoBtn.setAlpha(1f);
            // Defer syntax re-highlight so setText doesn't block the UI thread
            if (reHighlightPending[0] != null) undoHandler.removeCallbacks(reHighlightPending[0]);
            reHighlightPending[0] = () -> {
                if (prev.length() <= 60000) {
                    trackingChanges[0] = false;
                    editor.setText(prev);
                    editor.setSelection(Math.min(cursor, editor.getText().length()));
                    SyntaxHighlighter.highlightAsync(prev, item.name, result -> {
                        trackingChanges[0] = false;
                        editor.setText(result, EditText.BufferType.SPANNABLE);
                        editor.setSelection(Math.min(cursor, editor.getText().length()));
                        trackingChanges[0] = true;
                    });
                }
            };
            undoHandler.postDelayed(reHighlightPending[0], 300);
        });

        // ── Redo ──────────────────────────────────────────────────────────────
        redoBtn.setOnClickListener(v -> {
            if (redoStack.isEmpty()) return;
            String next = redoStack.pop();
            undoStack.push(next);
            trackingChanges[0] = false;
            int cursor = Math.min(editor.getSelectionStart(), next.length());
            editor.setText(next);
            editor.setSelection(cursor);
            trackingChanges[0] = true;
            undoBtn.setAlpha(1f);
            redoBtn.setAlpha(redoStack.isEmpty() ? 0.4f : 1f);
            if (reHighlightPending[0] != null) undoHandler.removeCallbacks(reHighlightPending[0]);
            reHighlightPending[0] = () -> {
                if (next.length() <= 60000) {
                    trackingChanges[0] = false;
                    editor.setText(next);
                    editor.setSelection(Math.min(cursor, editor.getText().length()));
                    SyntaxHighlighter.highlightAsync(next, item.name, result -> {
                        trackingChanges[0] = false;
                        editor.setText(result, EditText.BufferType.SPANNABLE);
                        editor.setSelection(Math.min(cursor, editor.getText().length()));
                        trackingChanges[0] = true;
                    });
                }
            };
            undoHandler.postDelayed(reHighlightPending[0], 300);
        });

        // ── Find button ───────────────────────────────────────────────────────
        findBtn.setOnClickListener(v -> {
            boolean showing = findBar.getVisibility() == android.view.View.VISIBLE;
            findBar.setVisibility(showing ? android.view.View.GONE : android.view.View.VISIBLE);
            if (!showing) {
                // Focus the find input inside the shared bar (first EditText child)
                EditText fet = (EditText) findBar.getChildAt(0);
                fet.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(fet, 0);
            }
        });

        // ── Go to Line ────────────────────────────────────────────────────────
        goToLineBtn.setOnClickListener(v -> {
            final EditText lineEt = styledInput("Line number (1–" + totalLines[0] + ")", false);
            lineEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            showViewDialog("Go to Line", lineEt, "Jump", ThemeManager.BRAND, () -> {
                String s2 = lineEt.getText().toString().trim();
                if (s2.isEmpty()) return;
                try {
                    int target = Integer.parseInt(s2);
                    scrollEditorToLine(editor, sv, target);
                } catch (NumberFormatException ignored) {}
            });
        });

        // ── Save ──────────────────────────────────────────────────────────────
        saveBtn.setOnClickListener(v -> {
            String newContent = editor.getText().toString();
            String msg = msgEt.getText().toString().trim();
            if (msg.isEmpty()) msg = "Edit " + item.name;
            final String finalMsg = msg;
            saveBtn.setText(".."); saveBtn.setEnabled(false);
            byte[] bytes = newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            GHApi.uploadFile(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                item.path, bytes, finalMsg, sha, mCurrentBranch,
                (ok, err) -> mHandler.post(() -> {
                    if (ok) {
                        toast("" + item.name + " saved");
                        onBackPressed();
                    } else {
                        saveBtn.setText("💾"); saveBtn.setEnabled(true);
                        showErr("Failed to save:\n" + err);
                    }
                }));
        });

        // ── Re-highlight ──────────────────────────────────────────────────────
        hlBtn.setOnClickListener(v -> {
            String currentText = editor.getText().toString();
            SyntaxHighlighter.highlightAsync(currentText, item.name, result -> {
                editor.setText(result, EditText.BufferType.SPANNABLE);
                editor.setSelection(editor.getText().length());
            });
            toast("Syntax refreshed.");
        });

        return outer;
    }


    // =========================================================================
    // DOWNLOAD REPO AS ZIP
    // =========================================================================

    private void doDownloadRepo() {
        toast("Downloading repo...");
        showNotif(NOTIF_UPLOAD, "Downloading Repo", mCurrentRepo + "/" + mCurrentBranch, -1, 0);

        GHApi.downloadRepo(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            mCurrentBranch,
            (dl, total) -> {
                String prog = fmtSize(dl) + (total > 0 ? " / " + fmtSize(total) : "");
                showNotif(NOTIF_UPLOAD, "Downloading Repo", prog,
                    (int)(total > 0 ? dl * 100 / total : 0), 100);
            },
            (bytes, err) -> mHandler.post(() -> {
                cancelNotif(NOTIF_UPLOAD);
                if (err != null) { showErr("Download failed:\n" + err); return; }
                try {
                    String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmm",
                        java.util.Locale.getDefault()).format(new java.util.Date());
                    String fname = mCurrentRepo + "_" + mCurrentBranch + "_" + ts + ".zip";
                    if (Build.VERSION.SDK_INT >= 29) {
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fname);
                        cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip");
                        cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                        android.net.Uri fu = getContentResolver().insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        if (fu == null) throw new Exception("MediaStore failed");
                        java.io.OutputStream os = getContentResolver().openOutputStream(fu);
                        if (os == null) throw new Exception("Stream is null");
                        os.write(bytes); os.close();
                    } else {
                        java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                        dir.mkdirs();
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(
                            new java.io.File(dir, fname));
                        fos.write(bytes); fos.close();
                    }
                    showNotifResult("✓ Repo Downloaded", "Downloads/" + fname);
                    showInfo("✓ Download Complete",
                        "Saved to Downloads/" + fname
                        + "\n\nUkuran: " + fmtSize(bytes.length));
                } catch (Exception e) { showErr("Failed to save:\n" + e.getMessage()); }
            }));
    }

    // =========================================================================
    // UI HELPERS — implementations in UiHelpers.java
    // =========================================================================

    private LinearLayout buildHeader2(String t, String s, Runnable r) { return mUi.buildHeader2(t, s, r); }
    private TextView iconBtn(String l, int c, Runnable r)              { return mUi.iconBtn(l, c, r); }
    private TextView primaryBtn(String l, int c)                       { return mUi.primaryBtn(l, c); }
    private TextView ghostBtn(String l)                                { return mUi.ghostBtn(l); }
    private TextView roundBtn(String l, int c, int b)                  { return mUi.roundBtn(l, c, b); }
    private TextView badgeChip(String t, int c, int b)                 { return mUi.badgeChip(t, c, b); }
    private View     fieldLabel(String t)                              { return mUi.fieldLabel(t); }
    private TextView secLabel(String t)                                { return mUi.secLabel(t); }
    private EditText styledInput(String h, boolean p)                  { return mUi.styledInput(h, p); }
    private View     rowDivider()                                      { return mUi.rowDivider(); }

    /** API-23-safe Runnable factory interface (replaces IntStream for API < 24) */
    interface ContentAction    { void run(java.util.List<com.gitdeploy.app.GHApi.RepoContent> items, Boolean fromCache); }
    interface StringPairAction { void run(String a, String b); }
    interface StringAction     { void run(String s); }
    interface RunnableFactory  { void create(int index); }
    private static Runnable[] makeRunnables(int size, RunnableFactory f) {
        Runnable[] r = new Runnable[size];
        for (int i = 0; i < size; i++) { final int idx = i; r[i] = () -> f.create(idx); }
        return r;
    }
    private static boolean isBooleanOptions(java.util.List<String> opts) {
        if (opts == null || opts.size() != 2) return false;
        java.util.Set<String> s = new java.util.HashSet<>();
        for (String o : opts) s.add(o.toLowerCase());
        return s.equals(new java.util.HashSet<>(java.util.Arrays.asList("true","false")));
    }

        // =========================================================================
    // SEARCH TAB (Fitur 3)
    // =========================================================================
    private View buildSearchTab() {
        LinearLayout c = lv(); c.setLayoutParams(mpWrap());

        // Info card
        LinearLayout infoCard = lv();
        infoCard.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        infoCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        icLp.bottomMargin = dp(14);
        infoCard.setLayoutParams(icLp);
        TextView infoTv = tv("Search code in this repository.\nExamples: isPremium · getSignature · void onStart", ThemeManager.TEXT2, 10.5f, false);
        infoTv.setLineSpacing(dp(1), 1.3f);
        infoCard.addView(infoTv);
        c.addView(infoCard);

        // Search input
        final EditText searchEt = styledInput("Search by method, class, or string...", false);
        searchEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams seLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        seLp.bottomMargin = dp(10);
        searchEt.setLayoutParams(seLp);
        c.addView(searchEt);

        final LinearLayout resultList = lv(); resultList.setLayoutParams(mpWrap());
        final TextView[] searchBtnRef = {null};

        final TextView searchBtn = primaryBtn("Search", ThemeManager.BRAND);
        searchBtn.setTextColor(0xFFFFFFFF);
        searchBtnRef[0] = searchBtn;
        searchBtn.setOnClickListener(v -> {
            String q = searchEt.getText().toString().trim();
            if (q.isEmpty()) { toast("Ketik kata kunci dulu"); return; }
            searchBtn.setText("Mencari..."); searchBtn.setEnabled(false);
            resultList.removeAllViews();
            resultList.addView(loadingRow());
            GHApi.searchCode(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, q,
                (results, err) -> mHandler.post(() -> {
                    searchBtn.setText("🔍  CARI"); searchBtn.setEnabled(true);
                    resultList.removeAllViews();
                    if (err != null) {
                        // GitHub search rate limit is strict
                        if (err.contains("403") || err.contains("422")) {
                            resultList.addView(errCard("Rate limit GitHub Search.\nTunggu 60 detik lalu coba lagi."));
                        } else {
                            resultList.addView(errCard(err));
                        }
                        return;
                    }
                    if (results == null || results.isEmpty()) {
                        TextView empty = tv("No results for: \"" + q + "\"", ThemeManager.DIM, 11f, false);
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding(0, dp(24), 0, dp(24));
                        resultList.addView(empty); return;
                    }
                    TextView countTv = tv(results.size() + " hasil ditemukan", ThemeManager.DIM, 10f, false);
                    LinearLayout.LayoutParams ctLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    ctLp.bottomMargin = dp(8);
                    countTv.setLayoutParams(ctLp);
                    resultList.addView(countTv);

                    LinearLayout resultCard = lv();
                    resultCard.setLayoutParams(mpWrap());
                    resultCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
                    for (int i = 0; i < results.size(); i++) {
                        GHApi.CodeSearchResult r = results.get(i);
                        resultCard.addView(buildSearchResultRow(r, q));
                        if (i < results.size() - 1) resultCard.addView(rowDivider());
                    }
                    resultList.addView(resultCard);
                }));
        });
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sbLp.bottomMargin = dp(14);
        searchBtn.setLayoutParams(sbLp);
        c.addView(searchBtn);
        c.addView(resultList);
        return c;
    }

    private View buildSearchResultRow(final GHApi.CodeSearchResult r, final String query) {
        LinearLayout row = lh(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(12), dp(13));
        row.setClickable(true);

        LinearLayout iconWrap = new LinearLayout(this);
        LinearLayout.LayoutParams iwLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        iwLp.rightMargin = dp(12); iconWrap.setLayoutParams(iwLp);
        iconWrap.setGravity(Gravity.CENTER);
        iconWrap.setBackground(rb(ThemeManager.PURPLE_D, 10));
        iconWrap.addView(tv(getFileIcon(r.name), ThemeManager.TEXT, 15f, false));

        LinearLayout info = lv();
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        info.addView(tv(r.name, ThemeManager.TEXT, 12.5f, true));
        TextView pathTv = tv(r.path, ThemeManager.DIM, 10f, false);
        pathTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        pathTv.setPadding(0, dp(2), 0, 0);
        info.addView(pathTv);

        row.addView(iconWrap); row.addView(info);
        row.addView(tv("›", ThemeManager.BORDER2, 20f, true));

        row.setOnClickListener(v -> {
            // Open the file in file editor
            GHApi.RepoContent item = new GHApi.RepoContent(r.name, r.path, "file", "", "", 0);
            openFileViewer(item);
        });
        applyRipple(row); // #5 Ripple effect
        return row;
    }

    // =========================================================================
    // SMART SYNC / APP TO GITHUB / ONE-CLICK INSTALL — SmartSyncManager.java
    // =========================================================================
    private void handleFolderSync(android.net.Uri u)               { mSync.handleFolderSync(u); }
    private void showAppToGitHubDialog()                           { mSync.showAppToGitHubDialog(); }
    private void tryInstallApkFromZip(byte[] b, String n)          { mSync.tryInstallApkFromZip(b, n); }

        // =========================================================================
    // TRACK ERROR TAB (tab 3)
    // =========================================================================
    private View buildTrackTab() {
        LinearLayout c = lv(); c.setLayoutParams(mpWrap());

        if (mTrackedErrors == null || mTrackedErrors.isEmpty()) {
            LinearLayout emptyCard = lv();
            emptyCard.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
            emptyCard.setPadding(dp(20), dp(32), dp(20), dp(32));
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setLayoutParams(mpWrap());
            TextView emptyIcon = tv("🎯", ThemeManager.TEXT, 36f, false);
            emptyIcon.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams eiLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            eiLp.bottomMargin = dp(12); emptyIcon.setLayoutParams(eiLp);
            emptyCard.addView(emptyIcon);
            TextView emptyTitle = tv("No errors tracked yet", ThemeManager.TEXT, 14f, true);
            emptyTitle.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            etLp.bottomMargin = dp(8); emptyTitle.setLayoutParams(etLp);
            emptyCard.addView(emptyTitle);
            TextView emptySub = tv("When a build fails:\n1. Open BUILDS tab\n2. Tap LOGS on the failed run\n3. Tap 📁 in the header to track error files here", ThemeManager.DIM, 11f, false);
            emptySub.setGravity(Gravity.CENTER);
            emptySub.setLineSpacing(dp(2), 1.3f);
            emptyCard.addView(emptySub);
            c.addView(emptyCard);
            return c;
        }

        // Info header
        LinearLayout infoCard = lv();
        infoCard.setBackground(rbs(ThemeManager.SURFACE, 12, ThemeManager.BORDER));
        infoCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        icLp.bottomMargin = dp(14); infoCard.setLayoutParams(icLp);
        String lastWfName = mPrefs.getLastFailedWorkflowName();
        TextView infoTv = tv("🎯  " + mTrackedErrors.size() + " file(s) caused the build failure"
            + (lastWfName.isEmpty() ? "" : " in \"" + lastWfName + "\"")
            + "\nTap a file to open the editor. After fixing → Save → Build.", ThemeManager.AMBER, 10.5f, false);
        infoTv.setLineSpacing(dp(1), 1.3f);
        infoCard.addView(infoTv);
        c.addView(infoCard);

        // Error file list
        LinearLayout fileList = lv();
        fileList.setLayoutParams(mpWrap());
        fileList.setBackground(rbs(ThemeManager.SURFACE, 14, ThemeManager.BORDER));
        LinearLayout.LayoutParams flLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flLp.bottomMargin = dp(14); fileList.setLayoutParams(flLp);

        for (int i = 0; i < mTrackedErrors.size(); i++) {
            GHApi.ErrorFileRef ref = mTrackedErrors.get(i);
            LinearLayout row = lh(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(14), dp(12), dp(14));
            row.setClickable(true);

            LinearLayout iconWrap = new LinearLayout(this);
            LinearLayout.LayoutParams iwLp = new LinearLayout.LayoutParams(dp(38), dp(38));
            iwLp.rightMargin = dp(12); iconWrap.setLayoutParams(iwLp);
            iconWrap.setGravity(Gravity.CENTER);
            iconWrap.setBackground(rb(ThemeManager.RED_D, 10));
            iconWrap.addView(tv(getFileIcon(ref.filename), ThemeManager.TEXT, 16f, false));

            LinearLayout info = lv();
            info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            info.addView(tv(ref.filename, ThemeManager.TEXT, 13f, true));

            LinearLayout metaRow = lh(Gravity.CENTER_VERTICAL);
            metaRow.setLayoutParams(mpWrap());
            LinearLayout.LayoutParams mrLp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mrLp2.topMargin = dp(3); metaRow.setLayoutParams(mrLp2);
            if (ref.lineNum > 0) {
                TextView lineBadge = badgeChip(":" + ref.lineNum, ThemeManager.RED, ThemeManager.RED_D);
                LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lbLp.rightMargin = dp(6); lineBadge.setLayoutParams(lbLp);
                metaRow.addView(lineBadge);
            }
            String preview = ref.errorMsg.length() > 48 ? ref.errorMsg.substring(0, 48) + "…" : ref.errorMsg;
            metaRow.addView(tv(preview, ThemeManager.RED, 10f, false));
            info.addView(metaRow);

            if (ref.repoPath != null) {
                TextView pathTv = tv(ref.repoPath, ThemeManager.DIM, 9.5f, false);
                pathTv.setTypeface(android.graphics.Typeface.MONOSPACE);
                LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                ptLp.topMargin = dp(2); pathTv.setLayoutParams(ptLp);
                info.addView(pathTv);
            } else {
                TextView notFound = tv("⚠ Path not resolved — open FILES tab to locate", ThemeManager.DIM, 9.5f, false);
                notFound.setPadding(0, dp(2), 0, 0); info.addView(notFound);
            }

            row.addView(iconWrap); row.addView(info);

            final GHApi.ErrorFileRef fRef = ref;

            // Edit button only
            TextView editBtn = buildActBtn("✏  Edit", ThemeManager.AMBER, ThemeManager.AMBER_D);
            LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
            editBtn.setPadding(dp(12), 0, dp(12), 0);
            editBtn.setLayoutParams(ebLp);

            row.addView(editBtn);

            editBtn.setOnClickListener(v -> openTrackedFile(fRef));
            // Quick AI Fix from track row — skip manual editor
            TextView rowAiBtn = roundBtn("🤖 Fix", ThemeManager.WARNING, ThemeManager.WARNING_D);
            LinearLayout.LayoutParams raLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            raLp.leftMargin = dp(6);
            rowAiBtn.setLayoutParams(raLp);
            rowAiBtn.setOnClickListener(v -> startAiFix(fRef, rowAiBtn));
            row.addView(rowAiBtn);
            row.setOnClickListener(v -> openTrackedFile(fRef));
            applyRipple(row); // #5 Ripple effect
            fileList.addView(row);
            if (i < mTrackedErrors.size() - 1) fileList.addView(rowDivider());
        }
        c.addView(fileList);

        TextView clearBtn = ghostBtn("✕  Clear tracked errors");
        clearBtn.setTextColor(ThemeManager.DIM);
        clearBtn.setOnClickListener(v -> { mTrackedErrors.clear(); navigateTo(() -> setContentView(buildRepoPageAtTab(3))); });
        c.addView(clearBtn);
        return c;
    }

    private void openTrackedFile(GHApi.ErrorFileRef ref) {
        if (ref.repoPath == null) { toast("Path unknown — use FILES tab to locate " + ref.filename); return; }
        android.app.Dialog prog = makeProgressDialog();
        ((TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0))
            .setText("Opening " + ref.filename + "...");
        prog.show();
        GHApi.getFileContent(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, ref.repoPath,
            (result, err) -> mHandler.post(() -> {
                prog.dismiss();
                if (err != null) { showErr("Failed to read file:\n" + err); return; }
                GHApi.RepoContent item = new GHApi.RepoContent(ref.filename, ref.repoPath, "file", result[1], "", 0);
                navigateTo(() -> setContentView(buildTrackedFileEditorPage(item, result[0], result[1], ref)));
            }));
    }

    private View buildTrackedFileEditorPage(final GHApi.RepoContent item,
                                              final String content, final String sha,
                                              final GHApi.ErrorFileRef ref) {
        LinearLayout outer = lv();
        outer.setBackgroundColor(0xFF050507);
        outer.setWeightSum(1f);

        // Remaining errors count badge in header subtitle
        int remaining = mTrackedErrors == null ? 0 : mTrackedErrors.size();
        String sub = item.path + " · line " + ref.lineNum
            + (remaining > 1 ? "  ·  " + remaining + " errors left" : "");
        LinearLayout header = buildHeader2(item.name, sub, null);
        LinearLayout ha = (LinearLayout) header.getTag();
        final TextView saveBtn = iconBtn("💾", ThemeManager.GREEN, null);
        // AI Fix button in header — triggers AI analysis of this specific error
        final TextView aiBtnHeader = iconBtn("🤖", ThemeManager.WARNING, null);
        aiBtnHeader.setOnClickListener(v -> startAiFix(ref, aiBtnHeader));
        ha.addView(aiBtnHeader);
        ha.addView(saveBtn);
        outer.addView(header);

        // Error bar — show full error message
        LinearLayout errBar = lh(Gravity.CENTER_VERTICAL);
        errBar.setBackgroundColor(0xFF0F0405);
        errBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        errBar.setLayoutParams(mpWrap());
        TextView errTv = tv("⚠  " + ref.errorMsg, ThemeManager.RED, 9.5f, false);
        errTv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        errTv.setMaxLines(2);
        errTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        errBar.addView(errTv);
        outer.addView(errBar);

        // Line number jump bar
        LinearLayout lineBar = lh(Gravity.CENTER_VERTICAL);
        lineBar.setBackgroundColor(0xFF0A080F);
        lineBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        lineBar.setLayoutParams(mpWrap());
        TextView lineLbl = tv("📍  Jumping to line " + ref.lineNum + "...", ThemeManager.BRAND, 9.5f, true);
        lineLbl.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        lineBar.addView(lineLbl);
        outer.addView(lineBar);

        // Commit message
        LinearLayout msgRow = lh(Gravity.CENTER_VERTICAL);
        msgRow.setBackgroundColor(ThemeManager.SURFACE);
        msgRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        msgRow.setLayoutParams(mpWrap());
        msgRow.addView(tv("Msg: ", ThemeManager.DIM, 10f, false));
        final EditText msgEt = new EditText(this);
        msgEt.setHint("Fix " + ref.filename + " line " + ref.lineNum);
        msgEt.setHintTextColor(0xFF404050);
        msgEt.setTextColor(ThemeManager.TEXT2);
        msgEt.setTextSize(11f);
        msgEt.setSingleLine(true);
        msgEt.setBackground(null);
        msgEt.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        msgRow.addView(msgEt);
        outer.addView(msgRow);

        View div = new View(this);
        div.setBackgroundColor(ThemeManager.BORDER);
        div.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        outer.addView(div);

        // Code editor (inside a ScrollView so we can scroll to line)
        final ScrollView editorSv = new ScrollView(this);
        editorSv.setBackgroundColor(0xFF050507);
        editorSv.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        final EditText editor = new EditText(this);

        // ── Highlight error line in red before setting text ───────────────────
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(content);
        if (ref.lineNum > 0) {
            try {
                String[] contentLines = content.split("\n", -1);
                int targetLine = Math.min(ref.lineNum - 1, contentLines.length - 1);
                int charStart = 0;
                for (int li = 0; li < targetLine; li++) charStart += contentLines[li].length() + 1;
                int charEnd = Math.min(charStart + contentLines[targetLine].length(), ssb.length());
                charStart = Math.min(charStart, ssb.length());
                if (charStart < charEnd) {
                    // Red background highlight on the error line
                    ssb.setSpan(new android.text.style.BackgroundColorSpan(0x55FF1133),
                        charStart, charEnd, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    // Red foreground color on error line (override green)
                    ssb.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF6677),
                        charStart, charEnd, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    // Bold
                    ssb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        charStart, charEnd, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } catch (Exception e) { android.util.Log.w("GitDeploy", "ex: " + e.getMessage()); }
        }
        editor.setText(ssb, EditText.BufferType.EDITABLE);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextColor(0xFF44FF88);
        editor.setBackgroundColor(0xFF050507);
        editor.setTextSize(11.5f);
        editor.setPadding(dp(12), dp(12), dp(12), dp(60));
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setSingleLine(false);
        editor.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        editorSv.addView(editor);

        // ── Find/Replace bar (shared helper) ──────────────────────────────────
        final LinearLayout tFindBar = buildFindBar(editor, editorSv);
        outer.addView(tFindBar);

        // ── Status bar ────────────────────────────────────────────────────────
        final LinearLayout tStatusBar = lh(Gravity.CENTER_VERTICAL);
        tStatusBar.setBackgroundColor(ThemeManager.SURFACE);
        tStatusBar.setPadding(dp(12), dp(4), dp(12), dp(4));
        tStatusBar.setLayoutParams(mpWrap());
        int tTotalLines = content.split("\n", -1).length;
        final TextView tStatusTv = tv("Line " + ref.lineNum + " / " + tTotalLines + " · " + content.length() + " chars",
            ThemeManager.DIM, 9f, false);
        tStatusTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView tGoToLine = tv("⇒ Go to Line", ThemeManager.BRAND, 9f, false);
        tGoToLine.setPadding(dp(8), dp(2), dp(4), dp(2));
        final int[] tTotalLinesArr = {tTotalLines};
        tGoToLine.setOnClickListener(v -> {
            final EditText lineEt = styledInput("Line number (1–" + tTotalLinesArr[0] + ")", false);
            lineEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            showViewDialog("Go to Line", lineEt, "Jump", ThemeManager.BRAND, () -> {
                String s2 = lineEt.getText().toString().trim();
                if (s2.isEmpty()) return;
                try { scrollEditorToLine(editor, editorSv, Integer.parseInt(s2)); }
                catch (NumberFormatException ignored) {}
            });
        });

        // Find button in header
        final TextView tFindBtn = iconBtn("🔍", ThemeManager.BRAND, null);
        tFindBtn.setOnClickListener(v -> {
            boolean showing = tFindBar.getVisibility() == android.view.View.VISIBLE;
            tFindBar.setVisibility(showing ? android.view.View.GONE : android.view.View.VISIBLE);
            if (!showing) {
                EditText fet = (EditText) tFindBar.getChildAt(0);
                fet.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(fet, 0);
            }
        });
        ((LinearLayout) outer.getChildAt(0).getTag()).addView(tFindBtn, 0);

        tStatusBar.addView(tStatusTv); tStatusBar.addView(tGoToLine);
        outer.addView(tStatusBar);
        outer.addView(editorSv);

        // ── Auto-scroll to error line — ViewTreeObserver (reliable) ──────────
        lineLbl.setText("📍  Line " + ref.lineNum + " — error highlighted in red");
        lineLbl.setTextColor(ThemeManager.RED);
        scrollEditorToLine(editor, editorSv, ref.lineNum);

        saveBtn.setOnClickListener(v -> {
            String newContent = editor.getText().toString();
            String msg = msgEt.getText().toString().trim();
            if (msg.isEmpty()) msg = "Fix " + ref.filename + " line " + ref.lineNum;
            final String finalMsg = msg;
            saveBtn.setEnabled(false);
            saveBtn.setText("...");
            byte[] bytes = newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            GHApi.getFileContent(mPrefs.getToken(), mCurrentOwner, mCurrentRepo, item.path,
                (latest, shaErr) -> {
                final String latestSha = (shaErr == null && latest != null) ? latest[1] : sha;
            GHApi.uploadFile(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
                item.path, bytes, finalMsg, latestSha, mCurrentBranch,
                (ok, err) -> mHandler.post(() -> {
                    saveBtn.setEnabled(true);
                    saveBtn.setText("💾");
                    if (!ok) { showErr("Save failed:\n" + err); return; }
                    mCachedContent.remove(cacheKey(item.path));
                    mCachedFiles.evictAll();
                    if (mTrackedErrors != null) mTrackedErrors.remove(ref);
                    int left = mTrackedErrors == null ? 0 : mTrackedErrors.size();
                    if (left == 0) {
                        toast("All errors fixed! Ready to build.");
                        offerBuildNow();
                    } else {
                        toast("Saved — " + left + " error file(s) remaining");
                        showMenu(ref.filename + " saved — " + left + " error(s) remaining",
                            new String[]{"Fix Next Error", "Build Now", "Back to Track"},
                            new Runnable[]{
                                () -> { GHApi.ErrorFileRef next = mTrackedErrors.get(0); onBackPressed(); openTrackedFile(next); },
                                () -> triggerLastFailedWorkflow(),
                                () -> { while (mNavStack.size() > 1) mNavStack.pop(); navigateTo(() -> setContentView(buildRepoPageAtTab(3))); }
                            });
                    }
                }));
                });
        });
        return outer;
    }

    void offerBuildNow() {
        String wfName = mPrefs.getLastFailedWorkflowName();
        String msg = wfName.isEmpty()
            ? "File saved. Do you want to trigger a new build now?"
            : "File saved. Re-run \"" + wfName + "\"?";
        showConfirm("Run Build?", msg, "Build Now", ThemeManager.BRAND, () -> triggerLastFailedWorkflow());
    }

    void triggerLastFailedWorkflow() {
        String wfName = mPrefs.getLastFailedWorkflowName();
        if (wfName.isEmpty()) { showTriggerDialog(false); return; }
        android.app.Dialog prog = makeProgressDialog();
        ((TextView)((LinearLayout)prog.findViewById(android.R.id.message)).getChildAt(0))
            .setText("Finding workflow...");
        prog.show();
        GHApi.listWorkflows(mPrefs.getToken(), mCurrentOwner, mCurrentRepo,
            (wfs, err) -> mHandler.post(() -> {
                prog.dismiss();
                if (err != null || wfs == null || wfs.isEmpty()) { showTriggerDialog(false); return; }
                GHApi.Workflow target = wfs.get(0);
                for (GHApi.Workflow wf : wfs) { if (wf.name.equals(wfName)) { target = wf; break; } }
                triggerWithInputs(target, new java.util.HashMap<>());
                toast("▶ Triggered: " + target.name);
                while (mNavStack.size() > 1) mNavStack.pop();
                navigateTo(() -> setContentView(buildRepoPageAtTab(1)));
            }));
    }


    // =========================================================================
    // AUTO-SIGN — implementations in AutoSignManager.java
    // =========================================================================
    private void maybeAutoSign(LinearLayout pageRoot)              { mAutoSign.maybeAutoSign(pageRoot); }
    private void showAutoSignBanner(LinearLayout r, String msg)    { mAutoSign.showAutoSignBanner(r, msg); }
    private void hideAutoSignBanner(LinearLayout r)                { mAutoSign.hideAutoSignBanner(r); }

        // =========================================================================
    // AI FIX — implementations in AiManager.java
    // =========================================================================
    private void startAiFix(GHApi.ErrorFileRef ref, TextView btn)  { mAi.startAiFix(ref, btn); }
    private void showGroqKeyDialog(Runnable onSaved)                { mAi.showGroqKeyDialog(onSaved); }

        // =========================================================================
    // AI CHAT TAB + BRANCH SWITCHER + STATUS BAR — implementations in AiManager.java
    // =========================================================================
    private View buildAiChatTab()                 { return mAi.buildAiChatTab(); }
    private void showBranchSwitcher(int tab)      { mAi.showBranchSwitcher(tab); }

    // ── In-App Status Bar helpers ─────────────────────────────────────────────
    private void showStatusBar(String msg, int color) { mDlg.showStatusBar(msg, color); }
    private void updateStatusBar(String msg)           { mDlg.updateStatusBar(msg); }
    private void hideStatusBar(long delayMs)           { mDlg.hideStatusBar(delayMs); }
    private View loadingRow()                          { return mDlg.loadingRow(); }
    private View errCard(String msg)                   { return mDlg.errCard(msg); }

        // =========================================================================
    // CUSTOM STYLED DIALOGS — implementations in DialogHelper.java
    // =========================================================================
    private void showMenu(String t, String[] i, Runnable[] a)            { mDlg.showMenu(t, i, a); }
    private void showConfirm(String t, String m, String p, int c, Runnable r) { mDlg.showConfirm(t, m, p, c, r); }
    private void showConfirm(String t, String m, String p, Runnable r)    { mDlg.showConfirm(t, m, p, r); }
    private void showAlert(String t, String m)                            { mDlg.showAlert(t, m); }
    private void showViewDialog(String t, android.view.View v, String p, int c, Runnable r) { mDlg.showViewDialog(t, v, p, c, r); }
    private android.app.Dialog makeProgressDialog()                       { return mDlg.makeProgressDialog(); }

        // =========================================================================
    // NOTIFICATIONS — implementations in NotificationHelper.java
    // =========================================================================
    private void setupNotifications() { mNotif.setupNotifications(); }
    @SuppressWarnings({"deprecation","NewApi"})
    private void showNotif(int id, String t, String txt, int p, int m)   { mNotif.showNotif(id, t, txt, p, m); }
    @SuppressWarnings({"deprecation","NewApi"})
    private void showNotifResult(String t, String txt)                    { mNotif.showNotifResult(t, txt); }
    private void cancelNotif(int id)                                      { mNotif.cancelNotif(id); }
    private void toast(String msg)                                        { mDlg.toast(msg); }
    private void showErr(String msg)                                      { mDlg.showErr(msg); }
    private void showInfo(String t, String m)                             { mDlg.showInfo(t, m); }
    private String getFileIcon(String name)                               { return mNotif.getFileIcon(name); }
    private String fmtSize(long bytes)                                    { return mNotif.fmtSize(bytes); }

        // =========================================================================
    // RIPPLE + PRIMITIVE HELPERS — implementations in UiHelpers.java
    // =========================================================================

    private void applyRipple(android.view.View v, int c) { mUi.applyRipple(v, c); }
    private void applyRipple(android.view.View v)         { mUi.applyRipple(v); }
    private void applyRippleRounded(android.view.View v)  { mUi.applyRippleRounded(v); }
    private LinearLayout lv()                              { return mUi.lv(); }
    private LinearLayout lh(int g)                         { return mUi.lh(g); }
    private TextView tv(String t, int c, float s, boolean b) { return mUi.tv(t, c, s, b); }
    private View sp(int d)                                 { return mUi.sp(d); }
    private int dp(int v)                                  { return mUi.dp(v); }
    private GradientDrawable rb(int c, int r)              { return mUi.rb(c, r); }
    private GradientDrawable rbs(int c, int r, int s)      { return mUi.rbs(c, r, s); }
    private LinearLayout.LayoutParams mpWrap()             { return mUi.mpWrap(); }
    private android.view.View buildExpiredPage()           { return mUi.buildExpiredPage(); }


}