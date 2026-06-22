package com.gitdeploy.app;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IMPROVEMENT #3 — THREAD MANAGEMENT
 *
 * Singleton executor pools that replace 34+ raw {@code new Thread().start()} calls
 * scattered across GHApi.java and MainActivity.java.
 *
 * Why this matters:
 *  - Unbounded thread creation can exhaust memory on long sessions (e.g. 10 rapid taps
 *    on the repo list each spin up a fresh Thread, never pooled, never reused).
 *  - ScheduledExecutorService for polling already existed in the code — this unifies
 *    ALL async work under one managed pool.
 *
 * Pools:
 *  networkIO  — 4-thread fixed pool for GitHub API calls (supports parallelism for
 *               simultaneous list + upload + polling scenarios without over-spawning).
 *  diskIO     — 1-thread serial executor for local file reads/writes, keystore ops,
 *               and ZIP extraction (serial = no concurrency bugs on the same file).
 *  mainThread — posts to the Android main looper (replaces new Handler(Looper.getMainLooper())).
 */
public final class AppExecutors {

    // Max concurrent network calls.  4 is a sweet-spot: allows parallel API calls
    // (list repos + list runs + poll) without overwhelming the GitHub rate limit.
    private static final int NETWORK_THREADS = 4;

    private static volatile AppExecutors sInstance;

    private final ExecutorService mNetworkIO;
    private final ExecutorService mDiskIO;
    private final ExecutorService mComputation;
    private final Executor        mMainThread;

    // ─────────────────────────────────────────────────────────────────────────
    private AppExecutors() {
        AtomicInteger netCount  = new AtomicInteger(0);
        ThreadFactory netFactory = r -> {
            Thread t = new Thread(r, "gd-net-" + netCount.incrementAndGet());
            t.setDaemon(true);   // don't block JVM shutdown
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        };

        mNetworkIO  = Executors.newFixedThreadPool(NETWORK_THREADS, netFactory);
        mDiskIO     = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gd-disk");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1); // slightly below normal
            return t;
        });
        mComputation = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "gd-compute");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        mMainThread = new Handler(Looper.getMainLooper())::post;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Singleton access — double-checked locking
    // ─────────────────────────────────────────────────────────────────────────
    public static AppExecutors getInstance() {
        if (sInstance == null || sInstance.isShutdown()) {
            synchronized (AppExecutors.class) {
                if (sInstance == null || sInstance.isShutdown()) {
                    sInstance = new AppExecutors();
                }
            }
        }
        return sInstance;
    }

    /** True jika pool sudah di-shutdown (safety check). */
    public boolean isShutdown() {
        return mNetworkIO.isShutdown() || mDiskIO.isShutdown();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pool accessors
    // ─────────────────────────────────────────────────────────────────────────

    /** 4-thread pool for all GitHub REST API calls. */
    public ExecutorService networkIO() { return mNetworkIO; }

    /** Single-thread executor for local file/ZIP/keystore operations. */
    public ExecutorService diskIO() { return mDiskIO; }

    /** 2-thread pool for CPU-bound work (syntax highlight, etc). */
    public ExecutorService computation() { return mComputation; }

    /** Runs on Android's main thread (UI thread). */
    public Executor mainThread() { return mMainThread; }

    // ─────────────────────────────────────────────────────────────────────────
    // Convenience — reduces boilerplate at call sites
    // ─────────────────────────────────────────────────────────────────────────

    /** Submit a network task (fire-and-forget). */
    public static void runOnNetwork(Runnable task) {
        getInstance().mNetworkIO.execute(task);
    }

    /** Submit a disk task (fire-and-forget). */
    public static void runOnDisk(Runnable task) {
        getInstance().mDiskIO.execute(task);
    }

    /** Post to UI thread (fire-and-forget). */
    public static void runOnMain(Runnable task) {
        getInstance().mMainThread.execute(task);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle — call from Application.onTerminate or Activity.onDestroy if needed
    // ─────────────────────────────────────────────────────────────────────────
    public void shutdown() {
        mNetworkIO.shutdownNow();
        mDiskIO.shutdownNow();
    }
}
