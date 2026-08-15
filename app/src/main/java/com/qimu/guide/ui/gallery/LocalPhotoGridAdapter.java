package com.qimu.guide.ui.gallery;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.qimu.guide.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Three-column gallery adapter. Preview and selection are intentionally separate gestures. */
public final class LocalPhotoGridAdapter
        extends RecyclerView.Adapter<LocalPhotoGridAdapter.PhotoViewHolder> {

    public interface Listener {
        void onOpenPhoto(@NonNull LocalPhoto photo);

        /** Return false to reject a change, for example when the share-photo cap is reached. */
        boolean onSelectionChanged(@NonNull LocalPhoto photo, boolean selected);
    }

    private final GalleryThumbnailLoader thumbnailLoader;
    private final Listener listener;
    private final int thumbnailSizePx;
    private final boolean selectionEnabled;
    private final List<LocalPhoto> photos = new ArrayList<>();
    private final Set<String> selectedUris = new HashSet<>();

    public LocalPhotoGridAdapter(@NonNull GalleryThumbnailLoader thumbnailLoader,
                                 @NonNull Listener listener,
                                 int thumbnailSizePx,
                                 boolean selectionEnabled) {
        this.thumbnailLoader = thumbnailLoader;
        this.listener = listener;
        this.thumbnailSizePx = thumbnailSizePx;
        this.selectionEnabled = selectionEnabled;
        setHasStableIds(true);
    }

    public void submit(@NonNull List<LocalPhoto> newPhotos,
                       @NonNull Set<String> newSelectedUris) {
        photos.clear();
        photos.addAll(newPhotos);
        selectedUris.clear();
        selectedUris.addAll(newSelectedUris);
        notifyDataSetChanged();
    }

    public void updateSelection(@NonNull Set<String> newSelectedUris) {
        selectedUris.clear();
        selectedUris.addAll(newSelectedUris);
        if (!photos.isEmpty()) notifyItemRangeChanged(0, photos.size(), Boolean.TRUE);
    }

    @Override
    public long getItemId(int position) {
        return photos.get(position).getId();
    }

    @NonNull
    @Override
    public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View item = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_local_photo, parent, false);
        return new PhotoViewHolder(item);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
        LocalPhoto photo = photos.get(position);
        boolean selected = selectedUris.contains(photo.selectionKey());
        holder.bind(photo, selected);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoViewHolder holder,
                                 int position,
                                 @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }
        LocalPhoto photo = photos.get(position);
        holder.updateSelectionOnly(photo, selectedUris.contains(photo.selectionKey()));
    }

    @Override
    public int getItemCount() {
        return photos.size();
    }

    @Override
    public void onViewRecycled(@NonNull PhotoViewHolder holder) {
        holder.recycle();
        super.onViewRecycled(holder);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull PhotoViewHolder holder) {
        holder.cancelThumbnail();
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewAttachedToWindow(@NonNull PhotoViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        holder.reloadThumbnailIfNeeded();
    }

    final class PhotoViewHolder extends RecyclerView.ViewHolder {
        private final ImageView photoView;
        private final CheckBox checkBox;
        private final View selectedBorder;
        private LocalPhoto boundPhoto;
        private boolean thumbnailRequested;

        PhotoViewHolder(@NonNull View itemView) {
            super(itemView);
            photoView = itemView.findViewById(R.id.gallery_photo_image);
            checkBox = itemView.findViewById(R.id.gallery_photo_checkbox);
            selectedBorder = itemView.findViewById(R.id.gallery_selected_border);
        }

        void bind(@NonNull LocalPhoto photo, boolean selected) {
            boundPhoto = photo;
            itemView.setContentDescription("查看" + photo.getDisplayName());
            photoView.setContentDescription("照片：" + photo.getDisplayName());
            checkBox.setVisibility(selectionEnabled ? View.VISIBLE : View.GONE);
            checkBox.setContentDescription(selectionEnabled
                    ? "选择" + photo.getDisplayName() : null);
            updateSelectionOnly(photo, selected);

            itemView.setOnClickListener(view -> listener.onOpenPhoto(photo));
            thumbnailLoader.load(photo, photoView, thumbnailSizePx);
            thumbnailRequested = true;
        }

        void updateSelectionOnly(@NonNull LocalPhoto photo, boolean selected) {
            selectedBorder.setVisibility(selectionEnabled && selected
                    ? View.VISIBLE : View.GONE);
            checkBox.setOnCheckedChangeListener(null);
            checkBox.setChecked(selected);
            if (selectionEnabled) attachSelectionListener(photo);
        }

        void cancelThumbnail() {
            if (!thumbnailRequested) return;
            thumbnailLoader.clear(photoView);
            thumbnailRequested = false;
        }

        void reloadThumbnailIfNeeded() {
            if (thumbnailRequested || boundPhoto == null) return;
            thumbnailLoader.load(boundPhoto, photoView, thumbnailSizePx);
            thumbnailRequested = true;
        }

        void recycle() {
            cancelThumbnail();
            boundPhoto = null;
            itemView.setOnClickListener(null);
            checkBox.setOnCheckedChangeListener(null);
        }

        private void attachSelectionListener(@NonNull LocalPhoto photo) {
            checkBox.setOnCheckedChangeListener((button, isChecked) -> {
                boolean accepted = listener.onSelectionChanged(photo, isChecked);
                if (!accepted) {
                    button.setOnCheckedChangeListener(null);
                    button.setChecked(!isChecked);
                    attachSelectionListener(photo);
                    return;
                }
                applySelectionVisual(photo, isChecked);
            });
        }

        private void applySelectionVisual(@NonNull LocalPhoto photo, boolean selected) {
            if (selected) selectedUris.add(photo.selectionKey());
            else selectedUris.remove(photo.selectionKey());
            selectedBorder.setVisibility(selected ? View.VISIBLE : View.GONE);
        }
    }
}
