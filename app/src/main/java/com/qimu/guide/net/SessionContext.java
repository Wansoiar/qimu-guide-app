package com.qimu.guide.net;

import java.util.concurrent.atomic.AtomicInteger;

/** 当前已经由游客显式开始的导览会话上下文。 */
public final class SessionContext {

    private static final SessionContext INSTANCE = new SessionContext();

    public static SessionContext get() {
        return INSTANCE;
    }

    private volatile String venueId = "";
    private volatile String sessionId = "";
    private final AtomicInteger querySequence = new AtomicInteger(0);

    private SessionContext() {
    }

    public String venueId() {
        return venueId;
    }

    public void setVenueId(String venueId) {
        this.venueId = venueId;
    }

    public String sessionId() {
        return sessionId;
    }

    public boolean isActive() {
        return !sessionId.isEmpty();
    }

    public synchronized void activate(String sessionId, String venueId) {
        this.sessionId = sessionId == null ? "" : sessionId.trim();
        if (venueId != null && !venueId.trim().isEmpty()) {
            this.venueId = venueId.trim();
        }
        querySequence.set(0);
    }

    public synchronized void clear() {
        sessionId = "";
        venueId = "";
        querySequence.set(0);
    }

    public String nextClientQueryId() {
        return "c-" + querySequence.incrementAndGet();
    }
}
