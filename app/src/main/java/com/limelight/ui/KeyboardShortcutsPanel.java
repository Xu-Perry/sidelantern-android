package com.limelight.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.KeyboardShortcut;
import com.limelight.binding.input.KeyboardShortcutStore;

import java.util.List;
import java.util.UUID;

/**
 * In-sidebar panel displaying built-in and custom shortcuts with an inline editor
 * for creating, editing, and deleting custom shortcuts.
 */
public class KeyboardShortcutsPanel extends FrameLayout implements KeyboardShortcutStore.Listener {
    public interface Callbacks {
        void onTriggerShortcut(KeyboardShortcut shortcut);
        void onEditingStarted();
    }

    private final KeyboardShortcutStore store;
    private Callbacks callbacks;

    private final LinearLayout listRoot;
    private final LinearLayout listItemsContainer;
    private View editorView;

    public KeyboardShortcutsPanel(Context context, KeyboardShortcutStore store) {
        super(context);
        this.store = store;
        setBackgroundColor(Color.BLACK);

        listRoot = new LinearLayout(context);
        listRoot.setOrientation(LinearLayout.VERTICAL);

        Button addBtn = new Button(context);
        addBtn.setAllCaps(false);
        addBtn.setText("+ " + context.getString(R.string.sl_shortcut_add));
        addBtn.setTextColor(Color.WHITE);
        addBtn.setTextSize(14);
        addBtn.setTypeface(null, Typeface.BOLD);
        addBtn.setBackground(SideLanternTheme.roundedRect(SideLanternTheme.ACCENT_PURPLE, dp(10)));
        addBtn.setOnClickListener(v -> showEditor(null));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        addLp.setMargins(dp(14), dp(10), dp(14), dp(8));
        listRoot.addView(addBtn, addLp);

        ScrollView scroll = new ScrollView(context);
        listItemsContainer = new LinearLayout(context);
        listItemsContainer.setOrientation(LinearLayout.VERTICAL);
        listItemsContainer.setPadding(dp(14), dp(4), dp(14), dp(24));
        scroll.addView(listItemsContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listRoot.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(listRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        reloadList();
    }

    public void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyboardShortcutStore.addListener(this);
        reloadList();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyboardShortcutStore.removeListener(this);
    }

    @Override
    public void onShortcutsChanged() {
        post(this::reloadList);
    }

    private void reloadList() {
        listItemsContainer.removeAllViews();
        Context ctx = getContext();

        addSectionHeader(ctx.getString(R.string.sl_shortcut_common));
        for (KeyboardShortcut sc : KeyboardShortcut.builtInShortcuts(ctx)) {
            listItemsContainer.addView(createShortcutRow(sc, false));
        }

        addSectionHeader(ctx.getString(R.string.sl_shortcut_custom));
        List<KeyboardShortcut> custom = store.getCustomShortcuts();
        if (custom.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(R.string.sl_shortcut_empty);
            empty.setTextColor(0xFF8E8E93);
            empty.setTextSize(13);
            empty.setPadding(dp(4), dp(8), dp(4), dp(12));
            listItemsContainer.addView(empty);
        } else {
            for (KeyboardShortcut sc : custom) {
                listItemsContainer.addView(createShortcutRow(sc, true));
            }
        }
    }

    private void addSectionHeader(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(0xFF9CA3AF);
        tv.setTextSize(12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(2), dp(12), dp(2), dp(6));
        listItemsContainer.addView(tv);
    }

    private View createShortcutRow(final KeyboardShortcut sc, boolean editable) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(SideLanternTheme.roundedRect(0xFF1A1A1D, dp(10)));
        row.setPadding(dp(12), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        TextView nameTv = new TextView(getContext());
        nameTv.setText(sc.name);
        nameTv.setTextColor(Color.WHITE);
        nameTv.setTextSize(14);
        nameTv.setMaxLines(2);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(nameTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView comboTv = new TextView(getContext());
        comboTv.setText(sc.getCombination());
        comboTv.setTextColor(0xFF9CA3AF);
        comboTv.setTextSize(12);
        comboTv.setPadding(dp(8), 0, dp(4), 0);
        row.addView(comboTv);

        if (editable) {
            TextView more = new TextView(getContext());
            more.setText("⋯");
            more.setTextColor(Color.WHITE);
            more.setTextSize(18);
            more.setGravity(Gravity.CENTER);
            more.setContentDescription(getContext().getString(R.string.sl_shortcut_manage));
            more.setOnClickListener(v -> {
                String[] opts = new String[]{
                        getContext().getString(R.string.sl_shortcut_editor_edit),
                        getContext().getString(R.string.sl_shortcut_editor_delete)
                };
                new AlertDialog.Builder(getContext())
                        .setTitle(sc.name)
                        .setItems(opts, (d, which) -> {
                            if (which == 0) showEditor(sc);
                            else store.removeShortcut(sc.identifier);
                        })
                        .setNegativeButton(R.string.sl_cancel, null)
                        .show();
            });
            row.addView(more, new LinearLayout.LayoutParams(dp(36), dp(36)));
        }

        row.setOnClickListener(v -> {
            if (callbacks != null) callbacks.onTriggerShortcut(sc);
        });
        return row;
    }

    private void showEditor(final KeyboardShortcut existing) {
        if (editorView != null) return;
        if (callbacks != null) callbacks.onEditingStarted();

        final Context ctx = getContext();
        final int[] chosenKeyCode = new int[]{existing != null ? existing.keyCode : 'A'};
        final int[] chosenModifiers = new int[]{existing != null ? existing.modifiers : KeyboardShortcut.MOD_CTRL};

        LinearLayout editor = new LinearLayout(ctx);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setBackgroundColor(Color.BLACK);

        // Header: Cancel | Title | Save
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView cancelBtn = new TextView(ctx);
        cancelBtn.setText(R.string.sl_cancel);
        cancelBtn.setTextColor(SideLanternTheme.ACCENT_BLUE);
        cancelBtn.setTextSize(14);
        cancelBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        cancelBtn.setOnClickListener(v -> closeEditor());
        header.addView(cancelBtn);

        TextView titleTv = new TextView(ctx);
        titleTv.setText(existing != null ? R.string.sl_shortcut_editor_edit : R.string.sl_shortcut_editor_new);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(15);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setGravity(Gravity.CENTER);
        header.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView saveBtn = new TextView(ctx);
        saveBtn.setText(R.string.sl_shortcut_editor_save);
        saveBtn.setTextColor(SideLanternTheme.ACCENT_BLUE);
        saveBtn.setTextSize(14);
        saveBtn.setTypeface(null, Typeface.BOLD);
        saveBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        header.addView(saveBtn);

        editor.addView(header);

        ScrollView formScroll = new ScrollView(ctx);
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(14), dp(8), dp(14), dp(24));

        TextView previewLabel = new TextView(ctx);
        previewLabel.setText(KeyboardShortcut.formatCombination(chosenKeyCode[0], chosenModifiers[0]));
        previewLabel.setTextColor(Color.WHITE);
        previewLabel.setTextSize(18);
        previewLabel.setTypeface(null, Typeface.BOLD);
        previewLabel.setGravity(Gravity.CENTER);
        previewLabel.setBackground(SideLanternTheme.roundedRect(0xFF1A1A1D, dp(10)));
        previewLabel.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prevLp.bottomMargin = dp(14);
        form.addView(previewLabel, prevLp);

        // Name field
        TextView nameLabel = makeFieldLabel(ctx.getString(R.string.sl_shortcut_editor_name));
        form.addView(nameLabel);

        final EditText nameField = new EditText(ctx);
        nameField.setHint(R.string.sl_shortcut_editor_name_placeholder);
        nameField.setHintTextColor(0xFF6B7280);
        nameField.setTextColor(Color.WHITE);
        nameField.setTextSize(14);
        nameField.setSingleLine(true);
        nameField.setBackground(SideLanternTheme.roundedRect(0xFF1A1A1D, dp(8)));
        nameField.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (existing != null) {
            nameField.setText(existing.name);
        }
        form.addView(nameField);

        final TextView errorLabel = new TextView(ctx);
        errorLabel.setTextColor(SideLanternTheme.DANGER_RED);
        errorLabel.setTextSize(12);
        errorLabel.setVisibility(View.GONE);
        errorLabel.setPadding(dp(2), dp(4), dp(2), 0);
        form.addView(errorLabel);

        // Modifier toggle buttons
        form.addView(makeFieldLabel(ctx.getString(R.string.sl_shortcut_editor_modifiers)));
        LinearLayout modRow = new LinearLayout(ctx);
        modRow.setOrientation(LinearLayout.HORIZONTAL);
        Object[][] mods = new Object[][]{
                {"Ctrl", KeyboardShortcut.MOD_CTRL},
                {"Alt", KeyboardShortcut.MOD_ALT},
                {"Shift", KeyboardShortcut.MOD_SHIFT},
                {"Win", KeyboardShortcut.MOD_WIN}
        };
        for (int i = 0; i < mods.length; i++) {
            final String label = (String) mods[i][0];
            final int flag = (Integer) mods[i][1];
            final TextView btn = new TextView(ctx);
            btn.setText(label);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(13);
            btn.setTypeface(null, Typeface.BOLD);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(6), dp(10), dp(6), dp(10));
            Runnable refreshMod = () -> {
                boolean active = (chosenModifiers[0] & flag) != 0;
                btn.setBackground(SideLanternTheme.roundedRect(
                        active ? SideLanternTheme.ACCENT_PURPLE : 0xFF252529, dp(8)));
            };
            refreshMod.run();
            btn.setOnClickListener(v -> {
                chosenModifiers[0] ^= flag;
                refreshMod.run();
                previewLabel.setText(KeyboardShortcut.formatCombination(chosenKeyCode[0], chosenModifiers[0]));
            });
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) mlp.leftMargin = dp(6);
            modRow.addView(btn, mlp);
        }
        form.addView(modRow);

