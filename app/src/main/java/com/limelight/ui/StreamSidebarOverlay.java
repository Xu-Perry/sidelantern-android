package com.limelight.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.binding.input.KeyboardShortcut;
import com.limelight.binding.input.KeyboardShortcutStore;
import com.limelight.nvstream.ManualVideoController;
import com.limelight.nvstream.http.ManualVideoSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SideLantern in-stream right-edge sidebar overlay.
 * Features:
 * - Full-screen transparent dismiss mask (consumes touches outside panel)
 * - Right vertical 76dp dark function rail (Stream Settings, Keyboard toggle, Shortcuts, Desktop placeholder)
 * - Top session bar with active section title and red Exit Stream button
 * - Stream Settings tab showing ONLY live hot-switchable items (no descriptions):
 *   Resolution / FPS / Bitrate (when manual video available), Touch Mode, On-Screen Controls (Touchpad only),
 *   Statistics Overlay, plus compact applying/error notice.
 */
public class StreamSidebarOverlay extends FrameLayout {
    public interface Callbacks {
        void onDismissed();
        void onExitStream();
        void onToggleKeyboard();
        void onResolutionSelected(int width, int height);
        void onFrameRateSelected(int fps);
        void onBitrateCommitted(int bitrateBudgetKbps);
        void onTouchModeChanged(boolean trackpadMode);
        void onOnScreenControlsChanged(boolean enabled);
        void onStatisticsOverlayChanged(boolean enabled);
        void onTriggerShortcut(KeyboardShortcut shortcut);
        void onShortcutEditorOpened();
    }

    private static final int TAB_STREAM = 0;
    private static final int TAB_SHORTCUTS = 2;
    private static final int TAB_DESKTOP = 3;

    private static final int[][] RESOLUTION_PRESETS = new int[][]{
            {640, 360},
            {854, 480},
            {1280, 720},
            {1920, 1080},
            {2560, 1440},
            {3840, 2160}
    };
    private static final String[] RESOLUTION_LABELS = new String[]{
            "360p", "480p", "720p", "1080p", "1440p", "4K"
    };
    private static final int[] STANDARD_FPS = new int[]{30, 60, 90, 120};

    // Bitrate steps in kbps (matching iOS SideLantern 0.5 Mbps - 150 Mbps curve)
    private static final int[] BITRATE_STEPS = new int[]{
            500, 1000, 1500, 2000, 2500, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000,
            12000, 15000, 18000, 20000, 25000, 30000, 35000, 40000, 45000, 50000,
            60000, 70000, 80000, 90000, 100000, 120000, 150000
    };

    private Callbacks callbacks;
    private final LinearLayout panelContainer;
    private final TextView headerTitle;
    private final TextView exitButton;

    // Content tabs
    private final ScrollView streamScrollView;
    private final KeyboardShortcutsPanel shortcutsPanel;
    private final LinearLayout desktopPlaceholder;

    // Rail buttons
    private final LinearLayout railStreamBtn;
    private final LinearLayout railKeyboardBtn;
    private final LinearLayout railShortcutsBtn;
    private final LinearLayout railDesktopBtn;

    // Stream settings controls
    private final TextView statusNoticeView;
    private final LinearLayout manualVideoGroup;
    private final LinearLayout resolutionGridContainer;
    private final LinearLayout fpsRowContainer;
    private final TextView bitrateLabel;
    private final SeekBar bitrateSeekBar;
    private final LinearLayout touchModeRow;
    private final LinearLayout oscGroup;
    private final LinearLayout oscRow;
    private final LinearLayout statsRow;

    private int activeTab = TAB_STREAM;
    private boolean keyboardVisible = false;
    private boolean manualVideoAvailable = false;
    private int currentWidth = 1920;
    private int currentHeight = 1080;
    private int currentFps = 60;
    private int currentBitrateBudgetKbps = 20000;
    private boolean trackpadMode = true;
    private boolean oscEnabled = false;
    private boolean statsEnabled = false;
    private boolean updatingSeekBar = false;

