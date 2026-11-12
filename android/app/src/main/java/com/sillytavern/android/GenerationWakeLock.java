package com.sillytavern.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Keeps the CPU awake only while the SillyTavern page has network requests in
 * flight (a reply being streamed, a summary, embeddings, image or audio
 * generation, a chat save), so that work can finish with the screen off.
 * Driven by MainActivity.GENERATION_HOOK_JS through GenerationBridge.
 */
final class GenerationWakeLock {
    // Safety cap in case a request never completes. Restarted whenever the page
    // goes from idle to busy.
    private static final long MAX_HOLD_MS = 10 * 60 * 1000L;

    // After real work, stay awake a little longer so follow-up requests that
    // start a moment later (saving the chat, an automatic summary) still run.
    private static final long LONG_GRACE_MS = 30 * 1000L;
    // After a quick request (e.g. a periodic status poll) release almost at once,
    // otherwise frequent polling would keep the CPU awake forever.
    private static final long SHORT_GRACE_MS = 3 * 1000L;
    private static final long LONG_WORK_THRESHOLD_MS = 5 * 1000L;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable releaseRunnable = GenerationWakeLock::releaseNow;
    private static final Runnable capRunnable = GenerationWakeLock::releaseNow;
    private static PowerManager.WakeLock wakeLock;
    private static long busySince;
    private static boolean longWorkPending;
    private static boolean holding;
    private static Listener listener;

    /** Told when a hold starts and ends, so the page can be kept running alongside the CPU. */
    interface Listener {
        void onHoldChanged(boolean holding);
    }

    static synchronized void setListener(Listener l) {
        listener = l;
    }

    private GenerationWakeLock() {}

    static synchronized boolean isHeld() {
        return wakeLock != null && wakeLock.isHeld();
    }

    static synchronized void acquire(Context context) {
        handler.removeCallbacks(releaseRunnable);
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) context.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SillyTavern::Generation");
            wakeLock.setReferenceCounted(false);
        }
        busySince = SystemClock.elapsedRealtime();
        wakeLock.acquire(MAX_HOLD_MS);
        // The wake lock times out by itself; this ends the hold at the same moment.
        handler.removeCallbacks(capRunnable);
        handler.postDelayed(capRunnable, MAX_HOLD_MS);
        if (!holding) {
            DiagLog.add("hold START (wake lock + page)");
            holding = true;
            if (listener != null) listener.onHoldChanged(true);
        }
    }

    /** Releases the wake lock after a grace period that depends on how long the work took. */
    static synchronized void release() {
        if (!holding) return;
        if (SystemClock.elapsedRealtime() - busySince >= LONG_WORK_THRESHOLD_MS) {
            longWorkPending = true;
        }
        // A quick request right after long work (the chat save) keeps the long grace.
        long grace = longWorkPending ? LONG_GRACE_MS : SHORT_GRACE_MS;
        handler.removeCallbacks(releaseRunnable);
        handler.postDelayed(releaseRunnable, grace);
    }

    /** Releases the wake lock immediately, e.g. when background mode is turned off. */
    static synchronized void releaseNow() {
        handler.removeCallbacks(releaseRunnable);
        handler.removeCallbacks(capRunnable);
        longWorkPending = false;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (holding) {
            DiagLog.add("hold END");
            holding = false;
            if (listener != null) listener.onHoldChanged(false);
        }
    }
}
