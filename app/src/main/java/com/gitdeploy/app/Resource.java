package com.gitdeploy.app;

/**
 * Resource<T> — wrapper status untuk operasi async.
 *
 * Tiga status:
 *   LOADING  — operasi sedang berjalan
 *   SUCCESS  — selesai, data tersedia
 *   ERROR    — gagal, pesan error tersedia
 *
 * Penggunaan di callback GHApi:
 * <pre>
 *   GHApi.listRepos(token, (repos, err) -> {
 *       Resource<List<GHApi.Repo>> res = Resource.from(repos, err);
 *       if (res.isSuccess()) { ... res.data ... }
 *       else if (res.isError()) { ErrorHandler.handle(activity, res.error); }
 *   });
 * </pre>
 *
 * Penggunaan manual:
 * <pre>
 *   Resource.loading()          // saat mulai fetch
 *   Resource.success(data)      // saat berhasil
 *   Resource.error("pesan")     // saat gagal
 * </pre>
 */
public final class Resource<T> {

    public enum Status { LOADING, SUCCESS, ERROR }

    public final Status status;
    public final T      data;
    public final String error;

    private Resource(Status status, T data, String error) {
        this.status = status;
        this.data   = data;
        this.error  = error;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static <T> Resource<T> loading() {
        return new Resource<>(Status.LOADING, null, null);
    }

    public static <T> Resource<T> success(T data) {
        return new Resource<>(Status.SUCCESS, data, null);
    }

    public static <T> Resource<T> error(String message) {
        return new Resource<>(Status.ERROR, null, message);
    }

    /**
     * Konversi langsung dari pola GHApi.Callback (result, err).
     * Jika err != null → ERROR; jika result == null → ERROR "Empty response";
     * selainnya → SUCCESS.
     */
    public static <T> Resource<T> from(T result, String err) {
        if (err != null)    return error(err);
        if (result == null) return error("Empty response");
        return success(result);
    }

    // ── Status checks ─────────────────────────────────────────────────────────

    public boolean isLoading() { return status == Status.LOADING; }
    public boolean isSuccess() { return status == Status.SUCCESS;  }
    public boolean isError()   { return status == Status.ERROR;    }

    @Override
    public String toString() {
        return "Resource{" + status + (data != null ? ", data=" + data : "")
               + (error != null ? ", error=" + error : "") + "}";
    }
}
