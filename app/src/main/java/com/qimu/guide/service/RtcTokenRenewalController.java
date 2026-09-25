package com.qimu.guide.service;

/** Bounds duplicate pre-expiry callbacks and fences late token responses without Android clocks. */
public final class RtcTokenRenewalController {
    public static final long EXPIRY_GRACE_SECONDS = 120L;
    private volatile long ticket;
    private boolean inFlight;
    private boolean waitingForExpiry;
    private boolean graceUsed;

    public long begin() {
        if (inFlight || waitingForExpiry) return -1;
        inFlight = true;
        return ++ticket;
    }

    public long ticket() { return ticket; }
    public boolean isCurrent(long expected) { return ticket == expected; }

    /** Local SDK replacement invalidates callbacks, but does not restore the expiry retry budget. */
    public void invalidate() {
        ticket++;
        inFlight = false;
        waitingForExpiry = false;
    }

    public void newCredentials() {
        invalidate();
        graceUsed = false;
    }

    public void waitForExpiry(long expected) {
        if (!isCurrent(expected)) return;
        inFlight = false;
        waitingForExpiry = true;
    }

    public boolean retryAtExpiry(long expected, long nowSeconds, long expireAt) {
        if (!isCurrent(expected) || !waitingForExpiry || graceUsed
                || nowSeconds < expireAt || nowSeconds > expireAt + EXPIRY_GRACE_SECONDS) return false;
        waitingForExpiry = false;
        graceUsed = true;
        return true;
    }

    public static boolean canRetryHttp(int attempt, long nowSeconds, long expireAt) {
        return attempt < 2 && nowSeconds <= expireAt + EXPIRY_GRACE_SECONDS;
    }
}
