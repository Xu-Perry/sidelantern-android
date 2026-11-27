package com.limelight.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.nvstream.http.NvApp;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Per-host preferences for SideLantern device browser:
 * - Selected desktop application ID (or auto-matched by common desktop names)
 * - Selected quick-launch application IDs
 */
public final class DeviceBrowserPrefs {
    private static final String PREF_FILE = "SideLanternDeviceBrowser";
    private static final String DESKTOP_KEY_PREFIX = "SideLantern.DesktopApp.";
    private static final String QUICK_KEY_PREFIX = "SideLantern.QuickLaunch.";

    private static final Set<String> DESKTOP_NAMES = new HashSet<>(Arrays.asList(
            "desktop", "桌面", "远程桌面", "entire desktop"
    ));

    private DeviceBrowserPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    public static NvApp findDesktopApp(Context context, String hostUuid, List<NvApp> apps) {
        if (apps == null || apps.isEmpty()) {
            return null;
        }
        String savedId = hostUuid != null ? prefs(context).getString(DESKTOP_KEY_PREFIX + hostUuid, null) : null;
        return selectDesktopApp(savedId, apps);
    }

    public static NvApp selectDesktopApp(String savedAppId, List<NvApp> apps) {
        if (apps == null || apps.isEmpty()) {
            return null;
        }
        if (savedAppId != null && !savedAppId.isEmpty()) {
            for (NvApp app : apps) {
                if (savedAppId.equals(Integer.toString(app.getAppId()))) {
                    return app;
                }
            }
        }
        List<NvApp> sorted = new ArrayList<>(apps);
        Collections.sort(sorted, (a, b) -> {
            String na = a.getAppName() != null ? a.getAppName() : "";
            String nb = b.getAppName() != null ? b.getAppName() : "";
            return na.compareToIgnoreCase(nb);
        });
        for (NvApp app : sorted) {
            if (app.getAppName() != null && DESKTOP_NAMES.contains(app.getAppName().trim().toLowerCase(Locale.US))) {
                return app;
            }
        }
        return null;
    }

    public static void setDesktopApp(Context context, String hostUuid, NvApp app) {
        if (hostUuid == null || app == null) return;
        prefs(context).edit()
                .putString(DESKTOP_KEY_PREFIX + hostUuid, Integer.toString(app.getAppId()))
                .apply();
    }

    public static Set<String> getQuickLaunchAppIds(Context context, String hostUuid, List<NvApp> apps, Set<Integer> hiddenIds) {
        if (hostUuid != null && prefs(context).contains(QUICK_KEY_PREFIX + hostUuid)) {
            String json = prefs(context).getString(QUICK_KEY_PREFIX + hostUuid, null);
            Set<String> parsed = parseIdSet(json);
            if (parsed != null) {
                return parsed;
            }
        }
        NvApp desktop = findDesktopApp(context, hostUuid, apps);
        Set<String> defaults = new HashSet<>();
        if (apps != null) {
            for (NvApp app : apps) {
                if (hiddenIds != null && hiddenIds.contains(app.getAppId())) continue;
                if (desktop != null && app.getAppId() == desktop.getAppId()) continue;
                defaults.add(Integer.toString(app.getAppId()));
            }
        }
        return defaults;
    }

    public static void setQuickLaunchAppIds(Context context, String hostUuid, Set<String> appIds) {
        if (hostUuid == null) return;
        JSONArray arr = new JSONArray();
        if (appIds != null) {
            for (String id : appIds) {
                if (id != null && !id.isEmpty()) {
                    arr.put(id);
                }
            }
        }
        prefs(context).edit()
                .putString(QUICK_KEY_PREFIX + hostUuid, arr.toString())
                .apply();
    }

    public static void clearForHost(Context context, String hostUuid) {
        if (hostUuid == null) return;
        prefs(context).edit()
                .remove(DESKTOP_KEY_PREFIX + hostUuid)
                .remove(QUICK_KEY_PREFIX + hostUuid)
                .apply();
    }

    private static Set<String> parseIdSet(String json) {
        if (json == null) return null;
        try {
            JSONArray arr = new JSONArray(json);
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, null);
                if (id != null && !id.isEmpty()) {
                    ids.add(id);
                }
            }
            return ids;
        } catch (Exception e) {
            return null;
        }
    }
}
