package com.gitdeploy.app;

import android.app.Application;
import androidx.lifecycle.AndroidViewModel;
import java.util.List;

/**
 * ViewModel untuk daftar repo GitHub.
 * Extends AndroidViewModel — dikelola ViewModelProvider, otomatis survive rotasi.
 *
 * Penggunaan di Activity (AppCompatActivity):
 *   mRepoViewModel = new ViewModelProvider(this).get(RepoListViewModel.class);
 */
public class RepoListViewModel extends AndroidViewModel {

    public interface RepoObserver  { void onRepos(List<GHApi.Repo> repos); }
    public interface ErrorObserver { void onError(String error); }

    private List<GHApi.Repo>  mRepos;
    private String            mLastError;
    private volatile boolean  mFetching;
    private RepoObserver      mRepoObserver;
    private ErrorObserver     mErrorObserver;
    private final RepoRepository mRepository;

    // AndroidViewModel default constructor — ViewModelProvider menangani ini
    public RepoListViewModel(Application application) {
        super(application);
        mRepository = new RepoRepository(application);
    }

    public void setRepoObserver(RepoObserver o)   { mRepoObserver = o; }
    public void setErrorObserver(ErrorObserver o) { mErrorObserver = o; }
    public void clearObservers() { mRepoObserver = null; mErrorObserver = null; }

    /**
     * Hanya hapus observer jika masih milik view yang memanggil ini.
     * Mencegah view lama menghapus observer yang sudah di-set view baru.
     */
    public void clearObserversIfMatch(RepoObserver ro, ErrorObserver eo) {
        if (mRepoObserver == ro)  mRepoObserver  = null;
        if (mErrorObserver == eo) mErrorObserver = null;
    }

    public List<GHApi.Repo> getCachedRepos() { return mRepos; }
    public String           getLastError()   { return mLastError; }

    public void fetchRepos(String token) { fetchRepos(token, false); }

    public void fetchRepos(String token, boolean force) {
        if (mFetching) return;
        if (!force && mRepos != null && !mRepos.isEmpty()) {
            if (mRepoObserver != null) mRepoObserver.onRepos(mRepos);
            return;
        }
        mFetching = true;
        mRepository.loadRepos(token, force, new RepoRepository.RepoCallback() {
            @Override public void onRepos(List<GHApi.Repo> repos) {
                mFetching = false; mLastError = null; mRepos = repos;
                if (mRepoObserver != null) mRepoObserver.onRepos(repos);
            }
            @Override public void onError(String message) {
                mFetching = false; mLastError = message;
                if (mErrorObserver != null) mErrorObserver.onError(message);
            }
        });
    }

    public void invalidate() {
        mRepos = null; mLastError = null; mFetching = false; // reset fetch flag
        mRepository.clearCache();
    }

    public void refresh(String token) {
        mRepos = null; mLastError = null;
        fetchRepos(token, true);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        clearObservers();
    }
}
