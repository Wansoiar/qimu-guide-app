package com.qimu.guide.ui.gallery;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.qimu.guide.R;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen, in-app preview for one MediaStore photo. */
public final class PhotoPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_URI = "photo_uri";
    public static final String EXTRA_NAME = "photo_name";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qimu-photo-preview");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private ZoomImageView imageView;
    private ProgressBar progressBar;
    private TextView errorView;
    private Bitmap loadedBitmap;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF080604);
        getWindow().setNavigationBarColor(0xFF080604);
        int systemUiFlags = getWindow().getDecorView().getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            systemUiFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemUiFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(systemUiFlags);
        setContentView(R.layout.activity_photo_preview);

        imageView = findViewById(R.id.photo_preview_image);
        progressBar = findViewById(R.id.photo_preview_progress);
        errorView = findViewById(R.id.photo_preview_error);
        TextView title = findViewById(R.id.photo_preview_title);
        findViewById(R.id.photo_preview_back).setOnClickListener(view -> finish());

        String name = getIntent().getStringExtra(EXTRA_NAME);
        title.setText(name == null || name.trim().isEmpty() ? "照片预览" : name);
        String uriText = getIntent().getStringExtra(EXTRA_URI);
        if (uriText == null || uriText.trim().isEmpty()) {
            showError("照片地址无效");
            return;
        }
        decodeInBackground(Uri.parse(uriText));
    }

    private void decodeInBackground(@NonNull Uri uri) {
        progressBar.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        int displayMax = Math.max(
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int maxDecodeDimension = Math.min(4096, Math.max(2048, displayMax * 2));

        decodeExecutor.execute(() -> {
            try {
                Bitmap bitmap = decodeSampled(uri, maxDecodeDimension);
                if (bitmap == null) throw new IOException("图片解码失败");
                mainHandler.post(() -> showBitmap(bitmap));
            } catch (Throwable error) {
                mainHandler.post(() -> showError(safeMessage(error)));
            }
        });
    }

    @Nullable
    private Bitmap decodeSampled(@NonNull Uri uri, int maxDimension) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
                int sourceMax = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                int sample = calculateSampleSize(sourceMax, maxDimension);
                decoder.setTargetSampleSize(sample);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法打开照片");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        int sourceMax = Math.max(bounds.outWidth, bounds.outHeight);
        if (sourceMax <= 0) throw new IOException("无法读取照片尺寸");

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateSampleSize(sourceMax, maxDimension);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法打开照片");
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private int calculateSampleSize(int sourceMax, int maxDimension) {
        int sample = 1;
        // Keep the decoded longest edge at or below the requested hard limit. Using the
        // common "nearest size above target" thumbnail formula can decode an 8K image at full
        // resolution and allocate well over 100 MB.
        while (((long) sourceMax + sample - 1L) / sample > maxDimension
                && sample <= Integer.MAX_VALUE / 2) {
            sample *= 2;
        }
        return sample;
    }

    private void showBitmap(@NonNull Bitmap bitmap) {
        if (destroyed) {
            bitmap.recycle();
            return;
        }
        loadedBitmap = bitmap;
        progressBar.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        imageView.setImageBitmap(bitmap);
    }

    private void showError(@NonNull String message) {
        if (destroyed) return;
        progressBar.setVisibility(View.GONE);
        errorView.setText("无法显示照片\n" + message);
        errorView.setVisibility(View.VISIBLE);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "请稍后重试" : message;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        decodeExecutor.shutdownNow();
        if (imageView != null) imageView.release();
        if (loadedBitmap != null && !loadedBitmap.isRecycled()) loadedBitmap.recycle();
        loadedBitmap = null;
        super.onDestroy();
    }
}
