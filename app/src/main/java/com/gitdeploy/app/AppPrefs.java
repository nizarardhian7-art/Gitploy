package com.gitdeploy.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * AppPrefs — EncryptedSharedPreferences wrapper.
 *
 * Update v4: Multi-Account Support
 *   Akun disimpan sebagai JSON array di key "accounts":
 *   [{"token":"ghp_...","username":"alice","avatar":"https://..."},...]
 *   Akun aktif ditandai dengan "active_account_index".
 *
 * Update v4: Theme persistence
 *   Theme disimpan di key "app_theme" (int: 0=Dark, 1=AMOLED, 2=Light).
 */
public class AppPrefs {

    private static final String TAG  = "AppPrefs";
    private static final String FILE = "gitdeploy_secure_v2";

    // ── Preference keys ───────────────────────────────────────────────────────
    private static final String KEY_TOKEN          = "gh_token";
    private static final String KEY_USERNAME       = "gh_username";
    private static final String KEY_LAST_REPO      = "last_repo";
    private static final String KEY_ACCOUNTS       = "accounts";
    private static final String KEY_ACTIVE_ACCOUNT = "active_account_index";
    private static final String KEY_THEME          = "app_theme";

    private final SharedPreferences prefs;

    public AppPrefs(Context ctx) {
        SharedPreferences p = null;
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = EncryptedSharedPreferences.create(
                    ctx.getApplicationContext(), FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.e(TAG, "EncryptedSharedPreferences unavailable; falling back", e);
        }
        prefs = p != null ? p
            : ctx.getSharedPreferences(FILE + "_fallback", Context.MODE_PRIVATE);
    }

    // ── Active credentials (single-account compat API) ────────────────────────
    public void saveToken(String token)       { prefs.edit().putString(KEY_TOKEN, token).apply(); }
    public String getToken()                  { return prefs.getString(KEY_TOKEN, ""); }
    public boolean hasToken()                 { return !getToken().isEmpty(); }

    public void saveUsername(String username) { prefs.edit().putString(KEY_USERNAME, username).apply(); }
    public String getUsername()               { return prefs.getString(KEY_USERNAME, ""); }

    public void saveLastRepo(String repo)     { prefs.edit().putString(KEY_LAST_REPO, repo).apply(); }
    public String getLastRepo()               { return prefs.getString(KEY_LAST_REPO, ""); }

    // =========================================================================
    // FITUR 2: MULTI-ACCOUNT SUPPORT
    // =========================================================================

    /** Model satu akun GitHub. */
    public static class Account {
        public String token;
        public String username;
        public String avatarUrl;

