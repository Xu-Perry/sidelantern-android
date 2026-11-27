package com.limelight.nvstream.http;

import android.content.Context;

import com.limelight.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Parses GET /sidelantern/v1/stream-modes responses and maps SideLantern display
 * mode identifiers and failure reasons to user-facing messages.
 */
public class StreamModesResponse {
    public static final String MODE_PRIMARY = "primary";
    public static final String MODE_SECONDARY = "secondary";
    public static final String MODE_EXTEND = "extend";

    private static final Set<String> VALID_MODES = new HashSet<>(
            Arrays.asList(MODE_PRIMARY, MODE_SECONDARY, MODE_EXTEND));

    private final int httpStatusCode;
    private int statusCode;
    private String statusMessage;
    private boolean validDocument;
    private boolean versionSupported;
    private boolean successStatus;
    private boolean manualVideoSupported;
    private final Map<String, ModeInfo> modes = new HashMap<>();

    private static final class ModeInfo {
        final boolean available;
        final String reason;

        ModeInfo(boolean available, String reason) {
            this.available = available;
            this.reason = reason;
        }
    }

    public StreamModesResponse(int httpStatusCode, String xmlBody) {
        this.httpStatusCode = httpStatusCode;
        parse(xmlBody);
    }

    private void parse(String xmlBody) {
        modes.clear();
        validDocument = false;
        versionSupported = false;
        successStatus = false;
        manualVideoSupported = false;
        statusCode = 0;
        statusMessage = null;

        if (xmlBody == null || xmlBody.trim().isEmpty()) {
            return;
        }

        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(new StringReader(xmlBody));

            int depth = 0;
            boolean valid = true;
            boolean sawRoot = false;
            int eventType = xpp.getEventType();

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    depth++;
                    String name = xpp.getName();
                    if (depth == 1) {
                        sawRoot = true;
                        if (!"root".equals(name)) {
                            valid = false;
                        }
                        String codeAttr = xpp.getAttributeValue(null, "status_code");
                        if (codeAttr != null) {
                            try {
                                statusCode = Integer.parseInt(codeAttr);
                            } catch (NumberFormatException e) {
                                valid = false;
                            }
                        }
                        successStatus = "200".equals(codeAttr);
                        versionSupported = "1".equals(xpp.getAttributeValue(null, "version"));
                        manualVideoSupported = "1".equals(xpp.getAttributeValue(null, "manual_video_version"));
                        String msg = xpp.getAttributeValue(null, "status_message");
                        if (msg != null && !msg.isEmpty()) {
                            statusMessage = msg;
                        }
                    } else if (depth == 2 && "mode".equals(name)) {
                        String id = xpp.getAttributeValue(null, "id");
                        if (id != null && VALID_MODES.contains(id)) {
                            String available = xpp.getAttributeValue(null, "available");
                            String reason = xpp.getAttributeValue(null, "reason");
                            if (modes.containsKey(id) ||
                                    (!"1".equals(available) && !"0".equals(available)) ||
                                    reason == null ||
                                    ("1".equals(available) && !reason.isEmpty())) {
                                valid = false;
                            } else {
                                modes.put(id, new ModeInfo("1".equals(available), reason));
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    depth--;
                }
                eventType = xpp.next();
            }

            validDocument = sawRoot && valid;
        } catch (Exception e) {
            validDocument = false;
        }
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public boolean supportsDisplayModes() {
        return validDocument && versionSupported && successStatus && statusCode == 200 && modes.size() == 3;
    }

    public boolean isLegacyHost() {
        return httpStatusCode == 404 || httpStatusCode == 501 || statusCode == 404 || statusCode == 501;
    }

    public boolean supportsManualVideo() {
        return supportsDisplayModes() && manualVideoSupported;
    }

    public boolean isModeAvailable(String mode) {
        if (!supportsDisplayModes() || mode == null) {
            return false;
        }
        ModeInfo info = modes.get(mode);
        return info != null && info.available;
    }

    public String reasonForMode(String mode) {
        if (!supportsDisplayModes() || mode == null) {
            return null;
        }
        ModeInfo info = modes.get(mode);
        return info != null ? info.reason : null;
    }

    public static int getFailureMessageResId(String reason) {
        if (reason == null) {
            return R.string.sl_target_error_unavailable;
        }
        switch (reason) {
            case "unknown_mode":
                return R.string.sl_target_error_unknown_mode;
            case "capture_busy":
                return R.string.sl_target_error_capture_busy;
            case "primary_display_unavailable":
                return R.string.sl_target_error_primary_display_unavailable;
            case "secondary_display_unavailable":
                return R.string.sl_target_error_secondary_display_unavailable;
            case "other_displays_active":
                return R.string.sl_target_error_other_displays_active;
            case "display_preparation_failed":
                return R.string.sl_target_error_display_preparation_failed;
            case "unsupported_platform":
                return R.string.sl_target_error_unsupported_platform;
            default:
                return R.string.sl_target_error_unavailable;
        }
    }

    public static String getFailureMessage(Context context, String reason) {
        return context.getString(getFailureMessageResId(reason));
    }
}