    public StreamSidebarOverlay(Context context, KeyboardShortcutStore shortcutStore) {
        super(context);
        setVisibility(GONE);

        // Full-screen transparent dismiss mask
        View dismissMask = new View(context);
        dismissMask.setBackgroundColor(0x01000000);
        dismissMask.setClickable(true);
        dismissMask.setOnClickListener(v -> dismiss());
        addView(dismissMask, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Right-edge dark panel
        panelContainer = new LinearLayout(context);
        panelContainer.setOrientation(LinearLayout.HORIZONTAL);
        panelContainer.setBackground(SideLanternTheme.roundedRectBordered(
                Color.BLACK, 0xFF404040, dp(1), 0));
        panelContainer.setClickable(true);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(420), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        addView(panelContainer, panelLp);

        // Left content column
        LinearLayout contentColumn = new LinearLayout(context);
        contentColumn.setOrientation(LinearLayout.VERTICAL);
        panelContainer.addView(contentColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // Top session action bar (Title + Red Exit Stream button)
        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(10), dp(12), dp(10));

        headerTitle = new TextView(context);
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setTextSize(17);
        headerTitle.setTypeface(null, Typeface.BOLD);
        topBar.addView(headerTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        exitButton = new TextView(context);
        exitButton.setText(R.string.sl_sidebar_exit_stream);
        exitButton.setTextColor(Color.WHITE);
        exitButton.setTextSize(13);
        exitButton.setTypeface(null, Typeface.BOLD);
        exitButton.setGravity(Gravity.CENTER);
        exitButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        exitButton.setBackground(SideLanternTheme.roundedRect(SideLanternTheme.DANGER_RED, dp(8)));
        exitButton.setOnClickListener(v -> {
            exitButton.setEnabled(false);
            exitButton.setAlpha(0.5f);
            if (callbacks != null) {
                callbacks.onExitStream();
            }
        });
        topBar.addView(exitButton);
        contentColumn.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View topDivider = new View(context);
        topDivider.setBackgroundColor(0xFF262626);
        contentColumn.addView(topDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Content stack
        FrameLayout contentStack = new FrameLayout(context);
        contentColumn.addView(contentStack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Tab 0: Live Stream Settings
        streamScrollView = new ScrollView(context);
        LinearLayout settingsBody = new LinearLayout(context);
        settingsBody.setOrientation(LinearLayout.VERTICAL);
        settingsBody.setPadding(dp(16), dp(12), dp(16), dp(24));

        statusNoticeView = new TextView(context);
        statusNoticeView.setTextSize(12);
        statusNoticeView.setTextColor(0xFFFFD60A);
        statusNoticeView.setBackground(SideLanternTheme.roundedRect(0x26FFD60A, dp(8)));
        statusNoticeView.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusNoticeView.setVisibility(GONE);
        LinearLayout.LayoutParams noticeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noticeLp.bottomMargin = dp(10);
        settingsBody.addView(statusNoticeView, noticeLp);

        // Manual Video Group (only visible when supported by host)
        manualVideoGroup = new LinearLayout(context);
        manualVideoGroup.setOrientation(LinearLayout.VERTICAL);
        manualVideoGroup.setVisibility(GONE);

        manualVideoGroup.addView(makeSectionLabel(context.getString(R.string.sl_settings_resolution)));
        resolutionGridContainer = new LinearLayout(context);
        resolutionGridContainer.setOrientation(LinearLayout.VERTICAL);
        manualVideoGroup.addView(resolutionGridContainer);

        manualVideoGroup.addView(makeSectionLabel(context.getString(R.string.sl_settings_framerate)));
        fpsRowContainer = new LinearLayout(context);
        fpsRowContainer.setOrientation(LinearLayout.HORIZONTAL);
        manualVideoGroup.addView(fpsRowContainer);

        bitrateLabel = makeSectionLabel(formatBitrateLabel(currentBitrateBudgetKbps));
        manualVideoGroup.addView(bitrateLabel);

        bitrateSeekBar = new SeekBar(context);
        bitrateSeekBar.setMax(BITRATE_STEPS.length - 1);
        bitrateSeekBar.setProgress(findClosestBitrateIndex(currentBitrateBudgetKbps));
        bitrateSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (updatingSeekBar) return;
                int kbps = BITRATE_STEPS[Math.max(0, Math.min(progress, BITRATE_STEPS.length - 1))];
                bitrateLabel.setText(formatBitrateLabel(kbps));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int idx = Math.max(0, Math.min(seekBar.getProgress(), BITRATE_STEPS.length - 1));
                int kbps = BITRATE_STEPS[idx];
                currentBitrateBudgetKbps = kbps;
                bitrateLabel.setText(formatBitrateLabel(kbps));
                if (callbacks != null) {
                    callbacks.onBitrateCommitted(kbps);
                }
            }
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seekLp.bottomMargin = dp(8);
        manualVideoGroup.addView(bitrateSeekBar, seekLp);

        settingsBody.addView(manualVideoGroup);

        // Touch Mode
        settingsBody.addView(makeSectionLabel(context.getString(R.string.sl_settings_touch)));
        touchModeRow = new LinearLayout(context);
        touchModeRow.setOrientation(LinearLayout.HORIZONTAL);
        settingsBody.addView(touchModeRow);

        // On-Screen Controls (Touchpad mode only)
        oscGroup = new LinearLayout(context);
        oscGroup.setOrientation(LinearLayout.VERTICAL);
        oscGroup.addView(makeSectionLabel(context.getString(R.string.sl_settings_controls)));
        oscRow = new LinearLayout(context);
        oscRow.setOrientation(LinearLayout.HORIZONTAL);
        oscGroup.addView(oscRow);
        settingsBody.addView(oscGroup);

        // Statistics Overlay
        settingsBody.addView(makeSectionLabel(context.getString(R.string.sl_settings_statistics)));
        statsRow = new LinearLayout(context);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        settingsBody.addView(statsRow);

        streamScrollView.addView(settingsBody, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentStack.addView(streamScrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Tab 2: Shortcuts panel
        shortcutsPanel = new KeyboardShortcutsPanel(context, shortcutStore);
        shortcutsPanel.setCallbacks(new KeyboardShortcutsPanel.Callbacks() {
            @Override
            public void onTriggerShortcut(KeyboardShortcut shortcut) {
                if (callbacks != null) {
                    callbacks.onTriggerShortcut(shortcut);
                }
            }

            @Override
            public void onEditingStarted() {
                if (callbacks != null) {
                    callbacks.onShortcutEditorOpened();
                }
            }
        });
        shortcutsPanel.setVisibility(GONE);
        contentStack.addView(shortcutsPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Tab 3: Desktop placeholder
        desktopPlaceholder = new LinearLayout(context);
        desktopPlaceholder.setOrientation(LinearLayout.VERTICAL);
        desktopPlaceholder.setGravity(Gravity.CENTER);
        desktopPlaceholder.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView dtTitle = new TextView(context);
        dtTitle.setText(R.string.sl_sidebar_desktop);
        dtTitle.setTextColor(Color.WHITE);
        dtTitle.setTextSize(18);
        dtTitle.setTypeface(null, Typeface.BOLD);
        desktopPlaceholder.addView(dtTitle);
        TextView dtSub = new TextView(context);
        dtSub.setText(R.string.sl_sidebar_pending);
        dtSub.setTextColor(0xFF8E8E93);
        dtSub.setTextSize(14);
        dtSub.setPadding(0, dp(8), 0, 0);
        desktopPlaceholder.addView(dtSub);
        desktopPlaceholder.setVisibility(GONE);
        contentStack.addView(desktopPlaceholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Divider between content and right function rail
        View railDivider = new View(context);
        railDivider.setBackgroundColor(0xFF262626);
        panelContainer.addView(railDivider, new LinearLayout.LayoutParams(
                dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        // Right vertical function rail (76dp)
        LinearLayout rail = new LinearLayout(context);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundColor(0xFF121212);
        rail.setPadding(dp(6), dp(14), dp(6), dp(14));
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        panelContainer.addView(rail, new LinearLayout.LayoutParams(
                dp(76), ViewGroup.LayoutParams.MATCH_PARENT));

        railStreamBtn = makeRailButton("⚙", context.getString(R.string.sl_sidebar_stream), () -> selectTab(TAB_STREAM));
        railKeyboardBtn = makeRailButton("⌨", context.getString(R.string.sl_sidebar_keyboard), () -> {
            if (callbacks != null) {
                callbacks.onToggleKeyboard();
            }
        });
        railShortcutsBtn = makeRailButton("⌘", context.getString(R.string.sl_sidebar_shortcuts), () -> selectTab(TAB_SHORTCUTS));
        railDesktopBtn = makeRailButton("🖥", context.getString(R.string.sl_sidebar_desktop), () -> selectTab(TAB_DESKTOP));

        rail.addView(railStreamBtn);
        rail.addView(railKeyboardBtn);
        rail.addView(railShortcutsBtn);
        rail.addView(railDesktopBtn);

        rebuildResolutionGrid();
        rebuildFpsRow();
        rebuildControlToggles();
        selectTab(TAB_STREAM);
    }

    public void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    public void open() {
        updatePanelWidth();
        resetExitButton();
        setVisibility(VISIBLE);
        bringToFront();
    }

    public void dismiss() {
        if (getVisibility() != GONE) {
            setVisibility(GONE);
            if (callbacks != null) {
                callbacks.onDismissed();
            }
        }
    }

    public void resetExitButton() {
        exitButton.setEnabled(true);
        exitButton.setAlpha(1f);
    }

    public void updatePanelWidth() {
        int parentW = getWidth();
        if (parentW <= 0) {
            parentW = getResources().getDisplayMetrics().widthPixels;
        }
        int desiredWidth = Math.min(dp(420), parentW);
        ViewGroup.LayoutParams lp = panelContainer.getLayoutParams();
        if (lp != null && lp.width != desiredWidth) {
            lp.width = desiredWidth;
            panelContainer.setLayoutParams(lp);
        }
    }

    public void setKeyboardVisible(boolean visible) {
        this.keyboardVisible = visible;
        refreshRailHighlights();
    }

    public void updateVideoState(boolean available, int width, int height, int fps,
                                 int bitrateBudgetKbps, ManualVideoController.State state,
                                 String errorReason) {
        this.manualVideoAvailable = available;
        if (width > 0 && height > 0) {
            this.currentWidth = width;
            this.currentHeight = height;
        }
        if (fps > 0) {
            this.currentFps = fps;
        }
        if (bitrateBudgetKbps > 0) {
            this.currentBitrateBudgetKbps = bitrateBudgetKbps;
        }

        manualVideoGroup.setVisibility(available ? VISIBLE : GONE);
        if (available) {
            rebuildResolutionGrid();
            rebuildFpsRow();
            updatingSeekBar = true;
            bitrateSeekBar.setProgress(findClosestBitrateIndex(currentBitrateBudgetKbps));
            bitrateLabel.setText(formatBitrateLabel(currentBitrateBudgetKbps));
            updatingSeekBar = false;
        }

        if (state == ManualVideoController.State.APPLYING || state == ManualVideoController.State.LOADING) {
            statusNoticeView.setText(R.string.sl_manual_compact_applying);
            statusNoticeView.setTextColor(0xFFFFD60A);
            statusNoticeView.setBackground(SideLanternTheme.roundedRect(0x26FFD60A, dp(8)));
            statusNoticeView.setVisibility(available ? VISIBLE : GONE);
        } else if (state == ManualVideoController.State.FAILED) {
            String detail = getContext().getString(ManualVideoSettings.getErrorMessageResId(errorReason));
            String prefix = "unconfirmed".equals(errorReason)
                    ? getContext().getString(R.string.sl_manual_compact_unconfirmed)
                    : getContext().getString(R.string.sl_manual_compact_failed);
            statusNoticeView.setText(prefix + " — " + detail);
            statusNoticeView.setTextColor(SideLanternTheme.DANGER_RED);
            statusNoticeView.setBackground(SideLanternTheme.roundedRect(0x26FF453A, dp(8)));
            statusNoticeView.setVisibility(VISIBLE);
        } else if ("unconfirmed".equals(errorReason)) {
            statusNoticeView.setText(R.string.sl_manual_compact_unconfirmed);
            statusNoticeView.setTextColor(0xFFFF9F0A);
            statusNoticeView.setBackground(SideLanternTheme.roundedRect(0x26FF9F0A, dp(8)));
            statusNoticeView.setVisibility(VISIBLE);
        } else {
            statusNoticeView.setVisibility(GONE);
        }
    }

    public void updateControlState(boolean trackpad, boolean osc, boolean stats) {
        this.trackpadMode = trackpad;
        this.oscEnabled = osc;
        this.statsEnabled = stats;
        rebuildControlToggles();
    }

    private void selectTab(int tab) {
        activeTab = tab;
        streamScrollView.setVisibility(tab == TAB_STREAM ? VISIBLE : GONE);
        shortcutsPanel.setVisibility(tab == TAB_SHORTCUTS ? VISIBLE : GONE);
        desktopPlaceholder.setVisibility(tab == TAB_DESKTOP ? VISIBLE : GONE);
        if (tab == TAB_STREAM) {
            headerTitle.setText(R.string.sl_settings_title);
        } else if (tab == TAB_SHORTCUTS) {
            headerTitle.setText(R.string.sl_sidebar_shortcuts);
        } else {
            headerTitle.setText(R.string.sl_sidebar_desktop);
        }
        refreshRailHighlights();
    }

    private void refreshRailHighlights() {
        applyRailButtonStyle(railStreamBtn, activeTab == TAB_STREAM);
        applyRailButtonStyle(railKeyboardBtn, keyboardVisible);
        applyRailButtonStyle(railShortcutsBtn, activeTab == TAB_SHORTCUTS);
        applyRailButtonStyle(railDesktopBtn, activeTab == TAB_DESKTOP);
    }

    private void applyRailButtonStyle(LinearLayout btn, boolean active) {
        btn.setBackground(SideLanternTheme.roundedRect(
                active ? SideLanternTheme.ACCENT_PURPLE : Color.TRANSPARENT, dp(10)));
    }

    private LinearLayout makeRailButton(String glyph, String label, Runnable onClick) {
        LinearLayout btn = new LinearLayout(getContext());
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(4), dp(10), dp(4), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        btn.setLayoutParams(lp);

        TextView iconTv = new TextView(getContext());
        iconTv.setText(glyph);
        iconTv.setTextColor(Color.WHITE);
        iconTv.setTextSize(18);
        iconTv.setGravity(Gravity.CENTER);
        btn.addView(iconTv);

        TextView labelTv = new TextView(getContext());
        labelTv.setText(label);
        labelTv.setTextColor(Color.WHITE);
        labelTv.setTextSize(11);
        labelTv.setGravity(Gravity.CENTER);
        labelTv.setPadding(0, dp(3), 0, 0);
        btn.addView(labelTv);

        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void rebuildResolutionGrid() {
        resolutionGridContainer.removeAllViews();
        boolean matchedPreset = false;
        for (int[] preset : RESOLUTION_PRESETS) {
            if (preset[0] == currentWidth && preset[1] == currentHeight) {
                matchedPreset = true;
                break;
            }
        }

        // Row 1: 360p, 480p, 720p, 1080p
        LinearLayout row1 = new LinearLayout(getContext());
        row1.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            final int w = RESOLUTION_PRESETS[i][0];
            final int h = RESOLUTION_PRESETS[i][1];
            boolean selected = (w == currentWidth && h == currentHeight);
            TextView chip = makeOptionChip(RESOLUTION_LABELS[i], selected);
            chip.setOnClickListener(v -> {
                if (callbacks != null) callbacks.onResolutionSelected(w, h);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            row1.addView(chip, lp);
        }
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1Lp.bottomMargin = dp(6);
        resolutionGridContainer.addView(row1, r1Lp);

        // Row 2: 1440p, 4K, Custom (shows WxH if currently custom)
        LinearLayout row2 = new LinearLayout(getContext());
        row2.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 4; i < 6; i++) {
            final int w = RESOLUTION_PRESETS[i][0];
            final int h = RESOLUTION_PRESETS[i][1];
            boolean selected = (w == currentWidth && h == currentHeight);
            TextView chip = makeOptionChip(RESOLUTION_LABELS[i], selected);
            chip.setOnClickListener(v -> {
                if (callbacks != null) callbacks.onResolutionSelected(w, h);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 4) lp.leftMargin = dp(6);
            row2.addView(chip, lp);
        }

        String customLabel = matchedPreset
                ? getContext().getString(R.string.sl_settings_custom)
                : (currentWidth + "×" + currentHeight);
        TextView customChip = makeOptionChip(customLabel, !matchedPreset);
        customChip.setOnClickListener(v -> showCustomResolutionDialog());
        LinearLayout.LayoutParams custLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        custLp.leftMargin = dp(6);
        row2.addView(customChip, custLp);
        resolutionGridContainer.addView(row2);
    }

    private void rebuildFpsRow() {
        fpsRowContainer.removeAllViews();
        List<Integer> values = new ArrayList<>();
        boolean foundCurrent = false;
        for (int fps : STANDARD_FPS) {
            values.add(fps);
            if (fps == currentFps) foundCurrent = true;
        }
        if (!foundCurrent && currentFps > 0) {
            values.add(currentFps);
        }
        for (int i = 0; i < values.size(); i++) {
            final int fps = values.get(i);
            TextView chip = makeOptionChip(Integer.toString(fps), fps == currentFps);
            chip.setOnClickListener(v -> {
                if (callbacks != null) callbacks.onFrameRateSelected(fps);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            fpsRowContainer.addView(chip, lp);
        }
    }

    private void rebuildControlToggles() {
        // Touch mode
        touchModeRow.removeAllViews();
        TextView padChip = makeOptionChip(getContext().getString(R.string.sl_settings_touchpad), trackpadMode);
        padChip.setOnClickListener(v -> {
            if (!trackpadMode && callbacks != null) {
                callbacks.onTouchModeChanged(true);
            }
        });
        TextView screenChip = makeOptionChip(getContext().getString(R.string.sl_settings_touchscreen), !trackpadMode);
        screenChip.setOnClickListener(v -> {
            if (trackpadMode && callbacks != null) {
                callbacks.onTouchModeChanged(false);
            }
        });
        touchModeRow.addView(padChip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        spLp.leftMargin = dp(6);
        touchModeRow.addView(screenChip, spLp);

        // OSC (visible ONLY in touchpad mode)
        oscGroup.setVisibility(trackpadMode ? VISIBLE : GONE);
        oscRow.removeAllViews();
        TextView oscOff = makeOptionChip(getContext().getString(R.string.sl_settings_off), !oscEnabled);
        oscOff.setOnClickListener(v -> {
            if (oscEnabled && callbacks != null) callbacks.onOnScreenControlsChanged(false);
        });
        TextView oscOn = makeOptionChip(getContext().getString(R.string.sl_settings_on), oscEnabled);
        oscOn.setOnClickListener(v -> {
            if (!oscEnabled && callbacks != null) callbacks.onOnScreenControlsChanged(true);
        });
        oscRow.addView(oscOff, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams ooLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ooLp.leftMargin = dp(6);
        oscRow.addView(oscOn, ooLp);

        // Stats overlay
        statsRow.removeAllViews();
        TextView stOff = makeOptionChip(getContext().getString(R.string.sl_settings_off), !statsEnabled);
        stOff.setOnClickListener(v -> {
            if (statsEnabled && callbacks != null) callbacks.onStatisticsOverlayChanged(false);
        });
        TextView stOn = makeOptionChip(getContext().getString(R.string.sl_settings_on), statsEnabled);
        stOn.setOnClickListener(v -> {
            if (!statsEnabled && callbacks != null) callbacks.onStatisticsOverlayChanged(true);
        });
        statsRow.addView(stOff, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams soLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        soLp.leftMargin = dp(6);
        statsRow.addView(stOn, soLp);
    }

    private void showCustomResolutionDialog() {
        Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        final EditText wInput = new EditText(ctx);
        wInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        wInput.setHint(R.string.sl_settings_custom_width_hint);
        wInput.setText(Integer.toString(currentWidth));
        box.addView(wInput);

        final EditText hInput = new EditText(ctx);
        hInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        hInput.setHint(R.string.sl_settings_custom_height_hint);
        hInput.setText(Integer.toString(currentHeight));
        box.addView(hInput);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.sl_settings_custom_resolution_title)
                .setView(box)
                .setNegativeButton(R.string.sl_cancel, null)
                .setPositiveButton(R.string.sl_ok, (d, which) -> {
                    try {
                        int w = Integer.parseInt(wInput.getText().toString().trim());
                        int h = Integer.parseInt(hInput.getText().toString().trim());
                        ManualVideoSettings check = new ManualVideoSettings(w, h, Math.max(1, currentFps), 10000);
                        if (!check.isValid()) {
                            Toast.makeText(ctx, R.string.sl_manual_error_invalid_video_settings, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (callbacks != null) {
                            callbacks.onResolutionSelected(w, h);
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(ctx, R.string.sl_manual_error_invalid_video_settings, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private TextView makeSectionLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(0xFFD1D5DB);
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(14), 0, dp(8));
        return tv;
    }

    private TextView makeOptionChip(String text, boolean selected) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(6), dp(10), dp(6), dp(10));
        tv.setBackground(SideLanternTheme.roundedRect(
                selected ? SideLanternTheme.ACCENT_PURPLE : 0xFF1F1F23, dp(8)));
        return tv;
    }

    private String formatBitrateLabel(int kbps) {
        return getContext().getString(R.string.sl_settings_bitrate_format, kbps / 1000.0f);
    }

    private static int findClosestBitrateIndex(int kbps) {
        int bestIdx = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < BITRATE_STEPS.length; i++) {
            int diff = Math.abs(BITRATE_STEPS[i] - kbps);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private int dp(float v) {
        return SideLanternTheme.dp(getContext(), v);
    }
}
