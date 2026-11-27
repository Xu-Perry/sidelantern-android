package com.limelight.binding.input;

import android.content.Context;

import com.limelight.R;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keyboard shortcut model (modifiers + primary Win32 VK code), key choice catalog,
 * event sequence generator, and serial non-blocking dispatcher.
 */
public final class KeyboardShortcut {
    public static final int MOD_SHIFT = 0x01;
    public static final int MOD_CTRL = 0x02;
    public static final int MOD_ALT = 0x04;
    public static final int MOD_WIN = 0x08;
    public static final int MOD_ALL_MASK = 0x0F;

    public static final int VK_SHIFT = 0xA0;
    public static final int VK_CTRL = 0xA2;
    public static final int VK_ALT = 0xA4;
    public static final int VK_WIN = 0x5B;

    private static final ExecutorService DISPATCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SideLantern-KeyboardShortcuts");
        t.setDaemon(true);
        return t;
    });

    public static final class KeyChoice {
        public final int keyCode;
        public final String title;

        KeyChoice(int keyCode, String title) {
            this.keyCode = keyCode;
            this.title = title;
        }
    }

    public static final class KeyEventStep {
        public final short keyCode;
        public final boolean down;
        public final byte modifiers;

        public KeyEventStep(short keyCode, boolean down, byte modifiers) {
            this.keyCode = keyCode;
            this.down = down;
            this.modifiers = modifiers;
        }
    }

    private static final List<KeyChoice> KEY_CHOICES = buildKeyChoices();

    private static List<KeyChoice> buildKeyChoices() {
        List<KeyChoice> keys = new ArrayList<>();
        Object[][] special = new Object[][]{
                {0x1B, "Esc"}, {0x09, "Tab"}, {0x0D, "Enter"}, {0x20, "Space"},
                {0x08, "Backspace"}, {0x2E, "Delete"}, {0x24, "Home"}, {0x23, "End"},
                {0x21, "Page Up"}, {0x22, "Page Down"}, {0x25, "←"}, {0x26, "↑"},
                {0x27, "→"}, {0x28, "↓"}, {0x2D, "Insert"}, {0x2C, "Print Screen"},
                {0x14, "Caps Lock"}, {0xBD, "-"}, {0xBB, "="}, {0xDB, "["}, {0xDD, "]"},
                {0xDC, "\\"}, {0xBA, ";"}, {0xDE, "'"}, {0xC0, "`"}, {0xBC, ","}, {0xBE, "."}, {0xBF, "/"}
        };
        for (Object[] item : special) {
            keys.add(new KeyChoice((Integer) item[0], (String) item[1]));
        }
        for (int c = 'A'; c <= 'Z'; c++) {
            keys.add(new KeyChoice(c, String.valueOf((char) c)));
        }
        for (int c = '0'; c <= '9'; c++) {
            keys.add(new KeyChoice(c, String.valueOf((char) c)));
        }
        for (int n = 1; n <= 24; n++) {
            keys.add(new KeyChoice(0x6F + n, "F" + n));
        }
        return Collections.unmodifiableList(keys);
    }

    public final String identifier;
    public final String name;
    public final int keyCode;
    public final int modifiers;

    public static KeyboardShortcut create(String identifier, String name, int keyCode, int modifiers) {
        if (identifier == null || identifier.isEmpty()
                || titleForKeyCode(keyCode) == null
                || (modifiers & ~MOD_ALL_MASK) != 0) {
            return null;
        }
        String combo = formatCombination(keyCode, modifiers);
        String trimmed = name != null ? name.trim() : "";
        String finalName = trimmed.isEmpty() ? combo : trimmed;
        if (finalName.length() > 64) {
            return null;
        }
        return new KeyboardShortcut(identifier, finalName, keyCode, modifiers);
    }

    private KeyboardShortcut(String identifier, String name, int keyCode, int modifiers) {
        this.identifier = identifier;
        this.name = name;
        this.keyCode = keyCode;
        this.modifiers = modifiers;
    }

    public static List<KeyChoice> getKeyChoices() {
        return KEY_CHOICES;
    }

    public static String titleForKeyCode(int keyCode) {
        for (KeyChoice kc : KEY_CHOICES) {
            if (kc.keyCode == keyCode) {
                return kc.title;
            }
        }
        return null;
    }

    public String getCombination() {
        return formatCombination(keyCode, modifiers);
    }

    public static String formatCombination(int keyCode, int modifiers) {
        List<String> parts = new ArrayList<>();
        if ((modifiers & MOD_CTRL) != 0) parts.add("Ctrl");
        if ((modifiers & MOD_ALT) != 0) parts.add("Alt");
        if ((modifiers & MOD_SHIFT) != 0) parts.add("Shift");
        if ((modifiers & MOD_WIN) != 0) parts.add("Win");
        String title = titleForKeyCode(keyCode);
        if (title != null) parts.add(title);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('+');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    public List<KeyEventStep> getKeyEvents() {
        List<KeyEventStep> events = new ArrayList<>();
        int[][] modOrder = new int[][]{
                {MOD_CTRL, VK_CTRL},
                {MOD_ALT, VK_ALT},
                {MOD_SHIFT, VK_SHIFT},
                {MOD_WIN, VK_WIN}
        };
        int held = 0;
        for (int[] mod : modOrder) {
            if ((modifiers & mod[0]) != 0) {
                held |= mod[0];
                events.add(new KeyEventStep((short) (mod[1] | 0x8000), true, (byte) held));
            }
        }
        events.add(new KeyEventStep((short) (keyCode | 0x8000), true, (byte) held));
        events.add(new KeyEventStep((short) (keyCode | 0x8000), false, (byte) held));
        for (int i = modOrder.length - 1; i >= 0; i--) {
            int[] mod = modOrder[i];
            if ((modifiers & mod[0]) != 0) {
                held &= ~mod[0];
                events.add(new KeyEventStep((short) (mod[1] | 0x8000), false, (byte) held));
            }
        }
        return events;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("identifier", identifier);
            obj.put("name", name);
            obj.put("keyCode", keyCode);
            obj.put("modifiers", modifiers);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    public static KeyboardShortcut fromJson(JSONObject obj) {
        if (obj == null) return null;
        Object idObj = obj.opt("identifier");
        Object nameObj = obj.opt("name");
        Object keyObj = obj.opt("keyCode");
        Object modObj = obj.opt("modifiers");
        if (!(idObj instanceof String) || !(nameObj instanceof String)
                || !(keyObj instanceof Number) || !(modObj instanceof Number)) {
            return null;
        }
        int kc = ((Number) keyObj).intValue();
        int mod = ((Number) modObj).intValue();
        if (kc < 1 || kc > 255 || mod < 0 || mod > 15) {
            return null;
        }
        return create((String) idObj, (String) nameObj, kc, mod);
    }

    public static List<KeyboardShortcut> builtInShortcuts(Context context) {
        Object[][] defs = new Object[][]{
                {"copy", R.string.sl_shortcut_copy, 0x43, MOD_CTRL},
                {"paste", R.string.sl_shortcut_paste, 0x56, MOD_CTRL},
                {"cut", R.string.sl_shortcut_cut, 0x58, MOD_CTRL},
                {"undo", R.string.sl_shortcut_undo, 0x5A, MOD_CTRL},
                {"select_all", R.string.sl_shortcut_select_all, 0x41, MOD_CTRL},
                {"switch_app", R.string.sl_shortcut_switch_app, 0x09, MOD_ALT},
                {"show_desktop", R.string.sl_shortcut_show_desktop, 0x44, MOD_WIN},
                {"task_manager", R.string.sl_shortcut_task_manager, 0x1B, MOD_CTRL | MOD_SHIFT}
        };
        List<KeyboardShortcut> list = new ArrayList<>(defs.length);
        for (Object[] item : defs) {
            String key = (String) item[0];
            String label = context != null ? context.getString((Integer) item[1]) : key;
            KeyboardShortcut sc = create("builtin." + key, label, (Integer) item[2], (Integer) item[3]);
            if (sc != null) {
                list.add(sc);
            }
        }
        return Collections.unmodifiableList(list);
    }

    public static void sendShortcut(final NvConnection conn, final KeyboardShortcut shortcut) {
        if (conn == null || shortcut == null) return;
        sendSteps(conn, shortcut.getKeyEvents());
    }

    public static void sendVirtualKeyTap(final NvConnection conn, int vk, int modifiers) {
        if (conn == null) return;
        List<KeyEventStep> steps = new ArrayList<>(2);
        steps.add(new KeyEventStep((short) (vk | 0x8000), true, (byte) modifiers));
        steps.add(new KeyEventStep((short) (vk | 0x8000), false, (byte) modifiers));
        sendSteps(conn, steps);
    }

    public static void sendVirtualKeyEdge(final NvConnection conn, int vk, boolean down, int modifiers) {
        if (conn == null) return;
        List<KeyEventStep> steps = Collections.singletonList(
                new KeyEventStep((short) (vk | 0x8000), down, (byte) modifiers));
        sendSteps(conn, steps);
    }

    private static void sendSteps(final NvConnection conn, final List<KeyEventStep> steps) {
        DISPATCH_EXECUTOR.execute(() -> {
            for (int i = 0; i < steps.size(); i++) {
                KeyEventStep step = steps.get(i);
                conn.sendKeyboardInput(
                        step.keyCode,
                        step.down ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP,
                        step.modifiers,
                        (byte) 0);
                if (i + 1 < steps.size()
                        && step.down
                        && !steps.get(i + 1).down
                        && step.keyCode == steps.get(i + 1).keyCode) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }
}
