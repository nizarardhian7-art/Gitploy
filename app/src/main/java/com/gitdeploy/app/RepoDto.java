package com.gitdeploy.app;

import com.google.gson.annotations.SerializedName;

/**
 * Retrofit/Gson DTO untuk response GET /user/repos.
 * Konversi ke GHApi.Repo (model lama) via toRepo().
 */
public class RepoDto {

    @SerializedName("name")
    public String name;

    @SerializedName("full_name")
    public String fullName;

    @SerializedName("description")
    public String description;

    @SerializedName("private")
    public boolean isPrivate;

    @SerializedName("default_branch")
    public String defaultBranch;

    /** Konversi ke model lama supaya caller tidak perlu diubah. */
    public GHApi.Repo toRepo() {
        return new GHApi.Repo(
            name != null ? name : "",
            fullName != null ? fullName : "",
            description != null ? description : "",
            isPrivate,
            defaultBranch != null ? defaultBranch : "main"
        );
    }
}
