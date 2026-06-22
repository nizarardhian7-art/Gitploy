package com.gitdeploy.app;

import android.util.Log;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.util.concurrent.TimeUnit;

/**
 * Factory untuk instance Retrofit yang siap pakai.
 *
 * Penggunaan:
 *   GitHubApiService service = GitHubApiClient.create(token);
 *   Call<List<RepoDto>> call = service.listRepos(50, "updated", 1);
 *
 * Token di-inject lewat OkHttp interceptor sehingga semua request
 * otomatis mendapat header Authorization, Accept, dan X-GitHub-Api-Version.
 */
public final class GitHubApiClient {

    private static final String BASE_URL = "https://api.github.com/";
    private static final String TAG      = "GitHubApiClient";

    // ── Singleton cache — re-use OkHttpClient + Retrofit per token ────────────
    private static GitHubApiService sCachedService = null;
    private static String           sCachedToken   = null;
    private static final Object     sLock          = new Object();

    private GitHubApiClient() {}   // util class, no instance

    /**
     * Buat atau re-use instance {@link GitHubApiService} untuk token yang diberikan.
     * OkHttpClient dan Retrofit di-cache selama token tidak berubah — connection pool
     * di-reuse antar request sehingga hemat memory dan socket.
     * Thread-safe via synchronized block.
     */
    public static GitHubApiService create(String token) {
        synchronized (sLock) {
            if (sCachedService != null && token != null && token.equals(sCachedToken)) {
                return sCachedService;
            }
            sCachedService = buildService(token);
            sCachedToken   = token;
            return sCachedService;
        }
    }

    /**
     * Paksa rebuild client — panggil ini saat token berubah (login/logout/switch akun).
     */
    public static void invalidate() {
        synchronized (sLock) {
            sCachedService = null;
            sCachedToken   = null;
        }
    }

    private static GitHubApiService buildService(String token) {

        // ── Logging interceptor (hanya aktif di debug build) ──────────────
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(
            msg -> Log.d(TAG, msg)
        );
        logging.setLevel(
            BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.BASIC
                : HttpLoggingInterceptor.Level.NONE
        );

        // ── Auth interceptor ──────────────────────────────────────────────
        OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                Request original = chain.request();
                Request signed   = original.newBuilder()
                    .header("Authorization",        "token " + token)
                    .header("Accept",               "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build();
                return chain.proceed(signed);
            })
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30,  TimeUnit.SECONDS)
            .build();

        // ── Retrofit instance ─────────────────────────────────────────────
        return new Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GitHubApiService.class);
    }
}
