package com.limelight;

import com.limelight.binding.input.KeyboardShortcut;
import com.limelight.binding.input.KeyboardShortcutStore;
import com.limelight.nvstream.http.NvApp;
import com.limelight.ui.DeviceBrowserPrefs;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KeyboardShortcutTest {

    @Test
    public void generatesPressAndReverseReleaseKeyEventSequence() {
        KeyboardShortcut sc = KeyboardShortcut.create("id1", "TaskMgr", 0x1B,
                KeyboardShortcut.MOD_CTRL | KeyboardShortcut.MOD_SHIFT);
        assertNotNull(sc);
        assertEquals("Ctrl+Shift+Esc", sc.getCombination());

        List<KeyboardShortcut.KeyEventStep> steps = sc.getKeyEvents();
        // Press order: Ctrl, Shift, Esc; Release order: Esc, Shift, Ctrl
        assertEquals(6, steps.size());
        assertTrue(steps.get(0).down);
        assertEquals((short) (KeyboardShortcut.VK_CTRL | 0x8000), steps.get(0).keyCode);
        assertTrue(steps.get(1).down);
        assertEquals((short) (KeyboardShortcut.VK_SHIFT | 0x8000), steps.get(1).keyCode);
        assertTrue(steps.get(2).down);
        assertEquals((short) (0x1B | 0x8000), steps.get(2).keyCode);

        assertFalse(steps.get(3).down);
        assertEquals((short) (0x1B | 0x8000), steps.get(3).keyCode);
        assertFalse(steps.get(4).down);
        assertEquals((short) (KeyboardShortcut.VK_SHIFT | 0x8000), steps.get(4).keyCode);
        assertFalse(steps.get(5).down);
        assertEquals((short) (KeyboardShortcut.VK_CTRL | 0x8000), steps.get(5).keyCode);
    }

    @Test
    public void supportsF24AndDefaultsNameToCombination() {
        KeyboardShortcut f24 = KeyboardShortcut.create("f24", "   ", 0x87, KeyboardShortcut.MOD_WIN);
        assertNotNull(f24);
        assertEquals("Win+F24", f24.name);
        assertEquals("F24", KeyboardShortcut.titleForKeyCode(0x87));
    }

    @Test
    public void storeSavesEditsDeletesAndFiltersCorruptRecords() {
        final Map<String, String> mem = new HashMap<>();
        KeyboardShortcutStore store = new KeyboardShortcutStore(new KeyboardShortcutStore.Storage() {
            @Override
            public String getString(String key) {
                return mem.get(key);
            }

            @Override
            public void putString(String key, String value) {
                mem.put(key, value);
            }
        });

        // Built-in shortcuts present even with empty custom store
        assertEquals(8, store.getAllShortcuts().size());
        assertEquals(0, store.getCustomShortcuts().size());

        KeyboardShortcut custom = KeyboardShortcut.create("u1", "Screenshot", 0x53,
                KeyboardShortcut.MOD_WIN | KeyboardShortcut.MOD_SHIFT);
        store.saveShortcut(custom);
        assertEquals(1, store.getCustomShortcuts().size());
        assertEquals(9, store.getAllShortcuts().size());

        // Edit existing
        KeyboardShortcut edited = KeyboardShortcut.create("u1", "Snip", 0x53, KeyboardShortcut.MOD_WIN);
        store.saveShortcut(edited);
        assertEquals(1, store.getCustomShortcuts().size());
        assertEquals("Snip", store.getCustomShortcuts().get(0).name);

        // Builtin ID cannot be saved as custom
        store.saveShortcut(KeyboardShortcut.create("builtin.copy", "Evil", 0x43, KeyboardShortcut.MOD_CTRL));
        assertEquals(1, store.getCustomShortcuts().size());

        // Delete
        store.removeShortcut("u1");
        assertEquals(0, store.getCustomShortcuts().size());

        // Corrupt JSON gracefully ignored
        mem.put("SideLantern.KeyboardShortcuts.v1", "[{\"identifier\":\"ok\",\"name\":\"OK\",\"keyCode\":65,\"modifiers\":2},{\"identifier\":\"builtin.x\",\"name\":\"Bad\",\"keyCode\":65,\"modifiers\":2},{\"identifier\":\"ok\",\"name\":\"Dup\",\"keyCode\":66,\"modifiers\":2},\"garbage\"]");
        List<KeyboardShortcut> loaded = store.getCustomShortcuts();
        assertEquals(1, loaded.size());
        assertEquals("OK", loaded.get(0).name);
    }

    @Test
    public void deviceBrowserDesktopSelectionMatchesSavedOrKnownNames() {
        NvApp steam = new NvApp("Steam", 1, false);
        NvApp desktop = new NvApp("Desktop", 2, false);
        NvApp game = new NvApp("Cyberpunk", 3, false);

        assertEquals(2, DeviceBrowserPrefs.selectDesktopApp(null, Arrays.asList(steam, desktop, game)).getAppId());
        assertEquals(3, DeviceBrowserPrefs.selectDesktopApp("3", Arrays.asList(steam, desktop, game)).getAppId());
        assertNull(DeviceBrowserPrefs.selectDesktopApp(null, Arrays.asList(steam, game)));

        NvApp cnDesktop = new NvApp("远程桌面", 9, false);
        assertEquals(9, DeviceBrowserPrefs.selectDesktopApp(null, Arrays.asList(steam, cnDesktop)).getAppId());
    }

    @Test
    public void touchModePrefRoundTripsAndDefaultsToTrackpad() {
        assertEquals(com.limelight.preferences.PreferenceConfiguration.TOUCH_MODE_TRACKPAD,
                com.limelight.preferences.PreferenceConfiguration.parseTouchMode("trackpad"));
        assertEquals(com.limelight.preferences.PreferenceConfiguration.TOUCH_MODE_TOUCHSCREEN,
                com.limelight.preferences.PreferenceConfiguration.parseTouchMode("touchscreen"));
        assertEquals(com.limelight.preferences.PreferenceConfiguration.TOUCH_MODE_NATIVE,
                com.limelight.preferences.PreferenceConfiguration.parseTouchMode("native"));
        assertEquals(com.limelight.preferences.PreferenceConfiguration.TOUCH_MODE_TRACKPAD,
                com.limelight.preferences.PreferenceConfiguration.parseTouchMode("unknown"));
        assertEquals(com.limelight.preferences.PreferenceConfiguration.TOUCH_MODE_TRACKPAD,
                com.limelight.preferences.PreferenceConfiguration.parseTouchMode(null));

        assertEquals("trackpad", com.limelight.preferences.PreferenceConfiguration.touchModeToPrefValue(
                com.limelight.preferences.PreferenceConfiguration.TOUCH_MODE_TRACKPAD));
        assertEquals("touchscreen", com.limelight.preferences.PreferenceConfiguration.touchModeToPrefValue(
                com.limelight.preferences.PreferenceConfiguration.TOUCH_MODE_TOUCHSCREEN));
        assertEquals("native", com.limelight.preferences.PreferenceConfiguration.touchModeToPrefValue(
                com.limelight.preferences.PreferenceConfiguration.TOUCH_MODE_NATIVE));
    }
}
