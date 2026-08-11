package com.qimu.guide.ui.gallery;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Reads photos that this app publishes to Pictures/齐目眼镜. */
public final class LocalPhotoRepository {

    public static final String ALBUM_NAME = "齐目眼镜";
    public static final String RELATIVE_ALBUM_PATH =
            Environment.DIRECTORY_PICTURES + "/" + ALBUM_NAME + "/";

    public interface Callback {
        void onPhotosLoaded(@NonNull List<LocalPhoto> photos);

        void onLoadFailed(@NonNull Throwable error);
    }

    private final ContentResolver resolver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService queryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qimu-gallery-query");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean closed;

    public LocalPhotoRepository(@NonNull Context context) {
        resolver = context.getApplicationContext().getContentResolver();
    }

    /**
     * Deletes every image published by the tour flow into its dedicated album.
     *
     * <p>This is synchronous by design: the return coordinator invokes it from its cleanup
     * worker and must not mark a visitor session as cleaned until MediaStore confirms that the
     * album is empty.</p>
     */
    public static int deleteAllPublishedPhotos(@NonNull Context context) throws IOException {
        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        int deleted;
        try {
            deleted = resolver.delete(
                    collection, albumSelection(false), albumSelectionArgs());
        } catch (RuntimeException deleteFailure) {
            throw new IOException("无法删除本机导览照片", deleteFailure);
        }

        int remaining;
        try {
            remaining = countAlbumRows(resolver, collection);
        } catch (RuntimeException verifyFailure) {
            throw new IOException("无法确认本机导览照片是否清空", verifyFailure);
        }
        if (remaining != 0) {
            throw new IOException("本机仍有 " + remaining + " 张导览照片未删除");
        }
        return deleted;
    }

    /**
     * Queries away from the main thread. A newer request supersedes callbacks from an older one.
     */
    public void loadPhotos(@NonNull Callback callback) {
        final int requestGeneration = generation.incrementAndGet();
        queryExecutor.execute(() -> {
            try {
                List<LocalPhoto> photos = queryPhotos();
                mainHandler.post(() -> {
                    if (!closed && requestGeneration == generation.get()) {
                        callback.onPhotosLoaded(photos);
                    }
                });
            } catch (Throwable error) {
                mainHandler.post(() -> {
                    if (!closed && requestGeneration == generation.get()) {
                        callback.onLoadFailed(error);
                    }
                });
            }
        });
    }

    @NonNull
    private List<LocalPhoto> queryPhotos() {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
        };

        List<LocalPhoto> result = new ArrayList<>();
        String order = MediaStore.Images.Media.DATE_ADDED + " DESC, "
                + MediaStore.Images.Media._ID + " DESC";
        try (Cursor cursor = resolver.query(
                collection, projection, albumSelection(true), albumSelectionArgs(), order)) {
            if (cursor == null) return Collections.emptyList();

            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE);
            int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String name = cursor.getString(nameColumn);
                String mime = cursor.getString(mimeColumn);
                long dateSeconds = cursor.getLong(dateColumn);
                long size = cursor.getLong(sizeColumn);
                Uri uri = ContentUris.withAppendedId(collection, id);
                result.add(new LocalPhoto(
                        id,
                        uri,
                        name == null || name.trim().isEmpty() ? "眼镜照片" : name,
                        mime == null || mime.trim().isEmpty() ? "image/jpeg" : mime,
                        dateSeconds * 1000L,
                        size));
            }
        }
        return Collections.unmodifiableList(result);
    }

    @NonNull
    private static String albumSelection(boolean visibleOnly) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String pathSelection = "(" + MediaStore.Images.Media.RELATIVE_PATH + " = ? OR "
                    + MediaStore.Images.Media.RELATIVE_PATH + " = ?)";
            return visibleOnly
                    ? pathSelection + " AND " + MediaStore.Images.Media.IS_PENDING + " = 0"
                    : pathSelection;
        }
        return MediaStore.Images.Media.DATA + " LIKE ?";
    }

    @NonNull
    private static String[] albumSelectionArgs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{
                    RELATIVE_ALBUM_PATH,
                    RELATIVE_ALBUM_PATH.substring(0, RELATIVE_ALBUM_PATH.length() - 1)
            };
        }
        File album = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), ALBUM_NAME);
        return new String[]{album.getAbsolutePath() + File.separator + "%"};
    }

    private static int countAlbumRows(ContentResolver resolver, Uri collection) {
        try (Cursor cursor = resolver.query(
                collection,
                new String[]{MediaStore.Images.Media._ID},
                albumSelection(false),
                albumSelectionArgs(),
                null)) {
            if (cursor == null) {
                throw new IllegalStateException("MediaStore 未返回照片清理校验结果");
            }
            return cursor.getCount();
        }
    }

    public void close() {
        closed = true;
        generation.incrementAndGet();
        mainHandler.removeCallbacksAndMessages(null);
        queryExecutor.shutdownNow();
    }
}
