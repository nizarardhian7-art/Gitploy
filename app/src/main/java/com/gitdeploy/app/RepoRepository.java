package com.gitdeploy.app;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * RepoRepository — satu-satunya sumber data repo untuk UI.
 *
 * Strategi cache-then-network:
 *   1. Baca dari Room dulu → kirim ke callback (tampil instan, no flicker)
 *   2. Fetch dari GitHub API di background
 *   3. Simpan hasil baru ke Room → kirim ke callback lagi (refresh silent)
 *
 * UI (RepoListViewModel) hanya perlu panggil loadRepos() dan pasang callback.
 * Tidak perlu tahu darimana data berasal.
 *
 * Cache TTL: CACHE_TTL_MS (default 5 menit).
 * Jika cache masih segar, tidak fetch ke network (hemat kuota & rate limit).
 */
public class RepoRepository {

    private static final String TAG         = "RepoRepository";
    private static final long   CACHE_TTL_MS = 60 * 1000L; // 1 menit — biar perubahan repo cepat keliatan

    private final AppDatabase db;

    public interface RepoCallback {
        /** Dipanggil di UI thread setiap kali ada data baru (dari cache atau network). */
        void onRepos(List<GHApi.Repo> repos);
        /** Dipanggil di UI thread jika network fetch gagal DAN tidak ada cache. */
        void onError(String message);
    }

    public RepoRepository(Context context) {
        db = AppDatabase.getInstance(context);
    }

    /**
     * Load repo list.
     * 1. Langsung kirim data dari Room (jika ada) ke callback.
     * 2. Jika cache sudah lama atau kosong, fetch dari network.
     *
     * @param token  GitHub PAT
     * @param force  true = abaikan TTL, selalu fetch dari network
     */
    public void loadRepos(String token, boolean force, RepoCallback callback) {
        // Baca cache dari disk (background thread)
        AppExecutors.getInstance().diskIO().execute(() -> {
            List<RepoEntity> cached = db.repoDao().getAll();
            long lastCached         = db.repoDao().getLastCachedAt();
            boolean cacheIsStale    = force ||
                System.currentTimeMillis() - lastCached > CACHE_TTL_MS;

            if (!cached.isEmpty()) {
                // Kirim cache ke UI dulu — instan
                List<GHApi.Repo> cachedRepos = toRepoList(cached);
                AppExecutors.getInstance().mainThread().execute(
                    () -> callback.onRepos(cachedRepos));

                if (!cacheIsStale) {
                    // Cache masih segar — tidak perlu network
                    Log.d(TAG, "Serving " + cached.size() + " repos from cache (fresh)");
                    return;
                }
            }

            // Fetch dari network
            Log.d(TAG, "Fetching repos from network (cache stale=" + cacheIsStale + ")");
            GHApi.listRepos(token, (repos, err) -> {
                if (err != null) {
                    Log.w(TAG, "Network fetch failed: " + err);
                    if (cached.isEmpty()) {
                        // Tidak ada cache sama sekali → kirim error ke UI
                        AppExecutors.getInstance().mainThread().execute(
                            () -> callback.onError(err));
                    }
                    // Jika ada cache lama, diam saja — cache sudah dikirim sebelumnya
                    return;
                }
                if (repos == null || repos.isEmpty()) {
                    AppExecutors.getInstance().mainThread().execute(
                        () -> callback.onRepos(new ArrayList<>()));
                    return;
                }

                // Simpan ke Room (background)
                AppExecutors.getInstance().diskIO().execute(() -> {
                    List<RepoEntity> entities = new ArrayList<>();
                    for (GHApi.Repo r : repos) entities.add(RepoEntity.fromRepo(r));
                    db.repoDao().deleteAll();
                    db.repoDao().insertAll(entities);
                    Log.d(TAG, "Saved " + entities.size() + " repos to Room cache");
                });

                // Kirim data segar ke UI
                AppExecutors.getInstance().mainThread().execute(
                    () -> callback.onRepos(repos));
            });
        });
    }

    /** Convenience overload — gunakan TTL normal (tidak force). */
    public void loadRepos(String token, RepoCallback callback) {
        loadRepos(token, false, callback);
    }

    /** Hapus semua cache — berguna setelah createRepo / deleteRepo. */
    public void clearCache() {
        AppExecutors.getInstance().diskIO().execute(() -> db.repoDao().deleteAll());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static List<GHApi.Repo> toRepoList(List<RepoEntity> entities) {
        List<GHApi.Repo> list = new ArrayList<>(entities.size());
        for (RepoEntity e : entities) list.add(e.toRepo());
        return list;
    }
}
