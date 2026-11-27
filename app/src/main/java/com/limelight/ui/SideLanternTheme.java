package com.limelight.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/**
 * Shared SideLantern color palette (light/dark adaptive) and view helpers
 * matching the iOS SideLantern visual design.
 */
public final class SideLanternTheme {
    public static final int ACCENT_BLUE = 0xFF0A84FF;
    public static final int ACCENT_BLUE_LIGHT = 0xFF007AFF;
    public static final int ACCENT_PURPLE = 0xFF735CC7;
    public static final int STATUS_GREEN = 0xFF34C759;
    public static final int STATUS_ORANGE = 0xFFFF9F0A;
    public static final int STATUS_GRAY = 0xFF8E8E93;
    public static final int DANGER_RED = 0xFFFF453A;
    public static final int BRAND_GREEN = 0xFF245742;

    private SideLanternTheme() {}

    public static boolean isDarkMode(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int background(Context context) {
        return isDarkMode(context) ? 0xFF0E1113 : 0xFFF1F5F7;
    }

    public static int surface(Context context) {
        return isDarkMode(context) ? 0xFF1C1F22 : 0xFFFFFFFF;
    }

    public static int secondarySurface(Context context) {
        return isDarkMode(context) ? 0xFF252A2E : 0xFFE9EFF2;
    }

    public static int textPrimary(Context context) {
        return isDarkMode(context) ? 0xFFF5F7FA : 0xFF111827;
    }

    public static int textSecondary(Context context) {
        return isDarkMode(context) ? 0xFF9CA3AF : 0xFF6B7280;
    }

    public static int divider(Context context) {
        return isDarkMode(context) ? 0xFF2E343B : 0xFFDCE3E8;
    }

    public static int accentBlue(Context context) {
        return isDarkMode(context) ? ACCENT_BLUE : ACCENT_BLUE_LIGHT;
    }

    public static int dp(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable roundedRect(int fillColor, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable roundedRectBordered(int fillColor, int strokeColor, int strokePx, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        d.setStroke(strokePx, strokeColor);
        return d;
    }

    public static GradientDrawable pillButtonBg(Context context) {
        int blue = accentBlue(context);
        int fill = (blue & 0x00FFFFFF) | 0x24000000;
        int stroke = (blue & 0x00FFFFFF) | 0x4D000000;
        return roundedRectBordered(fill, stroke, dp(context, 1), dp(context, 999));
    }

    public static GradientDrawable coverPlaceholderGradient() {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0A4D59, 0xFF26669E});
        return d;
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
