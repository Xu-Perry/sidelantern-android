package com.limelight.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.limelight.R;

/**
 * Floating draggable in-stream settings button matching iOS SideLantern:
 * 52dp circular dark button with subtle border, idle dimming (0.45 -> 1.0),
 * edge snapping (left/right), and persisted edge + vertical fraction.
 */
public class StreamSettingsButton extends FrameLayout {
    private static final String PREFS_NAME = "SideLanternStreamUi";
    private static final String KEY_EDGE = "SideLantern.SettingsButton.Edge";
    private static final String KEY_POSITION = "SideLantern.SettingsButton.Position";

    private static final float IDLE_ALPHA = 0.45f;
    private static final float ACTIVE_ALPHA = 1.0f;
    private static final long IDLE_DELAY_MS = 1600L;

    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int buttonSizePx;
    private final int edgeMarginPx;
    private final int touchSlop;

    private final Rect movementBounds = new Rect();
    private boolean hasPositioned = false;
    private boolean dockedRight = true;
    private float verticalFraction = 0.35f;

    private float downRawX;
    private float downRawY;
    private float startX;
    private float startY;
    private boolean dragging;

    private Runnable clickListener;

    private final Runnable fadeToIdleRunnable = () ->
            animate().alpha(IDLE_ALPHA).setDuration(250).start();

    public StreamSettingsButton(Context context) {
        super(context);
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.buttonSizePx = SideLanternTheme.dp(context, 52);
        this.edgeMarginPx = SideLanternTheme.dp(context, 8);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        this.dockedRight = !"left".equals(prefs.getString(KEY_EDGE, "right"));
        this.verticalFraction = clamp(prefs.getFloat(KEY_POSITION, 0.35f), 0f, 1f);

        setBackground(SideLanternTheme.roundedRectBordered(
                0xFF292929, 0x99808080, SideLanternTheme.dp(context, 1), buttonSizePx / 2f));
        setElevation(SideLanternTheme.dp(context, 8));
        setAlpha(IDLE_ALPHA);
        setClickable(true);
        setFocusable(false);
        setContentDescription(context.getString(R.string.sl_settings_title));

        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_settings);
        icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconSize = SideLanternTheme.dp(context, 24);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER);
        addView(icon, iconLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(buttonSizePx, buttonSizePx);
        setLayoutParams(lp);
    }

    public int getButtonSizePx() {
        return buttonSizePx;
    }

    public void setOnSettingsClickListener(Runnable listener) {
        this.clickListener = listener;
    }

    public void updateMovementBounds(Rect bounds) {
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        movementBounds.set(bounds);
        applyDockedPosition(false);
    }

    public void ensurePositionedForParent(int parentWidth, int parentHeight) {
        if (parentWidth <= 0 || parentHeight <= 0) {
            return;
        }
        if (movementBounds.isEmpty()
                || movementBounds.right > parentWidth
                || movementBounds.bottom > parentHeight) {
            movementBounds.set(edgeMarginPx, edgeMarginPx,
                    Math.max(edgeMarginPx + buttonSizePx, parentWidth - edgeMarginPx),
                    Math.max(edgeMarginPx + buttonSizePx, parentHeight - edgeMarginPx));
        }
        applyDockedPosition(false);
    }

    private void applyDockedPosition(boolean animateSnap) {
        if (movementBounds.isEmpty()) {
            return;
        }
        float minX = movementBounds.left;
        float maxX = Math.max(minX, movementBounds.right - buttonSizePx);
        float minY = movementBounds.top;
        float maxY = Math.max(minY, movementBounds.bottom - buttonSizePx);

        float targetX = dockedRight ? maxX : minX;
        float targetY = minY + verticalFraction * (maxY - minY);
        targetY = clamp(targetY, minY, maxY);

        if (animateSnap && hasPositioned) {
            animate()
                    .x(targetX)
                    .y(targetY)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            animate().cancel();
            setX(targetX);
            setY(targetY);
        }
        hasPositioned = true;
    }

    private void setActiveAppearance() {
        handler.removeCallbacks(fadeToIdleRunnable);
        animate().alpha(ACTIVE_ALPHA).setDuration(120).start();
    }

    private void scheduleIdleDimming() {
        handler.removeCallbacks(fadeToIdleRunnable);
        handler.postDelayed(fadeToIdleRunnable, IDLE_DELAY_MS);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animate().cancel();
                setActiveAppearance();
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startX = getX();
                startY = getY();
                dragging = false;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                }
                if (dragging && !movementBounds.isEmpty()) {
                    float minX = movementBounds.left;
                    float maxX = Math.max(minX, movementBounds.right - buttonSizePx);
                    float minY = movementBounds.top;
                    float maxY = Math.max(minY, movementBounds.bottom - buttonSizePx);
                    setX(clamp(startX + dx, minX, maxX));
                    setY(clamp(startY + dy, minY, maxY));
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                if (dragging) {
                    snapToNearestEdgeAndSave();
                } else {
                    performClick();
                    if (clickListener != null) {
                        clickListener.run();
                    }
                }
                scheduleIdleDimming();
                dragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    snapToNearestEdgeAndSave();
                }
                scheduleIdleDimming();
                dragging = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void snapToNearestEdgeAndSave() {
        if (movementBounds.isEmpty()) {
            return;
        }
        float centerX = getX() + buttonSizePx / 2f;
        float boundsMidX = (movementBounds.left + movementBounds.right) / 2f;
        dockedRight = centerX >= boundsMidX;

        float minY = movementBounds.top;
        float maxY = Math.max(minY, movementBounds.bottom - buttonSizePx);
        verticalFraction = (maxY > minY) ? clamp((getY() - minY) / (maxY - minY), 0f, 1f) : 0.35f;

        prefs.edit()
                .putString(KEY_EDGE, dockedRight ? "right" : "left")
                .putFloat(KEY_POSITION, verticalFraction)
                .apply();

        applyDockedPosition(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(fadeToIdleRunnable);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
