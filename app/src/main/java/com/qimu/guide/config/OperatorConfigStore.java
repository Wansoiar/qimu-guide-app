package com.qimu.guide.config;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Persists the small set of venue defaults managed by on-site operators.
 * A running tour keeps its original venue; changes apply to the next tour.
 */
public final class OperatorConfigStore {

    public static final String DEFAULT_VENUE_ID = "61f1f93d-fe42-49d0-b392-bcbf9cd1c13d";
    public static final String DEFAULT_VENUE_NAME = "中国美术馆";

    private static final String PREFS = "operator_config";
    private static final String KEY_VENUE_ID = "default_venue_id";
    private static final String KEY_VENUE_NAME = "default_venue_name";
    private static volatile OperatorConfigStore instance;

    public interface Listener {
        void onDefaultVenueChanged(@NonNull Venue venue);
    }

    public static OperatorConfigStore get(Context context) {
        if (instance == null) {
            synchronized (OperatorConfigStore.class) {
                if (instance == null) {
                    instance = new OperatorConfigStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final SharedPreferences preferences;
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private OperatorConfigStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public Venue defaultVenue() {
        String id = preferences.getString(KEY_VENUE_ID, DEFAULT_VENUE_ID);
        String name = preferences.getString(KEY_VENUE_NAME, DEFAULT_VENUE_NAME);
        return new Venue(normalize(id, DEFAULT_VENUE_ID), normalize(name, DEFAULT_VENUE_NAME));
    }

    public boolean saveDefaultVenue(String venueId, String venueName) {
        String normalizedId = normalize(venueId, "");
        String normalizedName = normalize(venueName, "");
        if (normalizedId.isEmpty() || normalizedName.isEmpty()) return false;

        Venue previous = defaultVenue();
        if (previous.id.equals(normalizedId) && previous.name.equals(normalizedName)) return true;

        preferences.edit()
                .putString(KEY_VENUE_ID, normalizedId)
                .putString(KEY_VENUE_NAME, normalizedName)
                .apply();
        notifyListeners(new Venue(normalizedId, normalizedName));
        return true;
    }

    public void restoreDefaults() {
        preferences.edit()
                .remove(KEY_VENUE_ID)
                .remove(KEY_VENUE_NAME)
                .apply();
        notifyListeners(new Venue(DEFAULT_VENUE_ID, DEFAULT_VENUE_NAME));
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(Venue venue) {
        for (Listener listener : listeners) listener.onDefaultVenueChanged(venue);
    }

    private static String normalize(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    public static final class Venue {
        public final String id;
        public final String name;

        Venue(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
