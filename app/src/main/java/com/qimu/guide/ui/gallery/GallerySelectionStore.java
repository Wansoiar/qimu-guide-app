package com.qimu.guide.ui.gallery;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persists photo selection and Vlog preference without tying it to an Activity instance. */
public final class GallerySelectionStore {

    public static final int MAX_SELECTION = 30;

    private static final String PREFERENCES = "qimu_local_gallery";
    private static final String KEY_SELECTION_INITIALIZED = "selection_initialized";
    private static final String KEY_SELECTED_URIS = "selected_photo_uris";
    private static final String KEY_VLOG_ENABLED = "vlog_enabled";

    private final SharedPreferences preferences;
    private final LinkedHashSet<String> selectedUris = new LinkedHashSet<>();
    private boolean selectionInitialized;

    public GallerySelectionStore(@NonNull Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        reload();
    }

    /** Reloads values changed by another screen that owns a separate store instance. */
    public void reload() {
        selectionInitialized = preferences.getBoolean(KEY_SELECTION_INITIALIZED, false);
        selectedUris.clear();
        Set<String> persisted = preferences.getStringSet(KEY_SELECTED_URIS, null);
        if (persisted != null) selectedUris.addAll(persisted);
    }

    /**
     * Removes vanished rows and applies the one-time default up to the share limit.
     * An empty first visit does not consume the default-selection behavior.
     */
    public void reconcile(@NonNull List<LocalPhoto> currentPhotos) {
        Set<String> available = new HashSet<>();
        for (LocalPhoto photo : currentPhotos) available.add(photo.selectionKey());

        boolean changed = selectedUris.retainAll(available);
        if (!selectionInitialized && !currentPhotos.isEmpty()) {
            selectedUris.clear();
            addFirstUpToLimit(currentPhotos, selectedUris);
            selectionInitialized = true;
            changed = true;
        } else if (selectedUris.size() > MAX_SELECTION) {
            LinkedHashSet<String> trimmed = new LinkedHashSet<>();
            for (LocalPhoto photo : currentPhotos) {
                if (selectedUris.contains(photo.selectionKey())) {
                    trimmed.add(photo.selectionKey());
                    if (trimmed.size() == MAX_SELECTION) break;
                }
            }
            selectedUris.clear();
            selectedUris.addAll(trimmed);
            changed = true;
        }
        if (changed) persistSelection();
    }

    public boolean isSelected(@NonNull LocalPhoto photo) {
        return selectedUris.contains(photo.selectionKey());
    }

    /** Returns false only when selecting this item would exceed the share-photo cap. */
    public boolean setSelected(@NonNull LocalPhoto photo, boolean selected) {
        String key = photo.selectionKey();
        if (selected && !selectedUris.contains(key) && !canAddSelection(selectedUris.size())) {
            return false;
        }
        boolean changed = selected ? selectedUris.add(key) : selectedUris.remove(key);
        if (changed) persistSelection();
        return true;
    }

    static boolean canAddSelection(int selectedCount) {
        return selectedCount < MAX_SELECTION;
    }

    public void selectAll(@NonNull List<LocalPhoto> photos) {
        selectedUris.clear();
        addFirstUpToLimit(photos, selectedUris);
        if (!photos.isEmpty()) selectionInitialized = true;
        persistSelection();
    }

    public void clearSelection() {
        if (selectedUris.isEmpty()) return;
        selectedUris.clear();
        selectionInitialized = true;
        persistSelection();
    }

    /** Clears all visitor-specific gallery state after the tour photos are deleted. */
    public boolean clearForTourEnd() {
        boolean cleared = preferences.edit().clear().commit();
        selectedUris.clear();
        selectionInitialized = false;
        return cleared;
    }

    /** "All" means every selectable item up to the share-photo limit. */
    public boolean areAllSelectablePhotosSelected(@NonNull List<LocalPhoto> photos) {
        int targetCount = Math.min(MAX_SELECTION, photos.size());
        if (targetCount == 0 || selectedUris.size() != targetCount) return false;
        for (int index = 0; index < targetCount; index++) {
            if (!selectedUris.contains(photos.get(index).selectionKey())) return false;
        }
        return true;
    }

    public int getSelectedCount() {
        return selectedUris.size();
    }

    @NonNull
    public Set<String> getSelectedUris() {
        return new HashSet<>(selectedUris);
    }

    @NonNull
    public List<String> getSelectedUrisInGalleryOrder(@NonNull List<LocalPhoto> photos) {
        List<String> ordered = new ArrayList<>();
        for (LocalPhoto photo : photos) {
            if (selectedUris.contains(photo.selectionKey())) ordered.add(photo.selectionKey());
        }
        return ordered;
    }

    public boolean isVlogEnabled() {
        return preferences.getBoolean(KEY_VLOG_ENABLED, false);
    }

    public void setVlogEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_VLOG_ENABLED, enabled).apply();
    }

    private void persistSelection() {
        preferences.edit()
                .putBoolean(KEY_SELECTION_INITIALIZED, selectionInitialized)
                .putStringSet(KEY_SELECTED_URIS, new HashSet<>(selectedUris))
                .apply();
    }

    private static void addFirstUpToLimit(@NonNull Collection<LocalPhoto> photos,
                                          @NonNull Set<String> destination) {
        int count = 0;
        for (LocalPhoto photo : photos) {
            destination.add(photo.selectionKey());
            count++;
            if (count == MAX_SELECTION) break;
        }
    }
}
