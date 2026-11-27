package com.limelight.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves a human-readable client device name for pairing requests instead of
 * the hardcoded "roth" identifier, and encodes it safely for URL query values.
 */
public final class DeviceNameHelper {
    private static final Set<String> GENERIC_NAMES = new HashSet<>(Arrays.asList(
            "android", "localhost", "unknown", "generic", "sdk", "emulator", "phone", "tablet"
    ));

    private static volatile String cachedDeviceName = null;

    private DeviceNameHelper() {}

    public static void initialize(Context context, String uniqueId) {
        if (context == null) {
            return;
        }
        String systemName = null;
        try {
            ContentResolver cr = context.getContentResolver();
            if (cr != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                    systemName = Settings.Global.getString(cr, Settings.Global.DEVICE_NAME);
                }
                if (isBlankOrGeneric(systemName)) {
                    systemName = Settings.Secure.getString(cr, "bluetooth_name");
                }
            }
        } catch (Throwable ignored) {
            // Some OEM builds or tests restrict Settings access.
        }
        cachedDeviceName = resolveDeviceName(systemName, Build.MANUFACTURER, Build.MODEL, uniqueId);
    }

    public static String getDeviceName() {
        if (cachedDeviceName != null && !cachedDeviceName.isEmpty()) {
            return cachedDeviceName;
        }
        return resolveDeviceName(null, Build.MANUFACTURER, Build.MODEL, null);
    }

    public static String getEncodedDeviceName() {
        return percentEncodeQueryValue(getDeviceName());
    }

    public static String resolveDeviceName(String systemName, String manufacturer, String model, String uniqueId) {
        String trimmedSystem = trimToNull(systemName);
        if (!isBlankOrGeneric(trimmedSystem)) {
            return trimmedSystem;
        }

        String trimmedModel = trimToNull(model);
        String trimmedManufacturer = trimToNull(manufacturer);
        if (!isBlankOrGeneric(trimmedModel)) {
            if (!isBlankOrGeneric(trimmedManufacturer) &&
                    !trimmedModel.toLowerCase(Locale.US).startsWith(trimmedManufacturer.toLowerCase(Locale.US))) {
                return trimmedManufacturer + " " + trimmedModel;
            }
            return trimmedModel;
        }

        String shortId = "DEVICE";
        String trimmedId = trimToNull(uniqueId);
        if (trimmedId != null) {
            shortId = trimmedId.substring(0, Math.min(6, trimmedId.length())).toUpperCase(Locale.US);
        }
        return "Android-" + shortId;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlankOrGeneric(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return true;
        }
        return GENERIC_NAMES.contains(trimmed.toLowerCase(Locale.US));
    }

    /**
     * Percent-encodes a string using RFC 3986 unreserved characters only so spaces,
     * CJK characters, emoji, and reserved delimiters (+, &, #, ?, %, =, /) round-trip cleanly.
     */
    public static String percentEncodeQueryValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') ||
                    (c >= 'a' && c <= 'z') ||
                    (c >= '0' && c <= '9') ||
                    c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(hex[(c >>> 4) & 0x0F]);
                sb.append(hex[c & 0x0F]);
            }
        }
        return sb.toString();
    }
}
