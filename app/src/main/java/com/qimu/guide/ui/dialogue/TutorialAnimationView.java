package com.qimu.guide.ui.dialogue;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.qimu.guide.R;

/** Silent, offline, looping tutorial playback with a poster until the first frame arrives. */
public class TutorialAnimationView extends FrameLayout
        implements TextureView.SurfaceTextureListener {
    private final TextureView texture;
    private final ImageView poster;
    private final TextView retry;
    private MediaPlayer player;
    private Surface surface;
    private int videoResource;
    private int videoWidth;
    private int videoHeight;
    private boolean prepared;
    private boolean playbackActive;

    public TutorialAnimationView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        texture = new TextureView(context);
        texture.setOpaque(false);
        texture.setSurfaceTextureListener(this);
        texture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(texture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        poster = new ImageView(context);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(poster, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        retry = new TextView(context);
        retry.setText(R.string.tutorial_animation_retry);
        retry.setTextColor(ContextCompat.getColor(context, R.color.qimu_text_primary));
        retry.setBackgroundColor(ContextCompat.getColor(context, R.color.qimu_tutorial_background));
        retry.setTextSize(14);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(16, 16, 16, 16);
        retry.setVisibility(GONE);
        retry.setOnClickListener(view -> openVideo());
        addView(retry, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    public void setVideo(int resource, int posterResource) {
        videoResource = resource;
        poster.setImageResource(posterResource);
        poster.setVisibility(VISIBLE);
        retry.setVisibility(GONE);
        openVideo();
    }

    public void resumePlayback() {
        playbackActive = true;
        if (prepared && player != null) player.start();
        else if (player == null) openVideo();
    }

    public void pausePlayback() {
        playbackActive = false;
        if (prepared && player != null && player.isPlaying()) player.pause();
    }

    public void releasePlayback() {
        playbackActive = false;
        videoResource = 0;
        releasePlayer();
    }

    public boolean isPlaying() {
        return prepared && player != null && player.isPlaying();
    }

    public int getCurrentPosition() {
        return prepared && player != null ? player.getCurrentPosition() : 0;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    private void openVideo() {
        releasePlayer();
        if (videoResource == 0 || !texture.isAvailable()) return;
        poster.setVisibility(VISIBLE);
        retry.setVisibility(GONE);
        MediaPlayer next = new MediaPlayer();
        player = next;
        try (AssetFileDescriptor file = getResources().openRawResourceFd(videoResource)) {
            surface = new Surface(texture.getSurfaceTexture());
            next.setSurface(surface);
            next.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
            next.setVolume(0f, 0f);
            next.setLooping(true);
            next.setOnPreparedListener(ready -> {
                if (ready != player) return;
                prepared = true;
                videoWidth = ready.getVideoWidth();
                videoHeight = ready.getVideoHeight();
                fitVideo();
                if (playbackActive) ready.start();
            });
            next.setOnInfoListener((active, what, extra) -> {
                if (active == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    poster.setVisibility(GONE);
                }
                return false;
            });
            next.setOnErrorListener((failed, what, extra) -> {
                if (failed == player) showPlaybackError();
                return true;
            });
            next.prepareAsync();
        } catch (Exception error) {
            Log.w("TourTutorial", "Could not prepare bundled tutorial", error);
            showPlaybackError();
        }
    }

    private void showPlaybackError() {
        releasePlayer();
        poster.setVisibility(VISIBLE);
        retry.setVisibility(VISIBLE);
    }

    private void fitVideo() {
        int width = texture.getWidth();
        int height = texture.getHeight();
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return;
        float scale = Math.min((float) width / videoWidth, (float) height / videoHeight);
        Matrix transform = new Matrix();
        transform.setScale(videoWidth * scale / width, videoHeight * scale / height,
                width / 2f, height / 2f);
        texture.setTransform(transform);
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            MediaPlayer previous = player;
            player = null;
            previous.setOnPreparedListener(null);
            previous.setOnInfoListener(null);
            previous.setOnErrorListener(null);
            previous.release();
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releasePlayer();
        super.onDetachedFromWindow();
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int w, int h) {
        openVideo();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int w, int h) {
        fitVideo();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        releasePlayer();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) { }
}
