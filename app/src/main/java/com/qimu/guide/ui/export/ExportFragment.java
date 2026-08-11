package com.qimu.guide.ui.export;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.moyoung.glasses.conn.listener.CRPBleConnectionStateListener;
import com.qimu.guide.R;
import com.qimu.guide.net.TourSessionManager;
import com.qimu.guide.service.BleService;
import com.qimu.guide.service.RealtimeGuideManager;
import com.qimu.guide.service.TourReturnCoordinator;
import com.qimu.guide.ui.gallery.GallerySelectionStore;
import com.qimu.guide.ui.gallery.LocalPhoto;
import com.qimu.guide.ui.gallery.LocalPhotoRepository;
import com.qimu.guide.ui.gallery.LocalPhotosActivity;
import com.qimu.guide.ui.share.ShareBundleActivity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Export hub for the current tour. Photo transfer uses the glasses SDK and selected local
 * photos can be uploaded into a server share bundle for QR/H5 access.
 */
public class ExportFragment extends Fragment {

    private static final long END_MEDIA_QUERY_TIMEOUT_MS = 5000L;

    private BleService bleService;
    private RealtimeGuideManager realtimeGuideManager;
    private TourSessionManager tourSessionManager;
    private TourReturnCoordinator returnCoordinator;
    private TextView tvDeviceState;
    private TextView tvPendingCount;
    private TextView tvLocalCount;
    private TextView tvExportStatus;
    private TextView tvQrStatus;
    private View exportStatusDot;
    private View btnRefreshMedia;
    private View btnExportPhotos;
    private View btnEndTour;
    private View btnGenerateQr;
    private View cardLocalPhotos;
    private ProgressBar exportProgress;
    private boolean exportRequested;
    private boolean connected;
    private boolean endMediaQueryPending;
    private boolean finishAfterExport;
    private int pendingPhotoCount = -1;
    private int localPhotoCount;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AlertDialog returnProgressDialog;
    private TextView returnProgressText;
    private LocalPhotoRepository localPhotoRepository;
    private GallerySelectionStore gallerySelectionStore;
    private final AtomicInteger galleryPublishGeneration = new AtomicInteger();

