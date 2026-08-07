package com.qimu.guide.ui.gallery;

import android.net.Uri;

import androidx.annotation.NonNull;

/** Immutable MediaStore row used by the local-photo gallery. */
public final class LocalPhoto {

    private final long id;
    private final Uri uri;
    private final String displayName;
    private final String mimeType;
    private final long dateAddedMillis;
    private final long sizeBytes;

    public LocalPhoto(long id,
                      @NonNull Uri uri,
                      @NonNull String displayName,
                      @NonNull String mimeType,
                      long dateAddedMillis,
                      long sizeBytes) {
        this.id = id;
        this.uri = uri;
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.dateAddedMillis = dateAddedMillis;
        this.sizeBytes = sizeBytes;
    }

    public long getId() {
        return id;
    }

    @NonNull
    public Uri getUri() {
        return uri;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @NonNull
    public String getMimeType() {
        return mimeType;
    }

    public long getDateAddedMillis() {
        return dateAddedMillis;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    @NonNull
    public String selectionKey() {
        return uri.toString();
    }
}
