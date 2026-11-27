package com.limelight.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.KeyboardShortcut;
import com.limelight.binding.input.KeyboardShortcutStore;

import java.util.HashMap;
import java.util.Map;

/**
 * Horizontal scrollable streaming keyboard toolbar docked above the soft keyboard.
 * Contains lockable Win/Ctrl/Alt/Shift modifiers, Esc/Tab/Del, all built-in + custom
 * shortcuts, and a fixed trailing Hide Keyboard button.
 */
public class KeyboardShortcutBar extends LinearLayout implements KeyboardShortcutStore.Listener {
    public interface Callbacks {
        void onKeyPressed(int vkCode, int modifierFlag);
        void onShortcutPressed(KeyboardShortcut shortcut);
        void onDismissKeyboard();
    }

    private static final class DragAwareHorizontalScrollView extends HorizontalScrollView {
        private final int touchSlop;
        private float downX;
        private boolean dragging;

        DragAwareHorizontalScrollView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    dragging = false;
                    super.onInterceptTouchEvent(ev);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(ev.getX() - downX) > touchSlop) {
                        dragging = true;
                        super.onInterceptTouchEvent(ev);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    break;
            }
            return super.onInterceptTouchEvent(ev);
        }
    }

    private final KeyboardShortcutStore store;
    private final LinearLayout keysRow;
    private final Map<Integer, TextView> modifierButtons = new HashMap<>();
    private Callbacks callbacks;
    private int activeModifiers = 0;

    public KeyboardShortcutBar(Context context, KeyboardShortcutStore store) {
        super(context);
        this.store = store;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(0xFF1C1C1E);
        setPadding(dp(6), dp(4), dp(4), dp(4));

        DragAwareHorizontalScrollView scroll = new DragAwareHorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_ALWAYS);
        scroll.setClipToPadding(true);

        keysRow = new LinearLayout(context);
        keysRow.setOrientation(HORIZONTAL);
        keysRow.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(keysRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        scrollLp.rightMargin = dp(6);
        addView(scroll, scrollLp);

        TextView dismissBtn = new TextView(context);
        dismissBtn.setText("⌨▾");
        dismissBtn.setTextColor(Color.WHITE);
        dismissBtn.setTextSize(16);
        dismissBtn.setGravity(Gravity.CENTER);
        dismissBtn.setContentDescription(context.getString(R.string.sl_kb_hide));
        dismissBtn.setBackground(SideLanternTheme.roundedRect(0xFF2C2C2E, dp(8)));
        dismissBtn.setOnClickListener(v -> {
            if (callbacks != null) callbacks.onDismissKeyboard();
        });
        addView(dismissBtn, new LinearLayout.LayoutParams(dp(44), dp(40)));

        reloadShortcuts();
    }

    public void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void setActiveModifiers(int modifiers) {
        this.activeModifiers = modifiers;
        for (Map.Entry<Integer, TextView> entry : modifierButtons.entrySet()) {
            boolean selected = (modifiers & entry.getKey()) != 0;
            entry.getValue().setBackground(SideLanternTheme.roundedRect(
                    selected ? SideLanternTheme.ACCENT_PURPLE : 0xFF2C2C2E, dp(8)));
            entry.getValue().setSelected(selected);
        }
    }

    public int getActiveModifiers() {
        return activeModifiers;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyboardShortcutStore.addListener(this);
        reloadShortcuts();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyboardShortcutStore.removeListener(this);
    }

    @Override
    public void onShortcutsChanged() {
        post(this::reloadShortcuts);
    }

    public void reloadShortcuts() {
        keysRow.removeAllViews();
        modifierButtons.clear();

        Object[][] fixedKeys = new Object[][]{
                {"Win", KeyboardShortcut.VK_WIN, KeyboardShortcut.MOD_WIN},
                {"Esc", 0x1B, 0},
                {"Tab", 0x09, 0},
                {"Ctrl", KeyboardShortcut.VK_CTRL, KeyboardShortcut.MOD_CTRL},
                {"Alt", KeyboardShortcut.VK_ALT, KeyboardShortcut.MOD_ALT},
                {"Shift", KeyboardShortcut.VK_SHIFT, KeyboardShortcut.MOD_SHIFT},
                {"Del", 0x2E, 0}
        };

        for (Object[] item : fixedKeys) {
            final String title = (String) item[0];
            final int vk = (Integer) item[1];
            final int modFlag = (Integer) item[2];
            TextView btn = makeKeyButton(title);
            btn.setOnClickListener(v -> {
                if (callbacks != null) callbacks.onKeyPressed(vk, modFlag);
            });
            if (modFlag != 0) {
                modifierButtons.put(modFlag, btn);
            }
            keysRow.addView(btn);
        }

        for (final KeyboardShortcut shortcut : store.getAllShortcuts()) {
            TextView btn = makeKeyButton(shortcut.name);
            btn.setContentDescription(shortcut.name + ", " + shortcut.getCombination());
            btn.setOnClickListener(v -> {
                if (callbacks != null) callbacks.onShortcutPressed(shortcut);
            });
            keysRow.addView(btn);
        }

        setActiveModifiers(activeModifiers);
    }

    private TextView makeKeyButton(String title) {
        TextView btn = new TextView(getContext());
        btn.setText(title);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setMinWidth(dp(44));
        btn.setPadding(dp(11), dp(6), dp(11), dp(6));
        btn.setBackground(SideLanternTheme.roundedRect(0xFF2C2C2E, dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.rightMargin = dp(6);
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(float v) {
        return SideLanternTheme.dp(getContext(), v);
    }
}