    private final Runnable endMediaQueryTimeout = () -> {
        if (!endMediaQueryPending || !isAdded()) return;
        endMediaQueryPending = false;
        updateActionState();
        new AlertDialog.Builder(requireContext())
                .setTitle("无法确认眼镜照片数量")
                .setMessage("可以返回重试，或跳过检查直接结束。跳过后眼镜中未导出的照片可能会被清除。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("跳过检查", (dialog, which) -> showFinalReturnConfirmation(true))
                .show();
    };

    private final TourSessionManager.Listener tourSessionListener = active -> {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            updateConnectionState();
            updateActionState();
        });
    };

    private final TourReturnCoordinator.Listener returnListener =
            new TourReturnCoordinator.Listener() {
                @Override
                public void onReturnStageChanged(String message) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> showReturnProgress(message));
                }

                @Override
                public void onReturnFinished(boolean glassesResetConfirmed,
                                             boolean serverCloseSucceeded,
                                             boolean localCleanupSucceeded) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        setNavigationEnabled(true);
                        if (returnProgressDialog != null) returnProgressDialog.dismiss();
                        if (!localCleanupSucceeded) {
                            Toast.makeText(requireContext(),
                                    "本机导览缓存未完全清理，已阻止下一位游客开始导览；请立即告知管理员",
                                    Toast.LENGTH_LONG).show();
                        } else if (!glassesResetConfirmed) {
                            Toast.makeText(requireContext(),
                                    "眼镜清理未确认，请归还前告知管理员",
                                    Toast.LENGTH_LONG).show();
                        } else if (!serverCloseSucceeded) {
                            Toast.makeText(requireContext(),
                                    "眼镜已清理，会话服务稍后会自动关闭",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(requireContext(),
                                    "本次游览已结束，眼镜正在准备下一位游客",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            };

    private final ActivityResultLauncher<String[]> wifiPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = true;
                for (Boolean value : result.values()) {
                    if (!Boolean.TRUE.equals(value)) {
                        granted = false;
                        break;
                    }
                }
                if (granted) startPhotoExport();
                else {
                    finishAfterExport = false;
                    Toast.makeText(requireContext(),
                            "需要附近设备、位置信息或存储权限才能连接眼镜 Wi-Fi 并保存照片",
                            Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<Intent> wifiSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (!isAdded()) return;
                if (isWifiEnvironmentReady()) {
                    startPhotoExport();
                } else {
                    finishAfterExport = false;
                    Toast.makeText(requireContext(),
                            requiresLocationService()
                                    ? "定位服务和手机 Wi-Fi 都开启后才能连接眼镜"
                                    : "请先开启手机 Wi-Fi",
                            Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<Intent> shareUploadLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (!isAdded() || result.getResultCode() != Activity.RESULT_OK
                        || result.getData() == null) return;
                int photoCount = result.getData().getIntExtra(
                        ShareBundleActivity.EXTRA_PHOTO_COUNT, 0);
                String vlogStatus = result.getData().getStringExtra(
                        ShareBundleActivity.EXTRA_VLOG_STATUS);
                String suffix = vlogStatus == null || vlogStatus.isEmpty()
                        ? "未生成 Vlog" : "Vlog " + vlogStatus;
                tvQrStatus.setText("分享码已生成 · " + photoCount + " 张 · " + suffix);
                Toast.makeText(requireContext(), "分享二维码已生成",
                        Toast.LENGTH_SHORT).show();
            });

    private final ActivityResultLauncher<Intent> shareSelectionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (!isAdded() || result.getResultCode() != Activity.RESULT_OK
                        || result.getData() == null) return;
                int selectedCount = result.getData().getIntExtra(
                        LocalPhotosActivity.EXTRA_SELECTED_COUNT, 0);
                boolean vlogEnabled = result.getData().getBooleanExtra(
                        LocalPhotosActivity.EXTRA_VLOG_ENABLED, false);
                if (selectedCount <= 0) return;
                gallerySelectionStore.reload();
                tvQrStatus.setText(getString(R.string.export_share_selection_summary,
                        selectedCount,
                        getString(vlogEnabled
                                ? R.string.vlog_enabled : R.string.vlog_disabled)));
                shareUploadLauncher.launch(ShareBundleActivity.createIntent(
                        requireContext(), selectedCount, vlogEnabled));
            });

    private final BleService.BleListener bleListener = new BleService.BleListener() {
        @Override
        public void onConnectionStateChanged(int state) {
            updateConnectionState();
        }

        @Override public void onBatteryUpdate(int level, boolean charging) { }
        @Override public void onFirmwareVersion(String version) { }

        @Override
        public void onMediaFileChanged(int photoCount, int videoCount, int audioCount) {
            pendingPhotoCount = photoCount;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (tvPendingCount != null) tvPendingCount.setText(String.valueOf(photoCount));
                if (endMediaQueryPending) {
                    endMediaQueryPending = false;
                    mainHandler.removeCallbacks(endMediaQueryTimeout);
                    handleEndMediaCount(photoCount);
                } else {
                    setStatus("已刷新眼镜文件数量");
                }
                updateActionState();
            });
        }

        @Override
        public void onWifiStateChange(int state) {
            // Raw SDK state is surfaced in diagnostics; the service owns the
            // ordered enable/connect/download state machine.
        }

        @Override
        public void onWifiConnectionChanged(boolean connected) {
            // Handled by BleService's transfer state machine.
        }

        @Override public void onLog(String tag, String message) { }

        @Override
        public void onError(String message) {
            setStatus(message);
            if (exportRequested && !bleService.isMediaDownloadActive()) finishExport(false, message);
            if (endMediaQueryPending) mainHandler.post(endMediaQueryTimeout);
        }
    };

    private final BleService.MediaDownloadListener mediaDownloadListener =
            new BleService.MediaDownloadListener() {
                @Override
                public void onStageChanged(String message) {
                    exportRequested = true;
                    setBusyUi(true);
                    setStatus(message);
                }

                @Override
                public void onProgress(int total, int downloaded, int percent) {
                    setStatus("正在下载照片 " + downloaded + "/" + total + "（" + percent + "%）");
                    if (exportProgress != null) {
                        exportProgress.setIndeterminate(false);
                        exportProgress.setMax(100);
                        exportProgress.setProgress(percent);
                    }
                }

                @Override
                public void onFileDownloaded(String path) {
                    // The service already reports transfer progress. Recount
                    // the directory once on completion instead of recursively
                    // walking the full history after every file.
                }

                @Override
                public void onCompleted(String directory, List<String> paths) {
                    setStatus("眼镜下载完成，正在保存到手机相册…");
                    publishDownloadedPhotos(directory, paths);
                }

                @Override
                public void onFailed(int code, String message) {
                    finishExport(false, message);
                }

                @Override
                public void onCancelled() {
                    finishExport(false, "已取消照片导出");
                }
            };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_export, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bleService = BleService.getInstance();
        realtimeGuideManager = RealtimeGuideManager.get();
        tourSessionManager = TourSessionManager.get();
        returnCoordinator = TourReturnCoordinator.get();
        localPhotoRepository = new LocalPhotoRepository(requireContext());
        gallerySelectionStore = new GallerySelectionStore(requireContext());

        tvDeviceState = view.findViewById(R.id.tv_export_device_state);
        tvPendingCount = view.findViewById(R.id.tv_pending_count);
        tvLocalCount = view.findViewById(R.id.tv_local_count);
        tvExportStatus = view.findViewById(R.id.tv_export_status);
        tvQrStatus = view.findViewById(R.id.tv_qr_status);
        exportStatusDot = view.findViewById(R.id.export_status_dot);
        exportProgress = view.findViewById(R.id.export_progress);
        btnRefreshMedia = view.findViewById(R.id.btn_refresh_media);
        btnExportPhotos = view.findViewById(R.id.btn_export_photos);
        btnEndTour = view.findViewById(R.id.btn_end_tour);
        btnGenerateQr = view.findViewById(R.id.btn_generate_qr);
        cardLocalPhotos = view.findViewById(R.id.card_local_photos);

        bleService.addListener(bleListener);
        bleService.setMediaDownloadListener(mediaDownloadListener);
        tourSessionManager.addListener(tourSessionListener);
        returnCoordinator.addListener(returnListener);
        updateConnectionState();
        refreshLocalCount();
        if (bleService.isMediaDownloadActive()) {
            exportRequested = true;
            setBusyUi(true);
        }

        btnRefreshMedia.setOnClickListener(v -> {
            if (!bleService.isConnected()) {
                Toast.makeText(requireContext(), R.string.must_connect_first, Toast.LENGTH_SHORT).show();
                return;
            }
            setStatus("正在查询眼镜文件…");
            bleService.queryNewMediaFile();
            refreshLocalCount();
        });

        btnExportPhotos.setOnClickListener(v -> startPhotoExport());

        cardLocalPhotos.setOnClickListener(v -> openLocalPhotos(false));
        btnGenerateQr.setOnClickListener(v -> openLocalPhotos(true));
        btnEndTour.setOnClickListener(v -> confirmEndTour());

        boolean returnInProgress = returnCoordinator.isInProgress();
        if (!returnInProgress && tourSessionManager.isActive() && bleService.isConnected()) {
            bleService.queryNewMediaFile();
        }
        if (returnInProgress) {
            setNavigationEnabled(false);
            showReturnProgress("正在完成归还流程…");
            updateActionState();
        }
    }

    private void openLocalPhotos(boolean selectionMode) {
        if (selectionMode) {
            if (!tourSessionManager.isActive()) {
                Toast.makeText(requireContext(), R.string.must_start_tour_first,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (localPhotoCount <= 0) {
                Toast.makeText(requireContext(), R.string.no_session_data,
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Intent intent = new Intent(requireContext(), LocalPhotosActivity.class);
        intent.putExtra(LocalPhotosActivity.EXTRA_SELECTION_MODE, selectionMode);
        if (selectionMode) shareSelectionLauncher.launch(intent);
        else startActivity(intent);
    }

    private void startPhotoExport() {
        if (!tourSessionManager.isActive()) {
            finishAfterExport = false;
            Toast.makeText(requireContext(), R.string.must_start_tour_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bleService.isConnected()) {
            finishAfterExport = false;
            Toast.makeText(requireContext(), R.string.must_connect_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (pendingPhotoCount <= 0) {
            finishAfterExport = false;
            Toast.makeText(requireContext(), R.string.export_nothing_pending, Toast.LENGTH_SHORT).show();
            return;
        }
        if (exportRequested) return;

        List<String> missingPermissions = getMissingWifiPermissions();
        if (!missingPermissions.isEmpty()) {
            wifiPermissionsLauncher.launch(missingPermissions.toArray(new String[0]));
            return;
        }

        if (requiresLocationService() && !isSystemLocationEnabled()) {
            Toast.makeText(requireContext(),
                    "请开启手机定位服务，以便发现眼镜 Wi-Fi",
                    Toast.LENGTH_LONG).show();
            wifiSettingsLauncher.launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }

        if (!isPhoneWifiEnabled()) {
            Toast.makeText(requireContext(), "请先开启手机 Wi-Fi", Toast.LENGTH_LONG).show();
            Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? new Intent(Settings.Panel.ACTION_WIFI)
                    : new Intent(Settings.ACTION_WIFI_SETTINGS);
            wifiSettingsLauncher.launch(intent);
            return;
        }

        exportRequested = true;
        setBusyUi(true);
        setStatus("正在暂停眼镜收音并准备照片传输…");
        realtimeGuideManager.suspendForMediaTransfer(this::beginPhotoExportAfterAudioReleased);
    }

    private void beginPhotoExportAfterAudioReleased() {
        if (!exportRequested) return;
        if (!bleService.isConnected()) {
            finishExport(false, "眼镜已断开，照片导出未启动");
            return;
        }
        setStatus("正在开启眼镜 Wi-Fi，App 将自动查找并连接…");
        if (!bleService.startMediaDownload()) {
            finishAfterExport = false;
            finishExport(false, "照片导出未启动，请稍后重试");
        }
    }

    private void finishExport(boolean success, @Nullable String errorMessage) {
        if (realtimeGuideManager != null) realtimeGuideManager.completeMediaTransferHold();
        exportRequested = false;
        setBusyUi(false);
        if (success) {
            pendingPhotoCount = 0;
            if (tvPendingCount != null) tvPendingCount.setText("0");
            bleService.queryNewMediaFile();
        }
        refreshLocalCount();
        updateActionState();
        if (!success) {
            setStatus(errorMessage == null
                    ? "导出未完成，请保持眼镜靠近后重试"
                    : errorMessage);
        }
        if (finishAfterExport) {
            finishAfterExport = false;
            if (success) {
                new AlertDialog.Builder(requireContext())
                        .setTitle("照片已保存到本机")
                        .setMessage("可以先检查选中的照片和 Vlog 设置；二维码上传暂未接入。是否现在结束游览？")
                        .setNegativeButton("先选照片", (dialog, which) ->
                                startActivity(new Intent(requireContext(), LocalPhotosActivity.class)))
                        .setPositiveButton("结束游览", (dialog, which) ->
                                showFinalReturnConfirmation(false))
                        .show();
            } else {
                new AlertDialog.Builder(requireContext())
                        .setTitle("照片导出未完成")
                        .setMessage("继续结束会清除眼镜中尚未导出的照片。")
                        .setNegativeButton("返回重试", null)
                        .setPositiveButton("仍然结束", (dialog, which) ->
                                showFinalReturnConfirmation(true))
                        .show();
            }
        }
    }

    private void setBusyUi(boolean busy) {
        if (exportProgress != null) {
            exportProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (busy) exportProgress.setIndeterminate(true);
        }
        updateActionState();
    }

    private List<String> getMissingWifiPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return missing;
    }

    /** Android 13+ uses NEARBY_WIFI_DEVICES and must not be blocked by the location toggle. */
    private boolean requiresLocationService() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU;
    }

    private boolean isWifiEnvironmentReady() {
        return isPhoneWifiEnabled()
                && (!requiresLocationService() || isSystemLocationEnabled());
    }

    private boolean isSystemLocationEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        LocationManager manager = (LocationManager) requireContext()
                .getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return manager.isLocationEnabled();
        try {
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isPhoneWifiEnabled() {
        WifiManager manager = (WifiManager) requireContext()
                .getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return manager != null && manager.isWifiEnabled();
    }

    /**
     * The SDK downloads into the app-private moyoung directory. Publish only image files into
     * MediaStore so they are visible in the phone's gallery; audio/video downloaded by the SDK
     * remain untouched in the private directory.
     */
    private void publishDownloadedPhotos(@Nullable String directory,
                                         @Nullable List<String> downloadedPaths) {
        Context context = getContext();
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        List<String> paths = downloadedPaths == null
                ? new ArrayList<>() : new ArrayList<>(downloadedPaths);
        int generation = galleryPublishGeneration.incrementAndGet();

        new Thread(() -> {
            int imageCount = 0;
            int savedCount = 0;
            String firstError = null;
            for (String path : paths) {
                File source = resolveDownloadedFile(directory, path);
                if (source == null || !source.isFile() || !isImagePath(source.getName())) continue;
                imageCount++;
                try {
                    publishImage(appContext, source);
                    savedCount++;
                } catch (Exception e) {
                    if (firstError == null) firstError = e.getMessage();
                }
            }

            int finalImageCount = imageCount;
            int finalSavedCount = savedCount;
            String finalFirstError = firstError;
            if (generation != galleryPublishGeneration.get() || !isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (generation != galleryPublishGeneration.get() || !isAdded()) return;
                if (finalImageCount == 0) {
                    finishExport(false, "下载完成，但 SDK 未返回可保存的图片文件");
                } else if (finalSavedCount == finalImageCount) {
                    setStatus("导出完成，已保存 " + finalSavedCount + " 张照片到手机相册");
                    finishExport(true, null);
                } else {
                    String detail = finalFirstError == null ? "未知错误" : finalFirstError;
                    finishExport(false, "下载已完成，但仅保存 " + finalSavedCount + "/"
                            + finalImageCount + " 张到相册：" + detail);
                }
            });
        }, "export-gallery-publish").start();
    }

    @Nullable
    private File resolveDownloadedFile(@Nullable String directory, @Nullable String path) {
        if (path == null || path.trim().isEmpty()) return null;
        File direct = new File(path);
        if (direct.isFile()) return direct;
        if (directory == null || directory.trim().isEmpty()) return direct;
        File relative = new File(directory, path);
        if (relative.isFile()) return relative;
        return new File(directory, direct.getName());
    }

    private void publishImage(Context context, File source) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishImageWithMediaStore(context, source);
        } else {
            publishLegacyImage(context, source);
        }
    }

    private void publishImageWithMediaStore(Context context, File source) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Images.Media.MIME_TYPE, imageMimeType(source.getName()));
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                LocalPhotoRepository.RELATIVE_ALBUM_PATH);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法创建系统相册文件");
        boolean completed = false;
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = resolver.openOutputStream(uri)) {
            if (output == null) throw new IOException("无法写入系统相册");
            copyStream(input, output);
            completed = true;
        } finally {
            if (!completed) resolver.delete(uri, null, null);
        }

        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        if (resolver.update(uri, values, null, null) <= 0) {
            resolver.delete(uri, null, null);
            throw new IOException("系统相册提交失败");
        }
    }

    @SuppressWarnings("deprecation")
    private void publishLegacyImage(Context context, File source) throws IOException {
        File pictures = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        File targetDirectory = new File(pictures, LocalPhotoRepository.ALBUM_NAME);
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw new IOException("无法创建相册目录");
        }
        File target = uniqueLegacyTarget(targetDirectory, source.getName());
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            copyStream(input, output);
        }
        CountDownLatch scanCompleted = new CountDownLatch(1);
        AtomicReference<Uri> scannedUri = new AtomicReference<>();
        MediaScannerConnection.scanFile(context,
                new String[]{target.getAbsolutePath()},
                new String[]{imageMimeType(target.getName())},
                (path, uri) -> {
                    scannedUri.set(uri);
                    scanCompleted.countDown();
                });
        try {
            if (!scanCompleted.await(15, TimeUnit.SECONDS)) {
                throw new IOException("系统相册扫描超时");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("系统相册扫描被中断", interrupted);
        }
        if (scannedUri.get() == null) throw new IOException("系统相册未识别该照片");
    }

    private File uniqueLegacyTarget(File directory, String fileName) {
        File target = new File(directory, fileName);
        if (!target.exists()) return target;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 1; ; index++) {
            target = new File(directory, base + "_" + index + extension);
            if (!target.exists()) return target;
        }
    }

    private void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    private String imageMimeType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        return "image/jpeg";
    }

    private void updateConnectionState() {
        if (!isAdded() || tvDeviceState == null) return;
        connected = bleService.isConnected()
                || bleService.getConnectionState() == CRPBleConnectionStateListener.STATE_CONNECTED;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded() || tvDeviceState == null) return;
            if (tourSessionManager != null && !tourSessionManager.isActive()) {
                tvDeviceState.setText("尚未开始导览 · 可查看本机照片");
            } else {
                tvDeviceState.setText(connected
                        ? R.string.export_session_ready
                        : R.string.export_session_offline);
            }
            exportStatusDot.setBackgroundResource(connected
                    ? R.drawable.dot_status_connected
                    : R.drawable.dot_status_disconnected);
            updateActionState();
        });
    }

    private void updateActionState() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            boolean activeTour = tourSessionManager != null && tourSessionManager.isActive();
            boolean returning = returnCoordinator != null && returnCoordinator.isInProgress();
            if (btnRefreshMedia != null) {
                btnRefreshMedia.setEnabled(activeTour && connected
                        && !exportRequested && !endMediaQueryPending && !returning);
            }
            if (btnExportPhotos != null) {
                btnExportPhotos.setEnabled(activeTour && connected && pendingPhotoCount > 0
                        && !exportRequested && !endMediaQueryPending && !returning);
            }
            if (btnEndTour != null) {
                btnEndTour.setEnabled(activeTour && !exportRequested
                        && !endMediaQueryPending && !returning);
            }
            if (cardLocalPhotos != null) cardLocalPhotos.setEnabled(!returning);
            if (btnGenerateQr != null) {
                btnGenerateQr.setEnabled(activeTour && localPhotoCount > 0 && !returning);
            }
        });
    }

    private void refreshLocalCount() {
        if (tvLocalCount == null || localPhotoRepository == null) return;
        localPhotoRepository.loadPhotos(new LocalPhotoRepository.Callback() {
            @Override
            public void onPhotosLoaded(@NonNull List<LocalPhoto> photos) {
                if (!isAdded() || tvLocalCount == null) return;
                localPhotoCount = photos.size();
                tvLocalCount.setText(String.valueOf(photos.size()));
                updateActionState();
            }

            @Override
            public void onLoadFailed(@NonNull Throwable error) {
                if (!isAdded() || tvLocalCount == null) return;
                localPhotoCount = 0;
                tvLocalCount.setText("—");
                updateActionState();
            }
        });
    }

    private boolean isImagePath(String path) {
        if (path == null) return false;
        String name = path.toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp")
                || name.endsWith(".heic") || name.endsWith(".heif");
    }

    private void setStatus(String status) {
        if (!isAdded() || tvExportStatus == null) return;
        requireActivity().runOnUiThread(() -> {
            if (tvExportStatus != null) tvExportStatus.setText(status);
        });
    }

    private void confirmEndTour() {
        if (!tourSessionManager.isActive()) {
            Toast.makeText(requireContext(), R.string.must_start_tour_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (returnCoordinator.isInProgress() || exportRequested || endMediaQueryPending) return;

        if (!connected) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("眼镜尚未连接")
                    .setMessage("无法检查和清理眼镜中的照片。建议先重新连接；也可以跳过检查结束，但 App 会保留清理告警。")
                    .setNegativeButton("重新连接", (dialog, which) ->
                            bleService.autoReconnectLastDevice())
                    .setPositiveButton("跳过检查", (dialog, which) ->
                            showFinalReturnConfirmation(true))
                    .show();
            return;
        }

        endMediaQueryPending = true;
        updateActionState();
        setStatus("正在检查眼镜上是否还有未导出的照片…");
        bleService.queryNewMediaFile();
        mainHandler.removeCallbacks(endMediaQueryTimeout);
        mainHandler.postDelayed(endMediaQueryTimeout, END_MEDIA_QUERY_TIMEOUT_MS);
    }

    private void handleEndMediaCount(int photoCount) {
        if (!isAdded()) return;
        if (photoCount <= 0) {
            showFinalReturnConfirmation(false);
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("眼镜上还有 " + photoCount + " 张未导出照片")
                .setMessage("可以先导出到手机再结束；直接结束会清除这些眼镜原图。")
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton("直接结束", (dialog, which) ->
                        showFinalReturnConfirmation(true))
                .setPositiveButton("先导出再结束", (dialog, which) -> {
                    finishAfterExport = true;
                    startPhotoExport();
                })
                .show();
    }

    private void showFinalReturnConfirmation(boolean photosMayBeLost) {
        String message = photosMayBeLost
                ? "结束后会重置眼镜，并清空本机“齐目眼镜”相册。尚未导出的眼镜照片也将无法恢复。"
                : "结束后会关闭本次会话、重置眼镜，并清空本机“齐目眼镜”相册。";
        new AlertDialog.Builder(requireContext())
                .setTitle("确认结束本次导览？")
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.end_tour_confirm, (dialog, which) ->
                        beginIrreversibleReturn())
                .show();
    }

    private void beginIrreversibleReturn() {
        Fragment dialogue = getParentFragmentManager().findFragmentByTag("tab_dialogue");
        if (dialogue != null) {
            FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
            transaction.remove(dialogue).commit();
        }
        setNavigationEnabled(false);
        showReturnProgress("正在开始归还流程…");
        if (!returnCoordinator.beginReturn()) {
            setNavigationEnabled(true);
            if (returnProgressDialog != null) returnProgressDialog.dismiss();
            Toast.makeText(requireContext(), "归还流程未启动，请重试", Toast.LENGTH_SHORT).show();
        }
        updateActionState();
    }

    private void showReturnProgress(String message) {
        if (!isAdded()) return;
        if (returnProgressDialog == null) {
            LinearLayout content = new LinearLayout(requireContext());
            content.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(24 * getResources().getDisplayMetrics().density);
            content.setPadding(padding, padding / 2, padding, padding);
            ProgressBar progress = new ProgressBar(requireContext());
            content.addView(progress, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            returnProgressText = new TextView(requireContext());
            returnProgressText.setTextColor(ContextCompat.getColor(
                    requireContext(), R.color.qimu_text_secondary));
            returnProgressText.setTextSize(14);
            returnProgressText.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textParams.topMargin = padding / 2;
            content.addView(returnProgressText, textParams);
            returnProgressDialog = new AlertDialog.Builder(requireContext())
                    .setTitle("正在结束导览")
                    .setView(content)
                    .setCancelable(false)
                    .create();
        }
        returnProgressText.setText(message);
        if (!returnProgressDialog.isShowing()) returnProgressDialog.show();
    }

    private void setNavigationEnabled(boolean enabled) {
        BottomNavigationView navigation = requireActivity().findViewById(R.id.bottom_navigation);
        for (int index = 0; index < navigation.getMenu().size(); index++) {
            navigation.getMenu().getItem(index).setEnabled(enabled);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshLocalCount();
    }

    @Override
    public void onDestroyView() {
        mainHandler.removeCallbacks(endMediaQueryTimeout);
        galleryPublishGeneration.incrementAndGet();
        if (returnProgressDialog != null) {
            returnProgressDialog.dismiss();
            returnProgressDialog = null;
        }
        if (localPhotoRepository != null) {
            localPhotoRepository.close();
            localPhotoRepository = null;
        }
        if (tourSessionManager != null) tourSessionManager.removeListener(tourSessionListener);
        if (returnCoordinator != null) returnCoordinator.removeListener(returnListener);
        if (bleService != null) {
            bleService.removeListener(bleListener);
            bleService.removeMediaDownloadListener(mediaDownloadListener);
        }
        super.onDestroyView();
    }
}