        public Account(String token, String username, String avatarUrl) {
            this.token     = token;
            this.username  = username != null ? username : "";
            this.avatarUrl = avatarUrl != null ? avatarUrl : "";
        }

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("token", token);
                o.put("username", username);
                o.put("avatar", avatarUrl);
                return o;
            } catch (Exception e) { return new JSONObject(); }
        }

        public static Account fromJson(JSONObject o) {
            return new Account(
                o.optString("token", ""),
                o.optString("username", ""),
                o.optString("avatar", "")
            );
        }
    }

    /** Ambil semua akun tersimpan. */
    public List<Account> getAccounts() {
        List<Account> list = new ArrayList<>();
        String raw = prefs.getString(KEY_ACCOUNTS, "");
        if (raw.isEmpty()) {
            // Migrasi: jika ada akun lama (single-account), masukkan ke list
            String tok = getToken();
            String usr = getUsername();
            if (!tok.isEmpty()) list.add(new Account(tok, usr, ""));
            return list;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++)
                list.add(Account.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return list;
    }

    /** Simpan seluruh list akun. */
    public void saveAccounts(List<Account> accounts) {
        try {
            JSONArray arr = new JSONArray();
            for (Account a : accounts) arr.put(a.toJson());
            prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Tambah akun baru. Jika username sudah ada, update token-nya. */
    public void addOrUpdateAccount(Account newAcc) {
        List<Account> list = getAccounts();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).username.equalsIgnoreCase(newAcc.username)) {
                list.set(i, newAcc); saveAccounts(list); return;
            }
        }
        list.add(newAcc);
        saveAccounts(list);
    }

    /** Remove account by username. If list becomes empty, clear all credentials. */
    public void removeAccount(String username) {
        List<Account> list = getAccounts();
        list.removeIf(a -> a.username.equalsIgnoreCase(username));
        if (list.isEmpty()) {
            // No accounts left — wipe all credentials so app goes to setup screen
            prefs.edit()
                .remove(KEY_ACCOUNTS)
                .remove(KEY_TOKEN)
                .remove(KEY_USERNAME)
                .remove(KEY_ACTIVE_ACCOUNT)
                .apply();
            return;
        }
        saveAccounts(list);
        // If active account was removed, switch to first remaining
        int idx = getActiveAccountIndex();
        if (idx >= list.size()) setActiveAccountIndex(0);
    }

    /** Index akun aktif (default 0). */
    public int getActiveAccountIndex() {
        return prefs.getInt(KEY_ACTIVE_ACCOUNT, 0);
    }
    public void setActiveAccountIndex(int idx) {
        prefs.edit().putInt(KEY_ACTIVE_ACCOUNT, idx).apply();
        // Sync KEY_TOKEN / KEY_USERNAME agar kode lama tetap bekerja
        List<Account> list = getAccounts();
        if (idx < list.size()) {
            saveToken(list.get(idx).token);
            saveUsername(list.get(idx).username);
        }
    }

    /**
     * Switch ke akun berdasarkan index, update active token + username.
     * Return true jika berhasil.
     */
    public boolean switchToAccount(int idx) {
        List<Account> list = getAccounts();
        if (idx < 0 || idx >= list.size()) return false;
        setActiveAccountIndex(idx);
        return true;
    }

    // ── Signing config ────────────────────────────────────────────────────────
    public void saveSigningAlias(String v)     { prefs.edit().putString("sign_alias", v).apply(); }
    public String getSigningAlias()            { return prefs.getString("sign_alias", ""); }
    public void saveSigningStorePass(String v) { prefs.edit().putString("sign_store_pass", v).apply(); }
    public String getSigningStorePass()        { return prefs.getString("sign_store_pass", ""); }
    public void saveSigningKeyPass(String v)   { prefs.edit().putString("sign_key_pass", v).apply(); }
    public String getSigningKeyPass()          { return prefs.getString("sign_key_pass", ""); }
    public void saveSigningEnabled(boolean v)  { prefs.edit().putBoolean("sign_enabled", v).apply(); }
    public boolean isSigningEnabled()          { return prefs.getBoolean("sign_enabled", false); }
    public boolean hasSigningConfig()          { return !getSigningAlias().isEmpty() && !getSigningStorePass().isEmpty(); }

    // ── Per-repo signing ──────────────────────────────────────────────────────
    private String repoKey(String owner, String repo) { return "repo_sign_" + owner + "_" + repo; }
    public void saveRepoSignAlias(String o, String r, String v)     { prefs.edit().putString(repoKey(o,r)+"_alias",v).apply(); }
    public String getRepoSignAlias(String o, String r)              { return prefs.getString(repoKey(o,r)+"_alias",""); }
    public void saveRepoSignPass(String o, String r, String v)      { prefs.edit().putString(repoKey(o,r)+"_pass",v).apply(); }
    public String getRepoSignPass(String o, String r)               { return prefs.getString(repoKey(o,r)+"_pass",""); }
    public void saveRepoSignConfigured(String o, String r, boolean v){ prefs.edit().putBoolean(repoKey(o,r)+"_done",v).apply(); }
    public boolean isRepoSignConfigured(String o, String r)         { return prefs.getBoolean(repoKey(o,r)+"_done",false); }

    // ── Last failed workflow ──────────────────────────────────────────────────
    public void saveLastFailedWorkflowName(String name) { prefs.edit().putString("last_wf_name", name).apply(); }
    public String getLastFailedWorkflowName()           { return prefs.getString("last_wf_name", ""); }

    // ── AI settings ───────────────────────────────────────────────────────────
    public void saveAiProvider(String v)      { prefs.edit().putString("ai_provider", v).apply(); }
    public String getAiProvider()             { return prefs.getString("ai_provider", "groq"); }
    public void saveAiKey(String prov, String key) { prefs.edit().putString("ai_key_" + prov, key).apply(); }
    public String getAiKey(String prov)       { return prefs.getString("ai_key_" + prov, ""); }
    public String getActiveAiKey()            { return getAiKey(getAiProvider()); }
    public boolean hasActiveAiKey()           { return !getActiveAiKey().isEmpty(); }
    public void saveGroqKey(String v)         { saveAiKey("groq", v); }
    public String getGroqKey()                { return getActiveAiKey(); }
    public boolean hasGroqKey()               { return !getAiKey("groq").isEmpty(); }
    public void saveAiModel(String v)         { prefs.edit().putString("ai_model", v).apply(); }
    public String getAiModel()                { return prefs.getString("ai_model", ""); }

    // ── FITUR 3: Theme ────────────────────────────────────────────────────────
    public void saveTheme(int theme)  { prefs.edit().putInt(KEY_THEME, theme).apply(); }
    public int  getTheme() {
        int t = prefs.getInt(KEY_THEME, ThemeManager.THEME_DARK);
        // Clamp: hanya 0 (Dark) dan 1 (Light) valid sejak v2
        // Nilai lama (misal AMOLED=1 di versi lama yang kini dihapus) fallback ke Dark
        return (t == ThemeManager.THEME_LIGHT) ? ThemeManager.THEME_LIGHT : ThemeManager.THEME_DARK;
    }

    public void clear() { prefs.edit().clear().apply(); }
}
