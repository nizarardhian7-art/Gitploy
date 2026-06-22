package com.gitdeploy.app;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import retrofit2.Call;
import retrofit2.Response;

public class GHApi {

    private static final String BASE = "https://api.github.com";
    private static final String TAG  = "GHApi";

    public interface Callback<T>  { void onResult(T result, String error); }
    public interface ProgressCallback { void onProgress(long downloaded, long total); }

    public static class Repo {
        public String name, fullName, description, defaultBranch;
        public boolean isPrivate;
        public Repo(String n, String fn, String d, boolean p, String branch) {
            name = n; fullName = fn; description = d; isPrivate = p; defaultBranch = branch;
        }
    }

    public static class WorkflowRun {
        public long   id;
        public String name, status, conclusion, createdAt, headBranch, htmlUrl;
        public WorkflowRun(long id, String name, String status, String conclusion,
                           String createdAt, String branch, String url) {
            this.id = id; this.name = name; this.status = status;
            this.conclusion = conclusion; this.createdAt = createdAt;
            this.headBranch = branch; this.htmlUrl = url;
        }
    }

    public static class Workflow {
        public long   id;
        public String name, path, state;
        public Workflow(long id, String name, String path, String state) {
            this.id = id; this.name = name; this.path = path; this.state = state;
        }
    }

    public static class Artifact {
        public long   id, sizeInBytes;
        public String name, createdAt;
        public boolean expired;
        public Artifact(long id, String name, long size, String created, boolean expired) {
            this.id = id; this.name = name; this.sizeInBytes = size;
            this.createdAt = created; this.expired = expired;
        }
    }

    // ── Workflow Input (for dynamic form) ────────────────────────────────────
    public static class WorkflowInput {
        public String key, description, defaultValue, type;
        public boolean required;
        public List<String> options;
        public WorkflowInput(String key) {
            this.key = key;
            this.description = "";
            this.defaultValue = "";
            this.type = "string";
            this.required = false;
            this.options = new ArrayList<>();
        }
    }

    /**
     * Fetch raw YAML content of a workflow file.
     * path = e.g. ".github/workflows/build.yml"
     */
    public static void getWorkflowYaml(final String token, final String owner,
                                        final String repo, final String path,
                                        final Callback<String> cb) {
        getFileContent(token, owner, repo, path, (result, err) -> {
            if (err != null) { cb.onResult(null, err); return; }
            cb.onResult(result != null ? result[0] : "", null);
        });
    }

    /**
     * Parse "workflow_dispatch.inputs" from raw YAML text.
     * Supports types: string, boolean, choice (with options list).
     * No external YAML library — pure line-by-line state machine.
     */
    public static List<WorkflowInput> parseWorkflowInputs(String yaml) {
        List<WorkflowInput> result = new ArrayList<>();
        if (yaml == null || yaml.isEmpty()) return result;

        String[] lines = yaml.split("\n");
        boolean inWorkflowDispatch = false;
        boolean inInputs           = false;
        boolean inOptions          = false;
        int wdIndent      = -1;
        int inputsIndent  = -1;
        int keyIndent     = -1;
        WorkflowInput current = null;

        for (String rawLine : lines) {
            String line = rawLine.replace("\r", "");
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int indent = countLeadingSpaces(line);

            if (!inWorkflowDispatch) {
                if (trimmed.equals("workflow_dispatch:") || trimmed.startsWith("workflow_dispatch:")) {
                    inWorkflowDispatch = true;
                    wdIndent = indent;
                }
                continue;
            }

            // Left the workflow_dispatch block
            if (indent <= wdIndent && !trimmed.startsWith("#") && !inInputs) {
                if (!trimmed.equals("inputs:")) break;
            }

            if (!inInputs) {
                if (trimmed.equals("inputs:")) {
                    inInputs = true;
                    inputsIndent = indent;
                }
                continue;
            }

            // Left the inputs block
            if (indent <= inputsIndent) break;

            // Determine indent of input keys
            if (keyIndent == -1) {
                if (!trimmed.startsWith("-") && trimmed.endsWith(":")) keyIndent = indent;
                else continue;
            }

            if (indent == keyIndent && trimmed.endsWith(":") && !trimmed.startsWith("-")) {
                // New input key
                if (current != null) result.add(current);
                current = new WorkflowInput(trimmed.substring(0, trimmed.length() - 1).trim());
                inOptions = false;
            } else if (indent > keyIndent && current != null) {
                if (trimmed.startsWith("options:")) {
                    inOptions = true;
                } else if (inOptions && trimmed.startsWith("- ")) {
                    String opt = trimmed.substring(2).trim()
                        .replace("'", "").replace("\"", "");
                    if (!opt.isEmpty()) current.options.add(opt);
                } else {
                    if (!trimmed.startsWith("-")) inOptions = false;
                    int colonIdx = trimmed.indexOf(':');
                    if (colonIdx > 0) {
                        String fk = trimmed.substring(0, colonIdx).trim();
                        String fv = trimmed.substring(colonIdx + 1).trim()
                            .replace("'", "").replace("\"", "");
                        switch (fk) {
                            case "description": current.description = fv;          break;
                            case "default":     current.defaultValue = fv;         break;
                            case "type":        current.type = fv;                 break;
                            case "required":    current.required = "true".equals(fv); break;
                        }
                    }
                }
            } else if (indent < keyIndent) {
                break;
            }
        }

        if (current != null) result.add(current);
        return result;
    }

    private static int countLeadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    public static class RepoContent {
        public String name, path, type, sha, downloadUrl;
        public long size;
        public RepoContent(String name, String path, String type, String sha,
                           String downloadUrl, long size) {
            this.name = name; this.path = path; this.type = type;
            this.sha = sha; this.downloadUrl = downloadUrl; this.size = size;
        }
    }

    // ── Auth header ──────────────────────────────────────────────────────────

    private static void setAuth(HttpURLConnection conn, String token) {
        conn.setRequestProperty("Authorization", "token " + token);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
    }

    // ── GET helper ───────────────────────────────────────────────────────────

