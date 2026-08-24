package com.qimu.guide.ui.gallery;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.qimu.guide.R;

import java.util.Collections;
import java.util.List;

/** App-owned gallery for photos published to the 齐目眼镜 MediaStore album. */
public final class LocalPhotosActivity extends AppCompatActivity {

    public static final String EXTRA_SELECTION_MODE = "selection_mode";
    public static final String EXTRA_SELECTED_COUNT = "selected_count";

    private RecyclerView photoGrid;
    private ProgressBar progressBar;
    private View emptyState;
    private TextView emptyTitle;
    private TextView emptyBody;
    private TextView selectionSummary;
    private TextView selectAllAction;
    private TextView galleryTitle;
    private MaterialButton retryAction;
    private MaterialButton confirmSelectionAction;
    private View selectionPanel;

    private LocalPhotoRepository repository;
    private GallerySelectionStore selectionStore;
    private GalleryThumbnailLoader thumbnailLoader;
    private LocalPhotoGridAdapter adapter;
    private List<LocalPhoto> photos = Collections.emptyList();
    private boolean firstLoad = true;
    private boolean legacyPermissionRequested;
    private boolean permissionRequestInFlight;
    private boolean selectionMode;
    private boolean initializeShareDefaults;

    private final ActivityResultLauncher<String> legacyReadPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                permissionRequestInFlight = false;
                if (granted) loadPhotos(true);
                else showLegacyPermissionRequired();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_photos);

        getWindow().setStatusBarColor(getColorCompat(R.color.qimu_app_bar));
        getWindow().setNavigationBarColor(getColorCompat(R.color.qimu_nav_background));

        selectionMode = getIntent().getBooleanExtra(EXTRA_SELECTION_MODE, false);
        initializeShareDefaults = selectionMode && savedInstanceState == null;

        repository = new LocalPhotoRepository(this);
        selectionStore = new GallerySelectionStore(this);
        thumbnailLoader = new GalleryThumbnailLoader(this);

        photoGrid = findViewById(R.id.gallery_photo_grid);
        progressBar = findViewById(R.id.gallery_progress);
        emptyState = findViewById(R.id.gallery_empty_state);
        emptyTitle = findViewById(R.id.gallery_empty_title);
        emptyBody = findViewById(R.id.gallery_empty_body);
        selectionSummary = findViewById(R.id.gallery_selection_summary);
        selectAllAction = findViewById(R.id.gallery_select_all);
        galleryTitle = findViewById(R.id.gallery_title);
        retryAction = findViewById(R.id.gallery_retry);
        confirmSelectionAction = findViewById(R.id.gallery_confirm_selection);
        selectionPanel = findViewById(R.id.gallery_selection_panel);

        findViewById(R.id.gallery_back).setOnClickListener(view -> finish());
        selectAllAction.setOnClickListener(view -> toggleSelectAll());
        retryAction.setOnClickListener(view -> retryPhotoAccess());
        confirmSelectionAction.setOnClickListener(view -> confirmShareSelection());

        galleryTitle.setText(selectionMode
                ? R.string.gallery_select_title : R.string.gallery_view_title);
        selectAllAction.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        selectionPanel.setVisibility(selectionMode ? View.VISIBLE : View.GONE);

        int spacing = dp(4);
        int estimatedCell = Math.max(dp(96),
                (getResources().getDisplayMetrics().widthPixels - dp(32)) / 3);
        adapter = new LocalPhotoGridAdapter(
                thumbnailLoader,
                new LocalPhotoGridAdapter.Listener() {
                    @Override
                    public void onOpenPhoto(@NonNull LocalPhoto photo) {
                        openPreview(photo);
                    }

                    @Override
                    public boolean onSelectionChanged(@NonNull LocalPhoto photo,
                                                      boolean selected) {
                        boolean accepted = selectionStore.setSelected(photo, selected);
                        if (!accepted) {
                            Toast.makeText(LocalPhotosActivity.this,
                                    "最多选择 " + GallerySelectionStore.MAX_SELECTION + " 张照片",
                                    Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        updateSelectionHeader();
                        return true;
                    }
                },
                estimatedCell,
                selectionMode);
        photoGrid.setLayoutManager(new GridLayoutManager(this, 3));
        photoGrid.setAdapter(adapter);
        photoGrid.addItemDecoration(new EvenGridSpacingDecoration(spacing));
        photoGrid.setHasFixedSize(true);
        selectionSummary.setText("正在读取照片…");
        selectAllAction.setEnabled(false);
        selectAllAction.setAlpha(0.45f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPhotosWhenPermitted(firstLoad, firstLoad);
        firstLoad = false;
    }

    private void loadPhotosWhenPermitted(boolean showLoading, boolean requestIfMissing) {
        if (!isLegacyReadPermissionMissing()) {
            loadPhotos(showLoading);
            return;
        }
        showLegacyPermissionRequired();
        if (requestIfMissing && !permissionRequestInFlight) requestLegacyReadPermission();
    }

    private boolean isLegacyReadPermissionMissing() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void requestLegacyReadPermission() {
        if (!isLegacyReadPermissionMissing()) {
            loadPhotos(true);
            return;
        }
        legacyPermissionRequested = true;
        permissionRequestInFlight = true;
        legacyReadPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private void retryPhotoAccess() {
        if (!isLegacyReadPermissionMissing()) {
            loadPhotos(true);
            return;
        }
        if (legacyPermissionRequested
                && !ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(settings);
        } else {
            requestLegacyReadPermission();
        }
    }

    private void showLegacyPermissionRequired() {
        confirmSelectionAction.setEnabled(false);
        progressBar.setVisibility(View.GONE);
        photoGrid.setVisibility(View.GONE);
        emptyState.setVisibility(View.VISIBLE);
        emptyTitle.setText("需要照片访问权限");
        emptyBody.setText("允许访问照片后，才能读取已保存到“"
                + LocalPhotoRepository.ALBUM_NAME + "”相册的图片。");
        retryAction.setText(legacyPermissionRequested
                && !ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.READ_EXTERNAL_STORAGE)
                ? "前往设置" : "授权并重试");
        retryAction.setVisibility(View.VISIBLE);
        selectionSummary.setText("等待照片访问权限");
        selectAllAction.setEnabled(false);
        selectAllAction.setAlpha(0.45f);
    }

    private void loadPhotos(boolean showLoading) {
        confirmSelectionAction.setEnabled(false);
        retryAction.setVisibility(View.GONE);
        if (showLoading) {
            progressBar.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
        }
        repository.loadPhotos(new LocalPhotoRepository.Callback() {
            @Override
            public void onPhotosLoaded(@NonNull List<LocalPhoto> loadedPhotos) {
                progressBar.setVisibility(View.GONE);
                photos = loadedPhotos;
                if (selectionMode) {
                    if (initializeShareDefaults && !photos.isEmpty()) {
                        selectionStore.selectAll(photos);
                        initializeShareDefaults = false;
                    } else {
                        selectionStore.reconcile(photos);
                    }
                    adapter.submit(photos, selectionStore.getSelectedUris());
                } else {
                    adapter.submit(photos, Collections.emptySet());
                }
                photoGrid.setVisibility(photos.isEmpty() ? View.GONE : View.VISIBLE);
                emptyState.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
                retryAction.setVisibility(View.GONE);
                if (photos.isEmpty()) {
                    emptyTitle.setText("还没有保存的照片");
                    emptyBody.setText("从眼镜导出完成后，照片会显示在这里，"
                            + "也会保存在系统相册“" + LocalPhotoRepository.ALBUM_NAME + "”中。");
                }
                updateSelectionHeader();
            }

            @Override
            public void onLoadFailed(@NonNull Throwable error) {
                confirmSelectionAction.setEnabled(false);
                progressBar.setVisibility(View.GONE);
                photoGrid.setVisibility(View.GONE);
                emptyState.setVisibility(View.VISIBLE);
                emptyTitle.setText("无法读取本机照片");
                emptyBody.setText("请确认照片访问权限后重试。\n" + safeErrorMessage(error));
                retryAction.setText("重试");
                retryAction.setVisibility(View.VISIBLE);
                photos = Collections.emptyList();
                selectionSummary.setText("读取失败");
                selectAllAction.setEnabled(false);
                selectAllAction.setAlpha(0.45f);
            }
        });
    }

    private void toggleSelectAll() {
        if (!selectionMode || photos.isEmpty()) return;
        if (selectionStore.areAllSelectablePhotosSelected(photos)) {
            selectionStore.clearSelection();
        } else {
            selectionStore.selectAll(photos);
            if (photos.size() > GallerySelectionStore.MAX_SELECTION) {
                Toast.makeText(this,
                        "最多选择 " + GallerySelectionStore.MAX_SELECTION
                                + " 张，已选择最新的 "
                                + GallerySelectionStore.MAX_SELECTION + " 张",
                        Toast.LENGTH_SHORT).show();
            }
        }
        adapter.updateSelection(selectionStore.getSelectedUris());
        updateSelectionHeader();
    }

    private void updateSelectionHeader() {
        int total = photos == null ? 0 : photos.size();
        if (!selectionMode) {
            selectionSummary.setText(getString(R.string.gallery_count_summary, total));
            return;
        }

        int selected = selectionStore == null ? 0 : selectionStore.getSelectedCount();
        selectionSummary.setText(getString(
                R.string.gallery_selection_summary, selected, total));
        boolean allSelected = selectionStore != null
                && selectionStore.areAllSelectablePhotosSelected(photos);
        selectAllAction.setText(allSelected
                ? R.string.gallery_select_none : R.string.gallery_select_all);
        selectAllAction.setEnabled(total > 0);
        selectAllAction.setAlpha(total > 0 ? 1f : 0.45f);
        confirmSelectionAction.setEnabled(selected > 0);
    }

    private void confirmShareSelection() {
        int selectedCount = selectionStore.getSelectedCount();
        if (selectedCount <= 0) {
            Toast.makeText(this, R.string.gallery_select_at_least_one,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent result = new Intent();
        result.putExtra(EXTRA_SELECTED_COUNT, selectedCount);
        setResult(RESULT_OK, result);
        finish();
    }

    private void openPreview(@NonNull LocalPhoto photo) {
        Intent intent = new Intent(this, PhotoPreviewActivity.class);
        intent.putExtra(PhotoPreviewActivity.EXTRA_URI, photo.getUri().toString());
        intent.putExtra(PhotoPreviewActivity.EXTRA_NAME, photo.getDisplayName());
        startActivity(intent);
    }

    private String safeErrorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "读取失败，请稍后重试" : message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getColorCompat(int colorResource) {
        return androidx.core.content.ContextCompat.getColor(this, colorResource);
    }

    @Override
    protected void onDestroy() {
        if (photoGrid != null) photoGrid.setAdapter(null);
        if (repository != null) repository.close();
        if (thumbnailLoader != null) thumbnailLoader.close();
        super.onDestroy();
    }

    private static final class EvenGridSpacingDecoration extends RecyclerView.ItemDecoration {
        private final int spacing;

        EvenGridSpacingDecoration(int spacing) {
            this.spacing = spacing;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect,
                                   @NonNull View view,
                                   @NonNull RecyclerView parent,
                                   @NonNull RecyclerView.State state) {
            outRect.set(spacing, spacing, spacing, spacing);
        }
    }
}
