package com.qimu.guide.ui.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/** Dependency-free fit-center image view with pinch, double-tap zoom and bounded panning. */
public final class ZoomImageView extends AppCompatImageView {

    private static final float DOUBLE_TAP_SCALE = 2.5f;
    private static final float MAX_SCALE_MULTIPLIER = 6f;

    private final Matrix drawMatrix = new Matrix();
    private final RectF drawableRect = new RectF();
    private final RectF mappedRect = new RectF();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private float minimumScale = 1f;
    private float maximumScale = 6f;
    private float currentScale = 1f;
    private float lastX;
    private float lastY;
    private boolean released;

    public ZoomImageView(@NonNull Context context) {
        this(context, null);
    }

    public ZoomImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomImageView(@NonNull Context context,
                         @Nullable AttributeSet attrs,
                         int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        super.setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
        setClickable(true);
    }

    @Override
    public void setScaleType(ScaleType scaleType) {
        // This view owns its Matrix. Callers get fit-center behavior by design.
        if (scaleType == ScaleType.MATRIX) super.setScaleType(scaleType);
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        released = false;
        super.setImageDrawable(drawable);
        post(this::resetToFit);
    }

    @Override
    public void setImageBitmap(@Nullable Bitmap bitmap) {
        released = false;
        super.setImageBitmap(bitmap);
        post(this::resetToFit);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width != oldWidth || height != oldHeight) post(this::resetToFit);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) return super.onTouchEvent(event);

        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (currentScale > minimumScale * 1.001f) {
                        drawMatrix.postTranslate(dx, dy);
                        constrainTranslation();
                        setImageMatrix(drawMatrix);
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() > 1) {
                    int remainingIndex = event.getActionIndex() == 0 ? 1 : 0;
                    lastX = event.getX(remainingIndex);
                    lastY = event.getY(remainingIndex);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                constrainTranslation();
                setImageMatrix(drawMatrix);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    public void resetToFit() {
        Drawable drawable = getDrawable();
        int contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (released || drawable == null || contentWidth <= 0 || contentHeight <= 0
                || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            return;
        }

        float drawableWidth = drawable.getIntrinsicWidth();
        float drawableHeight = drawable.getIntrinsicHeight();
        float fitScale = Math.min(contentWidth / drawableWidth, contentHeight / drawableHeight);
        float dx = getPaddingLeft() + (contentWidth - drawableWidth * fitScale) / 2f;
        float dy = getPaddingTop() + (contentHeight - drawableHeight * fitScale) / 2f;

        drawMatrix.reset();
        drawMatrix.setScale(fitScale, fitScale);
        drawMatrix.postTranslate(dx, dy);
        minimumScale = fitScale;
        maximumScale = fitScale * MAX_SCALE_MULTIPLIER;
        currentScale = fitScale;
        setImageMatrix(drawMatrix);
    }

    public void release() {
        released = true;
        drawMatrix.reset();
        super.setImageDrawable(null);
    }

    private void scaleAround(float targetScale, float focusX, float focusY) {
        float clampedTarget = Math.max(minimumScale, Math.min(maximumScale, targetScale));
        float factor = clampedTarget / currentScale;
        if (Math.abs(factor - 1f) < 0.001f) return;
        drawMatrix.postScale(factor, factor, focusX, focusY);
        currentScale = clampedTarget;
        constrainTranslation();
        setImageMatrix(drawMatrix);
    }

    private void constrainTranslation() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;

        drawableRect.set(0f, 0f, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        mappedRect.set(drawableRect);
        drawMatrix.mapRect(mappedRect);

        float contentLeft = getPaddingLeft();
        float contentTop = getPaddingTop();
        float contentRight = getWidth() - getPaddingRight();
        float contentBottom = getHeight() - getPaddingBottom();
        float contentWidth = contentRight - contentLeft;
        float contentHeight = contentBottom - contentTop;

        float dx = 0f;
        float dy = 0f;
        if (mappedRect.width() <= contentWidth) {
            dx = contentLeft + contentWidth / 2f - mappedRect.centerX();
        } else if (mappedRect.left > contentLeft) {
            dx = contentLeft - mappedRect.left;
        } else if (mappedRect.right < contentRight) {
            dx = contentRight - mappedRect.right;
        }

        if (mappedRect.height() <= contentHeight) {
            dy = contentTop + contentHeight / 2f - mappedRect.centerY();
        } else if (mappedRect.top > contentTop) {
            dy = contentTop - mappedRect.top;
        } else if (mappedRect.bottom < contentBottom) {
            dy = contentBottom - mappedRect.bottom;
        }

        drawMatrix.postTranslate(dx, dy);
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            scaleAround(currentScale * detector.getScaleFactor(),
                    detector.getFocusX(), detector.getFocusY());
            return true;
        }
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(@NonNull MotionEvent event) {
            return true;
        }

        @Override
        public boolean onDoubleTap(@NonNull MotionEvent event) {
            float target = currentScale > minimumScale * 1.2f
                    ? minimumScale : minimumScale * DOUBLE_TAP_SCALE;
            if (target == minimumScale) resetToFit();
            else scaleAround(target, event.getX(), event.getY());
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(@NonNull MotionEvent event) {
            return performClick();
        }
    }
}