    private static String get(String url, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            setAuth(conn, token);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String body = readStream(is);
            if (code >= 400) throw new Exception("HTTP " + code + ": " + body);
            return body;
        } finally { conn.disconnect(); }
    }

    // ── POST helper ──────────────────────────────────────────────────────────

    private static String post(String url, String token, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            setAuth(conn, token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp = readStream(is);
            if (code >= 400) throw new Exception("HTTP " + code + ": " + resp);
            return resp;
        } finally { conn.disconnect(); }
    }

    // ── PUT helper ───────────────────────────────────────────────────────────

    private static String put(String url, String token, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("PUT");
            setAuth(conn, token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp = readStream(is);
            if (code >= 400) throw new Exception("HTTP " + code + ": " + resp);
            return resp;
        } finally { conn.disconnect(); }
    }

    // ── DELETE helper ────────────────────────────────────────────────────────

    private static int delete(String url, String token, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("DELETE");
            setAuth(conn, token);
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            return conn.getResponseCode();
        } finally { conn.disconnect(); }
    }

    // ── Read stream ──────────────────────────────────────────────────────────

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        return sb.toString();
    }

    public static byte[] readStreamBytes(InputStream is) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    // =========================================================================
    // REPOS
    // =========================================================================

    /**
     * IMPROVEMENT #4 — PAGINATION
     * List ALL repos for the authenticated user.
     * Uses page-by-page fetching (50 per page) so accounts with 100+ repos are
     * fully loaded instead of silently cut off at 100.
     */
    public static void listRepos(final String token, final Callback<List<Repo>> cb) {
        // ── MIGRATED to Retrofit ─────────────────────────────────────────
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                GitHubApiService service = GitHubApiClient.create(token);

                List<Repo> all = new ArrayList<>();
                int page = 1;
                final int PAGE_SIZE = 50;
                final int MAX_REPOS = 1000; // safety cap

                while (all.size() < MAX_REPOS) {
                    Call<List<RepoDto>> call = service.listRepos(PAGE_SIZE, "updated", page);
                    Response<List<RepoDto>> response = call.execute();   // sync — sudah di background thread

                    // ── Error handling ────────────────────────────────────
                    if (!response.isSuccessful()) {
                        int code = response.code();
                        String errBody = response.errorBody() != null
                            ? response.errorBody().string() : "(empty)";

                        String msg;
                        switch (code) {
                            case 401: msg = "Token tidak valid atau expired (401)"; break;
                            case 403: msg = "Akses ditolak — cek scope token (403)"; break;
                            case 404: msg = "Endpoint tidak ditemukan (404)"; break;
                            case 422: msg = "Request tidak valid: " + errBody + " (422)"; break;
                            case 429: msg = "Rate limit GitHub tercapai, coba lagi nanti (429)"; break;
                            case 500: case 502: case 503:
                                msg = "GitHub server error (" + code + ")"; break;
                            default:  msg = "HTTP " + code + ": " + errBody;
                        }
                        cb.onResult(null, msg);
                        return;
                    }

                    List<RepoDto> page_data = response.body();
                    if (page_data == null || page_data.isEmpty()) break; // no more pages

                    for (RepoDto dto : page_data) {
                        all.add(dto.toRepo());
                    }

                    if (page_data.size() < PAGE_SIZE) break; // last partial page
                    page++;
                }

                cb.onResult(all, null);
            } catch (Exception e) {
                Log.e(TAG, "listRepos error", e);
                cb.onResult(null, e.getMessage());
            }
        });
    }

    /**
     * Legacy implementation menggunakan HttpURLConnection.
     * Tetap tersedia sebagai fallback selama masa transisi.
     * @deprecated Gunakan listRepos() yang sudah pakai Retrofit.
     */
    @Deprecated

    /** Create a new repo. */
    public static void createRepo(final String token, final String name,
                                   final boolean isPrivate, final Callback<Repo> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("name", name);
                body.put("private", isPrivate);
                body.put("auto_init", true);
                String resp = post(BASE + "/user/repos", token, body.toString());
                JSONObject o = new JSONObject(resp);
                cb.onResult(new Repo(
                    o.getString("name"),
                    o.getString("full_name"),
                    o.optString("description", ""),
                    o.getBoolean("private"),
                    o.optString("default_branch", "main")
                ), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    /**
     * IMPROVEMENT #4 — PAGINATION
     * List ALL branches in a repo (paginated, 50 per page).
     * Repos with 100+ branches (monorepos, feature-heavy projects) no longer
     * silently truncate at the old per_page=100 hard limit.
     */
    public static void listBranches(final String token, final String owner,
                                     final String repo, final Callback<List<String>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                List<String> all = new ArrayList<>();
                int page = 1;
                final int PAGE_SIZE = 50;

                while (all.size() < 500) { // cap at 500 branches
                    String url = BASE + "/repos/" + owner + "/" + repo
                            + "/branches?per_page=" + PAGE_SIZE + "&page=" + page;
                    String resp = get(url, token);
                    JSONArray arr = new JSONArray(resp);
                    if (arr.length() == 0) break;

                    for (int i = 0; i < arr.length(); i++) {
                        all.add(arr.getJSONObject(i).getString("name"));
                    }
                    if (arr.length() < PAGE_SIZE) break;
                    page++;
                }
                cb.onResult(all, null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    /** Delete a repo. */
    /** Toggle repo visibility between public and private. */
    public static void setRepoVisibility(final String token, final String owner,
                                          final String repo, final boolean makePrivate,
                                          final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("private", makePrivate);
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(BASE + "/repos/" + owner + "/" + repo).openConnection();
                conn.setRequestMethod("PATCH");
                setAuth(conn, token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code == 200) cb.onResult(true, null);
                else cb.onResult(false, "HTTP " + code);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    /** Get repo visibility: returns "public" or "private". */
    public static void getRepoVisibility(final String token, final String owner,
                                          final String repo, final Callback<String> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo, token);
                boolean isPrivate = new JSONObject(resp).optBoolean("private", false);
                cb.onResult(isPrivate ? "private" : "public", null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void deleteRepo(final String token, final String owner,
                                   final String repo, final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                int code = delete(BASE + "/repos/" + owner + "/" + repo, token, null);
                cb.onResult(code == 204, code == 204 ? null : "HTTP " + code);
            } catch (Exception e) {
                cb.onResult(false, e.getMessage());
            }
        });
    }

    /** Validate token + get username. */
    public static void validateToken(final String token, final Callback<String> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/user", token);
                JSONObject o = new JSONObject(resp);
                cb.onResult(o.getString("login"), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    // =========================================================================
    // REPO CONTENTS
    // =========================================================================

    /** List contents of a path in repo. */
    public static void listContents(final String token, final String owner,
                                     final String repo, final String path,
                                     final Callback<List<RepoContent>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String encodedPath = path.isEmpty() ? "" : "/" + path;
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/contents" + encodedPath, token);
                List<RepoContent> list = new ArrayList<>();
                // Response can be array (dir) or object (file)
                if (resp.trim().startsWith("[")) {
                    JSONArray arr = new JSONArray(resp);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        list.add(new RepoContent(
                            o.getString("name"),
                            o.getString("path"),
                            o.getString("type"),
                            o.optString("sha", ""),
                            o.optString("download_url", ""),
                            o.optLong("size", 0)
                        ));
                    }
                } else {
                    JSONObject o = new JSONObject(resp);
                    list.add(new RepoContent(
                        o.getString("name"),
                        o.getString("path"),
                        o.getString("type"),
                        o.optString("sha", ""),
                        o.optString("download_url", ""),
                        o.optLong("size", 0)
                    ));
                }
                cb.onResult(list, null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    /**
     * Upload or update a file in repo.
     * fileBytes = raw file content.
     * sha = existing file SHA (null if creating new file).
     */
    public static void uploadFile(final String token, final String owner,
                                   final String repo, final String path,
                                   final byte[] fileBytes, final String message,
                                   final String sha, final String branch,
                                   final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String encoded = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
                JSONObject body = new JSONObject();
                body.put("message", message);
                body.put("content", encoded);
                body.put("branch", branch);
                if (sha != null && !sha.isEmpty()) body.put("sha", sha);
                put(BASE + "/repos/" + owner + "/" + repo + "/contents/" + path,
                    token, body.toString());
                cb.onResult(true, null);
            } catch (Exception e) {
                cb.onResult(false, e.getMessage());
            }
        });
    }

    /** Delete a file from repo. */
    public static void deleteFile(final String token, final String owner,
                                   final String repo, final String path,
                                   final String sha, final String branch,
                                   final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("message", "Delete " + path);
                body.put("sha", sha);
                body.put("branch", branch);
                int code = delete(BASE + "/repos/" + owner + "/" + repo
                    + "/contents/" + path, token, body.toString());
                cb.onResult(code == 200 || code == 204,
                    (code == 200 || code == 204) ? null : "HTTP " + code);
            } catch (Exception e) {
                cb.onResult(false, e.getMessage());
            }
        });
    }

    // =========================================================================
    // WORKFLOWS & RUNS
    // =========================================================================

    /** List workflows in repo. */
    public static void listWorkflows(final String token, final String owner,
                                      final String repo, final Callback<List<Workflow>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/actions/workflows", token);
                JSONObject root = new JSONObject(resp);
                JSONArray arr  = root.getJSONArray("workflows");
                List<Workflow> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new Workflow(
                        o.getLong("id"),
                        o.getString("name"),
                        o.getString("path"),
                        o.getString("state")
                    ));
                }
                cb.onResult(list, null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }


    /** List recent workflow runs (up to 30). */
    public static void listRuns(final String token, final String owner,
                                 final String repo, final Callback<List<WorkflowRun>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/actions/runs?per_page=30", token);
                JSONObject root = new JSONObject(resp);
                JSONArray arr   = root.getJSONArray("workflow_runs");
                List<WorkflowRun> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new WorkflowRun(
                        o.getLong("id"),
                        o.getString("name"),
                        o.getString("status"),
                        o.isNull("conclusion") ? "—" : o.getString("conclusion"),
                        o.getString("created_at"),
                        o.getString("head_branch"),
                        o.getString("html_url")
                    ));
                }
                cb.onResult(list, null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    /**
     * Download workflow run logs.
     * GitHub returns a redirect to the actual zip — we follow it and return text content.
     */
    public static void getRunLogs(final String token, final String owner,
                                   final String repo, final long runId,
                                   final Callback<String> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // Step 1: get redirect URL
                HttpURLConnection conn = (HttpURLConnection) new URL(
                    BASE + "/repos/" + owner + "/" + repo
                    + "/actions/runs/" + runId + "/logs").openConnection();
                conn.setRequestMethod("GET");
                setAuth(conn, token);
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                int code = conn.getResponseCode();

                if (code == 302 || code == 307 || code == 301) {
                    String location = conn.getHeaderField("Location");
                    // Step 2: download the zip from redirect URL (no auth needed)
                    HttpURLConnection conn2 = (HttpURLConnection)
                        new URL(location).openConnection();
                    conn2.setConnectTimeout(20000);
                    conn2.setReadTimeout(60000);
                    int code2 = conn2.getResponseCode();
                    if (code2 == 200) {
                        byte[] zipBytes = readStreamBytes(conn2.getInputStream());
                        // Extract text from zip
                        String text = extractLogsFromZip(zipBytes);
                        cb.onResult(text, null);
                    } else {
                        cb.onResult(null, "Download failed HTTP " + code2);
                    }
                } else if (code == 200) {
                    byte[] zipBytes = readStreamBytes(conn.getInputStream());
                    cb.onResult(extractLogsFromZip(zipBytes), null);
                } else {
                    cb.onResult(null, "HTTP " + code);
                }
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    /**
     * Extract all text files from a workflow log zip, concatenated.
     *
     * IMPROVEMENT #4 — OOM PROTECTION
     * Workflow logs from long builds can exceed 10 MB.  Dumping that into a
     * single TextView causes OutOfMemoryError on low-RAM devices (≤2 GB).
     * We cap the final output at MAX_LOG_CHARS and prepend a truncation notice
     * when the limit is reached so the user knows the log was cut.
     */
    private static final int MAX_LOG_CHARS = 300_000; // ≈ 300 KB plain text

    private static String extractLogsFromZip(byte[] zipBytes) {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes));
            java.util.zip.ZipEntry entry;
            byte[] buf = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    // Stop accumulating if we are close to the limit
                    if (sb.length() >= MAX_LOG_CHARS) {
                        sb.append("\n\n[... log truncated — only last ")
                          .append(MAX_LOG_CHARS / 1024).append(" KB shown ...]");
                        zis.closeEntry(); break;
                    }
                    sb.append("\n━━━ ").append(entry.getName()).append(" ━━━\n");
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                        if (sb.length() + bos.size() > MAX_LOG_CHARS) break;
                    }
                    String chunk = bos.toString("UTF-8");
                    // If appending this chunk would exceed the limit, trim it
                    int remaining = MAX_LOG_CHARS - sb.length();
                    sb.append(chunk.length() > remaining
                        ? chunk.substring(0, remaining) : chunk);
                }
                zis.closeEntry();
            }
            zis.close();
        } catch (Exception e) {
            sb.append("\n[Error reading zip: ").append(e.getMessage()).append("]");
        }

        // Final safety net: if somehow we still exceed limit, tail-truncate
        if (sb.length() > MAX_LOG_CHARS) {
            return "[Log truncated — showing last "
                + MAX_LOG_CHARS / 1024 + " KB]\n\n"
                + sb.substring(sb.length() - MAX_LOG_CHARS);
        }
        return sb.toString();
    }

    // =========================================================================
    // ARTIFACTS
    // =========================================================================

    /** List artifacts for a workflow run. */
    public static void listArtifacts(final String token, final String owner,
                                      final String repo, final long runId,
                                      final Callback<List<Artifact>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/actions/runs/" + runId + "/artifacts", token);
                JSONObject root = new JSONObject(resp);
                JSONArray arr   = root.getJSONArray("artifacts");
                List<Artifact> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new Artifact(
                        o.getLong("id"),
                        o.getString("name"),
                        o.getLong("size_in_bytes"),
                        o.getString("created_at"),
                        o.getBoolean("expired")
                    ));
                }
                cb.onResult(list, null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    /** Download artifact zip with progress reporting. */
    public static void downloadArtifact(final String token, final String owner,
                                         final String repo, final long artifactId,
                                         final ProgressCallback progress,
                                         final Callback<byte[]> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(
                    BASE + "/repos/" + owner + "/" + repo
                    + "/actions/artifacts/" + artifactId + "/zip").openConnection();
                conn.setRequestMethod("GET");
                setAuth(conn, token);
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(120000);
                int code = conn.getResponseCode();

                HttpURLConnection dlConn;
                if (code == 302 || code == 307 || code == 301) {
                    String location = conn.getHeaderField("Location");
                    dlConn = (HttpURLConnection) new URL(location).openConnection();
                    dlConn.setConnectTimeout(20000);
                    dlConn.setReadTimeout(180000);
                } else {
                    dlConn = conn;
                }

                if (dlConn.getResponseCode() != 200) {
                    cb.onResult(null, "HTTP " + dlConn.getResponseCode()); return;
                }

                long total = dlConn.getContentLengthLong();
                InputStream is = dlConn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                long downloaded = 0; int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    downloaded += n;
                    if (progress != null) progress.onProgress(downloaded, total);
                }
                cb.onResult(bos.toByteArray(), null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    /**
     * Hapus folder secara rekursif — list semua file lalu hapus satu per satu.
     * callback: onResult(deletedCount, errorMessage)
     */
    public static void deleteFolderRecursive(final String token, final String owner,
                                              final String repo, final String path,
                                              final String branch,
                                              final Callback<Integer> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                final int[] count = {0};
                deleteFolderRecursiveSync(token, owner, repo, path, branch, count);
                cb.onResult(count[0], null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    private static void deleteFolderRecursiveSync(String token, String owner, String repo,
                                                   String path, String branch,
                                                   int[] count) throws Exception {
        String resp = get("https://api.github.com/repos/" + owner + "/" + repo
            + "/contents/" + path, token);

        JSONArray items = new JSONArray(resp);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String type     = item.getString("type");
            String itemPath = item.getString("path");

            if ("dir".equals(type)) {
                // Rekursi ke subfolder
                deleteFolderRecursiveSync(token, owner, repo, itemPath, branch, count);
            } else {
                // Hapus file
                String sha = item.getString("sha");
                JSONObject body = new JSONObject();
                body.put("message", "Delete " + itemPath);
                body.put("sha", sha);
                body.put("branch", branch);
                int code = delete("https://api.github.com/repos/" + owner + "/" + repo
                    + "/contents/" + itemPath, token, body.toString());
                if (code == 200 || code == 204) count[0]++;
                // Kecil delay untuk hindari rate limit
                Thread.sleep(150);
            }
        }
    }

    // =========================================================================
    // GITHUB ACTIONS VARIABLES (tidak perlu enkripsi seperti Secrets)
    // =========================================================================

    /** Set atau update sebuah Actions Variable. */
    public static void setVariable(final String token, final String owner,
                                    final String repo, final String name,
                                    final String value, final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String url = BASE + "/repos/" + owner + "/" + repo
                    + "/actions/variables/" + name;
                HttpURLConnection check = (HttpURLConnection) new URL(url).openConnection();
                check.setRequestMethod("GET");
                setAuth(check, token);
                check.setConnectTimeout(8000); check.setReadTimeout(8000);
                boolean exists = check.getResponseCode() == 200;

                JSONObject body = new JSONObject();
                body.put("name", name);
                body.put("value", value);

                if (exists) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("PATCH");
                    setAuth(conn, token);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    int code = conn.getResponseCode();
                    cb.onResult(code == 204 || code == 200, code == 204 || code == 200 ? null : "HTTP " + code);
                } else {
                    post(BASE + "/repos/" + owner + "/" + repo + "/actions/variables",
                        token, body.toString());
                    cb.onResult(true, null);
                }
            } catch (Exception e) {
                cb.onResult(false, e.getMessage());
            }
        });
    }

    /**
     * Setup auto-sign:
     * 1. Upload keystore ke .signing/release.jks di repo
     * 2. Set 4 Actions Variables: SIGNING_ALIAS, SIGNING_STORE_PASS, SIGNING_KEY_PASS, SIGNING_KEY_PATH
     */

    // =========================================================================
    // KEYSTORE GENERATION (BouncyCastle)
    // =========================================================================

    /**
     * Generate a PKCS12 keystore with an RSA-2048 self-signed certificate.
     * Returns the keystore bytes, or throws on failure.
     *
     * @param alias      Key alias (e.g. "release")
     * @param password   Keystore + key password
     * @param commonName Display name for certificate (e.g. "My App")
     */
    public static byte[] generateKeystore(String alias, String password,
                                           String commonName) throws Exception {
        // 1. Generate RSA-2048 key pair
        java.security.KeyPairGenerator kpg =
            java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new java.security.SecureRandom());
        java.security.KeyPair kp = kpg.generateKeyPair();

        // 2. Build X.509 self-signed certificate with BouncyCastle
        org.bouncycastle.asn1.x500.X500Name subject =
            new org.bouncycastle.asn1.x500.X500Name("CN=" + commonName
                + ", O=GitDeploy, C=US");
        java.math.BigInteger serial = java.math.BigInteger.valueOf(
            System.currentTimeMillis());

        java.util.Date notBefore = new java.util.Date();
        // Valid for 25 years
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.YEAR, 25);
        java.util.Date notAfter = cal.getTime();

        org.bouncycastle.cert.X509v3CertificateBuilder certBuilder =
            new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, kp.getPublic());

        org.bouncycastle.operator.ContentSigner signer =
            new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder(
                "SHA256withRSA").build(kp.getPrivate());

        org.bouncycastle.cert.jcajce.JcaX509CertificateConverter converter =
            new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter();
        java.security.cert.X509Certificate cert =
            converter.getCertificate(certBuilder.build(signer));

        // 3. Create PKCS12 KeyStore
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, kp.getPrivate(), password.toCharArray(),
            new java.security.cert.Certificate[]{cert});

        // 4. Export to bytes
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ks.store(bos, password.toCharArray());
        return bos.toByteArray();
    }

    // =========================================================================
    // ERROR FILE TRACKING
    // =========================================================================

    public static class ErrorFileRef {
        public String filename;   // e.g. "MainActivity.java"
        public String repoPath;   // full path in repo, resolved after git tree lookup
        public int    lineNum;
        public String errorMsg;
        public ErrorFileRef(String filename, int lineNum, String errorMsg) {
            this.filename = filename; this.lineNum = lineNum; this.errorMsg = errorMsg;
        }
    }

    // ── Error classifier ─────────────────────────────────────────────────────
    public enum ErrorType { SYNTAX, STRUCTURAL, CROSS_FILE }

    public static ErrorType classifyError(String errorMsg) {
        String e = errorMsg.toLowerCase();
        if (e.contains("cannot find symbol") || e.contains("must implement")
            || e.contains("is not abstract")  || e.contains("incompatible types")
            || e.contains("bad return type")   || e.contains("no suitable method")
            || e.contains("method does not override") || e.contains("cannot be applied"))
            return ErrorType.CROSS_FILE;
        if (e.contains("end of file") || e.contains("reached end") || e.contains("unclosed")
            || e.contains("class, interface") || e.contains("illegal start")
            || e.contains("expected '}'")     || e.contains("';' expected")
            || e.contains("missing return"))
            return ErrorType.STRUCTURAL;
        return ErrorType.SYNTAX;
    }

    /**
     * Finds the enclosing method/block boundary around errorLine.
     * Returns { excerpt, startLine (1-based), totalLines }.
     * Capped at 150 lines to stay within Groq free token budget.
     */
    public static String[] extractMethodBoundary(String fileContent, int errorLine) {
        String[] lines = fileContent.split("\n", -1);
        int idx = Math.min(Math.max(errorLine - 1, 0), lines.length - 1);

        // Scan upward — when brace depth goes negative we found the enclosing {
        int depth = 0;
        int blockStart = Math.max(0, idx - 5);
        outer:
        for (int i = idx; i >= 0; i--) {
            String ln = lines[i];
            for (int j = ln.length() - 1; j >= 0; j--) {
                char c = ln.charAt(j);
                if (c == '}')      depth++;
                else if (c == '{') {
                    if (depth == 0) { blockStart = i; break outer; }
                    depth--;
                }
            }
        }

        // Walk blockStart upward to include method signature lines
        int sigStart = blockStart;
        while (sigStart > 0) {
            String prev = lines[sigStart - 1].trim();
            if (prev.isEmpty() || prev.endsWith("}") || prev.endsWith(";")) break;
            sigStart--;
        }

        // Scan downward from sigStart to find matching closing }
        int openBraces = 0;
        int blockEnd = Math.min(lines.length - 1, idx + 5);
        for (int i = sigStart; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if      (c == '{') openBraces++;
                else if (c == '}') { if (--openBraces == 0) { blockEnd = i; i = lines.length; break; } }
            }
        }

        // Cap at 150 lines — center on error line
        final int MAX = 150;
        if (blockEnd - sigStart + 1 > MAX) {
            sigStart = Math.max(0, errorLine - 1 - MAX / 2);
            blockEnd = Math.min(lines.length - 1, sigStart + MAX - 1);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = sigStart; i <= blockEnd; i++) sb.append(lines[i]).append("\n");
        return new String[]{ sb.toString(), String.valueOf(sigStart + 1), String.valueOf(lines.length) };
    }

    /**
     * Extract symbol name from error: "cannot find symbol: method foo()" → "foo"
     */
    public static String extractSymbolName(String errorMsg) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "symbol:\\s+(?:method|class|variable)\\s+(\\w+)").matcher(errorMsg);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("method\\s+(\\w+)").matcher(errorMsg);
        if (m.find()) return m.group(1);
        return "";
    }

    /**
     * Search fileContent for a declaration of symbolName.
     * Returns up to ~10 lines around the first match, or empty string.
     */
    public static String extractSymbolDeclaration(String fileContent, String symbolName) {
        if (symbolName == null || symbolName.isEmpty()) return "";
        String[] lines = fileContent.split("\n", -1);
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(public|private|protected|static|interface|class|void|@Override).*\\b"
            + java.util.regex.Pattern.quote(symbolName) + "\\b");
        for (int i = 0; i < lines.length; i++) {
            if (p.matcher(lines[i]).find()) {
                int end = Math.min(lines.length, i + 10);
                StringBuilder sb = new StringBuilder();
                for (int j = Math.max(0, i - 1); j < end; j++) sb.append(lines[j]).append("\n");
                return sb.toString();
            }
        }
        return "";
    }

    /**
     * Parse error file references from a root-cause log string.
     * Extracts entries like:  MainActivity.java:76: error: variable already defined
     */
    /** Strip GitHub Actions log timestamp prefix: "2024-01-01T00:00:00.000Z " */
    private static String stripTimestamp(String line) {
        // Format: "2026-03-21T15:40:06.5276475Z "
        if (line.length() > 28 && line.charAt(4) == '-' && line.contains("T") && line.contains("Z ")) {
            int zSpace = line.indexOf("Z ");
            if (zSpace > 0 && zSpace < 35) return line.substring(zSpace + 2);
        }
        return line;
    }

    public static List<ErrorFileRef> parseErrorFiles(String log) {
        List<ErrorFileRef> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "([A-Za-z0-9_$]+\\.(?:java|kt|xml|gradle)):(\\d+):[ \\t]*(?:error|warning):(.*)");
        String[] rawLines = log.split("\n");
        for (int li = 0; li < rawLines.length; li++) {
            String line = stripTimestamp(rawLines[li]).trim();
            // Also handle full path: /home/runner/.../File.java:76: error:
            if (line.contains("/") && line.contains(".java:")) {
                int lastSlash = line.lastIndexOf('/');
                line = line.substring(lastSlash + 1);
            }
            java.util.regex.Matcher m = p.matcher(line);
            if (m.find()) {
                String fname = m.group(1);
                int ln = 0;
                try { ln = Integer.parseInt(m.group(2)); } catch (Exception ignored2) {}
                String msg = m.group(3).trim();

                // ── Capture "symbol:" and "location:" detail lines that follow ─
                // Compiler emits these after "cannot find symbol" errors.
                // GitHub Actions prepends timestamps — strip them first.
                StringBuilder extra = new StringBuilder();
                for (int look = li + 1; look <= li + 4 && look < rawLines.length; look++) {
                    String next = stripTimestamp(rawLines[look]).trim();
                    if (next.startsWith("symbol:") || next.startsWith("location:")) {
                        extra.append(" ").append(next);
                    } else if (next.startsWith("^") || next.isEmpty()) {
                        // caret pointer or blank — stop here
                        break;
                    }
                }
                if (extra.length() > 0) msg = msg + extra;

                String key = fname + ":" + ln;
                if (!seen.contains(key)) {
                    seen.add(key);
                    result.add(new ErrorFileRef(fname, ln, msg));
                }
            }
        }
        return result;
    }

    /**
     * Resolve full repo paths for ErrorFileRef list using the git tree.
     * Sets repoPath on each ref where the filename matches a blob in the tree.
     */
    public static void resolveFilePaths(final String token, final String owner,
                                         final String repo, final String branch,
                                         final List<ErrorFileRef> refs,
                                         final Callback<List<ErrorFileRef>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/git/trees/" + branch + "?recursive=1", token);
                JSONArray tree = new JSONObject(resp).getJSONArray("tree");
                for (int i = 0; i < tree.length(); i++) {
                    JSONObject o = tree.getJSONObject(i);
                    if (!"blob".equals(o.optString("type"))) continue;
                    String fullPath = o.getString("path");
                    String name = fullPath.contains("/")
                        ? fullPath.substring(fullPath.lastIndexOf('/') + 1) : fullPath;
                    for (ErrorFileRef ref : refs) {
                        if (ref.repoPath == null && ref.filename.equals(name)) {
                            ref.repoPath = fullPath;
                        }
                    }
                }
            } catch (Exception ignored) {}
            cb.onResult(refs, null);
        });
    }

    public static void setupSigning(final String token, final String owner,
                                     final String repo, final String branch,
                                     final byte[] keystoreBytes,
                                     final String alias,
                                     final String storePass,
                                     final String keyPass,
                                     final Callback<String> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // Step 1: Upload keystore file
                final String ksPath = ".signing/release.jks";
                String sha = null;
                try {
                    String ex = get(BASE + "/repos/" + owner + "/" + repo
                        + "/contents/" + ksPath, token);
                    sha = new JSONObject(ex).optString("sha", null);
                } catch (Exception ignored) {}

                JSONObject ub = new JSONObject();
                ub.put("message", "Update signing keystore [skip ci]");
                ub.put("content", Base64.encodeToString(keystoreBytes, Base64.NO_WRAP));
                ub.put("branch", branch);
                if (sha != null) ub.put("sha", sha);
                put(BASE + "/repos/" + owner + "/" + repo + "/contents/" + ksPath,
                    token, ub.toString());

                // Step 2: Set variables
                String[] vNames  = {"SIGNING_KEY_PATH", "SIGNING_ALIAS",
                                    "SIGNING_STORE_PASS", "SIGNING_KEY_PASS"};
                String[] vValues = {ksPath, alias, storePass, keyPass};
                for (int i = 0; i < vNames.length; i++) {
                    JSONObject vb = new JSONObject();
                    vb.put("name", vNames[i]); vb.put("value", vValues[i]);
                    String vUrl = BASE + "/repos/" + owner + "/" + repo
                        + "/actions/variables/" + vNames[i];
                    HttpURLConnection vc = (HttpURLConnection) new URL(vUrl).openConnection();
                    vc.setRequestMethod("GET"); setAuth(vc, token);
                    vc.setConnectTimeout(8000); vc.setReadTimeout(8000);
                    if (vc.getResponseCode() == 200) {
                        HttpURLConnection pc = (HttpURLConnection) new URL(vUrl).openConnection();
                        pc.setRequestMethod("PATCH"); setAuth(pc, token);
                        pc.setRequestProperty("Content-Type", "application/json");
                        pc.setDoOutput(true);
                        try (OutputStream os = pc.getOutputStream()) {
                            os.write(vb.toString().getBytes(StandardCharsets.UTF_8));
                        }
                        pc.getResponseCode();
                    } else {
                        post(BASE + "/repos/" + owner + "/" + repo + "/actions/variables",
                            token, vb.toString());
                    }
                }

                // Step 3: Patch app/build.gradle to inject signingConfigs
                patchBuildGradleSync(token, owner, repo, branch);

                // Step 4: Patch all workflow yml files to expose signing env vars
                patchWorkflowYmlsSync(token, owner, repo, branch);

                cb.onResult("ok", null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    /**
     * Read app/build.gradle and inject signingConfigs block if not already present.
     * Uses System.getenv() so the env vars from workflow are readable by Gradle.
     */
    private static void patchBuildGradleSync(String token, String owner,
                                              String repo, String branch) {
        try {
            String gradlePath = "app/build.gradle";
            String resp = get(BASE + "/repos/" + owner + "/" + repo
                + "/contents/" + gradlePath + "?ref=" + branch, token);
            JSONObject obj = new JSONObject(resp);
            String sha = obj.optString("sha", null);
            String enc = obj.optString("content", "").replace("\n", "").replace("\r", "");
            String content = new String(Base64.decode(enc, Base64.DEFAULT), StandardCharsets.UTF_8);

            // If already patched, check if old broken syntax (without =) needs repair
            if (content.contains("signingConfigs")) {
                if (content.contains("signingConfig signingConfigs.release")
                        && !content.contains("signingConfig = signingConfigs.release")) {
                    // Repair: replace old AGP7 syntax with AGP8-compatible syntax
                    content = content.replace(
                        "signingConfig signingConfigs.release",
                        "signingConfig = signingConfigs.release");
                    JSONObject body = new JSONObject();
                    body.put("message", "Auto-patch: fix signingConfig syntax for AGP8 [skip ci]");
                    body.put("content", Base64.encodeToString(
                        content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                    body.put("branch", branch);
                    if (sha != null) body.put("sha", sha);
                    put(BASE + "/repos/" + owner + "/" + repo + "/contents/" + gradlePath,
                        token, body.toString());
                }
                return;
            }

            String signingBlock =
                "\n    signingConfigs {\n" +
                "        release {\n" +
                "            def sp = System.getenv(\'SIGNING_KEY_PATH\')\n" +
                "            storeFile sp ? file(sp) : null\n" +
                "            storePassword System.getenv(\'SIGNING_STORE_PASS\') ?: \'\'\n" +
                "            keyAlias System.getenv(\'SIGNING_ALIAS\') ?: \'\'\n" +
                "            keyPassword System.getenv(\'SIGNING_KEY_PASS\') ?: \'\'\n" +
                "        }\n" +
                "    }\n";

            // Insert signingConfigs after FIRST "android {" only (use replaceFirst
            // to avoid corrupting files that mention "android {" in comments/plugins)
            content = content.replaceFirst("android \\{", "android {" + signingBlock);

            // Add signingConfig reference inside release buildType.
            // Use "signingConfig = ..." (with =) — required by AGP 8.x.
            if (content.contains("release {") && !content.contains("signingConfig")) {
                content = content.replaceFirst(
                    "release \\{",
                    "release {\n            signingConfig = signingConfigs.release"
                );
            }

            // Upload patched file
            JSONObject body = new JSONObject();
            body.put("message", "Auto-patch: add signing config [skip ci]");
            body.put("content", Base64.encodeToString(
                content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            body.put("branch", branch);
            if (sha != null) body.put("sha", sha);
            put(BASE + "/repos/" + owner + "/" + repo + "/contents/" + gradlePath,
                token, body.toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to patch build.gradle: " + e.getMessage(), e);
        }
    }

    /**
     * List all workflow yml files and inject signing env vars at job level
     * (after runs-on:) so Gradle can read them via System.getenv().
     */
    private static void patchWorkflowYmlsSync(String token, String owner,
                                               String repo, String branch) {
        try {
            String resp = get(BASE + "/repos/" + owner + "/" + repo
                + "/contents/.github/workflows", token);
            JSONArray files = new JSONArray(resp);
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String name = f.getString("name");
                if (!name.endsWith(".yml") && !name.endsWith(".yaml")) continue;
                String path = f.getString("path");
                try {
                    String fr = get(BASE + "/repos/" + owner + "/" + repo
                        + "/contents/" + path + "?ref=" + branch, token);
                    JSONObject fo = new JSONObject(fr);
                    String fsha = fo.optString("sha", null);
                    String fenc = fo.optString("content","").replace("\n","").replace("\r","");
                    String yml = new String(Base64.decode(fenc, Base64.DEFAULT), StandardCharsets.UTF_8);

                    if (yml.contains("SIGNING_ALIAS")) continue; // already patched

                    String patched = injectSigningEnvIntoYml(yml);
                    if (patched.equals(yml)) continue; // nothing to patch

                    JSONObject fb = new JSONObject();
                    fb.put("message", "Auto-patch: inject signing env vars [skip ci]");
                    fb.put("content", Base64.encodeToString(
                        patched.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                    fb.put("branch", branch);
                    if (fsha != null) fb.put("sha", fsha);
                    put(BASE + "/repos/" + owner + "/" + repo + "/contents/" + path,
                        token, fb.toString());
                } catch (Exception e2) { /* skip individual yml file, best-effort */ }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to patch workflow YAMLs: " + e.getMessage(), e);
        }
    }

    /** Inject signing env block after "runs-on:" in each job definition. */
    private static String injectSigningEnvIntoYml(String yml) {
        String[] lines = yml.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append("\n");
            String trimmed = line.trim();
            if (trimmed.startsWith("runs-on:")) {
                // Detect indentation level of runs-on:
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                String pad = "                    ".substring(0, indent);
                out.append(pad).append("env:\n");
                out.append(pad).append("  SIGNING_ALIAS: ${{ vars.SIGNING_ALIAS }}\n");
                out.append(pad).append("  SIGNING_STORE_PASS: ${{ vars.SIGNING_STORE_PASS }}\n");
                out.append(pad).append("  SIGNING_KEY_PASS: ${{ vars.SIGNING_KEY_PASS }}\n");
                out.append(pad).append("  SIGNING_KEY_PATH: ${{ vars.SIGNING_KEY_PATH }}\n");
            }
        }
        return out.toString();
    }

    // =========================================================================
    // SECRETS
    // =========================================================================

    public static class Secret {
        public String name, createdAt, updatedAt;
        public Secret(String name, String created, String updated) {
            this.name = name; this.createdAt = created; this.updatedAt = updated;
        }
    }

    /** List repo secrets (names only — values never returned by GitHub). */
    public static void listSecrets(final String token, final String owner,
                                    final String repo, final Callback<List<Secret>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/actions/secrets", token);
                JSONArray arr = new JSONObject(resp).getJSONArray("secrets");
                List<Secret> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new Secret(o.getString("name"),
                        o.optString("created_at",""), o.optString("updated_at","")));
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }


    /** Delete a secret. */
    public static void deleteSecret(final String token, final String owner,
                                     final String repo, final String name,
                                     final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                int code = delete(BASE + "/repos/" + owner + "/" + repo
                    + "/actions/secrets/" + name, token, null);
                cb.onResult(code == 204, code == 204 ? null : "HTTP " + code);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    /** List repo variables. */
    public static void listVariables(final String token, final String owner,
                                      final String repo, final Callback<List<String[]>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/actions/variables?per_page=100", token);
                JSONArray arr = new JSONObject(resp).getJSONArray("variables");
                List<String[]> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new String[]{o.getString("name"), o.optString("value","")});
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    /** Delete a variable. */
    public static void deleteVariable(final String token, final String owner,
                                       final String repo, final String name,
                                       final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                int code = delete(BASE + "/repos/" + owner + "/" + repo
                    + "/actions/variables/" + name, token, null);
                cb.onResult(code == 204, code == 204 ? null : "HTTP " + code);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    // =========================================================================
    // WORKFLOW RUN MANAGEMENT
    // =========================================================================

    /** Cancel a single run. */
    public static void cancelRun(final String token, final String owner,
                                  final String repo, final long runId,
                                  final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                    new java.net.URL(BASE + "/repos/" + owner + "/" + repo
                        + "/actions/runs/" + runId + "/cancel").openConnection();
                conn.setRequestMethod("POST");
                setAuth(conn, token);
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
                try (OutputStream os = conn.getOutputStream()) { os.write("{}".getBytes(StandardCharsets.UTF_8)); }
                int code = conn.getResponseCode();
                cb.onResult(code == 202 || code == 200, code == 202 || code == 200 ? null : "HTTP " + code);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    /** Delete a completed workflow run. */
    public static void deleteRun(final String token, final String owner,
                                  final String repo, final long runId,
                                  final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                int code = delete(BASE + "/repos/" + owner + "/" + repo
                    + "/actions/runs/" + runId, token, null);
                cb.onResult(code == 204, code == 204 ? null : "HTTP " + code);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    // =========================================================================
    // WORKFLOW RUN BULK MANAGEMENT  (fixed: pagination + two-phase)
    // =========================================================================

    /**
     * Cancel active runs. If deleteAll=true, also deletes ALL completed runs
     * (including those just cancelled) in a second pass.
     * Paginated — handles repos with >100 runs.
     */
    public static void cancelAllRuns(final String token, final String owner,
                                      final String repo, final boolean deleteAll,
                                      final Callback<int[]> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                List<Long> toCancel     = new ArrayList<>();
                List<Long> alreadyDone = new ArrayList<>();

                // Phase 0 — paginate to collect ALL runs
                for (int page = 1; ; page++) {
                    String resp = get(BASE + "/repos/" + owner + "/" + repo
                        + "/actions/runs?per_page=100&page=" + page, token);
                    JSONArray arr = new JSONObject(resp).getJSONArray("workflow_runs");
                    if (arr.length() == 0) break;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o  = arr.getJSONObject(i);
                        long   id     = o.getLong("id");
                        String status = o.getString("status");
                        boolean active = "in_progress".equals(status)
                                      || "queued".equals(status)
                                      || "waiting".equals(status);
                        if (active) {
                            toCancel.add(id);
                        } else if ("completed".equals(status) || "skipped".equals(status)) {
                            alreadyDone.add(id);
                        }
                    }
                    if (arr.length() < 100) break;
                }

                // Phase 1 — cancel active runs
                int cancelled = 0;
                for (long id : toCancel) {
                    try {
                        HttpURLConnection conn = (HttpURLConnection)
                            new URL(BASE + "/repos/" + owner + "/" + repo
                                + "/actions/runs/" + id + "/cancel").openConnection();
                        conn.setRequestMethod("POST");
                        setAuth(conn, token);
                        conn.setDoOutput(true);
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write("{}".getBytes(StandardCharsets.UTF_8));
                        }
                        if (conn.getResponseCode() == 202) cancelled++;
                    } catch (Exception ignored) {}
                    Thread.sleep(150);
                }

                if (!deleteAll) {
                    cb.onResult(new int[]{cancelled, 0}, null);
                    return;
                }

                // Phase 2 — wait for GitHub to process cancellations
                if (!toCancel.isEmpty()) Thread.sleep(4500);

                // Phase 3 — delete all: already-completed + newly-cancelled
                List<Long> toDelete = new ArrayList<>(alreadyDone);
                toDelete.addAll(toCancel);
                int deleted = 0;
                for (long id : toDelete) {
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try {
                            int code = delete(BASE + "/repos/" + owner + "/" + repo
                                + "/actions/runs/" + id, token, null);
                            if (code == 204) { deleted++; break; }
                            if (code == 409) Thread.sleep(2500L * (attempt + 1)); // still cancelling
                            else break;
                        } catch (Exception ignored) { break; }
                    }
                    Thread.sleep(120);
                }

                final int fc = cancelled, fd = deleted;
                cb.onResult(new int[]{fc, fd}, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    /** Delete only completed runs (no cancel). Paginated. */
    public static void deleteCompletedRuns(final String token, final String owner,
                                            final String repo, final Callback<int[]> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                List<Long> toDelete = new ArrayList<>();
                for (int page = 1; ; page++) {
                    String resp = get(BASE + "/repos/" + owner + "/" + repo
                        + "/actions/runs?per_page=100&page=" + page, token);
                    JSONArray arr = new JSONObject(resp).getJSONArray("workflow_runs");
                    if (arr.length() == 0) break;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o  = arr.getJSONObject(i);
                        String status = o.getString("status");
                        if ("completed".equals(status) || "skipped".equals(status))
                            toDelete.add(o.getLong("id"));
                    }
                    if (arr.length() < 100) break;
                }
                int deleted = 0;
                for (long id : toDelete) {
                    try {
                        if (delete(BASE + "/repos/" + owner + "/" + repo
                                + "/actions/runs/" + id, token, null) == 204) deleted++;
                    } catch (Exception ignored) {}
                    Thread.sleep(120);
                }
                cb.onResult(new int[]{0, deleted}, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    // =========================================================================
    // RELIABLE SYNC UPLOAD  (for sequential batch uploads)
    // =========================================================================

    /**
     * Synchronous file upload — fetches existing SHA, uploads with PUT,
     * retries on conflict / rate-limit / transient errors.
     * Must be called from a background thread.
     *
     * @return true on success
     */
    public static boolean uploadFileSync(String token, String owner, String repo,
                                          String path, byte[] content,
                                          String message, String branch, int maxRetry) {
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                // 1. Get existing SHA (needed to update existing file)
                String sha = null;
                try {
                    HttpURLConnection chk = (HttpURLConnection)
                        new URL(BASE + "/repos/" + owner + "/" + repo
                            + "/contents/" + path).openConnection();
                    chk.setRequestMethod("GET");
                    setAuth(chk, token);
                    chk.setConnectTimeout(12000);
                    chk.setReadTimeout(12000);
                    if (chk.getResponseCode() == 200) {
                        String body = readStream(chk.getInputStream());
                        sha = new JSONObject(body).optString("sha", null);
                    }
                } catch (Exception ignored) {}

                // 2. Build request body
                JSONObject body = new JSONObject();
                body.put("message", message);
                body.put("content", Base64.encodeToString(content, Base64.NO_WRAP));
                body.put("branch", branch);
                if (sha != null && !sha.isEmpty()) body.put("sha", sha);

                // 3. PUT
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(BASE + "/repos/" + owner + "/" + repo
                        + "/contents/" + path).openConnection();
                conn.setRequestMethod("PUT");
                setAuth(conn, token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(90000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code == 200 || code == 201) return true;

                // Retriable errors
                if ((code == 409 || code == 422 || code >= 500) && attempt < maxRetry) {
                    Thread.sleep(1500L * (attempt + 1));
                    continue;
                }
                if (code == 429 && attempt < maxRetry) {          // rate-limited
                    String retryAfter = conn.getHeaderField("Retry-After");
                    long wait = retryAfter != null ? Long.parseLong(retryAfter) * 1000L : 8000L;
                    Thread.sleep(wait);
                    continue;
                }
                Log.w(TAG, "uploadFileSync HTTP " + code + " path=" + path);
                return false;

            } catch (Exception e) {
                Log.w(TAG, "uploadFileSync attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < maxRetry) {
                    try { Thread.sleep(1500L * (attempt + 1)); } catch (Exception ignored) {}
                }
            }
        }
        return false;
    }

    // =========================================================================
    // FILE CONTENT VIEW / EDIT
    // =========================================================================

    /** Fetch a single file's decoded text + sha. Returns String[]{text, sha}. */
    public static void getFileContent(final String token, final String owner,
                                       final String repo, final String path,
                                       final Callback<String[]> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/contents/" + path, token);
                JSONObject o   = new JSONObject(resp);
                String sha     = o.optString("sha", "");
                String enc     = o.optString("encoding", "");
                String content = o.optString("content", "");
                if ("base64".equals(enc)) {
                    content = content.replace("\n", "").replace("\r", "");
                    byte[] bytes = Base64.decode(content, Base64.DEFAULT);
                    content = new String(bytes, StandardCharsets.UTF_8);
                }
                cb.onResult(new String[]{content, sha}, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    // =========================================================================
    // DOWNLOAD REPO AS ZIP
    // =========================================================================

    /** Download entire repo as zipball with progress. */
    public static void downloadRepo(final String token, final String owner,
                                     final String repo, final String branch,
                                     final ProgressCallback progress,
                                     final Callback<byte[]> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(
                    BASE + "/repos/" + owner + "/" + repo + "/zipball/" + branch).openConnection();
                conn.setRequestMethod("GET");
                setAuth(conn, token);
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(300000);
                int code = conn.getResponseCode();

                HttpURLConnection dlConn;
                if (code == 302 || code == 301 || code == 307) {
                    dlConn = (HttpURLConnection) new URL(conn.getHeaderField("Location")).openConnection();
                    dlConn.setConnectTimeout(20000);
                    dlConn.setReadTimeout(300000);
                } else { dlConn = conn; }
                if (dlConn.getResponseCode() != 200) {
                    cb.onResult(null, "HTTP " + dlConn.getResponseCode()); return;
                }
                long total = dlConn.getContentLengthLong();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384]; long dl = 0; int n;
                try (InputStream is = dlConn.getInputStream()) {
                    while ((n = is.read(buf)) != -1) {
                        bos.write(buf, 0, n); dl += n;
                        if (progress != null) progress.onProgress(dl, total);
                    }
                }
                cb.onResult(bos.toByteArray(), null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    // =========================================================================
    // CODE SEARCH
    // =========================================================================

    public static class CodeSearchResult {
        public String name, path, htmlUrl;
        public CodeSearchResult(String name, String path, String htmlUrl) {
            this.name = name; this.path = path; this.htmlUrl = htmlUrl;
        }
    }

    /** Search code within a repo. */
    public static void searchCode(final String token, final String owner,
                                   final String repo, final String query,
                                   final Callback<List<CodeSearchResult>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String q = java.net.URLEncoder.encode(query + " repo:" + owner + "/" + repo, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(BASE + "/search/code?q=" + q + "&per_page=30").openConnection();
                conn.setRequestMethod("GET");
                setAuth(conn, token);
                conn.setConnectTimeout(15000); conn.setReadTimeout(30000);
                int code = conn.getResponseCode();
                String body = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                if (code >= 400) { cb.onResult(null, "HTTP " + code + ": " + body); return; }
                JSONArray items = new JSONObject(body).getJSONArray("items");
                List<CodeSearchResult> list = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    list.add(new CodeSearchResult(
                        o.getString("name"),
                        o.getString("path"),
                        o.optString("html_url", "")
                    ));
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    // =========================================================================
    // GIT TREE (for Smart Sync)
    // =========================================================================

    public static class GitTreeItem {
        public String path, sha;
        public GitTreeItem(String path, String sha) { this.path = path; this.sha = sha; }
    }


    /**
     * Clear all repo contents EXCEPT .github/ folder.
     * Uses recursive tree API to get all blobs, then deletes each file
     * that is not under .github/.
     * Progress callback: (deleted, total, currentPath)
     */
    public interface ClearRepoProgress {
        void onProgress(int deleted, int total, String path);
        void onComplete(int deleted, String err);
    }

    public static void clearRepoContents(final String token, final String owner,
                                          final String repo, final String branch,
                                          final ClearRepoProgress cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // 1. Get full file tree
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/git/trees/" + branch + "?recursive=1", token);
                JSONArray tree = new JSONObject(resp).getJSONArray("tree");

                // 2. Filter: only blobs, skip .github/**
                List<String[]> toDelete = new ArrayList<>(); // [path, sha]
                for (int i = 0; i < tree.length(); i++) {
                    JSONObject o = tree.getJSONObject(i);
                    if (!"blob".equals(o.optString("type"))) continue;
                    String path = o.getString("path");
                    if (path.startsWith(".github/")) continue; // keep CI/CD
                    toDelete.add(new String[]{path, o.optString("sha", "")});
                }

                int total = toDelete.size();
                int deleted = 0;

                // 3. Delete each file
                for (String[] f : toDelete) {
                    String path = f[0]; String sha = f[1];
                    try {
                        JSONObject body = new JSONObject();
                        body.put("message", "chore: clear repo contents [skip ci]");
                        body.put("sha", sha);
                        body.put("branch", branch);
                        delete(BASE + "/repos/" + owner + "/" + repo
                            + "/contents/" + path, token, body.toString());
                        deleted++;
                        final int d = deleted; final int t = total;
                        cb.onProgress(d, t, path);
                        // Small delay to avoid rate limit
                        if (deleted % 10 == 0) Thread.sleep(500);
                    } catch (Exception ignored) {}
                }

                final int finalDeleted = deleted;
                cb.onComplete(finalDeleted, null);
            } catch (Exception e) {
                cb.onComplete(0, e.getMessage());
            }
        });
    }


    /**
     * Compute Git blob SHA-1: SHA1("blob " + size + "\0" + content)
     */
    public static String computeGitBlobSha(byte[] content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] header = ("blob " + content.length + "\0").getBytes(StandardCharsets.UTF_8);
            md.update(header); md.update(content);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // =========================================================================
    // SYNC HELPERS (for background polling thread)
    // =========================================================================

    // =========================================================================
    // SYNC HELPERS — for auto-sign background thread
    // =========================================================================

    /**
     * Check if a file exists in repo. Returns SHA if exists, null if not.
     * Synchronous — call from background thread only.
     */
    public static String checkFileExists(String token, String owner,
                                          String repo, String path) {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                new URL(BASE + "/repos/" + owner + "/" + repo
                    + "/contents/" + path).openConnection();
            conn.setRequestMethod("GET");
            setAuth(conn, token);
            conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code == 200) {
                String body = readStream(conn.getInputStream());
                return new JSONObject(body).optString("sha", "exists");
            }
            return null;
        } catch (Exception e) { return null; }
    }

    /**
     * Async overload — dipakai oleh showCiCdSetupDialog di MainActivity.
     * Callback: onResult(sha_or_"exists", null) jika file ada,
     *           onResult(null, null) jika file tidak ada,
     *           onResult(null, errMsg) jika network error.
     */
    public static void checkFileExists(String token, String owner,
                                        String repo, String path,
                                        Callback<String> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(BASE + "/repos/" + owner + "/" + repo
                        + "/contents/" + path).openConnection();
                conn.setRequestMethod("GET");
                setAuth(conn, token);
                conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
                int code = conn.getResponseCode();
                if (code == 200) {
                    String body = readStream(conn.getInputStream());
                    cb.onResult(new JSONObject(body).optString("sha", "exists"), null);
                } else {
                    cb.onResult(null, null); // file tidak ada, bukan error
                }
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    /**
     * Synchronous getFileContent — returns [decodedText, sha] or null.
     * Call from background thread only.
     */
    public static String[] getFileContentSync(String token, String owner,
                                               String repo, String path) {
        try {
            String resp = get(BASE + "/repos/" + owner + "/" + repo
                + "/contents/" + path, token);
            JSONObject o = new JSONObject(resp);
            String sha  = o.optString("sha", "");
            String enc  = o.optString("content", "").replace("\n", "").replace("\r", "");
            String text = new String(Base64.decode(enc, Base64.DEFAULT), StandardCharsets.UTF_8);
            return new String[]{text, sha};
        } catch (Exception e) { return null; }
    }

    /**
     * Download file from repo and return raw base64-encoded content string.
     * Used for binary files (e.g. keystore) where UTF-8 decoding would corrupt data.
     * Returns null if file not found or error.
     */
    public static String getFileContentRaw(String token, String owner,
                                            String repo, String path) {
        try {
            String resp = get(BASE + "/repos/" + owner + "/" + repo
                + "/contents/" + path, token);
            String enc = new JSONObject(resp).optString("content", "");
            if (enc.isEmpty()) return null;
            // Strip newlines — GitHub wraps base64 at 60 chars
            return enc.replace("\n", "").replace("\r", "");
        } catch (Exception e) { return null; }
    }

    /**
     * Get a single Actions Variable value.
     * Returns the value string, or null if not found.
     */
    public static String getVariableValue(String token, String owner,
                                           String repo, String varName) {
        try {
            String resp = get(BASE + "/repos/" + owner + "/" + repo
                + "/actions/variables/" + varName, token);
            return new JSONObject(resp).optString("value", null);
        } catch (Exception e) { return null; }
    }


    /**
     * Get full recursive file tree as newline-separated paths.
     * Synchronous — call from background thread only.
     */
    public static String getFileTreeSync(String token, String owner,
                                          String repo, String branch) {
        try {
            String resp = get(BASE + "/repos/" + owner + "/" + repo
                + "/git/trees/" + branch + "?recursive=1", token);
            JSONArray tree = new JSONObject(resp).getJSONArray("tree");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tree.length(); i++) {
                JSONObject o = tree.getJSONObject(i);
                if ("blob".equals(o.optString("type"))) {
                    sb.append(o.getString("path")).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    // =========================================================================
    // AI CHAT — multi-turn conversation
    // =========================================================================

    /**
     * Multi-turn chat with AI. Supports Groq, Gemini, DeepSeek, OpenRouter.
     * @param apiKey     Provider API key
     * @param provider   "groq" | "gemini" | "deepseek" | "openrouter"
     * @param repoContext "owner/repo" for system prompt context
     * @param history    Full conversation history [[role, content], ...]
     * @param cb         Callback with AI reply text
     */
    public static void callAiChat(final String apiKey, final String provider,
                                   final String model,
                                   final String repoContext,
                                   final java.util.List<String[]> history,
                                   final Callback<String> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // repoContext format: "owner/repo|branch|lastError" (pipe-separated extras optional)
                String[] ctxParts = repoContext.split("\\|", -1);
                String repoName   = ctxParts.length > 0 ? ctxParts[0] : repoContext;
                String branch     = ctxParts.length > 1 && !ctxParts[1].isEmpty() ? ctxParts[1] : "main";
                String lastError  = ctxParts.length > 2 && !ctxParts[2].isEmpty() ? ctxParts[2] : "";

                String sysPrompt =
                    "You are an expert Android developer assistant for the GitHub repo: "
                    + repoName + " (branch: " + branch + ").\n"
                    + "You specialize in: Android Java/Kotlin, Gradle, GitHub Actions CI/CD, "
                    + "build errors, APK signing, and code review.\n"
                    + (lastError.isEmpty() ? "" :
                        "The user's last build error was:\n```\n" + lastError + "\n```\n")
                    + "Rules:\n"
                    + "- Be direct and concise. No unnecessary preamble.\n"
                    + "- For code fixes: show ONLY the changed lines with brief context, never the full file.\n"
                    + "- For build errors: identify root cause first, then give exact fix.\n"
                    + "- If you need more info, ask ONE specific question.\n"
                    + "- Format code in ```java or ```gradle blocks.";

                String reply;

                if ("gemini".equals(provider)) {
                    // Gemini format — systemInstruction is TOP-LEVEL, not inside generationConfig
                    org.json.JSONObject body = new org.json.JSONObject();

                    // System instruction at top level
                    org.json.JSONObject sysInstr = new org.json.JSONObject();
                    org.json.JSONArray sysParts = new org.json.JSONArray();
                    sysParts.put(new org.json.JSONObject().put("text", sysPrompt));
                    sysInstr.put("parts", sysParts);
                    body.put("system_instruction", sysInstr);

                    // Conversation history
                    org.json.JSONArray contents = new org.json.JSONArray();
                    for (String[] m : history) {
                        org.json.JSONObject turn = new org.json.JSONObject();
                        turn.put("role", "user".equals(m[0]) ? "user" : "model");
                        org.json.JSONArray parts = new org.json.JSONArray();
                        parts.put(new org.json.JSONObject().put("text", m[1]));
                        turn.put("parts", parts);
                        contents.put(turn);
                    }
                    body.put("contents", contents);

                    // Generation config — no systemInstruction here
                    org.json.JSONObject cfg = new org.json.JSONObject();
                    cfg.put("temperature", 0.7);
                    cfg.put("maxOutputTokens", 2048);
                    body.put("generationConfig", cfg);

                    String gemModel = (model != null && !model.isEmpty()) ? model : "gemini-2.0-flash";
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/"
                            + gemModel + ":generateContent?key=" + apiKey).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true); conn.setConnectTimeout(20000); conn.setReadTimeout(60000);
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    int code = conn.getResponseCode();
                    String resp = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                    if (code != 200) {
                        String em = "Gemini error " + code;
                        try { em = new org.json.JSONObject(resp).getJSONObject("error").optString("message", em); } catch (Exception ig) {}
                        cb.onResult(null, em); return;
                    }
                    reply = new org.json.JSONObject(resp)
                        .getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                        .getJSONObject(0).getString("text").trim();
                } else {
                    // OpenAI-compatible
                    String url;
                    String defaultModel;
                    if ("deepseek".equals(provider)) {
                        url = "https://api.deepseek.com/v1/chat/completions";
                        defaultModel = "deepseek-chat";
                    } else if ("openrouter".equals(provider)) {
                        url = "https://openrouter.ai/api/v1/chat/completions";
                        defaultModel = "meta-llama/llama-3.3-70b-instruct:free";
                    } else {
                        url = "https://api.groq.com/openai/v1/chat/completions";
                        defaultModel = "llama-3.3-70b-versatile";
                    }
                    String useModel = (model != null && !model.isEmpty()) ? model : defaultModel; // model = param
                    org.json.JSONObject body = new org.json.JSONObject();
                    body.put("model", useModel); body.put("max_tokens", 2048); body.put("temperature", 0.7);
                    org.json.JSONArray messages = new org.json.JSONArray();
                    // System message
                    org.json.JSONObject sys = new org.json.JSONObject();
                    sys.put("role", "system"); sys.put("content", sysPrompt);
                    messages.put(sys);
                    // Conversation history
                    for (String[] m : history) {
                        org.json.JSONObject msg = new org.json.JSONObject();
                        msg.put("role", m[0]); msg.put("content", m[1]);
                        messages.put(msg);
                    }
                    body.put("messages", messages);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(url).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true); conn.setConnectTimeout(20000); conn.setReadTimeout(60000);
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    int code = conn.getResponseCode();
                    String resp = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                    if (code != 200) {
                        String em = provider + " error " + code;
                        try { em = new org.json.JSONObject(resp).getJSONObject("error").getString("message"); } catch (Exception ig) {}
                        cb.onResult(null, em); return;
                    }
                    reply = new org.json.JSONObject(resp)
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim();
                }
                cb.onResult(reply, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    // =========================================================================
    // AI FIX — multi-provider code fix
    // =========================================================================
    // Built-in Groq key (fallback when user has no key set)
    public static final String BUILTIN_GROQ_KEY = ""; // User must provide their own key

    /**
     * Call Groq API (llama-3.3-70b-versatile) to fix a Java/Kotlin compiler error.
     *
     * Strategy is chosen by error type:
     *   SYNTAX     → method boundary excerpt
     *   STRUCTURAL → smart excerpt (header + error zone + tail)
     *   CROSS_FILE → method boundary + optional crossFileContext snippet
     *
     * @param crossFileContext  null for SYNTAX/STRUCTURAL;
     *                          for CROSS_FILE: declarations from other file(s)
     */
    public static void callGroqFix(final String apiKey,
                                    final String fileContent,
                                    final String filename,
                                    final int lineNum,
                                    final String errorMsg,
                                    final String crossFileContext,
                                    final Callback<String> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String[] fileLines = fileContent.split("\n", -1);
                int totalLines = fileLines.length;
                ErrorType errType = classifyError(errorMsg);

                // ── Pick excerpt strategy ────────────────────────────────────
                final String codeToSend;
                final int    contextStartLine;
                final boolean isFull;  // true → AI returns complete file/method replacement

                if (errType == ErrorType.STRUCTURAL) {
                    // Smart excerpt: class header + error zone + file tail
                    int HEAD = Math.min(30, totalLines);
                    int TAIL = Math.min(30, totalLines);
                    int errStart = Math.max(HEAD, lineNum - 30 - 1);
                    int errEnd   = Math.min(totalLines - TAIL, lineNum + 30);
                    StringBuilder smart = new StringBuilder();
                    for (int i = 0; i < HEAD; i++) smart.append(fileLines[i]).append("\n");
                    if (errStart > HEAD) {
                        smart.append("// ... (lines ").append(HEAD + 1).append("-").append(errStart).append(" omitted)\n");
                        for (int i = errStart; i < errEnd && i < totalLines; i++) smart.append(fileLines[i]).append("\n");
                    }
                    if (errEnd < totalLines - TAIL)
                        smart.append("// ... (lines ").append(errEnd + 1).append("-").append(totalLines - TAIL).append(" omitted)\n");
                    for (int i = Math.max(errEnd, totalLines - TAIL); i < totalLines; i++) smart.append(fileLines[i]).append("\n");
                    codeToSend       = smart.toString();
                    contextStartLine = 1;
                    isFull           = true; // returned as complete replacement
                } else {
                    // SYNTAX or CROSS_FILE: use method boundary
                    String[] boundary = extractMethodBoundary(fileContent, lineNum);
                    codeToSend       = boundary[0];
                    contextStartLine = Integer.parseInt(boundary[1]);
                    isFull           = false; // excerpt → splice back
                }

                // ── Build prompt ─────────────────────────────────────────────
                boolean hasCrossCtx = crossFileContext != null && !crossFileContext.isEmpty();

                String systemPrompt =
                    "You are an expert Android Java developer. Fix compiler errors precisely.\n"
                    + "CRITICAL RULES:\n"
                    + "1. Return ONLY the fixed code — no markdown, no ``` fences, no explanation\n"
                    + "2. Do NOT include line numbers in your response\n"
                    + "3. Fix ONLY the reported error — change nothing else\n"
                    + "4. Preserve ALL indentation, comments, and formatting exactly\n"
                    + (hasCrossCtx
                        ? "5. The READ-ONLY CONTEXT section shows types/interfaces from other files — "
                          + "use it to understand the API but do NOT return it in your response\n"
                        : "");

                String excerptLineCount = String.valueOf(codeToSend.split("\n", -1).length);
                String userPrompt;

                if (errType == ErrorType.STRUCTURAL) {
                    userPrompt =
                        "File: " + filename + " (" + totalLines + " lines total)\n"
                        + "Structural error at line " + lineNum + ": " + errorMsg + "\n\n"
                        + "NOTE: Showing class header + error zone + file tail.\n"
                        + "Missing brace is likely near the END of the file.\n\n"
                        + "Excerpt:\n" + codeToSend
                        + "Return this excerpt with the structural fix applied. No line numbers.";
                } else {
                    userPrompt =
                        "File: " + filename + " (" + totalLines + " lines total)\n"
                        + "Error at line " + lineNum + ": " + errorMsg + "\n\n"
                        + "Method containing error (lines " + contextStartLine
                        + "-" + (contextStartLine + Integer.parseInt(excerptLineCount) - 1) + "):\n"
                        + codeToSend
                        + (hasCrossCtx
                            ? "\n// ── READ-ONLY CONTEXT (do NOT return this) ──────────────\n"
                              + crossFileContext
                              + "// ────────────────────────────────────────────────────────\n\n"
                            : "\n")
                        + "Return ONLY the fixed method (" + excerptLineCount
                        + " lines). No line numbers, no explanation.";
                }

                // ── Call Groq ────────────────────────────────────────────────
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("model", "llama-3.3-70b-versatile");
                body.put("max_tokens", 4096);
                body.put("temperature", 0.0);
                org.json.JSONArray msgs = new org.json.JSONArray();
                org.json.JSONObject sm = new org.json.JSONObject();
                sm.put("role", "system"); sm.put("content", systemPrompt);
                org.json.JSONObject um = new org.json.JSONObject();
                um.put("role", "user"); um.put("content", userPrompt);
                msgs.put(sm); msgs.put(um);
                body.put("messages", msgs);

                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL("https://api.groq.com/openai/v1/chat/completions").openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(90000);
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                String raw = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                if (code != 200) {
                    String em = "Groq error " + code;
                    try { em = new org.json.JSONObject(raw).getJSONObject("error").getString("message"); } catch (Exception ig) {}
                    cb.onResult(null, em); return;
                }
                String fixedExcerpt = new org.json.JSONObject(raw)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim();

                // ── Clean AI response ────────────────────────────────────────
                // Strip markdown fences
                if (fixedExcerpt.startsWith("```")) {
                    int nl = fixedExcerpt.indexOf("\n");
                    if (nl >= 0) fixedExcerpt = fixedExcerpt.substring(nl + 1);
                    if (fixedExcerpt.endsWith("```"))
                        fixedExcerpt = fixedExcerpt.substring(0, fixedExcerpt.length() - 3).trim();
                    if (fixedExcerpt.startsWith("java\n") || fixedExcerpt.startsWith("kotlin\n"))
                        fixedExcerpt = fixedExcerpt.substring(fixedExcerpt.indexOf("\n") + 1);
                }
                // Strip line-number prefixes AI may have added
                StringBuilder cleaned = new StringBuilder();
                for (String ln : fixedExcerpt.split("\n", -1)) {
                    String stripped = ln.replaceFirst("^\\s{0,4}\\d{1,5}\\s{1,3}", "");
                    if (stripped.trim().matches("^\\d{1,5}$")) continue;
                    cleaned.append(stripped).append("\n");
                }
                fixedExcerpt = cleaned.toString();
                while (fixedExcerpt.endsWith("\n\n"))
                    fixedExcerpt = fixedExcerpt.substring(0, fixedExcerpt.length() - 1);

                // ── Hard absolute cap — reject before any merge ──────────────
                // If AI returned more than 400 lines in excerpt mode, it returned
                // the full file regardless of ratio. Reject immediately.
                int rawResponseLines = fixedExcerpt.split("\n", -1).length;
                if (!isFull && rawResponseLines > 400) {
                    cb.onResult(null,
                        "AI returned " + rawResponseLines + " lines but only ~"
                        + codeToSend.split("\n", -1).length + " were expected.\n\n"
                        + "This error is too complex for Auto-Fix. "
                        + "Use the Ask tab for step-by-step guidance.");
                    return;
                }

                // ── Merge back into full file ────────────────────────────────
                String result;
                if (isFull) {
                    result = fixedExcerpt;
                    if (!result.endsWith("\n")) result += "\n";
                } else {
                    // Validate ratio: AI should return ~same line count as the excerpt.
                    int excerptLineCount2 = codeToSend.split("\n", -1).length;
                    int responseLineCount = fixedExcerpt.split("\n", -1).length;
                    if (responseLineCount > excerptLineCount2 * 2) {
                        cb.onResult(null,
                            "AI returned the full file instead of just the fix ("
                            + responseLineCount + " lines for a " + excerptLineCount2
                            + "-line excerpt).\n\nUse the Ask tab for guidance on this error.");
                        return;
                    }
                    // Splice fixed method excerpt back into original
                    String[] origLines  = fileContent.split("\n", -1);
                    String[] fixedLines = fixedExcerpt.split("\n", -1);
                    int replaceStart = Math.max(0, contextStartLine - 1);
                    int windowLines  = codeToSend.split("\n", -1).length;
                    int replaceEnd   = Math.min(origLines.length, replaceStart + windowLines);
                    StringBuilder fullFixed = new StringBuilder();
                    for (int i = 0; i < replaceStart; i++)       fullFixed.append(origLines[i]).append("\n");
                    for (String fl : fixedLines)                   fullFixed.append(fl).append("\n");
                    for (int i = replaceEnd; i < origLines.length; i++) fullFixed.append(origLines[i]).append("\n");
                    result = fullFixed.toString();

                    // Diff similarity check: count changed lines in the spliced region.
                    // If >80% of lines changed it's likely a bad reformat — reject.
                    int changedInRegion = 0;
                    String[] resultLines = result.split("\n", -1);
                    int checkEnd = Math.min(replaceEnd, Math.min(origLines.length, resultLines.length));
                    for (int i = replaceStart; i < checkEnd; i++) {
                        if (!origLines[i].equals(resultLines[i])) changedInRegion++;
                    }
                    int regionSize = checkEnd - replaceStart;
                    if (regionSize > 5 && changedInRegion > regionSize * 8 / 10) {
                        cb.onResult(null,
                            "AI reformatted the code instead of fixing the error ("
                            + changedInRegion + "/" + regionSize + " lines changed).\n\n"
                            + "Use the Ask tab for guidance on this error.");
                        return;
                    }
                }

                // Sanity: result must be at least 50% of original length
                if (result.trim().length() < fileContent.length() / 2) {
                    cb.onResult(null, "AI returned too little content. Please fix manually."); return;
                }
                cb.onResult(result, null);

            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    // Keep start/end accessible for context reconstruction
    @SuppressWarnings("unused")
    private static int _ctxStart, _ctxEnd;

    /** Synchronous listRuns — call from a background thread only. */
    public static List<WorkflowRun> listRunsSync(String token, String owner,
                                                   String repo) throws Exception {
        String resp = get(BASE + "/repos/" + owner + "/" + repo
            + "/actions/runs?per_page=30", token);
        JSONArray arr = new JSONObject(resp).getJSONArray("workflow_runs");
        List<WorkflowRun> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            list.add(new WorkflowRun(
                o.getLong("id"), o.getString("name"), o.getString("status"),
                o.isNull("conclusion") ? "—" : o.getString("conclusion"),
                o.getString("created_at"), o.getString("head_branch"),
                o.getString("html_url")
            ));
        }
        return list;
    }

    // =========================================================================
    // FITUR 1: GITHUB RELEASES
    // =========================================================================

    /** Model untuk sebuah GitHub Release. */
    public static class Release {
        public long   id;
        public String tagName, name, body, htmlUrl, uploadUrl, createdAt;
        public boolean draft, prerelease;
        public Release(long id, String tagName, String name, String body,
                       String htmlUrl, String uploadUrl, String createdAt,
                       boolean draft, boolean prerelease) {
            this.id = id; this.tagName = tagName; this.name = name;
            this.body = body; this.htmlUrl = htmlUrl; this.uploadUrl = uploadUrl;
            this.createdAt = createdAt; this.draft = draft; this.prerelease = prerelease;
        }
    }

    /**
     * Buat GitHub Release baru.
     * Callback: onResult(release, null) sukses, onResult(null, err) gagal.
     *
     * @param tagName   mis. "v1.0.0"
     * @param name      judul release, mis. "Version 1.0.0"
     * @param body      release notes (Markdown)
     * @param prerelease true = tandai sebagai pre-release
     */
    public static void createRelease(final String token, final String owner,
                                      final String repo, final String tagName,
                                      final String name, final String body,
                                      final boolean prerelease,
                                      final Callback<Release> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("tag_name",   tagName);
                req.put("name",       name.isEmpty() ? tagName : name);
                req.put("body",       body);
                req.put("draft",      false);
                req.put("prerelease", prerelease);
                String resp = post(BASE + "/repos/" + owner + "/" + repo + "/releases",
                    token, req.toString());
                JSONObject o = new JSONObject(resp);
                cb.onResult(parseRelease(o), null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    /**
     * Upload asset (mis. APK) ke sebuah GitHub Release.
     * GitHub menggunakan uploads.github.com, bukan api.github.com.
     *
     * @param uploadUrl  upload_url dari Release object (sudah berisi {?name,label} template)
     * @param assetName  nama file yang akan ditampilkan di release page
     * @param assetBytes konten file
     */
    public static void uploadReleaseAsset(final String token,
                                           final String uploadUrl,
                                           final String assetName,
                                           final byte[] assetBytes,
                                           final Callback<String> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // Hapus template suffix {?name,label} dari uploadUrl
                String url = uploadUrl.replaceAll("\\{.*\\}", "");
                url = url + "?name=" + java.net.URLEncoder.encode(assetName, "UTF-8");

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "token " + token);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);

                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(assetBytes);
                }

                int code = conn.getResponseCode();
                String resp = readStream(code >= 400
                    ? conn.getErrorStream() : conn.getInputStream());
                if (code == 201) {
                    String downloadUrl = new JSONObject(resp)
                        .optString("browser_download_url", "");
                    cb.onResult(downloadUrl, null);
                } else {
                    String msg = "HTTP " + code;
                    try { msg = new JSONObject(resp).optString("message", msg); }
                    catch (Exception ig) {}
                    cb.onResult(null, msg);
                }
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    /**
     * Ambil daftar releases yang sudah ada (untuk cek duplikasi tag).
     */
    public static void listReleases(final String token, final String owner,
                                     final String repo,
                                     final Callback<List<Release>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/releases?per_page=20", token);
                JSONArray arr = new JSONArray(resp);
                List<Release> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++)
                    list.add(parseRelease(arr.getJSONObject(i)));
                cb.onResult(list, null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    private static Release parseRelease(JSONObject o) throws Exception {
        return new Release(
            o.getLong("id"),
            o.getString("tag_name"),
            o.optString("name", ""),
            o.optString("body", ""),
            o.optString("html_url", ""),
            o.optString("upload_url", ""),
            o.optString("created_at", ""),
            o.optBoolean("draft", false),
            o.optBoolean("prerelease", false)
        );
    }

    /** Model for a single asset attached to a GitHub Release. */
    public static class ReleaseAsset {
        public long   id, size;
        public String name, contentType, downloadUrl, state, updatedAt;
        public ReleaseAsset(long id, String name, long size, String contentType,
                            String downloadUrl, String state, String updatedAt) {
            this.id = id; this.name = name; this.size = size;
            this.contentType = contentType; this.downloadUrl = downloadUrl;
            this.state = state; this.updatedAt = updatedAt;
        }
    }

    /** List all assets attached to a specific release. */
    public static void listReleaseAssets(final String token, final String owner,
                                          final String repo, final long releaseId,
                                          final Callback<List<ReleaseAsset>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/releases/" + releaseId + "/assets?per_page=30", token);
                JSONArray arr = new JSONArray(resp);
                List<ReleaseAsset> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new ReleaseAsset(
                        o.getLong("id"),
                        o.getString("name"),
                        o.optLong("size", 0),
                        o.optString("content_type", "application/octet-stream"),
                        o.optString("browser_download_url", ""),
                        o.optString("state", ""),
                        o.optString("updated_at", "")
                    ));
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    /** Download a release asset by its direct download URL. */
    public static void downloadReleaseAsset(final String token,
                                             final String downloadUrl,
                                             final ProgressCallback onProgress,
                                             final Callback<byte[]> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new java.net.URL(downloadUrl).openConnection();
                conn.setRequestProperty("Authorization", "token " + token);
                conn.setRequestProperty("Accept", "application/octet-stream");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code >= 400) { cb.onResult(null, "HTTP " + code); return; }
                long total = conn.getContentLengthLong();
                try (java.io.InputStream is = conn.getInputStream()) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    long downloaded = 0; int n;
                    while ((n = is.read(buf)) != -1) {
                        bos.write(buf, 0, n); downloaded += n;
                        if (onProgress != null) onProgress.onProgress(downloaded, total);
                    }
                    cb.onResult(bos.toByteArray(), null);
                }
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    /** Delete a release asset. */
    public static void deleteReleaseAsset(final String token, final String owner,
                                           final String repo, final long assetId,
                                           final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                delete(BASE + "/repos/" + owner + "/" + repo + "/releases/assets/" + assetId, token, "");
                cb.onResult(true, null);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    /** Delete an entire release. */
    public static void deleteRelease(final String token, final String owner,
                                      final String repo, final long releaseId,
                                      final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                delete(BASE + "/repos/" + owner + "/" + repo + "/releases/" + releaseId, token, "");
                cb.onResult(true, null);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    /** Delete a git tag by name (e.g. "v1.0.0"). */
    public static void deleteTag(final String token, final String owner,
                                  final String repo, final String tagName,
                                  final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                delete(BASE + "/repos/" + owner + "/" + repo + "/git/refs/tags/" + tagName, token, "");
                cb.onResult(true, null);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    // =========================================================================
    // FITUR 2: COMMIT HISTORY
    // =========================================================================

    /** Model untuk satu commit entry. */
    public static class CommitEntry {
        public String sha, shortSha, message, author, email, date, avatarUrl;
        public CommitEntry(String sha, String message, String author,
                           String email, String date, String avatarUrl) {
            this.sha      = sha;
            this.shortSha = sha.length() >= 7 ? sha.substring(0, 7) : sha;
            this.message  = message;
            this.author   = author;
            this.email    = email;
            this.date     = date;
            this.avatarUrl = avatarUrl;
        }
    }

    /**
     * Ambil daftar commit terbaru untuk branch tertentu.
     * Paginated: 30 per halaman, mendukung load-more.
     *
     * @param page  halaman (1-based)
     */
    public static void listCommits(final String token, final String owner,
                                    final String repo, final String branch,
                                    final int page,
                                    final Callback<List<CommitEntry>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String url = BASE + "/repos/" + owner + "/" + repo
                    + "/commits?sha=" + branch + "&per_page=30&page=" + page;
                String resp = get(url, token);
                JSONArray arr = new JSONArray(resp);
                List<CommitEntry> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o   = arr.getJSONObject(i);
                    JSONObject c   = o.getJSONObject("commit");
                    JSONObject aut = c.getJSONObject("author");
                    // Coba ambil avatar dari committer GitHub account jika tersedia
                    String avatar = "";
                    if (!o.isNull("author")) {
                        JSONObject ghAuthor = o.getJSONObject("author");
                        avatar = ghAuthor.optString("avatar_url", "");
                    }
                    list.add(new CommitEntry(
                        o.getString("sha"),
                        c.getString("message"),
                        aut.optString("name", "Unknown"),
                        aut.optString("email", ""),
                        aut.optString("date", ""),
                        avatar
                    ));
                }
                cb.onResult(list, null);
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    // =========================================================================
    // REPO SETTINGS
    // =========================================================================

    public static class RepoInfo {
        public String name, fullName, description, defaultBranch, homepage, language;
        public boolean isPrivate, hasIssues, hasWiki;
        public int stars, forks, openIssues;
        public String createdAt, updatedAt, pushedAt;
        public long size;
        public RepoInfo() {}
    }

    /** Fetch full repo info. */
    public static void getRepoInfo(final String token, final String owner,
                                    final String repo, final Callback<RepoInfo> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo, token);
                JSONObject o = new JSONObject(resp);
                RepoInfo r = new RepoInfo();
                r.name          = o.optString("name", repo);
                r.fullName      = o.optString("full_name", owner + "/" + repo);
                r.description   = o.optString("description", "");
                r.defaultBranch = o.optString("default_branch", "main");
                r.homepage      = o.optString("homepage", "");
                r.language      = o.optString("language", "");
                r.isPrivate     = o.optBoolean("private", false);
                r.hasIssues     = o.optBoolean("has_issues", true);
                r.hasWiki       = o.optBoolean("has_wiki", false);
                r.stars         = o.optInt("stargazers_count", 0);
                r.forks         = o.optInt("forks_count", 0);
                r.openIssues    = o.optInt("open_issues_count", 0);
                r.size          = o.optLong("size", 0);
                r.createdAt     = o.optString("created_at", "");
                r.updatedAt     = o.optString("updated_at", "");
                r.pushedAt      = o.optString("pushed_at", "");
                cb.onResult(r, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    /**
     * Edit repo metadata: name, description, homepage, private, has_issues, has_wiki.
     * Pass null for fields you don't want to change.
     */
    public static void editRepo(final String token, final String owner, final String repo,
                                 final String newName, final String description,
                                 final String homepage, final Boolean makePrivate,
                                 final Callback<RepoInfo> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                if (newName    != null) body.put("name",        newName);
                if (description!= null) body.put("description", description);
                if (homepage   != null) body.put("homepage",    homepage);
                if (makePrivate!= null) body.put("private",     makePrivate);
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(BASE + "/repos/" + owner + "/" + repo).openConnection();
                conn.setRequestMethod("PATCH");
                setAuth(conn, token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(12000); conn.setReadTimeout(12000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code == 200) {
                    // Parse updated repo info from response
                    java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject o = new JSONObject(sb.toString());
                    RepoInfo r = new RepoInfo();
                    r.name        = o.optString("name", repo);
                    r.fullName    = o.optString("full_name", owner + "/" + repo);
                    r.description = o.optString("description", "");
                    r.isPrivate   = o.optBoolean("private", false);
                    r.defaultBranch = o.optString("default_branch", "main");
                    cb.onResult(r, null);
                } else cb.onResult(null, "HTTP " + code);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    // =========================================================================
    // NOTIFICATIONS
    // =========================================================================

    public static class GitNotification {
        public String id, title, type, reason, repoName, repoFullName, url, updatedAt;
        public boolean unread;
        public GitNotification(String id, String title, String type, String reason,
                                String repoName, String repoFullName, String url,
                                String updatedAt, boolean unread) {
            this.id = id; this.title = title; this.type = type; this.reason = reason;
            this.repoName = repoName; this.repoFullName = repoFullName;
            this.url = url; this.updatedAt = updatedAt; this.unread = unread;
        }
    }

    public static void listNotifications(final String token, final boolean allNotifs,
                                          final Callback<List<GitNotification>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String url = BASE + "/notifications?per_page=30&participating=false"
                    + (allNotifs ? "&all=true" : "");
                String resp = get(url, token);
                JSONArray arr = new JSONArray(resp);
                List<GitNotification> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    JSONObject subj = o.optJSONObject("subject");
                    JSONObject repo = o.optJSONObject("repository");
                    String title       = subj != null ? subj.optString("title", "") : "";
                    String type        = subj != null ? subj.optString("type", "") : "";
                    String subjUrl     = subj != null ? subj.optString("url", "") : "";
                    String repoName    = repo != null ? repo.optString("name", "") : "";
                    String repoFull    = repo != null ? repo.optString("full_name", "") : "";
                    list.add(new GitNotification(
                        o.optString("id", ""),
                        title, type,
                        o.optString("reason", ""),
                        repoName, repoFull, subjUrl,
                        o.optString("updated_at", ""),
                        o.optBoolean("unread", false)
                    ));
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void markNotificationRead(final String token, final String notifId,
                                             final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(BASE + "/notifications/threads/" + notifId).openConnection();
                conn.setRequestMethod("PATCH");
                setAuth(conn, token);
                conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
                int code = conn.getResponseCode();
                cb.onResult(code == 205 || code == 200, code == 205 || code == 200 ? null : "HTTP " + code);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    public static void markAllNotificationsRead(final String token, final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(BASE + "/notifications").openConnection();
                conn.setRequestMethod("PUT");
                setAuth(conn, token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
                // Simple body: mark all as read
                String body = "{\"read\":true}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                cb.onResult(code == 205 || code == 200, null);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    /** Count unread notifications (fast — just checks header). */
    public static void getUnreadCount(final String token, final Callback<Integer> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/notifications?per_page=50", token);
                JSONArray arr = new JSONArray(resp);
                int count = 0;
                for (int i = 0; i < arr.length(); i++)
                    if (arr.getJSONObject(i).optBoolean("unread", false)) count++;
                cb.onResult(count, null);
            } catch (Exception e) { cb.onResult(0, e.getMessage()); }
        });
    }


    // =========================================================================
    // ISSUES
    // =========================================================================

    public static class Issue {
        public int    number;
        public String title, body, state, createdAt, updatedAt, userLogin, userAvatar;
        public java.util.List<String> labels = new java.util.ArrayList<>();
        public String assignee;
        public int    comments;
        public Issue() {}
    }

    public static class IssueComment {
        public long   id;
        public String body, userLogin, createdAt;
        public IssueComment(long id, String body, String user, String created) {
            this.id = id; this.body = body; this.userLogin = user; this.createdAt = created;
        }
    }

    public static void listIssues(final String token, final String owner,
                                   final String repo, final String state,
                                   final Callback<List<Issue>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/issues?state=" + state + "&per_page=30&sort=updated", token);
                JSONArray arr = new JSONArray(resp);
                List<Issue> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    // Skip pull requests (they appear in issues API too)
                    if (!o.isNull("pull_request")) continue;
                    Issue iss = new Issue();
                    iss.number    = o.getInt("number");
                    iss.title     = o.optString("title", "");
                    iss.body      = o.optString("body", "");
                    iss.state     = o.optString("state", "open");
                    iss.createdAt = o.optString("created_at", "");
                    iss.updatedAt = o.optString("updated_at", "");
                    iss.comments  = o.optInt("comments", 0);
                    JSONObject user = o.optJSONObject("user");
                    if (user != null) {
                        iss.userLogin  = user.optString("login", "");
                        iss.userAvatar = user.optString("avatar_url", "");
                    }
                    JSONArray lbls = o.optJSONArray("labels");
                    if (lbls != null)
                        for (int j = 0; j < lbls.length(); j++)
                            iss.labels.add(lbls.getJSONObject(j).optString("name", ""));
                    JSONObject asgn = o.optJSONObject("assignee");
                    if (asgn != null) iss.assignee = asgn.optString("login", "");
                    list.add(iss);
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void getIssueComments(final String token, final String owner,
                                         final String repo, final int number,
                                         final Callback<List<IssueComment>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/issues/" + number + "/comments?per_page=50", token);
                JSONArray arr = new JSONArray(resp);
                List<IssueComment> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    JSONObject user = o.optJSONObject("user");
                    list.add(new IssueComment(
                        o.getLong("id"),
                        o.optString("body", ""),
                        user != null ? user.optString("login", "") : "",
                        o.optString("created_at", "")
                    ));
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void createIssue(final String token, final String owner,
                                    final String repo, final String title,
                                    final String body, final Callback<Issue> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("title", title);
                if (body != null && !body.isEmpty()) req.put("body", body);
                String resp = post(BASE + "/repos/" + owner + "/" + repo + "/issues",
                    token, req.toString());
                JSONObject o = new JSONObject(resp);
                Issue iss = new Issue();
                iss.number    = o.getInt("number");
                iss.title     = o.optString("title", "");
                iss.state     = "open";
                iss.createdAt = o.optString("created_at", "");
                cb.onResult(iss, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void addIssueComment(final String token, final String owner,
                                        final String repo, final int number,
                                        final String body, final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("body", body);
                post(BASE + "/repos/" + owner + "/" + repo + "/issues/" + number + "/comments",
                    token, req.toString());
                cb.onResult(true, null);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    public static void setIssueState(final String token, final String owner,
                                      final String repo, final int number,
                                      final String state, final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("state", state); // "open" or "closed"
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(BASE + "/repos/" + owner + "/" + repo + "/issues/" + number)
                    .openConnection();
                conn.setRequestMethod("PATCH");
                setAuth(conn, token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                cb.onResult(conn.getResponseCode() == 200, null);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    // =========================================================================
    // BRANCHES — EXTENDED
    // =========================================================================

    public static class Branch {
        public String name, sha, commitMessage, commitDate, committerName;
        public boolean isProtected, isDefault;
        public Branch(String name, String sha, boolean prot) {
            this.name = name; this.sha = sha; this.isProtected = prot;
        }
    }

    public static void listBranchesDetail(final String token, final String owner,
                                           final String repo, final String defaultBranch,
                                           final Callback<List<Branch>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/branches?per_page=50", token);
                JSONArray arr = new JSONArray(resp);
                List<Branch> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String bname = o.getString("name");
                    String sha = "";
                    JSONObject commit = o.optJSONObject("commit");
                    if (commit != null) sha = commit.optString("sha", "");
                    Branch b = new Branch(bname, sha, o.optBoolean("protected", false));
                    b.isDefault = bname.equals(defaultBranch);
                    list.add(b);
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void createBranch(final String token, final String owner,
                                     final String repo, final String newBranch,
                                     final String fromBranch, final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // Get SHA of fromBranch head
                String refResp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/git/ref/heads/" + fromBranch, token);
                String sha = new JSONObject(refResp)
                    .getJSONObject("object").getString("sha");
                // Create new branch ref
                JSONObject body = new JSONObject();
                body.put("ref", "refs/heads/" + newBranch);
                body.put("sha", sha);
                post(BASE + "/repos/" + owner + "/" + repo + "/git/refs",
                    token, body.toString());
                cb.onResult(true, null);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    public static void deleteBranch(final String token, final String owner,
                                     final String repo, final String branch,
                                     final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                int code = delete(BASE + "/repos/" + owner + "/" + repo
                    + "/git/refs/heads/" + branch, token, null);
                cb.onResult(code == 204, code == 204 ? null : "HTTP " + code);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    // =========================================================================
    // WORKFLOW INSPECTOR
    // =========================================================================

    public static class WorkflowDetail {
        public long   id;
        public String name, path, state, yamlContent;
        public boolean hasDispatch, hasPush, hasPullRequest, hasSchedule;
        public java.util.List<WorkflowInput> inputs = new java.util.ArrayList<>();
        public WorkflowDetail(long id, String name, String path, String state) {
            this.id = id; this.name = name; this.path = path; this.state = state;
        }
    }

    /** List workflows with full detail including YAML + trigger detection. */
    public static void listWorkflowsDetail(final String token, final String owner,
                                            final String repo,
                                            final Callback<List<WorkflowDetail>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/actions/workflows", token);
                JSONArray arr = new JSONObject(resp).getJSONArray("workflows");
                List<WorkflowDetail> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    WorkflowDetail wd = new WorkflowDetail(
                        o.getLong("id"),
                        o.getString("name"),
                        o.getString("path"),
                        o.optString("state", "unknown")
                    );
                    // Fetch YAML to parse triggers
                    try {
                        String yaml = null;
                        String yamlResp = get(BASE + "/repos/" + owner + "/" + repo
                            + "/contents/" + wd.path, token);
                        String enc = new JSONObject(yamlResp)
                            .optString("content", "").replace("\n", "").replace("\r", "");
                        if (!enc.isEmpty())
                            yaml = new String(Base64.decode(enc, Base64.DEFAULT),
                                StandardCharsets.UTF_8);
                        if (yaml != null) {
                            wd.yamlContent = yaml;
                            String lower = yaml.toLowerCase();
                            wd.hasDispatch    = lower.contains("workflow_dispatch");
                            wd.hasPush        = lower.contains("push:");
                            wd.hasPullRequest = lower.contains("pull_request");
                            wd.hasSchedule    = lower.contains("schedule:");
                            wd.inputs         = parseWorkflowInputs(yaml);
                        }
                    } catch (Exception ignored) {}
                    list.add(wd);
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    /** Add workflow_dispatch trigger to an existing workflow YAML. */
    public static void addWorkflowDispatchTrigger(final String token, final String owner,
                                                   final String repo, final String branch,
                                                   final String yamlPath,
                                                   final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // Fetch current file
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/contents/" + yamlPath, token);
                JSONObject obj = new JSONObject(resp);
                String sha = obj.optString("sha", null);
                String enc = obj.optString("content", "").replace("\n","").replace("\r","");
                String yaml = new String(Base64.decode(enc, Base64.DEFAULT), StandardCharsets.UTF_8);
                if (yaml.contains("workflow_dispatch")) {
                    cb.onResult(true, null); // already has it
                    return;
                }
                // Inject workflow_dispatch after "on:" line
                String patched;
                if (yaml.contains("on:")) {
                    patched = yaml.replaceFirst("on:", "on:\n  workflow_dispatch:");
                } else {
                    patched = "on:\n  workflow_dispatch:\n\n" + yaml;
                }
                JSONObject body = new JSONObject();
                body.put("message", "Add workflow_dispatch trigger [skip ci]");
                body.put("content", Base64.encodeToString(
                    patched.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                body.put("branch", branch);
                if (sha != null) body.put("sha", sha);
                put(BASE + "/repos/" + owner + "/" + repo + "/contents/" + yamlPath,
                    token, body.toString());
                cb.onResult(true, null);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }


    // =========================================================================
    // PULL REQUESTS
    // =========================================================================

    public static class PullRequest {
        public int    number, additions, deletions, changedFiles, comments;
        public String title, body, state, createdAt, updatedAt, mergedAt;
        public String authorLogin, headBranch, baseBranch, headSha;
        public boolean mergeable, draft;
        public PullRequest() {}
    }

    public static class PRFile {
        public String filename, status, patch;
        public int additions, deletions, changes;
        public PRFile(String filename, String status, String patch, int add, int del, int ch) {
            this.filename = filename; this.status = status; this.patch = patch;
            this.additions = add; this.deletions = del; this.changes = ch;
        }
    }

    public static class PRReview {
        public String userLogin, state, body, submittedAt;
        public PRReview(String user, String state, String body, String date) {
            this.userLogin = user; this.state = state;
            this.body = body; this.submittedAt = date;
        }
    }

    public static void listPRs(final String token, final String owner,
                                final String repo, final String state,
                                final Callback<List<PullRequest>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/pulls?state=" + state + "&per_page=30&sort=updated", token);
                JSONArray arr = new JSONArray(resp);
                List<PullRequest> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    PullRequest pr = new PullRequest();
                    pr.number      = o.getInt("number");
                    pr.title       = o.optString("title", "");
                    pr.body        = o.optString("body", "");
                    pr.state       = o.optString("state", "open");
                    pr.draft       = o.optBoolean("draft", false);
                    pr.createdAt   = o.optString("created_at", "");
                    pr.updatedAt   = o.optString("updated_at", "");
                    pr.mergedAt    = o.optString("merged_at", "");
                    pr.comments    = o.optInt("comments", 0);
                    pr.changedFiles= o.optInt("changed_files", 0);
                    pr.additions   = o.optInt("additions", 0);
                    pr.deletions   = o.optInt("deletions", 0);
                    JSONObject user = o.optJSONObject("user");
                    if (user != null) pr.authorLogin = user.optString("login", "");
                    JSONObject head = o.optJSONObject("head");
                    if (head != null) {
                        pr.headBranch = head.optString("ref", "");
                        pr.headSha    = head.optString("sha", "");
                    }
                    JSONObject base = o.optJSONObject("base");
                    if (base != null) pr.baseBranch = base.optString("ref", "");
                    list.add(pr);
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void getPRFiles(final String token, final String owner,
                                   final String repo, final int number,
                                   final Callback<List<PRFile>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/pulls/" + number + "/files?per_page=30", token);
                JSONArray arr = new JSONArray(resp);
                List<PRFile> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new PRFile(
                        o.optString("filename", ""),
                        o.optString("status", ""),
                        o.optString("patch", ""),
                        o.optInt("additions", 0),
                        o.optInt("deletions", 0),
                        o.optInt("changes", 0)
                    ));
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void getPRComments(final String token, final String owner,
                                      final String repo, final int number,
                                      final Callback<List<IssueComment>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/issues/" + number + "/comments?per_page=50", token);
                JSONArray arr = new JSONArray(resp);
                List<IssueComment> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    JSONObject user = o.optJSONObject("user");
                    list.add(new IssueComment(
                        o.getLong("id"),
                        o.optString("body", ""),
                        user != null ? user.optString("login", "") : "",
                        o.optString("created_at", "")
                    ));
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void addPRComment(final String token, final String owner,
                                     final String repo, final int number,
                                     final String body, final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("body", body);
                post(BASE + "/repos/" + owner + "/" + repo
                    + "/issues/" + number + "/comments", token, req.toString());
                cb.onResult(true, null);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    public static void mergePR(final String token, final String owner,
                                final String repo, final int number,
                                final String mergeMethod, final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("merge_method", mergeMethod); // "merge", "squash", "rebase"
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(BASE + "/repos/" + owner + "/" + repo
                        + "/pulls/" + number + "/merge").openConnection();
                conn.setRequestMethod("PUT");
                setAuth(conn, token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000); conn.setReadTimeout(15000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                cb.onResult(code == 200, code == 200 ? null : "HTTP " + code);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    public static void closePR(final String token, final String owner,
                                final String repo, final int number,
                                final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("state", "closed");
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(BASE + "/repos/" + owner + "/" + repo
                        + "/pulls/" + number).openConnection();
                conn.setRequestMethod("PATCH");
                setAuth(conn, token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                cb.onResult(conn.getResponseCode() == 200, null);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    // =========================================================================
    // COMMIT DIFF
    // =========================================================================

    public static class CommitDiffFile {
        public String filename, status, patch;
        public int additions, deletions;
        public CommitDiffFile(String f, String s, String p, int a, int d) {
            filename = f; status = s; patch = p; additions = a; deletions = d;
        }
    }

    public static void getCommitDiff(final String token, final String owner,
                                      final String repo, final String sha,
                                      final Callback<List<CommitDiffFile>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/commits/" + sha, token);
                JSONObject root = new JSONObject(resp);
                JSONArray files = root.optJSONArray("files");
                List<CommitDiffFile> list = new ArrayList<>();
                if (files != null) {
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject f = files.getJSONObject(i);
                        list.add(new CommitDiffFile(
                            f.optString("filename", ""),
                            f.optString("status", ""),
                            f.optString("patch", ""),
                            f.optInt("additions", 0),
                            f.optInt("deletions", 0)
                        ));
                    }
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    // =========================================================================
    // PROJECT DETECTION
    // =========================================================================

    public enum ProjectType {
        ANDROID, ANDROID_NDK, FLUTTER, NODE_JS, PYTHON, REACT_NATIVE, JAVA_LIB, DOTNET,
        RUST, GO, PHP, RUBY, DOCKER_ONLY, CPP_CMAKE, CPP_NDKBUILD, GENERIC
    }

    /**
     * RepoContext — hasil scan repo yang detail.
     * Dipakai untuk generate YAML yang benar-benar adaptive.
     */
    public static class RepoContext {
        public ProjectType type           = ProjectType.GENERIC;
        public String      label          = "Generic";

        // Node / JS
        public String  nodePackageManager = "npm";   // npm | yarn | pnpm
        public boolean hasLockFile        = false;
        public boolean hasTestScript      = false;
        public boolean hasBuildScript     = false;
        public String  nodeVersion        = "20";
        public String  nodeScripts        = "";      // raw scripts block from package.json

        // Python
        public boolean hasPyproject       = false;
        public boolean hasPoetry          = false;
        public boolean hasRequirements    = false;
        public boolean hasPytest          = false;
        public String  pythonVersion      = "3.11";

        // Java / Android
        public boolean hasGradlew         = false;
        public boolean hasMvnw            = false;
        public boolean hasPomXml          = false;
        public String  gradleVariant      = "assembleRelease"; // Android
        public String  javaVersion        = "17";

        // Rust
        public boolean hasCargoLock       = false;

        // Go
        public boolean hasGoSum           = false;

        // PHP
        public boolean hasComposerLock    = false;

        // Ruby
        public boolean hasGemfileLock     = false;

        // Docker
        public boolean hasDockerfile      = false;
        public boolean hasDockerCompose   = false;

        // C++ / NDK
        public boolean hasCMakeLists      = false;  // CMakeLists.txt di root
        public boolean hasAndroidMk       = false;  // Android.mk
        public boolean hasApplicationMk   = false;  // Application.mk
        public boolean hasJniDir          = false;  // folder jni/
        public boolean hasCppDir          = false;  // folder cpp/ atau src/main/cpp
        public boolean hasNdkInGradle     = false;  // externalNativeBuild di build.gradle
        public String  ndkVersion         = "r25c"; // default NDK version
        public String  cppStandard        = "c++17";

        // General
        public boolean hasMakefile        = false;
        public java.util.List<String> outputDirs = new java.util.ArrayList<>();
        public String detectionLog        = ""; // untuk debug/feedback ke user
    }

    /**
     * Scan repo dan return RepoContext yang detail.
     * Satu API call untuk list file, fetch file-file penting (package.json, dll).
     */
    public static void detectProjectContext(final String token, final String owner,
                                             final String repo, final String branch,
                                             final Callback<RepoContext> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            RepoContext ctx = new RepoContext();
            StringBuilder log = new StringBuilder();
            try {
                // Step 1: List root files
                String resp = get(BASE + "/repos/" + owner + "/" + repo
                    + "/contents/?ref=" + branch, token);
                JSONArray arr = new JSONArray(resp);
                java.util.Set<String> names = new java.util.HashSet<>();
                java.util.Set<String> namesFull = new java.util.HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    String n = arr.getJSONObject(i).optString("name", "");
                    names.add(n.toLowerCase());
                    namesFull.add(n);
                }
                log.append("Root files: ").append(names).append("\n");

                // ── ANDROID ─────────────────────────────────────────────────
                if (names.contains("app") || names.contains("build.gradle")
                        || names.contains("build.gradle.kts")) {
                    boolean isAndroid = false;
                    // Cek /app/build.gradle
                    if (names.contains("app")) {
                        try {
                            String aResp = get(BASE + "/repos/" + owner + "/" + repo
                                + "/contents/app?ref=" + branch, token);
                            JSONArray aArr = new JSONArray(aResp);
                            for (int i = 0; i < aArr.length(); i++) {
                                String n2 = aArr.getJSONObject(i).optString("name","").toLowerCase();
                                if (n2.equals("build.gradle") || n2.equals("build.gradle.kts")) {
                                    isAndroid = true; break;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    // Cek settings.gradle punya "application" plugin
                    if (!isAndroid && names.contains("settings.gradle")) {
                        try {
                            String sg = fetchFileContent(token, owner, repo, branch, "settings.gradle");
                            if (sg.contains("com.android.application") || sg.contains("com.android.library")) isAndroid = true;
                        } catch (Exception ignored) {}
                    }
                    if (isAndroid) {
                        ctx.type  = ProjectType.ANDROID;
                        ctx.label = "Android";
                        ctx.hasGradlew = names.contains("gradlew");
                        if (!ctx.hasGradlew) ctx.hasGradlew = names.contains("gradlew.bat");
                        ctx.gradleVariant = "assembleRelease";
                        // Cek apakah ada C++ native build
                        ctx.hasCMakeLists = names.contains("cmakelists.txt");
                        ctx.hasAndroidMk  = names.contains("android.mk");
                        ctx.hasJniDir     = names.contains("jni");
                        ctx.hasCppDir     = names.contains("cpp");
                        // Cek di folder app/src/main/ — CMakeLists.txt sering di sini
                        try {
                            String mainResp = get(BASE + "/repos/" + owner + "/" + repo
                                + "/contents/app/src/main?ref=" + branch, token);
                            JSONArray mainArr = new JSONArray(mainResp);
                            for (int i = 0; i < mainArr.length(); i++) {
                                String mn = mainArr.getJSONObject(i).optString("name","").toLowerCase();
                                if (mn.equals("cpp")) ctx.hasCppDir = true;
                                if (mn.equals("jni")) ctx.hasJniDir = true;
                                if (mn.equals("cmakelists.txt")) ctx.hasCMakeLists = true;
                                if (mn.equals("android.mk")) ctx.hasAndroidMk = true;
                            }
                        } catch (Exception ignored) {}
                        // Cek di folder app/src/main/cpp/ — lokasi paling umum CMakeLists.txt
                        if (!ctx.hasCMakeLists) {
                            try {
                                String cppResp = get(BASE + "/repos/" + owner + "/" + repo
                                    + "/contents/app/src/main/cpp?ref=" + branch, token);
                                JSONArray cppArr = new JSONArray(cppResp);
                                for (int i = 0; i < cppArr.length(); i++) {
                                    String cn = cppArr.getJSONObject(i).optString("name","").toLowerCase();
                                    if (cn.equals("cmakelists.txt")) ctx.hasCMakeLists = true;
                                    if (cn.equals("android.mk")) ctx.hasAndroidMk = true;
                                }
                            } catch (Exception ignored) {}
                        }
                        // Cek di folder jni/ — lokasi umum Android.mk
                        if (!ctx.hasAndroidMk && ctx.hasJniDir) {
                            try {
                                String jniResp = get(BASE + "/repos/" + owner + "/" + repo
                                    + "/contents/jni?ref=" + branch, token);
                                JSONArray jniArr = new JSONArray(jniResp);
                                for (int i = 0; i < jniArr.length(); i++) {
                                    String jn = jniArr.getJSONObject(i).optString("name","").toLowerCase();
                                    if (jn.equals("android.mk")) ctx.hasAndroidMk = true;
                                    if (jn.equals("application.mk")) ctx.hasApplicationMk = true;
                                }
                            } catch (Exception ignored) {}
                        }
                        // Cek app/jni/ — lokasi umum Android.mk di project AIDE
                        if (!ctx.hasAndroidMk) {
                            try {
                                String appJniResp = get(BASE + "/repos/" + owner + "/" + repo
                                    + "/contents/app/jni?ref=" + branch, token);
                                JSONArray appJniArr = new JSONArray(appJniResp);
                                for (int i = 0; i < appJniArr.length(); i++) {
                                    String ajn = appJniArr.getJSONObject(i).optString("name","").toLowerCase();
                                    if (ajn.equals("android.mk")) ctx.hasAndroidMk = true;
                                    if (ajn.equals("application.mk")) ctx.hasApplicationMk = true;
                                    if (ajn.equals("cmakelists.txt")) ctx.hasCMakeLists = true;
                                }
                            } catch (Exception ignored) {}
                        }
                        // Cek app/src/main/jni/
                        if (!ctx.hasAndroidMk) {
                            try {
                                String jniMainResp = get(BASE + "/repos/" + owner + "/" + repo
                                    + "/contents/app/src/main/jni?ref=" + branch, token);
                                JSONArray jniMainArr = new JSONArray(jniMainResp);
                                for (int i = 0; i < jniMainArr.length(); i++) {
                                    String jmn = jniMainArr.getJSONObject(i).optString("name","").toLowerCase();
                                    if (jmn.equals("android.mk")) ctx.hasAndroidMk = true;
                                    if (jmn.equals("application.mk")) ctx.hasApplicationMk = true;
                                    if (jmn.equals("cmakelists.txt")) ctx.hasCMakeLists = true;
                                }
                            } catch (Exception ignored) {}
                        }
                        // Cek externalNativeBuild di app/build.gradle dan build.gradle.kts
                        for (String bgPath : new String[]{"app/build.gradle", "app/build.gradle.kts"}) {
                            try {
                                String bg = fetchFileContent(token, owner, repo, branch, bgPath);
                                if (bg.contains("externalNativeBuild") || bg.contains("ndkBuild"))
                                    ctx.hasNdkInGradle = true;
                                // cmake block → ada CMakeLists.txt di suatu tempat
                                if (bg.contains("cmake {") || bg.contains("cmake{")
                                        || bg.contains("cmake(") || bg.contains("path("))
                                    ctx.hasCMakeLists = true;
                                // Parse cmake path property: path "src/main/cpp/CMakeLists.txt"
                                if (bg.contains("path") && bg.contains("CMakeLists.txt"))
                                    ctx.hasCMakeLists = true;
                                if (bg.contains("c++17") || bg.contains("cppFlags \"-std=c++17\""))
                                    ctx.cppStandard = "c++17";
                                else if (bg.contains("c++14") || bg.contains("cppFlags \"-std=c++14\""))
                                    ctx.cppStandard = "c++14";
                            } catch (Exception ignored) {}
                        }
                        if (ctx.hasCppDir || ctx.hasJniDir || ctx.hasNdkInGradle
                                || ctx.hasCMakeLists || ctx.hasAndroidMk) {
                            ctx.type  = ProjectType.ANDROID_NDK;
                            ctx.label = ctx.hasCMakeLists ? "Android + CMake (C++)"
                                      : ctx.hasAndroidMk  ? "Android + ndk-build (C++)"
                                      : "Android + Native (C++)";
                        }
                        log.append("Detected: ").append(ctx.label).append("\n");
                        cb.onResult(ctx, null); return;
                    }
                }

                // ── PURE C++ CMAKE (tanpa Android/Gradle) ────────────────────
                // Cek root dulu, lalu subdirektori umum: src/, source/, lib/
                boolean hasCMakeStandalone = names.contains("cmakelists.txt");
                if (!hasCMakeStandalone) {
                    for (String subdir : new String[]{"src", "source", "lib", "core"}) {
                        if (names.contains(subdir)) {
                            try {
                                String subResp = get(BASE + "/repos/" + owner + "/" + repo
                                    + "/contents/" + subdir + "?ref=" + branch, token);
                                JSONArray subArr = new JSONArray(subResp);
                                for (int i = 0; i < subArr.length(); i++) {
                                    if (subArr.getJSONObject(i).optString("name","")
                                            .equalsIgnoreCase("CMakeLists.txt")) {
                                        hasCMakeStandalone = true; break;
                                    }
                                }
                            } catch (Exception ignored) {}
                            if (hasCMakeStandalone) break;
                        }
                    }
                }
                if (hasCMakeStandalone && !names.contains("build.gradle")
                        && !names.contains("build.gradle.kts") && !names.contains("gradlew")) {
                    ctx.type         = ProjectType.CPP_CMAKE;
                    ctx.label        = "C++ (CMake)";
                    ctx.hasCMakeLists = true;
                    ctx.hasAndroidMk  = names.contains("android.mk");
                    ctx.hasMakefile   = names.contains("makefile") || names.contains("Makefile");
                    log.append("Detected: C++ CMake\n");
                    cb.onResult(ctx, null); return;
                }

                // ── STANDALONE NDK-BUILD (Android.mk tanpa Gradle) ───────────
                // Cek semua lokasi umum Android.mk: root, jni/, src/, src/jni/
                boolean hasAndroidMkAnywhere = names.contains("android.mk");
                boolean hasAppMkAnywhere     = names.contains("application.mk");
                boolean hasJniDirAnywhere    = names.contains("jni");
                if (!hasAndroidMkAnywhere) {
                    for (String subdir : new String[]{"jni", "src", "src/jni", "app/jni"}) {
                        try {
                            String subResp = get(BASE + "/repos/" + owner + "/" + repo
                                + "/contents/" + subdir + "?ref=" + branch, token);
                            JSONArray subArr = new JSONArray(subResp);
                            for (int i = 0; i < subArr.length(); i++) {
                                String fn = subArr.getJSONObject(i).optString("name","").toLowerCase();
                                if (fn.equals("android.mk")) hasAndroidMkAnywhere = true;
                                if (fn.equals("application.mk")) hasAppMkAnywhere = true;
                            }
                        } catch (Exception ignored) {}
                        if (hasAndroidMkAnywhere) break;
                    }
                }
                if ((hasAndroidMkAnywhere || hasJniDirAnywhere)
                        && !names.contains("build.gradle") && !names.contains("build.gradle.kts")
                        && !names.contains("gradlew")) {
                    ctx.type          = ProjectType.CPP_NDKBUILD;
                    ctx.label         = "C++ (ndk-build)";
                    ctx.hasAndroidMk  = hasAndroidMkAnywhere;
                    ctx.hasApplicationMk = hasAppMkAnywhere;
                    ctx.hasJniDir     = hasJniDirAnywhere;
                    log.append("Detected: C++ ndk-build\n");
                    cb.onResult(ctx, null); return;
                }

                // ── FLUTTER ──────────────────────────────────────────────────
                if (names.contains("pubspec.yaml")) {
                    ctx.type  = ProjectType.FLUTTER;
                    ctx.label = "Flutter";
                    // Verifikasi ini benar Flutter (bukan Dart library) + cek flutter SDK version
                    try {
                        String pubspec = fetchFileContent(token, owner, repo, branch, "pubspec.yaml");
                        if (!pubspec.contains("flutter")) {
                            // Ini Dart library biasa, bukan Flutter app
                            ctx.label = "Dart";
                        }
                    } catch (Exception ignored) {}
                    log.append("Detected: ").append(ctx.label).append("\n");
                    cb.onResult(ctx, null); return;
                }

                // ── NODE / REACT NATIVE ──────────────────────────────────────
                if (names.contains("package.json")) {
                    // Baca package.json untuk scripts dan dependencies
                    String pkgContent = "";
                    try {
                        pkgContent = fetchFileContent(token, owner, repo, branch, "package.json");
                    } catch (Exception ignored) {}
                    ctx.nodeScripts = pkgContent;

                    // Detect package manager dari lock file
                    if (names.contains("yarn.lock")) {
                        ctx.nodePackageManager = "yarn";
                        ctx.hasLockFile = true;
                    } else if (names.contains("pnpm-lock.yaml")) {
                        ctx.nodePackageManager = "pnpm";
                        ctx.hasLockFile = true;
                    } else if (names.contains("package-lock.json") || names.contains("npm-shrinkwrap.json")) {
                        ctx.nodePackageManager = "npm";
                        ctx.hasLockFile = true;
                    } else {
                        ctx.nodePackageManager = "npm";
                        ctx.hasLockFile = false; // pakai npm install bukan npm ci
                    }

                    // Cek .nvmrc atau .node-version
                    if (names.contains(".nvmrc")) {
                        try {
                            String nv = fetchFileContent(token, owner, repo, branch, ".nvmrc").trim();
                            if (nv.matches("\\d+.*")) ctx.nodeVersion = nv.split("\\.")[0];
                        } catch (Exception ignored) {}
                    }

                    // Parse scripts dari package.json
                    try {
                        JSONObject pkg = new JSONObject(pkgContent);
                        JSONObject scripts = pkg.optJSONObject("scripts");
                        if (scripts != null) {
                            ctx.hasTestScript  = scripts.has("test");
                            ctx.hasBuildScript = scripts.has("build");
                        }
                        // Cek engines.node
                        JSONObject engines = pkg.optJSONObject("engines");
                        if (engines != null && engines.has("node")) {
                            String nv = engines.getString("node").replaceAll("[^0-9.]","");
                            if (!nv.isEmpty()) ctx.nodeVersion = nv.split("\\.")[0];
                        }
                        // React Native
                        JSONObject deps = pkg.optJSONObject("dependencies");
                        JSONObject devDeps = pkg.optJSONObject("devDependencies");
                        boolean isRN = (deps != null && deps.has("react-native"))
                            || (devDeps != null && devDeps.has("react-native"));
                        // Konfirmasi dengan cek folder android/ dan ios/
                        boolean hasAndroidDir = names.contains("android");
                        boolean hasIosDir     = names.contains("ios");
                        if (!isRN && (hasAndroidDir || hasIosDir)) {
                            // Mungkin RN tanpa explicit react-native di deps root
                            // cek android/app/build.gradle
                            try {
                                String abg = fetchFileContent(token, owner, repo, branch, "android/app/build.gradle");
                                if (abg.contains("react-native") || abg.contains("com.facebook.react"))
                                    isRN = true;
                            } catch (Exception ignored) {}
                        }
                        if (isRN) {
                            ctx.type  = ProjectType.REACT_NATIVE;
                            ctx.label = "React Native";
                            ctx.hasLockFile = names.contains("package-lock.json") || names.contains("yarn.lock");
                            log.append("Detected: React Native\n");
                            cb.onResult(ctx, null); return;
                        }
                        // Expo
                        boolean isExpo = (deps != null && deps.has("expo"))
                            || (devDeps != null && devDeps.has("expo"));
                        if (isExpo) {
                            ctx.type  = ProjectType.REACT_NATIVE;
                            ctx.label = "Expo (React Native)";
                            log.append("Detected: Expo\n");
                            cb.onResult(ctx, null); return;
                        }
                    } catch (Exception ignored) {}

                    ctx.type  = ProjectType.NODE_JS;
                    // Cek TypeScript
                    boolean isTS = names.contains("tsconfig.json") || names.contains("tsconfig.base.json");
                    ctx.label = isTS ? "Node.js (TypeScript)" : "Node.js";
                    log.append("Detected: ").append(ctx.label).append("\n")
                       .append("  packageManager=").append(ctx.nodePackageManager)
                       .append(", hasLockFile=").append(ctx.hasLockFile)
                       .append(", test=").append(ctx.hasTestScript)
                       .append(", build=").append(ctx.hasBuildScript).append("\n");
                    cb.onResult(ctx, null); return;
                }

                // ── PYTHON ───────────────────────────────────────────────────
                if (names.contains("requirements.txt") || names.contains("setup.py")
                        || names.contains("pyproject.toml") || names.contains("pipfile")) {
                    ctx.type  = ProjectType.PYTHON;
                    ctx.label = "Python";
                    ctx.hasRequirements = names.contains("requirements.txt");
                    ctx.hasPyproject    = names.contains("pyproject.toml");
                    // Cek poetry
                    if (ctx.hasPyproject) {
                        try {
                            String py = fetchFileContent(token, owner, repo, branch, "pyproject.toml");
                            ctx.hasPoetry = py.contains("[tool.poetry]") || py.contains("poetry");
                        } catch (Exception ignored) {}
                    }
                    // Cek .python-version
                    if (names.contains(".python-version")) {
                        try {
                            String pv = fetchFileContent(token, owner, repo, branch, ".python-version").trim();
                            if (pv.matches("\\d+\\.\\d+.*")) ctx.pythonVersion = pv;
                        } catch (Exception ignored) {}
                    }
                    // Cek apakah pytest dipakai
                    ctx.hasPytest = names.contains("pytest.ini") || names.contains("setup.cfg")
                        || names.contains("tests") || names.contains("test");
                    if (!ctx.hasPytest && ctx.hasRequirements) {
                        try {
                            String req = fetchFileContent(token, owner, repo, branch, "requirements.txt");
                            ctx.hasPytest = req.toLowerCase().contains("pytest");
                        } catch (Exception ignored) {}
                    }
                    log.append("Detected: Python\n")
                       .append("  poetry=").append(ctx.hasPoetry)
                       .append(", pytest=").append(ctx.hasPytest).append("\n");
                    cb.onResult(ctx, null); return;
                }

                // ── RUST ─────────────────────────────────────────────────────
                if (names.contains("cargo.toml")) {
                    ctx.type  = ProjectType.RUST;
                    ctx.label = "Rust";
                    ctx.hasCargoLock = names.contains("cargo.lock");
                    log.append("Detected: Rust\n");
                    cb.onResult(ctx, null); return;
                }

                // ── GO ───────────────────────────────────────────────────────
                if (names.contains("go.mod")) {
                    ctx.type  = ProjectType.GO;
                    ctx.label = "Go";
                    ctx.hasGoSum = names.contains("go.sum");
                    log.append("Detected: Go\n");
                    cb.onResult(ctx, null); return;
                }

                // ── PHP ──────────────────────────────────────────────────────
                if (names.contains("composer.json")) {
                    ctx.type  = ProjectType.PHP;
                    ctx.label = "PHP";
                    ctx.hasComposerLock = names.contains("composer.lock");
                    log.append("Detected: PHP\n");
                    cb.onResult(ctx, null); return;
                }

                // ── RUBY ─────────────────────────────────────────────────────
                if (names.contains("gemfile")) {
                    ctx.type  = ProjectType.RUBY;
                    ctx.label = "Ruby";
                    ctx.hasGemfileLock = names.contains("gemfile.lock");
                    log.append("Detected: Ruby\n");
                    cb.onResult(ctx, null); return;
                }

                // ── JAVA / KOTLIN LIBRARY ─────────────────────────────────────
                if (names.contains("pom.xml") || names.contains("build.gradle")
                        || names.contains("build.gradle.kts")) {
                    ctx.type     = ProjectType.JAVA_LIB;
                    ctx.label    = "Java/Kotlin";
                    ctx.hasPomXml  = names.contains("pom.xml");
                    ctx.hasGradlew = names.contains("gradlew");
                    ctx.hasMvnw    = names.contains("mvnw");
                    log.append("Detected: Java/Kotlin\n");
                    cb.onResult(ctx, null); return;
                }

                // ── .NET ─────────────────────────────────────────────────────
                boolean isDotnet = names.contains("global.json") || names.contains("nuget.config");
                if (!isDotnet) {
                    for (String n : namesFull) {
                        if (n.endsWith(".csproj") || n.endsWith(".sln") || n.endsWith(".fsproj")) {
                            isDotnet = true; break;
                        }
                    }
                }
                if (isDotnet) {
                    ctx.type  = ProjectType.DOTNET;
                    ctx.label = ".NET";
                    log.append("Detected: .NET\n");
                    cb.onResult(ctx, null); return;
                }

                // ── DOCKER ONLY ───────────────────────────────────────────────
                if (names.contains("dockerfile")) {
                    ctx.type  = ProjectType.DOCKER_ONLY;
                    ctx.label = "Docker";
                    ctx.hasDockerfile = true;
                    ctx.hasDockerCompose = names.contains("docker-compose.yml")
                        || names.contains("docker-compose.yaml");
                    log.append("Detected: Docker\n");
                    cb.onResult(ctx, null); return;
                }

                // ── GENERIC ───────────────────────────────────────────────────
                ctx.type  = ProjectType.GENERIC;
                ctx.label = "Generic";
                ctx.hasMakefile = names.contains("makefile");
                ctx.hasDockerfile = names.contains("dockerfile");
                log.append("Detected: Generic (no known marker found)\n");
                cb.onResult(ctx, null);

            } catch (Exception e) {
                log.append("Error: ").append(e.getMessage());
                ctx.detectionLog = log.toString();
                cb.onResult(ctx, e.getMessage());
            }
        });
    }

    /** Fetch raw text content of a file from repo. */
    private static String fetchFileContent(String token, String owner, String repo,
                                            String branch, String path) throws Exception {
        String resp = get(BASE + "/repos/" + owner + "/" + repo
            + "/contents/" + path + "?ref=" + branch, token);
        String enc = new JSONObject(resp).optString("content", "")
            .replace("\n","").replace("\r","");
        if (enc.isEmpty()) return "";
        return new String(Base64.decode(enc, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    /**
     * Generate YAML yang adaptive berdasarkan RepoContext nyata.
     * Tidak ada hardcoded template — setiap step diputuskan dari data repo.
     */
    public static String generateAdaptiveYaml(RepoContext ctx, boolean hasSigning) {
        switch (ctx.type) {
            case ANDROID:      return buildAndroidYamlAdaptive(ctx, hasSigning);
            case ANDROID_NDK:  return buildAndroidNdkYamlAdaptive(ctx, hasSigning);
            case FLUTTER:      return buildFlutterYamlAdaptive(ctx);
            case NODE_JS:      return buildNodeYamlAdaptive(ctx);
            case REACT_NATIVE: return buildReactNativeYamlAdaptive(ctx);
            case PYTHON:       return buildPythonYamlAdaptive(ctx);
            case JAVA_LIB:     return buildJavaYamlAdaptive(ctx);
            case DOTNET:       return buildDotnetYamlAdaptive(ctx);
            case RUST:         return buildRustYamlAdaptive(ctx);
            case GO:           return buildGoYamlAdaptive(ctx);
            case PHP:          return buildPhpYamlAdaptive(ctx);
            case RUBY:         return buildRubyYamlAdaptive(ctx);
            case DOCKER_ONLY:  return buildDockerYamlAdaptive(ctx);
            case CPP_CMAKE:    return buildCppCmakeYamlAdaptive(ctx);
            case CPP_NDKBUILD: return buildCppNdkBuildYamlAdaptive(ctx);
            default:           return buildGenericYamlAdaptive(ctx);
        }
    }


    // ── ANDROID ──────────────────────────────────────────────────────────────
    private static String buildAndroidYamlAdaptive(RepoContext ctx, boolean hasSigning) {
        String gradleCmd = ctx.hasGradlew ? "./gradlew" : "gradle";
        String s = "name: Android Build\n\non:\n"
            + "  push:\n    branches: [\"" + "main" + "\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: actions/setup-java@v4\n"
            + "        with:\n          java-version: '" + ctx.javaVersion + "'\n"
            + "          distribution: temurin\n          cache: gradle\n";
        if (ctx.hasGradlew) s += "      - run: chmod +x gradlew\n";
        s += "      - name: Build Release APK\n"
            + "        run: " + gradleCmd + " " + ctx.gradleVariant + " --no-daemon\n"
            + "      - uses: actions/upload-artifact@v4\n"
            + "        with:\n          name: apk\n"
            + "          path: app/build/outputs/apk/release/\n";
        return s;
    }

    // ── FLUTTER ──────────────────────────────────────────────────────────────
    private static String buildFlutterYamlAdaptive(RepoContext ctx) {
        return "name: Flutter Build\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: subosito/flutter-action@v2\n"
            + "        with:\n          channel: stable\n"
            + "      - run: flutter pub get\n"
            + "      - run: flutter test\n"
            + "      - run: flutter build apk --release\n"
            + "      - uses: actions/upload-artifact@v4\n"
            + "        with:\n          name: flutter-apk\n"
            + "          path: build/app/outputs/flutter-apk/\n"
            + "          if-no-files-found: ignore\n";
    }

    // ── NODE.JS — fully adaptive ──────────────────────────────────────────────
    private static String buildNodeYamlAdaptive(RepoContext ctx) {
        // Pilih install command sesuai package manager + lock file
        String installCmd;
        String cacheKey;
        String setupExtra = "";
        switch (ctx.nodePackageManager) {
            case "yarn":
                installCmd = "yarn install --frozen-lockfile";
                cacheKey   = "yarn";
                break;
            case "pnpm":
                installCmd = "pnpm install --frozen-lockfile";
                cacheKey   = "pnpm";
                setupExtra = "      - uses: pnpm/action-setup@v4\n"
                           + "        with:\n          version: latest\n";
                break;
            default: // npm
                installCmd = ctx.hasLockFile ? "npm ci" : "npm install";
                cacheKey   = "npm";
                break;
        }

        String s = "name: Node.js CI\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + setupExtra
            + "      - uses: actions/setup-node@v4\n"
            + "        with:\n          node-version: '" + ctx.nodeVersion + "'\n"
            + "          cache: '" + cacheKey + "'\n"
            + "      - name: Install dependencies\n"
            + "        run: " + installCmd + "\n";

        if (ctx.hasTestScript) {
            s += "      - name: Run tests\n"
              +  "        run: " + (ctx.nodePackageManager.equals("yarn") ? "yarn test"
                                : ctx.nodePackageManager.equals("pnpm") ? "pnpm test"
                                : "npm test") + "\n";
        }

        if (ctx.hasBuildScript) {
            s += "      - name: Build\n"
              +  "        run: " + (ctx.nodePackageManager.equals("yarn") ? "yarn build"
                                : ctx.nodePackageManager.equals("pnpm") ? "pnpm run build"
                                : "npm run build") + "\n"
              +  "      - name: Upload build output\n"
              +  "        uses: actions/upload-artifact@v4\n"
              +  "        with:\n          name: node-build\n"
              +  "          path: |\n"
              +  "            dist/\n"
              +  "            build/\n"
              +  "            out/\n"
              +  "          if-no-files-found: ignore\n";
        }
        return s;
    }

    // ── REACT NATIVE ─────────────────────────────────────────────────────────
    private static String buildReactNativeYamlAdaptive(RepoContext ctx) {
        String installCmd = ctx.hasLockFile ? "npm ci" : "npm install";
        return "name: React Native Build\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: actions/setup-node@v4\n"
            + "        with:\n          node-version: '" + ctx.nodeVersion + "'\n          cache: npm\n"
            + "      - run: " + installCmd + "\n"
            + "      - uses: actions/setup-java@v4\n"
            + "        with:\n          java-version: '17'\n          distribution: temurin\n"
            + "      - run: cd android && ./gradlew assembleRelease --no-daemon\n"
            + "      - uses: actions/upload-artifact@v4\n"
            + "        with:\n          name: rn-apk\n"
            + "          path: android/app/build/outputs/apk/release/\n";
    }

    // ── PYTHON — fully adaptive ───────────────────────────────────────────────
    private static String buildPythonYamlAdaptive(RepoContext ctx) {
        String s = "name: Python CI\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: actions/setup-python@v5\n"
            + "        with:\n          python-version: '" + ctx.pythonVersion + "'\n"
            + "      - name: Install dependencies\n"
            + "        run: |\n"
            + "          python -m pip install --upgrade pip\n";

        if (ctx.hasPoetry) {
            s += "          pip install poetry\n"
              +  "          poetry install\n";
        } else if (ctx.hasRequirements) {
            s += "          pip install -r requirements.txt\n";
        } else if (ctx.hasPyproject) {
            s += "          pip install -e .\n";
        }

        if (ctx.hasPytest) {
            s += "      - name: Run tests\n"
              +  "        run: |\n"
              +  "          if [ -d tests ]; then python -m pytest tests/ -v; fi\n"
              +  "          if [ -d test ]; then python -m pytest test/ -v; fi\n";
        }
        return s;
    }

    // ── JAVA / KOTLIN LIBRARY ─────────────────────────────────────────────────
    private static String buildJavaYamlAdaptive(RepoContext ctx) {
        String s = "name: Java Build\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: actions/setup-java@v4\n"
            + "        with:\n          java-version: '" + ctx.javaVersion + "'\n"
            + "          distribution: temurin\n";

        if (ctx.hasPomXml) {
            String mvn = ctx.hasMvnw ? "./mvnw" : "mvn";
            s += "          cache: maven\n"
              +  "      - name: Build with Maven\n"
              +  "        run: " + mvn + " --batch-mode -DskipTests package\n"
              +  "      - name: Run tests\n"
              +  "        run: " + mvn + " --batch-mode test\n"
              +  "      - uses: actions/upload-artifact@v4\n"
              +  "        with:\n          name: jar\n          path: target/*.jar\n"
              +  "          if-no-files-found: ignore\n";
        } else {
            String gradle = ctx.hasGradlew ? "./gradlew" : "gradle";
            s += "          cache: gradle\n";
            if (ctx.hasGradlew) s += "      - run: chmod +x gradlew\n";
            s += "      - name: Build with Gradle\n"
              +  "        run: " + gradle + " build --no-daemon\n"
              +  "      - uses: actions/upload-artifact@v4\n"
              +  "        with:\n          name: jar\n          path: build/libs/\n"
              +  "          if-no-files-found: ignore\n";
        }
        return s;
    }

    // ── .NET ─────────────────────────────────────────────────────────────────
    private static String buildDotnetYamlAdaptive(RepoContext ctx) {
        return "name: .NET Build\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: actions/setup-dotnet@v4\n"
            + "        with:\n          dotnet-version: '8.x'\n"
            + "      - run: dotnet restore\n"
            + "      - run: dotnet build --no-restore\n"
            + "      - run: dotnet test --no-build --verbosity normal\n"
            + "      - run: dotnet publish -c Release -o ./publish\n"
            + "      - uses: actions/upload-artifact@v4\n"
            + "        with:\n          name: dotnet-publish\n          path: publish/\n"
            + "          if-no-files-found: ignore\n";
    }

    // ── RUST ─────────────────────────────────────────────────────────────────
    private static String buildRustYamlAdaptive(RepoContext ctx) {
        return "name: Rust Build\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: dtolnay/rust-toolchain@stable\n"
            + "      - uses: Swatinem/rust-cache@v2\n"
            + "      - run: cargo build --release\n"
            + "      - run: cargo test\n"
            + "      - uses: actions/upload-artifact@v4\n"
            + "        with:\n          name: rust-binary\n"
            + "          path: target/release/\n"
            + "          if-no-files-found: ignore\n";
    }

    // ── GO ───────────────────────────────────────────────────────────────────
    private static String buildGoYamlAdaptive(RepoContext ctx) {
        return "name: Go Build\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: actions/setup-go@v5\n"
            + "        with:\n          go-version: stable\n          cache: true\n"
            + "      - run: go mod download\n"
            + "      - run: go build ./...\n"
            + "      - run: go test ./...\n"
            + "      - uses: actions/upload-artifact@v4\n"
            + "        with:\n          name: go-binary\n"
            + "          path: |\n            *.exe\n            ./bin/\n"
            + "          if-no-files-found: ignore\n";
    }

    // ── PHP ──────────────────────────────────────────────────────────────────
    private static String buildPhpYamlAdaptive(RepoContext ctx) {
        String install = ctx.hasComposerLock ? "composer install --no-interaction --prefer-dist"
                                             : "composer install --no-interaction";
        return "name: PHP CI\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: shivammathur/setup-php@v2\n"
            + "        with:\n          php-version: '8.3'\n"
            + "      - run: " + install + "\n"
            + "      - name: Run tests\n"
            + "        run: |\n"
            + "          if [ -f vendor/bin/phpunit ]; then vendor/bin/phpunit; fi\n"
            + "          if [ -f artisan ]; then php artisan test; fi\n";
    }

    // ── RUBY ─────────────────────────────────────────────────────────────────
    private static String buildRubyYamlAdaptive(RepoContext ctx) {
        String install = ctx.hasGemfileLock ? "bundle install" : "bundle install";
        return "name: Ruby CI\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: ruby/setup-ruby@v1\n"
            + "        with:\n          ruby-version: '3.3'\n          bundler-cache: true\n"
            + "      - run: " + install + "\n"
            + "      - name: Run tests\n"
            + "        run: |\n"
            + "          if [ -f Rakefile ]; then bundle exec rake test; fi\n"
            + "          if [ -f spec ]; then bundle exec rspec; fi\n";
    }

    // ── DOCKER ───────────────────────────────────────────────────────────────
    private static String buildDockerYamlAdaptive(RepoContext ctx) {
        return "name: Docker Build\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: docker/setup-buildx-action@v3\n"
            + "      - name: Build Docker image\n"
            + "        run: docker build -t app:latest .\n"
            + (ctx.hasDockerCompose
                ? "      - name: Test with Docker Compose\n"
                + "        run: docker compose up --abort-on-container-exit\n"
                : "");
    }

    // ── GENERIC ──────────────────────────────────────────────────────────────

    // ── ANDROID + NDK (CMake atau ndk-build) ─────────────────────────────────
    private static String buildAndroidNdkYamlAdaptive(RepoContext ctx, boolean hasSigning) {
        String gradleCmd = ctx.hasGradlew ? "./gradlew" : "gradle";
        // NDK version default r25c, bisa di-override
        String ndkVer = ctx.ndkVersion != null && !ctx.ndkVersion.isEmpty()
            ? ctx.ndkVersion : "r25c";
        String s = "name: Android Native Build\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - uses: actions/setup-java@v4\n"
            + "        with:\n          java-version: '" + ctx.javaVersion + "'\n"
            + "          distribution: temurin\n          cache: gradle\n"
            + "      - name: Setup Android NDK\n"
            + "        uses: nttld/setup-ndk@v1\n"
            + "        with:\n          ndk-version: " + ndkVer + "\n"
            + "          add-to-path: true\n";
        if (ctx.hasGradlew) s += "      - run: chmod +x gradlew\n";
        s += "      - name: Build Release APK\n"
          +  "        run: " + gradleCmd + " " + ctx.gradleVariant + " --no-daemon\n"
          +  "      - uses: actions/upload-artifact@v4\n"
          +  "        with:\n          name: apk\n"
          +  "          path: app/build/outputs/apk/release/\n"
          +  "          if-no-files-found: ignore\n";
        return s;
    }

    // ── PURE C++ CMAKE (standalone, bukan Android) ────────────────────────────
    private static String buildCppCmakeYamlAdaptive(RepoContext ctx) {
        return "name: C++ CMake Build\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - name: Install build tools\n"
            + "        run: sudo apt-get install -y cmake ninja-build build-essential\n"
            + "      - name: Configure\n"
            + "        run: cmake -B build -DCMAKE_BUILD_TYPE=Release\n"
            + "      - name: Build\n"
            + "        run: cmake --build build --config Release -- -j$(nproc)\n"
            + "      - name: Test\n"
            + "        run: |\n"
            + "          if [ -f build/CTestTestfile.cmake ]; then cd build && ctest --output-on-failure; fi\n"
            + "      - uses: actions/upload-artifact@v4\n"
            + "        with:\n          name: cpp-build\n"
            + "          path: build/\n"
            + "          if-no-files-found: ignore\n";
    }

    // ── STANDALONE NDK-BUILD (Android.mk / jni/) ──────────────────────────────
    private static String buildCppNdkBuildYamlAdaptive(RepoContext ctx) {
        String ndkVer = ctx.ndkVersion != null && !ctx.ndkVersion.isEmpty()
            ? ctx.ndkVersion : "r25c";
        // Tentukan path Android.mk
        String mkPath = ctx.hasAndroidMk ? "." : (ctx.hasJniDir ? "./jni" : ".");
        String appMkLine = ctx.hasApplicationMk
            ? "          APP_BUILD_SCRIPT=" + (ctx.hasJniDir ? "jni/Android.mk" : "Android.mk") + "\n"
            + "          NDK_APPLICATION_MK=" + (ctx.hasJniDir ? "jni/Application.mk" : "Application.mk") + "\n"
            : "          APP_BUILD_SCRIPT=" + (ctx.hasJniDir ? "jni/Android.mk" : "Android.mk") + "\n";
        // ndk-build menerima args sebagai parameter, bukan multiline
        String mkArg = ctx.hasJniDir ? "jni/Android.mk" : (ctx.hasAndroidMk ? "Android.mk" : "Android.mk");
        String appMkArg = ctx.hasApplicationMk
            ? " APP_BUILD_SCRIPT=" + mkArg + (ctx.hasJniDir ? " NDK_APPLICATION_MK=jni/Application.mk" : " NDK_APPLICATION_MK=Application.mk")
            : " APP_BUILD_SCRIPT=" + mkArg;
        return "name: NDK Build\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n"
            + "      - name: Setup Android NDK\n"
            + "        uses: nttld/setup-ndk@v1\n"
            + "        id: setup-ndk\n"
            + "        with:\n          ndk-version: " + ndkVer + "\n"
            + "          add-to-path: true\n"
            + "      - name: Build with ndk-build\n"
            + "        run: |\n"
            + "          NDK_PATH=\"${{ steps.setup-ndk.outputs.ndk-path }}\"\n"
            + "          \"$NDK_PATH/ndk-build\"" + appMkArg + " NDK_PROJECT_PATH=." + " NDK_LIBS_OUT=./libs\n"
            + "      - uses: actions/upload-artifact@v4\n"
            + "        with:\n          name: native-libs\n"
            + "          path: libs/\n"
            + "          if-no-files-found: ignore\n";
    }

    private static String buildGenericYamlAdaptive(RepoContext ctx) {
        String s = "name: CI\n\non:\n"
            + "  push:\n    branches: [\"main\"]\n"
            + "  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
            + "    steps:\n"
            + "      - uses: actions/checkout@v4\n";
        if (ctx.hasMakefile) {
            s += "      - name: Build with Make\n        run: make\n";
        } else {
            s += "      - name: Run build\n"
              +  "        run: echo \"No build system detected. Add your build commands here.\"\n";
        }
        return s;
    }


    // =========================================================================
    // SEARCH & EXPLORE
    // =========================================================================

    public static class SearchRepo {
        public String fullName, name, owner, description, language, defaultBranch;
        public int stars, forks, openIssues;
        public boolean isPrivate, isFork;
        public String pushedAt, createdAt, topics;
        public long size;
        public SearchRepo() {}
    }

    public static class SearchUser {
        public String login, avatarUrl, type, bio, company, location, blog;
        public int publicRepos, followers, following;
        public SearchUser() {}
    }

    public static void searchRepos(final String token, final String query,
                                    final String language, final String sort,
                                    final int page, final Callback<List<SearchRepo>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String q = query.trim().isEmpty() ? "stars:>100" : query;
                if (language != null && !language.isEmpty())
                    q += "+language:" + language.replace(" ", "+");
                String sortStr = (sort != null && !sort.isEmpty()) ? sort : "stars";
                String url = BASE + "/search/repositories?q=" + java.net.URLEncoder.encode(q, "UTF-8")
                    + "&sort=" + sortStr + "&order=desc&per_page=20&page=" + page;
                String resp = get(url, token);
                JSONArray items = new JSONObject(resp).getJSONArray("items");
                List<SearchRepo> list = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    SearchRepo r = new SearchRepo();
                    r.fullName     = o.optString("full_name", "");
                    r.name         = o.optString("name", "");
                    r.description  = o.optString("description", "");
                    r.language     = o.optString("language", "");
                    r.defaultBranch= o.optString("default_branch", "main");
                    r.stars        = o.optInt("stargazers_count", 0);
                    r.forks        = o.optInt("forks_count", 0);
                    r.openIssues   = o.optInt("open_issues_count", 0);
                    r.isPrivate    = o.optBoolean("private", false);
                    r.isFork       = o.optBoolean("fork", false);
                    r.pushedAt     = o.optString("pushed_at", "");
                    r.createdAt    = o.optString("created_at", "");
                    r.size         = o.optLong("size", 0);
                    JSONObject owner = o.optJSONObject("owner");
                    if (owner != null) r.owner = owner.optString("login", "");
                    JSONArray topicArr = o.optJSONArray("topics");
                    if (topicArr != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < Math.min(topicArr.length(), 4); j++) {
                            if (j > 0) sb.append(", ");
                            sb.append(topicArr.getString(j));
                        }
                        r.topics = sb.toString();
                    }
                    list.add(r);
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void searchUsers(final String token, final String query,
                                    final Callback<List<SearchUser>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String url = BASE + "/search/users?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                    + "&per_page=20";
                String resp = get(url, token);
                JSONArray items = new JSONObject(resp).getJSONArray("items");
                List<SearchUser> list = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    SearchUser u = new SearchUser();
                    u.login     = o.optString("login", "");
                    u.avatarUrl = o.optString("avatar_url", "");
                    u.type      = o.optString("type", "User");
                    list.add(u);
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void getUserProfile(final String token, final String username,
                                       final Callback<SearchUser> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/users/" + username, token);
                JSONObject o = new JSONObject(resp);
                SearchUser u = new SearchUser();
                u.login       = o.optString("login", "");
                u.avatarUrl   = o.optString("avatar_url", "");
                u.type        = o.optString("type", "User");
                u.bio         = o.optString("bio", "");
                u.company     = o.optString("company", "");
                u.location    = o.optString("location", "");
                u.blog        = o.optString("blog", "");
                u.publicRepos = o.optInt("public_repos", 0);
                u.followers   = o.optInt("followers", 0);
                u.following   = o.optInt("following", 0);
                cb.onResult(u, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    public static void getUserPublicRepos(final String token, final String username,
                                           final Callback<List<SearchRepo>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/users/" + username
                    + "/repos?sort=updated&per_page=30", token);
                JSONArray arr = new JSONArray(resp);
                List<SearchRepo> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    SearchRepo r = new SearchRepo();
                    r.fullName      = o.optString("full_name", "");
                    r.name          = o.optString("name", "");
                    r.description   = o.optString("description", "");
                    r.language      = o.optString("language", "");
                    r.defaultBranch = o.optString("default_branch", "main");
                    r.stars         = o.optInt("stargazers_count", 0);
                    r.forks         = o.optInt("forks_count", 0);
                    r.isPrivate     = o.optBoolean("private", false);
                    r.isFork        = o.optBoolean("fork", false);
                    r.pushedAt      = o.optString("pushed_at", "");
                    r.owner         = username;
                    list.add(r);
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    /** Trending = most starred repos created in last 7 days. */
    public static void getTrendingRepos(final String token, final String language,
                                         final Callback<List<SearchRepo>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                // Last 7 days
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.add(java.util.Calendar.DAY_OF_YEAR, -7);
                String date = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.getDefault()).format(cal.getTime());
                String q = "created:>" + date;
                if (language != null && !language.isEmpty())
                    q += " language:" + language;
                String url = BASE + "/search/repositories?q=" + java.net.URLEncoder.encode(q, "UTF-8")
                    + "&sort=stars&order=desc&per_page=20";
                String resp = get(url, token);
                JSONArray items = new JSONObject(resp).getJSONArray("items");
                List<SearchRepo> list = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    SearchRepo r = new SearchRepo();
                    r.fullName      = o.optString("full_name", "");
                    r.name          = o.optString("name", "");
                    r.description   = o.optString("description", "");
                    r.language      = o.optString("language", "");
                    r.defaultBranch = o.optString("default_branch", "main");
                    r.stars         = o.optInt("stargazers_count", 0);
                    r.forks         = o.optInt("forks_count", 0);
                    r.isPrivate     = o.optBoolean("private", false);
                    r.pushedAt      = o.optString("pushed_at", "");
                    JSONObject owner = o.optJSONObject("owner");
                    if (owner != null) r.owner = owner.optString("login", "");
                    JSONArray topicArr = o.optJSONArray("topics");
                    if (topicArr != null && topicArr.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < Math.min(topicArr.length(), 3); j++) {
                            if (j > 0) sb.append(", ");
                            sb.append(topicArr.getString(j));
                        }
                        r.topics = sb.toString();
                    }
                    list.add(r);
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    /** Fork a public repo into the authenticated user's account. */
    public static void forkRepo(final String token, final String owner,
                                 final String repo, final Callback<String> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = post(BASE + "/repos/" + owner + "/" + repo + "/forks",
                    token, "{}");
                String forkedName = new JSONObject(resp).optString("full_name", "");
                cb.onResult(forkedName, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    /** Star a repo. */
    public static void starRepo(final String token, final String owner,
                                 final String repo, final boolean star,
                                 final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String url = BASE + "/user/starred/" + owner + "/" + repo;
                if (star) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("PUT");
                    setAuth(conn, token);
                    conn.setRequestProperty("Content-Length", "0");
                    conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
                    conn.connect();
                    cb.onResult(conn.getResponseCode() == 204, null);
                } else {
                    cb.onResult(delete(url, token, null) == 204, null);
                }
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }

    /** Check if current user starred a repo. */
    public static void isRepoStarred(final String token, final String owner,
                                      final String repo, final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(BASE + "/user/starred/" + owner + "/" + repo).openConnection();
                conn.setRequestMethod("GET");
                setAuth(conn, token);
                conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                cb.onResult(code == 204, null);
            } catch (Exception e) { cb.onResult(false, e.getMessage()); }
        });
    }


    // =========================================================================
    // STARRED REPOS
    // =========================================================================

    /** List repos starred by the authenticated user, paginated (up to 50). */
    public static void listStarredRepos(final String token,
                                         final Callback<List<SearchRepo>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/user/starred?per_page=50&sort=updated", token);
                JSONArray arr = new JSONArray(resp);
                List<SearchRepo> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    SearchRepo r = new SearchRepo();
                    r.fullName      = o.optString("full_name", "");
                    r.name          = o.optString("name", "");
                    r.description   = o.optString("description", "");
                    r.language      = o.optString("language", "");
                    r.defaultBranch = o.optString("default_branch", "main");
                    r.stars         = o.optInt("stargazers_count", 0);
                    r.forks         = o.optInt("forks_count", 0);
                    r.openIssues    = o.optInt("open_issues_count", 0);
                    r.isPrivate     = o.optBoolean("private", false);
                    r.isFork        = o.optBoolean("fork", false);
                    r.pushedAt      = o.optString("pushed_at", "");
                    JSONObject owner = o.optJSONObject("owner");
                    if (owner != null) r.owner = owner.optString("login", "");
                    JSONArray topicArr = o.optJSONArray("topics");
                    if (topicArr != null && topicArr.length() > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < Math.min(topicArr.length(), 3); j++) {
                            if (j > 0) sb.append(", ");
                            sb.append(topicArr.getString(j));
                        }
                        r.topics = sb.toString();
                    }
                    list.add(r);
                }
                cb.onResult(list, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }

    // =========================================================================
    // README
    // =========================================================================

    /**
     * Fetch README content for a repo (decoded text).
     * Tries README.md, readme.md, README, README.rst in order.
     * Callback: onResult(markdownText, null) or onResult(null, "not found").
     */
    public static void getReadme(final String token, final String owner,
                                  final String repo, final String branch,
                                  final Callback<String> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            String[] candidates = {"README.md", "readme.md", "Readme.md",
                                   "README", "README.rst", "README.txt"};
            String ref = (branch != null && !branch.isEmpty()) ? "?ref=" + branch : "";
            for (String name : candidates) {
                try {
                    String resp = get(BASE + "/repos/" + owner + "/" + repo
                        + "/contents/" + name + ref, token);
                    JSONObject o = new JSONObject(resp);
                    String enc = o.optString("content", "").replace("\n", "").replace("\r", "");
                    if (!enc.isEmpty()) {
                        String text = new String(Base64.decode(enc, Base64.DEFAULT),
                            StandardCharsets.UTF_8);
                        cb.onResult(text, null);
                        return;
                    }
                } catch (Exception ignored) {}
            }
            cb.onResult(null, "No README found");
        });
    }

    // =========================================================================
    // LANGUAGE STATS
    // =========================================================================

    /**
     * Fetch language byte counts for a repo.
     * Returns LinkedHashMap<language, bytes> in descending order.
     */
    public static void getLanguages(final String token, final String owner,
                                     final String repo,
                                     final Callback<LinkedHashMap<String, Long>> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String resp = get(BASE + "/repos/" + owner + "/" + repo + "/languages", token);
                JSONObject o = new JSONObject(resp);
                LinkedHashMap<String, Long> map = new LinkedHashMap<>();
                // Sort by value descending
                java.util.List<String> keys = new java.util.ArrayList<>();
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) keys.add(it.next());
                keys.sort((a, b2) -> Long.compare(o.optLong(b2, 0), o.optLong(a, 0)));
                for (String k : keys) map.put(k, o.optLong(k, 0));
                cb.onResult(map, null);
            } catch (Exception e) { cb.onResult(null, e.getMessage()); }
        });
    }    // ═════════════════════════════════════════════════════════════════════════
    // TREE API — batch upload semua file dalam 1 commit
    // ═════════════════════════════════════════════════════════════════════════

    public interface TreeApiProgressCallback {
        void onProgress(int done, int total, String currentPath);
    }

    /**
     * Upload semua file dalam 1 commit via GitHub Tree API.
     *
     * Keunggulan vs per-file upload:
     *   - 1 commit = 1 push event = workflow hanya trigger 1x (alami, tanpa [skip ci] trick)
     *   - Git history bersih — tidak ada puluhan commit kecil
     *   - Tidak ada race condition SHA antar file
     *
     * Flow:
     *   1. POST /git/blobs      — upload content tiap file, dapat blob SHA
     *   2. GET  /git/ref        — dapat current commit SHA
     *   3. GET  /git/commits    — dapat base tree SHA
     *   4. POST /git/trees      — buat tree baru dari semua blob
     *   5. POST /git/commits    — buat 1 commit
     *   6. PATCH /git/refs      — update branch ke commit baru
     */
    public static boolean uploadFilesViaTreeApi(String token, String owner, String repo,
            String branch, String commitMsg, List<String[]> files,
            TreeApiProgressCallback progress) {
        try {
            int total = files.size();

            // ── Step 1: Create blob untuk setiap file ─────────────────────
            List<JSONObject> treeEntries = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                String path = files.get(i)[0];
                String b64  = files.get(i)[1]; // already base64

                if (progress != null) progress.onProgress(i + 1, total, path);

                JSONObject blobReq = new JSONObject();
                blobReq.put("content",  b64);
                blobReq.put("encoding", "base64");

                HttpURLConnection bc = (HttpURLConnection)
                    new URL(BASE + "/repos/" + owner + "/" + repo + "/git/blobs").openConnection();
                bc.setRequestMethod("POST");
                setAuth(bc, token);
                bc.setRequestProperty("Content-Type", "application/json");
                bc.setDoOutput(true);
                bc.setConnectTimeout(20000);
                bc.setReadTimeout(60000);
                try (OutputStream os = bc.getOutputStream()) {
                    os.write(blobReq.toString().getBytes(StandardCharsets.UTF_8));
                }
                int blobCode = bc.getResponseCode();
                if (blobCode != 201) {
                    Log.w(TAG, "Tree API blob HTTP " + blobCode + " path=" + path);
                    return false;
                }
                String blobSha = new JSONObject(readStream(bc.getInputStream())).getString("sha");

                JSONObject entry = new JSONObject();
                entry.put("path", path);
                entry.put("mode", "100644");
                entry.put("type", "blob");
                entry.put("sha",  blobSha);
                treeEntries.add(entry);
            }

            // ── Step 2: Get current commit SHA dari branch ref ─────────────
            HttpURLConnection refConn = (HttpURLConnection)
                new URL(BASE + "/repos/" + owner + "/" + repo
                    + "/git/ref/heads/" + branch).openConnection();
            refConn.setRequestMethod("GET");
            setAuth(refConn, token);
            refConn.setConnectTimeout(10000);
            refConn.setReadTimeout(10000);
            String currentCommitSha = new JSONObject(readStream(refConn.getInputStream()))
                .getJSONObject("object").getString("sha");

            // ── Step 3: Get base tree SHA dari commit ──────────────────────
            HttpURLConnection commitConn = (HttpURLConnection)
                new URL(BASE + "/repos/" + owner + "/" + repo
                    + "/git/commits/" + currentCommitSha).openConnection();
            commitConn.setRequestMethod("GET");
            setAuth(commitConn, token);
            commitConn.setConnectTimeout(10000);
            commitConn.setReadTimeout(10000);
            String baseTreeSha = new JSONObject(readStream(commitConn.getInputStream()))
                .getJSONObject("tree").getString("sha");

            // ── Step 4: Create new tree ────────────────────────────────────
            if (progress != null) progress.onProgress(total, total, "⏳ Creating commit...");
            JSONObject treeReq = new JSONObject();
            treeReq.put("base_tree", baseTreeSha);
            treeReq.put("tree", new JSONArray(treeEntries));

            HttpURLConnection treeConn = (HttpURLConnection)
                new URL(BASE + "/repos/" + owner + "/" + repo + "/git/trees").openConnection();
            treeConn.setRequestMethod("POST");
            setAuth(treeConn, token);
            treeConn.setRequestProperty("Content-Type", "application/json");
            treeConn.setDoOutput(true);
            treeConn.setConnectTimeout(30000);
            treeConn.setReadTimeout(90000);
            try (OutputStream os = treeConn.getOutputStream()) {
                os.write(treeReq.toString().getBytes(StandardCharsets.UTF_8));
            }
            int treeCode = treeConn.getResponseCode();
            if (treeCode != 201) {
                Log.w(TAG, "Tree API create tree HTTP " + treeCode);
                return false;
            }
            String newTreeSha = new JSONObject(readStream(treeConn.getInputStream())).getString("sha");

            // ── Step 5: Create commit ──────────────────────────────────────
            JSONObject commitReq = new JSONObject();
            commitReq.put("message", commitMsg);
            commitReq.put("tree",    newTreeSha);
            commitReq.put("parents", new JSONArray().put(currentCommitSha));

            HttpURLConnection createCommitConn = (HttpURLConnection)
                new URL(BASE + "/repos/" + owner + "/" + repo + "/git/commits").openConnection();
            createCommitConn.setRequestMethod("POST");
            setAuth(createCommitConn, token);
            createCommitConn.setRequestProperty("Content-Type", "application/json");
            createCommitConn.setDoOutput(true);
            createCommitConn.setConnectTimeout(20000);
            createCommitConn.setReadTimeout(30000);
            try (OutputStream os = createCommitConn.getOutputStream()) {
                os.write(commitReq.toString().getBytes(StandardCharsets.UTF_8));
            }
            int createCode = createCommitConn.getResponseCode();
            if (createCode != 201) {
                Log.w(TAG, "Tree API create commit HTTP " + createCode);
                return false;
            }
            String newCommitSha = new JSONObject(readStream(createCommitConn.getInputStream())).getString("sha");

            // ── Step 6: Update branch ref ──────────────────────────────────
            JSONObject updateReq = new JSONObject();
            updateReq.put("sha",   newCommitSha);
            updateReq.put("force", false);

            HttpURLConnection updateConn = (HttpURLConnection)
                new URL(BASE + "/repos/" + owner + "/" + repo
                    + "/git/refs/heads/" + branch).openConnection();
            updateConn.setRequestMethod("PATCH");
            setAuth(updateConn, token);
            updateConn.setRequestProperty("Content-Type", "application/json");
            updateConn.setDoOutput(true);
            updateConn.setConnectTimeout(15000);
            updateConn.setReadTimeout(15000);
            try (OutputStream os = updateConn.getOutputStream()) {
                os.write(updateReq.toString().getBytes(StandardCharsets.UTF_8));
            }
            int updateCode = updateConn.getResponseCode();
            Log.d(TAG, "Tree API update ref → HTTP " + updateCode);
            return updateCode == 200;

        } catch (Exception e) {
            Log.e(TAG, "uploadFilesViaTreeApi failed: " + e.getMessage());
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ZIP DEPLOY — setup build.yml untuk ZIP deploy (extract dari ZIP atau buat default)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Setup build.yml untuk ZIP deploy. Prioritas:
     *
     *   1. Extract .github/workflows/build.yml dari dalam ZIP → upload ke repo as-is
     *      (user sudah punya YAML di dalam project mereka)
     *
     *   2. Jika tidak ada di ZIP → patch build.yml yang sudah ada di repo
     *      (tambah step unzip setelah checkout)
     *
     *   3. Jika keduanya tidak ada → buat default build.yml minimal dengan step unzip
     *      (repo baru / bare, belum ada YAML sama sekali)
     *
     * @param zipBytes     raw bytes ZIP yang diupload user (untuk extract YAML dari dalamnya)
     * @param zipRepoPath  path ZIP di repo, misal "Game booster last.zip"
     */
    public static void setupYamlForZipDeploy(final String token, final String owner,
            final String repo, final String branch,
            final String zipRepoPath, final byte[] zipBytes,
            final Callback<Boolean> cb) {
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                String yamlPath = ".github/workflows/build.yml";

                // ── Prioritas 1: Extract build.yml dari dalam ZIP ──────────
                byte[] yamlFromZip = extractFileFromZip(zipBytes, ".github/workflows/build.yml");
                if (yamlFromZip == null) {
                    // Coba path tanpa leading slash atau dengan subfolder
                    yamlFromZip = extractFileFromZip(zipBytes, "github/workflows/build.yml");
                }
                // Coba cari di subfolder (misal project ada di dalam folder ZIP)
                if (yamlFromZip == null) {
                    yamlFromZip = extractFileFromZipPattern(zipBytes, "workflows/build.yml");
                }

                if (yamlFromZip != null) {
                    // Dapat YAML dari dalam ZIP — upload langsung ke repo
                    Log.d(TAG, "Found build.yml inside ZIP, uploading to repo");
                    uploadYamlToRepo(token, owner, repo, branch, yamlPath, yamlFromZip, cb);
                    return;
                }

                // ── Prioritas 2: Patch build.yml yang ada di repo ──────────
                String existingYaml = null, existingSha = null;
                try {
                    HttpURLConnection chk = (HttpURLConnection)
                        new URL(BASE + "/repos/" + owner + "/" + repo
                            + "/contents/" + yamlPath).openConnection();
                    chk.setRequestMethod("GET");
                    setAuth(chk, token);
                    chk.setConnectTimeout(10000);
                    chk.setReadTimeout(10000);
                    if (chk.getResponseCode() == 200) {
                        JSONObject j = new JSONObject(readStream(chk.getInputStream()));
                        String raw = j.optString("content", "").replace("\n", "").replace("\r", "");
                        existingYaml = new String(Base64.decode(raw, Base64.DEFAULT), StandardCharsets.UTF_8);
                        existingSha  = j.optString("sha");
                    }
                } catch (Exception ignored) {}

                if (existingYaml != null) {
                    if (existingYaml.contains("gitdeploy-zip-extract")) {
                        // Sudah di-patch sebelumnya
                        cb.onResult(true, null);
                        return;
                    }
                    // Inject extract step setelah checkout
                    String patched = injectExtractStep(existingYaml, zipRepoPath);
                    uploadYamlToRepo(token, owner, repo, branch, yamlPath,
                        patched.getBytes(StandardCharsets.UTF_8), existingSha, cb);
                    return;
                }

                // ── Prioritas 3: Buat default build.yml dari scratch ───────
                Log.d(TAG, "No build.yml found anywhere, creating default for ZIP deploy");
                String zipFilename = zipRepoPath.contains("/")
                    ? zipRepoPath.substring(zipRepoPath.lastIndexOf('/') + 1)
                    : zipRepoPath;
                String defaultYaml = buildDefaultZipDeployYaml(zipRepoPath, zipFilename);
                uploadYamlToRepo(token, owner, repo, branch, yamlPath,
                    defaultYaml.getBytes(StandardCharsets.UTF_8), null, cb);

            } catch (Exception e) {
                cb.onResult(false, e.getMessage());
            }
        });
    }

    /** Extract file exact path dari ZIP bytes. */
    private static byte[] extractFileFromZip(byte[] zipBytes, String targetPath) {
        try {
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes));
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace("\\", "/");
                // Strip leading slash dan leading folder (misal "myproject/.github/...")
                if (name.endsWith(targetPath) || name.equals(targetPath)
                        || name.replaceFirst("^[^/]+/", "").equals(targetPath)) {
                    byte[] data = readStreamBytes(zis);
                    zis.close();
                    return data;
                }
                zis.closeEntry();
            }
            zis.close();
        } catch (Exception ignored) {}
        return null;
    }

    /** Extract file yang mengandung pattern path (suffix match). */
    private static byte[] extractFileFromZipPattern(byte[] zipBytes, String pathSuffix) {
        try {
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes));
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace("\\", "/");
                if (name.endsWith(pathSuffix)) {
                    byte[] data = readStreamBytes(zis);
                    zis.close();
                    return data;
                }
                zis.closeEntry();
            }
            zis.close();
        } catch (Exception ignored) {}
        return null;
    }

    /** Inject extract step ke YAML yang sudah ada, setelah checkout block. */
    private static String injectExtractStep(String yaml, String zipRepoPath) {
        String zipFilename = zipRepoPath.contains("/")
            ? zipRepoPath.substring(zipRepoPath.lastIndexOf('/') + 1)
            : zipRepoPath;
        String extractStep =
            "\n      - name: Extract ZIP (gitdeploy-zip-extract)\n" +
            "        run: |\n" +
            "          unzip -q \"" + zipRepoPath + "\" -d .\n" +
            "          echo \"ZIP extracted: " + zipFilename + "\"\n";
        int checkoutIdx = yaml.indexOf("uses: actions/checkout");
        if (checkoutIdx >= 0) {
            int lineEnd  = yaml.indexOf('\n', checkoutIdx);
            if (lineEnd < 0) lineEnd = yaml.length();
            int nextStep = yaml.indexOf("\n      -", lineEnd);
            if (nextStep < 0) nextStep = yaml.length() - 1;
            return yaml.substring(0, nextStep + 1) + extractStep + yaml.substring(nextStep + 1);
        }
        // Fallback: inject di awal steps
        int stepsIdx = yaml.indexOf("    steps:");
        if (stepsIdx >= 0) {
            int insertAt = yaml.indexOf('\n', stepsIdx) + 1;
            return yaml.substring(0, insertAt) + extractStep + yaml.substring(insertAt);
        }
        return yaml + extractStep;
    }

    /** Buat default build.yml untuk project yang hanya punya ZIP. */
    private static String buildDefaultZipDeployYaml(String zipRepoPath, String zipFilename) {
        return "name: Build from ZIP\n\n" +
               "on:\n" +
               "  push:\n" +
               "    paths:\n" +
               "      - '" + zipRepoPath + "'\n" +
               "  workflow_dispatch:\n" +
               "    inputs:\n" +
               "      sign_apk:\n" +
               "        description: 'Sign APK'\n" +
               "        required: false\n" +
               "        default: 'false'\n" +
               "        type: choice\n" +
               "        options:\n" +
               "          - 'false'\n" +
               "          - 'true'\n\n" +
               "jobs:\n" +
               "  build:\n" +
               "    runs-on: ubuntu-latest\n" +
               "    steps:\n" +
               "      - name: Checkout\n" +
               "        uses: actions/checkout@v4\n\n" +
               "      - name: Extract ZIP (gitdeploy-zip-extract)\n" +
               "        run: |\n" +
               "          unzip -q \"" + zipRepoPath + "\" -d .\n" +
               "          echo \"ZIP extracted: " + zipFilename + "\"\n\n" +
               "      - name: Set up JDK 17\n" +
               "        uses: actions/setup-java@v4\n" +
               "        with:\n" +
               "          java-version: '17'\n" +
               "          distribution: 'temurin'\n\n" +
               "      - name: Make gradlew executable\n" +
               "        run: chmod +x gradlew\n\n" +
               "      - name: Build Release APK\n" +
               "        run: ./gradlew assembleRelease\n\n" +
               "      - name: Sign APK\n" +
               "        if: ${{ inputs.sign_apk == 'true' && vars.SIGNING_ALIAS != '' }}\n" +
               "        run: |\n" +
               "          APK=$(find app/build/outputs/apk/release -name \"*.apk\" ! -name \"signed*\" | head -1)\n" +
               "          $ANDROID_HOME/build-tools/33.0.1/apksigner sign \\\n" +
               "            --ks \"${{ vars.SIGNING_KEY_PATH }}\" \\\n" +
               "            --ks-key-alias \"${{ vars.SIGNING_ALIAS }}\" \\\n" +
               "            --ks-pass \"pass:${{ vars.SIGNING_STORE_PASS }}\" \\\n" +
               "            --key-pass \"pass:${{ vars.SIGNING_KEY_PASS }}\" \\\n" +
               "            --out app/build/outputs/apk/release/signed-release.apk \"$APK\"\n\n" +
               "      - name: Upload APK\n" +
               "        uses: actions/upload-artifact@v4\n" +
               "        with:\n" +
               "          name: release-apk\n" +
               "          path: app/build/outputs/apk/release/*.apk\n" +
               "          retention-days: 30\n";
    }

    /** Upload (create atau update) YAML file ke repo. */
    private static void uploadYamlToRepo(String token, String owner, String repo,
            String branch, String path, byte[] content, Callback<Boolean> cb) {
        uploadYamlToRepo(token, owner, repo, branch, path, content, null, cb);
    }

    private static void uploadYamlToRepo(String token, String owner, String repo,
            String branch, String path, byte[] content, String sha, Callback<Boolean> cb) {
        try {
            JSONObject body = new JSONObject();
            body.put("message", "ci: setup build.yml for ZIP deploy [skip ci]");
            body.put("content", Base64.encodeToString(content, Base64.NO_WRAP));
            body.put("branch", branch);
            if (sha != null && !sha.isEmpty()) body.put("sha", sha);

            HttpURLConnection conn = (HttpURLConnection)
                new URL(BASE + "/repos/" + owner + "/" + repo + "/contents/" + path).openConnection();
            conn.setRequestMethod("PUT");
            setAuth(conn, token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code == 200 || code == 201) {
                cb.onResult(true, null);
            } else {
                cb.onResult(false, "HTTP " + code);
            }
        } catch (Exception e) {
            cb.onResult(false, e.getMessage());
        }
    }

    /** @deprecated Gunakan setupYamlForZipDeploy yang lebih cerdas */
    @Deprecated
    public static void patchYamlForZipDeploy(final String token, final String owner,
            final String repo, final String branch,
            final String zipRepoPath, final Callback<Boolean> cb) {
        setupYamlForZipDeploy(token, owner, repo, branch, zipRepoPath, new byte[0], cb);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WORKFLOW TRIGGER — dipakai oleh UploadForegroundService setelah upload selesai
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Kirim 1 commit kecil ke .github/.gitdeploy-trigger TANPA [skip ci].
     *
     * Strategi upload di pushFiles() memakai [skip ci] di setiap commit file
     * agar GitHub Actions tidak jalan N kali. Method ini dipanggil 1x setelah
     * semua file selesai diupload → workflow berjalan tepat 1x.
     *
     * File trigger berisi timestamp sehingga selalu berubah → selalu bikin commit baru.
     * Tidak mengganggu kode/build, hanya sebuah metadata file kecil.
     */
    public static void triggerWorkflowCommit(String token, String owner, String repo,
                                              String branch, String originalMsg) {
        try {
            String triggerPath = ".github/.gitdeploy-trigger";
            String content = "# GitDeploy auto-trigger\n# " + new java.util.Date().toString() + "\n";
            String message  = originalMsg.isEmpty() ? "chore: trigger build" : originalMsg;

            // Get SHA jika file sudah ada (untuk update, bukan create baru)
            String sha = null;
            try {
                HttpURLConnection chk = (HttpURLConnection)
                    new URL(BASE + "/repos/" + owner + "/" + repo
                        + "/contents/" + triggerPath).openConnection();
                chk.setRequestMethod("GET");
                setAuth(chk, token);
                chk.setConnectTimeout(10000);
                chk.setReadTimeout(10000);
                if (chk.getResponseCode() == 200) {
                    sha = new JSONObject(readStream(chk.getInputStream())).optString("sha", null);
                }
            } catch (Exception ignored) {}

            // PUT file trigger (dengan atau tanpa sha)
            JSONObject body = new JSONObject();
            body.put("message", message);
            body.put("content", Base64.encodeToString(
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP));
            body.put("branch", branch);
            if (sha != null && !sha.isEmpty()) body.put("sha", sha);

            HttpURLConnection conn = (HttpURLConnection)
                new URL(BASE + "/repos/" + owner + "/" + repo
                    + "/contents/" + triggerPath).openConnection();
            conn.setRequestMethod("PUT");
            setAuth(conn, token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            Log.d(TAG, "triggerWorkflowCommit → HTTP " + code);
        } catch (Exception e) {
            Log.w(TAG, "triggerWorkflowCommit failed: " + e.getMessage());
            // Non-fatal — upload sudah berhasil, workflow trigger gagal bukan masalah kritis
        }
    }


}
