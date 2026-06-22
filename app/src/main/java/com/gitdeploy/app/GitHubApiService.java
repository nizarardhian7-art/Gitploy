package com.gitdeploy.app;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/**
 * Retrofit interface untuk GitHub REST API v3.
 *
 * Migrasi bertahap: tambah endpoint baru di sini satu per satu.
 * Method existing di GHApi.java tetap jalan sampai semua dipindah.
 */
public interface GitHubApiService {

    /**
     * GET /user/repos
     * List repos milik authenticated user.
     *
     * @param perPage jumlah item per page (max 100)
     * @param sort    field sort: "created", "updated", "pushed", "full_name"
     * @param page    halaman ke-N (1-based)
     */
    @GET("user/repos")
    Call<List<RepoDto>> listRepos(
        @Query("per_page") int perPage,
        @Query("sort")     String sort,
        @Query("page")     int page
    );
}
