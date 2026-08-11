package com.qimu.guide.ui.share;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.qimu.guide.BuildConfig;
import com.qimu.guide.R;
import com.qimu.guide.net.ShareBundleApiClient;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.ui.gallery.GallerySelectionStore;
import com.qimu.guide.ui.gallery.LocalPhoto;
import com.qimu.guide.ui.gallery.LocalPhotoRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/** Uploads the selected tour photos, finishes the bundle, and renders its H5 QR code. */
public final class ShareBundleActivity extends AppCompatActivity {

    private static final String EXTRA_SELECTED_COUNT = "share_selected_count";
    private static final String EXTRA_NEED_VLOG = "share_need_vlog";
    public static final String EXTRA_SHARE_URL = "share_url";
    public static final String EXTRA_PHOTO_COUNT = "share_photo_count";
    public static final String EXTRA_VLOG_STATUS = "share_vlog_status";
    private static final int MAX_PHOTO_BYTES = 10 * 1024 * 1024;

    public static Intent createIntent(@NonNull Context context,
                                      int selectedCount,
                                      boolean needVlog) {
        return new Intent(context, ShareBundleActivity.class)
                .putExtra(EXTRA_SELECTED_COUNT, selectedCount)
                .putExtra(EXTRA_NEED_VLOG, needVlog);
    }

    private final ExecutorService orchestrationExecutor =
            Executors.newSingleThreadExecutor(runnable -> namedThread(runnable, "share-flow"));
    private final ExecutorService uploadExecutor =
            Executors.newFixedThreadPool(4, runnable -> namedThread(runnable, "share-photo"));
    private final List<Future<?>> uploadFutures = new ArrayList<>();

    private TextInputLayout phoneLayout;
    private TextInputEditText phoneInput;
    private MaterialButton startButton;
    private View formPanel;
    private View progressPanel;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView errorText;
    private View qrPanel;
    private ImageView qrImage;
    private TextView qrSummary;
    private TextView shareUrlText;

