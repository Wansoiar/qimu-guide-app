package com.qimu.guide.net;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Owns the distinction between a connected pair of glasses and an active visitor tour. */
public final class TourSessionManager {

    private static final String PREFS = "tour_session_state";
    private static final String KEY_ACTIVE_MARKER = "active_marker";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_CLEANUP_WARNING = "cleanup_warning";
    private static final TourSessionManager INSTANCE = new TourSessionManager();

    public interface Listener {
        void onTourSessionChanged(boolean active);
    }

    public static TourSessionManager get() {
        return INSTANCE;
    }

    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private SharedPreferences preferences;
    private volatile TourSession session;
    private volatile boolean cleanupWarning;
    private boolean tutorialShown;
    private int sessionRequestGeneration;

    private TourSessionManager() {
    }

    public synchronized void initialize(Context context) {
        if (preferences != null) return;
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean interruptedSession = preferences.getBoolean(KEY_ACTIVE_MARKER, false);
        cleanupWarning = preferences.getBoolean(KEY_CLEANUP_WARNING, false)
                || interruptedSession;
        preferences.edit()
                .putBoolean(KEY_ACTIVE_MARKER, false)
                .putBoolean(KEY_CLEANUP_WARNING, cleanupWarning)
                .apply();
        SessionContext.get().clear();
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isActive() {
        return session != null;
    }

    @Nullable
    public TourSession current() {
        return session;
    }

    public boolean hasCleanupWarning() {
        return cleanupWarning;
    }

    /** Starts a new create request and invalidates any older response still in flight. */
    public synchronized int beginSessionRequest() {
        if (session != null || cleanupWarning) return -1;
        return ++sessionRequestGeneration;
    }

    public synchronized boolean isSessionRequestCurrent(int requestGeneration) {
        return requestGeneration > 0
                && requestGeneration == sessionRequestGeneration
                && session == null && !cleanupWarning;
    }

    public synchronized void invalidatePendingSessionRequests() {
        sessionRequestGeneration++;
    }

    public synchronized boolean beginSession(int requestGeneration,
                                             String sessionId, String orderNo,
                                             String venueId, String venueName,
                                             String deviceId, boolean serverBacked,
                                             boolean demoMode) {
        if (!isSessionRequestCurrent(requestGeneration)) return false;
        sessionRequestGeneration++;
        session = new TourSession(sessionId, orderNo, venueId, venueName,
                deviceId, System.currentTimeMillis(), serverBacked, demoMode);
        tutorialShown = false;
        cleanupWarning = false;
        SessionContext.get().activate(sessionId, venueId);
        if (preferences != null) {
            preferences.edit()
                    .putBoolean(KEY_ACTIVE_MARKER, true)
                    .putString(KEY_SESSION_ID, sessionId)
                    .putBoolean(KEY_CLEANUP_WARNING, false)
                    .apply();
        }
        notifyListeners(true);
        return true;
    }

    public synchronized boolean completeSession(@Nullable String expectedSessionId,
                                                boolean cleanupConfirmed) {
        TourSession current = session;
        if (current == null || expectedSessionId == null
                || !expectedSessionId.equals(current.sessionId)) {
            return false;
        }
        session = null;
        sessionRequestGeneration++;
        tutorialShown = false;
        cleanupWarning = !cleanupConfirmed;
        SessionContext.get().clear();
        if (preferences != null) {
            preferences.edit()
                    .putBoolean(KEY_ACTIVE_MARKER, false)
                    .remove(KEY_SESSION_ID)
                    .putBoolean(KEY_CLEANUP_WARNING, cleanupWarning)
                    .apply();
        }
        notifyListeners(false);
        return true;
    }

    public synchronized void clearCleanupWarning() {
        cleanupWarning = false;
        if (preferences != null) {
            preferences.edit().putBoolean(KEY_CLEANUP_WARNING, false).apply();
        }
        notifyListeners(isActive());
    }

    /** Returns true exactly once for each newly started tour. */
    public synchronized boolean consumeFirstTutorial() {
        if (session == null || tutorialShown) return false;
        tutorialShown = true;
        return true;
    }

    private void notifyListeners(boolean active) {
        for (Listener listener : listeners) listener.onTourSessionChanged(active);
    }

    public static final class TourSession {
        public final String sessionId;
        public final String orderNo;
        public final String venueId;
        public final String venueName;
        public final String deviceId;
        public final long startedAt;
        public final boolean serverBacked;
        public final boolean demoMode;

        TourSession(String sessionId, String orderNo, String venueId, String venueName,
                    String deviceId, long startedAt, boolean serverBacked,
                    boolean demoMode) {
            this.sessionId = sessionId;
            this.orderNo = orderNo;
            this.venueId = venueId;
            this.venueName = venueName;
            this.deviceId = deviceId;
            this.startedAt = startedAt;
            this.serverBacked = serverBacked;
            this.demoMode = demoMode;
        }
    }
}
