package com.gitdeploy.app;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Room database singleton untuk GitDeploy.
 *
 * Penggunaan:
 *   RepoDao dao = AppDatabase.getInstance(context).repoDao();
 *
 * Versi: naikkan angka version DAN tambah Migration jika skema berubah.
 * Untuk sekarang fallbackToDestructiveMigration() dipakai — data cache
 * dihapus saat upgrade skema (aman karena ini hanya cache, bukan data utama).
 */
@Database(entities = {RepoEntity.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase sInstance;
    private static final String DB_NAME = "gitdeploy.db";

    public abstract RepoDao repoDao();

    public static AppDatabase getInstance(Context context) {
        if (sInstance == null) {
            synchronized (AppDatabase.class) {
                if (sInstance == null) {
                    sInstance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            DB_NAME)
                        .fallbackToDestructiveMigration() // cache-only, aman dihapus
                        .build();
                }
            }
        }
        return sInstance;
    }
}