        // Key selector button
        form.addView(makeFieldLabel(ctx.getString(R.string.sl_shortcut_editor_key)));
        final Button keyBtn = new Button(ctx);
        keyBtn.setAllCaps(false);
        keyBtn.setText(KeyboardShortcut.titleForKeyCode(chosenKeyCode[0]));
        keyBtn.setTextColor(Color.WHITE);
        keyBtn.setTextSize(14);
        keyBtn.setBackground(SideLanternTheme.roundedRect(0xFF252529, dp(8)));
        keyBtn.setOnClickListener(v -> {
            List<KeyboardShortcut.KeyChoice> choices = KeyboardShortcut.getKeyChoices();
            String[] titles = new String[choices.size()];
            int selectedIdx = 0;
            for (int i = 0; i < choices.size(); i++) {
                titles[i] = choices.get(i).title;
                if (choices.get(i).keyCode == chosenKeyCode[0]) selectedIdx = i;
            }
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.sl_shortcut_editor_key)
                    .setSingleChoiceItems(titles, selectedIdx, (dialog, which) -> {
                        chosenKeyCode[0] = choices.get(which).keyCode;
                        keyBtn.setText(choices.get(which).title);
                        previewLabel.setText(KeyboardShortcut.formatCombination(chosenKeyCode[0], chosenModifiers[0]));
                        dialog.dismiss();
                    })
                    .setNegativeButton(R.string.sl_cancel, null)
                    .show();
        });
        form.addView(keyBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        if (existing != null) {
            Button deleteBtn = new Button(ctx);
            deleteBtn.setAllCaps(false);
            deleteBtn.setText(R.string.sl_shortcut_editor_delete);
            deleteBtn.setTextColor(SideLanternTheme.DANGER_RED);
            deleteBtn.setBackground(SideLanternTheme.roundedRect(0x33FF453A, dp(8)));
            deleteBtn.setOnClickListener(v -> {
                store.removeShortcut(existing.identifier);
                closeEditor();
            });
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            delLp.topMargin = dp(18);
            form.addView(deleteBtn, delLp);
        }

        saveBtn.setOnClickListener(v -> {
            String enteredName = nameField.getText().toString().trim();
            if (enteredName.length() > 64) {
                errorLabel.setText(R.string.sl_shortcut_editor_name_too_long);
                errorLabel.setVisibility(View.VISIBLE);
                return;
            }
            String id = existing != null ? existing.identifier : UUID.randomUUID().toString();
            KeyboardShortcut updated = KeyboardShortcut.create(id, enteredName, chosenKeyCode[0], chosenModifiers[0]);
            if (updated != null) {
                store.saveShortcut(updated);
                closeEditor();
            }
        });

        formScroll.addView(form);
        editor.addView(formScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        editorView = editor;
        listRoot.setVisibility(View.GONE);
        addView(editorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void closeEditor() {
        if (editorView != null) {
            removeView(editorView);
            editorView = null;
        }
        listRoot.setVisibility(View.VISIBLE);
        reloadList();
    }

    private TextView makeFieldLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(0xFF9CA3AF);
        tv.setTextSize(12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(2), dp(12), dp(2), dp(6));
        return tv;
    }

    private int dp(float v) {
        return SideLanternTheme.dp(getContext(), v);
    }
}
