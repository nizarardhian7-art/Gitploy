package com.gitdeploy.app;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * DAO untuk tabel "repos".
 * Semua query dijalankan di background thread via AppExecutors.diskIO().
 */
@Dao
public interface RepoDao {

    /** Ambil semua repo, diurutkan berdasarkan waktu cache (terbaru duluan). */
    @Query("SELECT * FROM repos ORDER BY cachedAt DESC")
    List<RepoEntity> getAll();

    /**
     * Insert atau replace seluruh list repo.
     * REPLACE strategy: jika fullName sudah ada, data lama ditimpa.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<RepoEntity> repos);

    /** Hapus semua repo dari cache (dipanggil sebelum refresh penuh). */
    @Query("DELETE FROM repos")
    void deleteAll();

    /** Cek timestamp repo termuda — untuk tahu kapan terakhir cache diperbarui. */
    @Query("SELECT MAX(cachedAt) FROM repos")
    long getLastCachedAt();
}