    private LocalPhotoRepository photoRepository;
    private GallerySelectionStore selectionStore;
    private List<LocalPhoto> selectedPhotos = java.util.Collections.emptyList();
    private boolean needVlog;
    private boolean uploading;
    private boolean completed;
    private int uploadPhotoCount;
    private String shareUrl = "";
    private String phoneLast4 = "";
    private volatile ShareBundleApiClient apiClient;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_bundle);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.qimu_app_bar));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.qimu_nav_background));

        needVlog = getIntent().getBooleanExtra(EXTRA_NEED_VLOG, false);
        bindViews();
        photoRepository = new LocalPhotoRepository(this);
        selectionStore = new GallerySelectionStore(this);

        int expectedCount = getIntent().getIntExtra(EXTRA_SELECTED_COUNT, 0);
        setSelectionSummary(expectedCount);
        startButton.setEnabled(false);
        startButton.setOnClickListener(view -> startShare());
        findViewById(R.id.share_back).setOnClickListener(view -> handleBack());
        findViewById(R.id.share_done).setOnClickListener(view -> finish());
        findViewById(R.id.share_copy_link).setOnClickListener(view -> copyShareUrl());
        findViewById(R.id.share_open_h5).setOnClickListener(view -> openShareUrl());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });
        loadSelectedPhotos();
    }

    private void bindViews() {
        phoneLayout = findViewById(R.id.share_phone_layout);
        phoneInput = findViewById(R.id.share_phone);
        startButton = findViewById(R.id.share_start);
        formPanel = findViewById(R.id.share_form);
        progressPanel = findViewById(R.id.share_progress_panel);
        progressBar = findViewById(R.id.share_progress);
        progressText = findViewById(R.id.share_progress_text);
        errorText = findViewById(R.id.share_error);
        qrPanel = findViewById(R.id.share_qr_panel);
        qrImage = findViewById(R.id.share_qr_image);
        qrSummary = findViewById(R.id.share_qr_summary);
        shareUrlText = findViewById(R.id.share_url);
    }

    private void loadSelectedPhotos() {
        photoRepository.loadPhotos(new LocalPhotoRepository.Callback() {
            @Override
            public void onPhotosLoaded(@NonNull List<LocalPhoto> photos) {
                selectionStore.reload();
                selectionStore.reconcile(photos);
                List<LocalPhoto> selected = new ArrayList<>();
                for (LocalPhoto photo : photos) {
                    if (selectionStore.isSelected(photo)) selected.add(photo);
                }
                selectedPhotos = java.util.Collections.unmodifiableList(selected);
                setSelectionSummary(selected.size());
                startButton.setEnabled(!selected.isEmpty());
                if (selected.isEmpty()) showError("已选照片不存在，请返回重新选择");
            }

            @Override
            public void onLoadFailed(@NonNull Throwable error) {
                startButton.setEnabled(false);
                showError("无法读取已选照片：" + safeMessage(error));
            }
        });
    }

    private void setSelectionSummary(int count) {
        TextView summary = findViewById(R.id.share_selection_summary);
        summary.setText(getString(R.string.share_selection_summary,
                count, needVlog ? "已开启" : "未开启"));
    }

    private void startShare() {
        if (uploading || completed) return;
        if (!TourSessionManager.get().isActive()) {
            showError("本次导览已结束，无法继续创建分享");
            return;
        }
        if (selectedPhotos.isEmpty()) {
            showError("请先选择至少 1 张照片");
            return;
        }
        if (BuildConfig.SHARE_APP_TOKEN == null
                || BuildConfig.SHARE_APP_TOKEN.trim().isEmpty()) {
            showError("此安装包未配置分享服务密钥，请让管理员重新生成联调包");
            return;
        }

        String normalizedPhone = normalizePhone(textOf(phoneInput));
        if (!normalizedPhone.matches("^1\\d{10}$")) {
            phoneLayout.setError("请输入正确的 11 位手机号");
            return;
        }
        phoneLayout.setError(null);
        phoneLast4 = normalizedPhone.substring(normalizedPhone.length() - 4);
        errorText.setVisibility(View.GONE);
        setUploading(true, "正在创建分享…", true, 0);

        AtomicReference<String> phone = new AtomicReference<>(normalizedPhone);
        orchestrationExecutor.execute(() -> runUploadFlow(phone));
    }

    private void runUploadFlow(@NonNull AtomicReference<String> phone) {
        ShareBundleApiClient client = new ShareBundleApiClient(
                BuildConfig.SHARE_APP_TOKEN, androidDeviceId());
        apiClient = client;
        try {
            runOnUiThread(() -> setUploading(true, "正在检查照片…", true, 0));
            List<PreparedPhoto> preparedPhotos = preparePhotos();
            int duplicateCount = selectedPhotos.size() - preparedPhotos.size();
            uploadPhotoCount = preparedPhotos.size();
            runOnUiThread(() -> {
                setSelectionSummary(preparedPhotos.size());
                if (duplicateCount > 0) {
                    Toast.makeText(this, "已跳过 " + duplicateCount + " 张重复照片",
                            Toast.LENGTH_SHORT).show();
                }
                setUploading(true, "正在创建分享…", true, 0);
            });
            TourSessionManager.TourSession session = TourSessionManager.get().current();
            String venueId = session == null ? null : validUuidOrNull(session.venueId);
            ShareBundleApiClient.CreateResult created = client.createBundle(
                    phone.get(), preparedPhotos.size(), needVlog, venueId);
            phone.set(null);
            runOnUiThread(() -> phoneInput.setText(""));

            uploadAllPhotos(client, created.bundleId, preparedPhotos);
            runOnUiThread(() -> setUploading(true, "正在激活分享…", true, 0));
            ShareBundleApiClient.FinishResult finished = client.finishBundle(created.bundleId);
            runOnUiThread(() -> showShareResult(finished));
        } catch (Throwable error) {
            phone.set(null);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setUploading(false, "", false, 0);
                showError(userMessage(error));
            });
        } finally {
            apiClient = null;
            client.close();
        }
    }

    @NonNull
    private List<PreparedPhoto> preparePhotos() throws IOException {
        LinkedHashMap<String, PreparedPhoto> uniqueBySha = new LinkedHashMap<>();
        for (LocalPhoto photo : selectedPhotos) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("上传已取消");
            String mimeType = supportedMimeType(photo);
            String sha256 = calculatePhotoSha256(photo);
            if (!uniqueBySha.containsKey(sha256)) {
                uniqueBySha.put(sha256, new PreparedPhoto(photo, mimeType, sha256));
            }
        }
        if (uniqueBySha.isEmpty()) throw new IOException("没有可上传的有效照片");
        return new ArrayList<>(uniqueBySha.values());
    }

    private void uploadAllPhotos(@NonNull ShareBundleApiClient client,
                                 @NonNull String bundleId,
                                 @NonNull List<PreparedPhoto> photos) throws IOException {
        CompletionService<ShareBundleApiClient.UploadResult> completion =
                new ExecutorCompletionService<>(uploadExecutor);
        synchronized (uploadFutures) {
            uploadFutures.clear();
            for (int index = 0; index < photos.size(); index++) {
                PreparedPhoto prepared = photos.get(index);
                int sortOrder = index;
                uploadFutures.add(completion.submit(() -> {
                    byte[] bytes = readPhotoBytes(prepared.photo);
                    return client.uploadPhoto(
                            bundleId,
                            prepared.photo.getDisplayName(),
                            prepared.mimeType,
                            bytes,
                            sortOrder,
                            prepared.sha256);
                }));
            }
        }

        Throwable firstFailure = null;
        int failureCount = 0;
        int successCount = 0;
        for (int completedCount = 1; completedCount <= photos.size(); completedCount++) {
            try {
                completion.take().get();
                successCount++;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("上传已取消", error);
            } catch (ExecutionException error) {
                failureCount++;
                if (firstFailure == null) firstFailure = error.getCause();
            }
            int uploaded = successCount;
            runOnUiThread(() -> setUploading(
                    true,
                    "已上传 " + uploaded + "/" + photos.size() + " 张",
                    false,
                    uploaded));
        }
        synchronized (uploadFutures) {
            uploadFutures.clear();
        }
        if (failureCount > 0) {
            throw new BatchUploadException(failureCount, firstFailure);
        }
    }

    @NonNull
    private String calculatePhotoSha256(@NonNull LocalPhoto photo) throws IOException {
        if (photo.getSizeBytes() > MAX_PHOTO_BYTES) {
            throw new IOException(photo.getDisplayName() + " 超过 10MB");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = getContentResolver().openInputStream(photo.getUri())) {
                if (input == null) throw new IOException("无法打开 " + photo.getDisplayName());
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_PHOTO_BYTES) {
                        throw new IOException(photo.getDisplayName() + " 超过 10MB");
                    }
                    digest.update(buffer, 0, read);
                }
                if (total == 0) throw new IOException(photo.getDisplayName() + " 内容为空");
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("当前设备不支持 SHA-256", error);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            output[index * 2] = digits[value >>> 4];
            output[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(output);
    }

    @NonNull
    private byte[] readPhotoBytes(@NonNull LocalPhoto photo) throws IOException {
        if (photo.getSizeBytes() > MAX_PHOTO_BYTES) {
            throw new IOException(photo.getDisplayName() + " 超过 10MB");
        }
        try (InputStream input = getContentResolver().openInputStream(photo.getUri());
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     (int) Math.min(Math.max(photo.getSizeBytes(), 8192), MAX_PHOTO_BYTES))) {
            if (input == null) throw new IOException("无法打开 " + photo.getDisplayName());
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_PHOTO_BYTES) {
                    throw new IOException(photo.getDisplayName() + " 超过 10MB");
                }
                output.write(buffer, 0, read);
            }
            if (total == 0) throw new IOException(photo.getDisplayName() + " 内容为空");
            return output.toByteArray();
        }
    }

    private String supportedMimeType(LocalPhoto photo) throws IOException {
        String mime = photo.getMimeType().toLowerCase(java.util.Locale.ROOT);
        String name = photo.getDisplayName().toLowerCase(java.util.Locale.ROOT);
        if ("image/png".equals(mime) || name.endsWith(".png")) return "image/png";
        if ("image/jpeg".equals(mime) || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        throw new IOException(photo.getDisplayName() + " 不是 JPG/PNG 图片");
    }

    private void showShareResult(@NonNull ShareBundleApiClient.FinishResult result) {
        if (isFinishing() || isDestroyed()) return;
        completed = true;
        uploading = false;
        shareUrl = result.shareUrl;
        progressPanel.setVisibility(View.GONE);
        formPanel.setVisibility(View.GONE);
        errorText.setVisibility(View.GONE);
        qrPanel.setVisibility(View.VISIBLE);
        try {
            qrImage.setImageBitmap(createQrBitmap(result.shareUrl, 720));
        } catch (WriterException error) {
            showError("二维码绘制失败，可复制下方链接打开 H5");
        }
        shareUrlText.setText(result.shareUrl);
        String vlogText = result.vlogEnabled
                ? "Vlog 正在后台生成，扫码后页面会自动更新"
                : "未生成 Vlog";
        qrSummary.setText(getString(R.string.share_qr_summary,
                phoneLast4, result.photoCount, vlogText));
        Intent resultIntent = new Intent()
                .putExtra(EXTRA_SHARE_URL, result.shareUrl)
                .putExtra(EXTRA_PHOTO_COUNT, result.photoCount)
                .putExtra(EXTRA_VLOG_STATUS, result.vlogStatus);
        setResult(Activity.RESULT_OK, resultIntent);
    }

    private void setUploading(boolean active, String message,
                              boolean indeterminate, int completedCount) {
        uploading = active;
        startButton.setEnabled(!active && !selectedPhotos.isEmpty());
        phoneInput.setEnabled(!active);
        progressPanel.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) return;
        progressText.setText(message);
        progressBar.setIndeterminate(indeterminate);
        if (!indeterminate) {
            progressBar.setMax(Math.max(1,
                    uploadPhotoCount > 0 ? uploadPhotoCount : selectedPhotos.size()));
            progressBar.setProgress(completedCount);
        }
    }

    private void showError(@NonNull String message) {
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    private String userMessage(Throwable error) {
        Throwable actual = error;
        int failed = 0;
        if (error instanceof BatchUploadException) {
            failed = ((BatchUploadException) error).failureCount;
            if (error.getCause() != null) actual = error.getCause();
        }
        String message;
        if (actual instanceof ShareBundleApiClient.ApiException) {
            ShareBundleApiClient.ApiException api = (ShareBundleApiClient.ApiException) actual;
            switch (api.code) {
                case 30001: message = "APP 分享服务密钥无效，请联系管理员"; break;
                case 30002: message = "手机号格式不正确，请重新输入"; break;
                case 30003:
                case 30014: message = "最多只能分享 50 张照片"; break;
                case 30011: message = "分享会话不存在，请重新生成"; break;
                case 30012: message = "本次分享已完成或已失效，请重新生成"; break;
                case 30013: message = "有照片超过 10MB，无法上传"; break;
                case 30015: message = "有照片不是有效的 JPG/PNG 图片"; break;
                case 30016: message = "仍有照片未上传完成，请重试"; break;
                case 30018: message = "照片传输校验失败，请重试"; break;
                case 404: message = "分享服务尚未部署到当前联调地址"; break;
                default: message = "分享服务返回错误：" + api.serviceMessage;
            }
            if (!api.requestId.isEmpty()) message += "\n请求编号：" + api.requestId;
        } else {
            message = safeMessage(actual);
        }
        return failed > 0 ? failed + " 张照片上传失败。" + message : message;
    }

    private void copyShareUrl() {
        if (shareUrl.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("导览分享链接", shareUrl));
        Toast.makeText(this, "分享链接已复制", Toast.LENGTH_SHORT).show();
    }

    private void openShareUrl() {
        if (shareUrl.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(shareUrl)));
        } catch (RuntimeException error) {
            showError("没有可打开 H5 的浏览器，请复制链接后在游客手机查看");
        }
    }

    @SuppressLint("HardwareIds")
    private String androidDeviceId() {
        String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null || id.trim().isEmpty() ? "android-unknown" : "android-" + id;
    }

    @Nullable
    private static String validUuidOrNull(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizePhone(String raw) {
        String value = raw == null ? "" : raw.replaceAll("[\\s-]", "");
        if (value.startsWith("+86")) value = value.substring(3);
        else if (value.startsWith("86") && value.length() == 13) value = value.substring(2);
        return value;
    }

    private static String textOf(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "网络异常，请稍后重试" : message;
    }

    private static Thread namedThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, "qimu-" + name);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    }

    private static Bitmap createQrBitmap(String content, int size) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, size, size, hints);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            int offset = y * size;
            for (int x = 0; x < size; x++) {
                pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }

    private void handleBack() {
        if (!uploading) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("取消上传？")
                .setMessage("取消后本次分享码不会生成，需要重新上传。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("取消上传", (dialog, which) -> {
                    cancelUpload();
                    finish();
                })
                .show();
    }

    private void cancelUpload() {
        ShareBundleApiClient client = apiClient;
        if (client != null) client.close();
        synchronized (uploadFutures) {
            for (Future<?> future : uploadFutures) future.cancel(true);
            uploadFutures.clear();
        }
    }

    @Override
    protected void onDestroy() {
        cancelUpload();
        orchestrationExecutor.shutdownNow();
        uploadExecutor.shutdownNow();
        if (photoRepository != null) photoRepository.close();
        phoneInput.setText("");
        phoneLast4 = "";
        super.onDestroy();
    }

    private static final class BatchUploadException extends IOException {
        final int failureCount;

        BatchUploadException(int failureCount, @Nullable Throwable cause) {
            super("照片上传失败", cause);
            this.failureCount = failureCount;
        }
    }

    private static final class PreparedPhoto {
        final LocalPhoto photo;
        final String mimeType;
        final String sha256;

        PreparedPhoto(LocalPhoto photo, String mimeType, String sha256) {
            this.photo = photo;
            this.mimeType = mimeType;
            this.sha256 = sha256;
        }
    }
}
