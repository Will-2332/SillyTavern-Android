package com.sillytavern.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Keeps the CPU awake only while SillyTavern is generating a reply, so a
 * streamed response can finish with the screen off. Started and stopped from
 * SillyTavern's GENERATION_STARTED / GENERATION_ENDED / GENERATION_STOPPED
 * events (see MainActivity.GENERATION_HOOK_JS).
 */
final class GenerationWakeLock {
    // Safety cap in case an "ended" event never arrives (page reload, crash,
    // network drop mid-stream). Long enough for any single generation.
    private static final long MAX_HOLD_MS = 10 * 60 * 1000L;

    // SillyTavern fires GENERATION_ENDED when it unlocks the UI, which can be
    // before it has finished saving the chat to the server. Stay awake a little
    // longer so that save request isn't cut off when the CPU goes to sleep.
    private static final long RELEASE_GRACE_MS = 30 * 1000L;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable releaseRunnable = GenerationWakeLock::releaseNow;
    private static PowerManager.WakeLock wakeLock;

    private GenerationWakeLock() {}

    static synchronized void acquire(Context context) {
        handler.removeCallbacks(releaseRunnable);
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) context.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SillyTavern::Generation");
            // Not reference counted: a new generation just restarts the cap.
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(MAX_HOLD_MS);
    }

    /** Releases the wake lock after a short grace period. */
    static synchronized void release() {
        if (wakeLock == null || !wakeLock.isHeld()) return;
        handler.removeCallbacks(releaseRunnable);
        handler.postDelayed(releaseRunnable, RELEASE_GRACE_MS);
    }

    /** Releases the wake lock immediately, e.g. when background mode is turned off. */
    static synchronized void releaseNow() {
        handler.removeCallbacks(releaseRunnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
