package com.gitdeploy.app;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room entity — satu baris di tabel "repos".
 * Menyimpan data GHApi.Repo ke disk agar tetap tersedia saat offline.
 *
 * Konversi ke/dari GHApi.Repo via toRepo() dan fromRepo().
 */
@Entity(tableName = "repos")
public class RepoEntity {

    @PrimaryKey
    @NonNull
    public String fullName = "";   // "owner/repo" — unique key

    public String  name;
    public String  description;
    public String  defaultBranch;
    public boolean isPrivate;
    public long    cachedAt;       // System.currentTimeMillis() saat disimpan

    /** Konversi ke model GHApi yang dipakai UI. */
    public GHApi.Repo toRepo() {
        return new GHApi.Repo(
            name          != null ? name          : "",
            fullName,
            description   != null ? description   : "",
            isPrivate,
            defaultBranch != null ? defaultBranch : "main"
        );
    }

    /** Buat RepoEntity dari GHApi.Repo. */
    public static RepoEntity fromRepo(GHApi.Repo r) {
        RepoEntity e   = new RepoEntity();
        e.fullName      = r.fullName     != null ? r.fullName     : "";
        e.name          = r.name         != null ? r.name         : "";
        e.description   = r.description  != null ? r.description  : "";
        e.defaultBranch = r.defaultBranch != null ? r.defaultBranch : "main";
        e.isPrivate     = r.isPrivate;
        e.cachedAt      = System.currentTimeMillis();
        return e;
    }
}
