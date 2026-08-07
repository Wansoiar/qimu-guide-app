package com.qimu.guide.ui.gallery;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small dependency-free thumbnail pipeline. All MediaStore decoding happens off the UI thread. */
public final class GalleryThumbnailLoader {

    private static final int DECODE_THREADS = 2;
    private static final int MAX_QUEUED_JOBS = 18;

    private final ContentResolver resolver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor decodeExecutor;
    private final ConcurrentHashMap<String, DecodeJob> inFlight = new ConcurrentHashMap<>();
    private final LruCache<String, Bitmap> memoryCache;
    private volatile boolean closed;

    public GalleryThumbnailLoader(@NonNull Context context) {
        resolver = context.getApplicationContext().getContentResolver();
        decodeExecutor = new ThreadPoolExecutor(
                DECODE_THREADS,
                DECODE_THREADS,
                15L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_JOBS),
                runnable -> {
                    Thread thread = new Thread(runnable, "qimu-gallery-thumbnail");
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                },
                (runnable, executor) -> {
                    if (executor.isShutdown()) {
                        discardJob(runnable, false);
                        return;
                    }
                    // Favor the latest bind request. The oldest queued item is normally already
                    // off-screen; if it is still bound, it is retried at the back of the queue.
                    Runnable stale = executor.getQueue().poll();
                    discardJob(stale, true);
                    if (!executor.getQueue().offer(runnable)) discardJob(runnable, true);
                });
        decodeExecutor.allowCoreThreadTimeOut(true);
        int availableKb = (int) Math.min(Integer.MAX_VALUE,
                Runtime.getRuntime().maxMemory() / 1024L);
        int cacheKb = Math.max(4 * 1024, Math.min(24 * 1024, availableKb / 10));
        memoryCache = new LruCache<String, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                return Math.max(1, value.getAllocationByteCount() / 1024);
            }
        };
    }

    public void load(@NonNull LocalPhoto photo,
                     @NonNull ImageView target,
                     int requestedSizePx) {
        if (closed) return;
        int safeSize = Math.max(96, requestedSizePx);
        String requestKey = photo.selectionKey() + "@" + safeSize;
        cancelTargetRequest(target);
        TargetRequest request = new TargetRequest(requestKey, target);
        target.setTag(request);
        target.setImageDrawable(null);

        Bitmap cached = memoryCache.get(requestKey);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }

        while (!closed && !request.cancelled) {
            DecodeJob existing = inFlight.get(requestKey);
            if (existing != null) {
                if (existing.add(request)) return;
                inFlight.remove(requestKey, existing);
                Bitmap completed = memoryCache.get(requestKey);
                if (completed != null && !completed.isRecycled()) {
                    if (target.getTag() == request) target.setImageBitmap(completed);
                    return;
                }
                continue;
            }

            DecodeJob created = new DecodeJob(requestKey, photo, safeSize);
            DecodeJob raced = inFlight.putIfAbsent(requestKey, created);
            if (raced != null) continue;
            if (!created.add(request)) {
                inFlight.remove(requestKey, created);
                return;
            }
            decodeExecutor.execute(created);
            return;
        }
    }

    public void clear(@NonNull ImageView target) {
        cancelTargetRequest(target);
        target.setImageDrawable(null);
    }

    private void cancelTargetRequest(@NonNull ImageView target) {
        Object tag = target.getTag();
        if (tag instanceof TargetRequest) ((TargetRequest) tag).cancel();
        target.setTag(null);
    }

    private Bitmap decodeThumbnail(LocalPhoto photo, int requestedSizePx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return resolver.loadThumbnail(
                        photo.getUri(), new Size(requestedSizePx, requestedSizePx), null);
            }
            // getThumbnail is deprecated on Android 10, but remains the efficient MediaStore path
            // for this project's API 21-28 support window.
            return MediaStore.Images.Thumbnails.getThumbnail(
                    resolver,
                    photo.getId(),
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null);
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    public void close() {
        closed = true;
        mainHandler.removeCallbacksAndMessages(null);
        for (DecodeJob job : new ArrayList<>(inFlight.values())) job.discard(false);
        inFlight.clear();
        decodeExecutor.shutdownNow();
        memoryCache.evictAll();
    }

    private void discardJob(Runnable runnable, boolean retryIfStillBound) {
        if (runnable instanceof GalleryThumbnailLoader.DecodeJob) {
            ((DecodeJob) runnable).discard(retryIfStillBound);
        }
    }

    private final class TargetRequest {
        final String key;
        final WeakReference<ImageView> target;
        volatile DecodeJob job;
        volatile boolean cancelled;

        TargetRequest(String key, ImageView target) {
            this.key = key;
            this.target = new WeakReference<>(target);
        }

        void cancel() {
            if (cancelled) return;
            cancelled = true;
            DecodeJob current = job;
            job = null;
            if (current != null) current.remove(this);
        }
    }

    private final class DecodeJob implements Runnable {
        final String key;
        final LocalPhoto photo;
        final int requestedSizePx;
        final Set<TargetRequest> requests = new HashSet<>();
        boolean accepting = true;

        DecodeJob(String key, LocalPhoto photo, int requestedSizePx) {
            this.key = key;
            this.photo = photo;
            this.requestedSizePx = requestedSizePx;
        }

        synchronized boolean add(TargetRequest request) {
            if (!accepting || request.cancelled) return false;
            requests.add(request);
            request.job = this;
            return true;
        }

        void remove(TargetRequest request) {
            boolean cancelQueuedJob = false;
            synchronized (this) {
                requests.remove(request);
                if (requests.isEmpty() && accepting) {
                    accepting = false;
                    cancelQueuedJob = true;
                }
            }
            if (cancelQueuedJob) {
                inFlight.remove(key, this);
                decodeExecutor.remove(this);
            }
        }

        private synchronized boolean isActive() {
            return accepting && !requests.isEmpty();
        }

        private synchronized List<TargetRequest> finish() {
            if (!accepting) return Collections.emptyList();
            accepting = false;
            List<TargetRequest> result = new ArrayList<>(requests);
            requests.clear();
            for (TargetRequest request : result) {
                if (request.job == this) request.job = null;
            }
            return result;
        }

        @Override
        public void run() {
            if (closed || !isActive()) {
                inFlight.remove(key, this);
                return;
            }

            Bitmap decoded;
            try {
                decoded = decodeThumbnail(photo, requestedSizePx);
            } catch (Throwable ignored) {
                decoded = null;
            }

            if (closed || !isActive()) {
                inFlight.remove(key, this);
                return;
            }
            if (decoded != null && !decoded.isRecycled()) memoryCache.put(key, decoded);

            List<TargetRequest> subscribers = finish();
            inFlight.remove(key, this);
            Bitmap result = decoded;
            mainHandler.post(() -> {
                if (closed || result == null || result.isRecycled()) return;
                for (TargetRequest request : subscribers) {
                    if (request.cancelled) continue;
                    ImageView imageView = request.target.get();
                    if (imageView != null && imageView.getTag() == request) {
                        imageView.setImageBitmap(result);
                    }
                }
            });
        }

        void discard(boolean retryIfStillBound) {
            List<TargetRequest> subscribers = finish();
            inFlight.remove(key, this);
            if (!retryIfStillBound || closed || subscribers.isEmpty()) return;
            mainHandler.post(() -> {
                if (closed) return;
                for (TargetRequest request : subscribers) {
                    if (request.cancelled) continue;
                    ImageView imageView = request.target.get();
                    if (imageView != null && imageView.getTag() == request) {
                        load(photo, imageView, requestedSizePx);
                    }
                }
            });
        }
    }
}
