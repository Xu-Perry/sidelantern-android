package com.limelight.binding.input;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local persistence store for custom keyboard shortcuts (SideLantern.KeyboardShortcuts.v1).
 */
public class KeyboardShortcutStore {
    private static final String PREF_NAME = "SideLanternKeyboardShortcuts";
    private static final String KEY_SHORTCUTS = "SideLantern.KeyboardShortcuts.v1";

    public interface Storage {
        String getString(String key);
        void putString(String key, String value);
    }

    public interface Listener {
        void onShortcutsChanged();
    }

    private final Context context;
    private final Storage storage;
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public KeyboardShortcutStore(Context context) {
        this.context = context.getApplicationContext();
        final SharedPreferences prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.storage = new Storage() {
            @Override
            public String getString(String key) {
                return prefs.getString(key, null);
            }

            @Override
            public void putString(String key, String value) {
                prefs.edit().putString(key, value).apply();
            }
        };
    }

    public KeyboardShortcutStore(Storage storage) {
        this.context = null;
        this.storage = storage;
    }

    public static void addListener(Listener listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public List<KeyboardShortcut> getCustomShortcuts() {
        String raw = storage.getString(KEY_SHORTCUTS);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<KeyboardShortcut> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (!(item instanceof JSONObject)) continue;
                KeyboardShortcut sc = KeyboardShortcut.fromJson((JSONObject) item);
                if (sc != null && !sc.identifier.startsWith("builtin.") && !seenIds.contains(sc.identifier)) {
                    seenIds.add(sc.identifier);
                    result.add(sc);
                }
            }
        } catch (Exception ignored) {
        }
        return Collections.unmodifiableList(result);
    }

    public List<KeyboardShortcut> getAllShortcuts() {
        List<KeyboardShortcut> all = new ArrayList<>(KeyboardShortcut.builtInShortcuts(context));
        all.addAll(getCustomShortcuts());
        return Collections.unmodifiableList(all);
    }

    public void saveShortcut(KeyboardShortcut shortcut) {
        if (shortcut == null || shortcut.identifier.startsWith("builtin.")) {
            return;
        }
        List<KeyboardShortcut> list = new ArrayList<>(getCustomShortcuts());
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).identifier.equals(shortcut.identifier)) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) list.set(idx, shortcut);
        else list.add(shortcut);
        writeShortcuts(list);
    }

    public void removeShortcut(String identifier) {
        if (identifier == null || identifier.isEmpty()) return;
        List<KeyboardShortcut> list = new ArrayList<>(getCustomShortcuts());
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).identifier.equals(identifier)) {
                list.remove(i);
                removed = true;
            }
        }
        if (removed) {
            writeShortcuts(list);
        }
    }

    private void writeShortcuts(List<KeyboardShortcut> shortcuts) {
        JSONArray arr = new JSONArray();
        for (KeyboardShortcut sc : shortcuts) {
            arr.put(sc.toJson());
        }
        storage.putString(KEY_SHORTCUTS, arr.toString());
        for (Listener l : LISTENERS) {
            l.onShortcutsChanged();
        }
    }
}
