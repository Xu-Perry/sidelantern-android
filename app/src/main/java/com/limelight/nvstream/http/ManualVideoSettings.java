package com.limelight.nvstream.http;

import com.limelight.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Protocol models, bitrate-overhead conversion, XML parser, and request builder
 * for SideLantern's /sidelantern/v1/manual-video live video adjustment API.
 */
public final class ManualVideoSettings {
    public static final int FIELD_RESOLUTION = 1;
    public static final int FIELD_FRAMERATE = 1 << 1;
    public static final int FIELD_BITRATE = 1 << 2;
    public static final int FIELD_ALL = FIELD_RESOLUTION | FIELD_FRAMERATE | FIELD_BITRATE;

    public final int width;
    public final int height;
    public final int frameRate;
    public final int bitrate;

    public ManualVideoSettings(int width, int height, int frameRate, int bitrate) {
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.bitrate = bitrate;
    }

    public boolean isValid() {
        return width >= 64 && width <= 7680
                && height >= 64 && height <= 7680
                && (width % 2 == 0) && (height % 2 == 0)
                && ((long) width * (long) height) <= (7680L * 4320L)
                && frameRate >= 1 && frameRate <= 240
                && bitrate >= 500 && bitrate <= 250000;
    }

    public ManualVideoSettings replacingFields(int fields, ManualVideoSettings other) {
        if (other == null) {
            return this;
        }
        return new ManualVideoSettings(
                (fields & FIELD_RESOLUTION) != 0 ? other.width : this.width,
                (fields & FIELD_RESOLUTION) != 0 ? other.height : this.height,
                (fields & FIELD_FRAMERATE) != 0 ? other.frameRate : this.frameRate,
                (fields & FIELD_BITRATE) != 0 ? other.bitrate : this.bitrate
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ManualVideoSettings)) return false;
        ManualVideoSettings that = (ManualVideoSettings) o;
        return width == that.width
                && height == that.height
                && frameRate == that.frameRate
                && bitrate == that.bitrate;
    }

    @Override
    public int hashCode() {
        return width ^ (height << 1) ^ (frameRate << 2) ^ (bitrate << 3);
    }

    /**
     * Converts Moonlight's configured bandwidth budget (including FEC, audio, and
     * transport overhead) into the encoder-only bitrate accepted by manual-video.
     */
    public static final class BitrateBudget {
        private final int initialBudget;
        private final int initialEncoderBitrate;
        private final int fec;
        private final int audioOverhead;
        private final double fraction;

        private static int encoderBitrateForBudget(int budget, int fec, int audioOverhead) {
            int video = (int) (budget / (100.0f / (100 - fec)));
            video -= Math.min(audioOverhead, video / 5);
            video -= Math.min(500, video / 10);
            return video;
        }

        public static BitrateBudget create(int budget, int encoderBitrate, int audioChannels) {
            if (budget <= 0 || encoderBitrate <= 0) {
                return null;
            }
            return new BitrateBudget(budget, encoderBitrate, audioChannels);
        }

        private BitrateBudget(int budget, int encoderBitrate, int audioChannels) {
            this.initialBudget = budget;
            this.initialEncoderBitrate = encoderBitrate;
            int channels = Math.max(1, Math.min(8, audioChannels));
            int foundFec = -1;
            int foundAudio = 0;
            int[] audioRates = new int[]{96, 256};
            for (int f = 0; f <= 80; f++) {
                for (int rate : audioRates) {
                    int audio = channels * rate;
                    if (encoderBitrateForBudget(budget, f, audio) == encoderBitrate) {
                        foundFec = Math.max(foundFec, f);
                        foundAudio = Math.max(foundAudio, audio);
                    }
                }
            }
            if (foundFec >= 0) {
                this.fec = foundFec;
                this.audioOverhead = foundAudio;
                this.fraction = 1.0;
            } else {
                this.fec = -1;
                this.fraction = Math.min(1.0, (double) encoderBitrate / (double) budget);
                this.audioOverhead = channels * 256;
            }
        }

        public int encoderBitrateForBudget(int budget) {
            if (budget < 500 || budget > 250000) {
                return 0;
            }
            if (budget == initialBudget) {
                return initialEncoderBitrate;
            }
            if (fec >= 0) {
                return encoderBitrateForBudget(budget, fec, audioOverhead);
            }
            int video = (int) (budget * fraction);
            video -= Math.min(audioOverhead, video / 5);
            video -= Math.min(500, video / 10);
            return video;
        }
    }

    public static final class Response {
        private static final Set<String> ALLOWED_FIELDS = new HashSet<>(Arrays.asList(
                "session_id", "width", "height", "fps", "bitrate_kbps", "status", "reason"));
        private static final Set<Integer> ALLOWED_STATUS_CODES = new HashSet<>(Arrays.asList(
                200, 202, 400, 409));
        private static final Set<String> ALLOWED_STATES = new HashSet<>(Arrays.asList(
                "idle", "pending", "applied", "rejected"));

        public final int httpStatusCode;
        public final int statusCode;
        public final String sessionIdentifier;
        public final String state;
        public final String reason;
        public final ManualVideoSettings settings;
        private final boolean validDocument;

        public Response(int httpStatusCode, String xmlBody) {
            this.httpStatusCode = httpStatusCode;

            Map<String, String> values = new HashMap<>();
            int parsedStatusCode = 0;
            boolean valid = false;

            if (xmlBody != null && !xmlBody.trim().isEmpty()) {
                try {
                    XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                    factory.setNamespaceAware(false);
                    XmlPullParser xpp = factory.newPullParser();
                    xpp.setInput(new StringReader(xmlBody));

                    int depth = 0;
                    boolean docValid = true;
                    boolean sawRoot = false;
                    String currentField = null;
                    StringBuilder fieldText = null;
                    int eventType = xpp.getEventType();

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            depth++;
                            String elem = xpp.getName();
                            if (depth == 1) {
                                sawRoot = true;
                                docValid = "root".equals(elem) && "1".equals(xpp.getAttributeValue(null, "version"));
                                Long code = parseUnsignedInt(xpp.getAttributeValue(null, "status_code"));
                                if (code == null) {
                                    docValid = false;
                                } else {
                                    parsedStatusCode = code.intValue();
                                }
                            } else if (depth == 2 && ALLOWED_FIELDS.contains(elem)) {
                                if (values.containsKey(elem)) {
                                    docValid = false;
                                }
                                currentField = elem;
                                fieldText = new StringBuilder();
                            } else if (currentField != null) {
                                docValid = false;
                            }
                        } else if (eventType == XmlPullParser.TEXT) {
                            if (currentField != null && fieldText != null) {
                                fieldText.append(xpp.getText());
                            }
                        } else if (eventType == XmlPullParser.END_TAG) {
                            if (depth == 2 && currentField != null && fieldText != null) {
                                values.put(currentField, fieldText.toString());
                                currentField = null;
                                fieldText = null;
                            }
                            depth--;
                        }
                        eventType = xpp.next();
                    }
                    valid = sawRoot && docValid;
                } catch (Exception e) {
                    valid = false;
                }
            }

            this.statusCode = parsedStatusCode;
            this.state = values.get("status");
            this.reason = values.get("reason");
            Long sid = parseUnsignedInt(values.get("session_id"));
            Long w = parseUnsignedInt(values.get("width"));
            Long h = parseUnsignedInt(values.get("height"));
            Long fps = parseUnsignedInt(values.get("fps"));
            Long br = parseUnsignedInt(values.get("bitrate_kbps"));
            if (sid != null && w != null && h != null && fps != null && br != null) {
                this.sessionIdentifier = Long.toString(sid);
                this.settings = new ManualVideoSettings(w.intValue(), h.intValue(), fps.intValue(), br.intValue());
            } else {
                this.sessionIdentifier = null;
                this.settings = null;
            }
            this.validDocument = valid;
        }

        public boolean isValidSession() {
            return validDocument
                    && sessionIdentifier != null
                    && settings != null
                    && settings.isValid()
                    && ALLOWED_STATUS_CODES.contains(statusCode)
                    && state != null
                    && ALLOWED_STATES.contains(state);
        }
    }

    public static Long parseUnsignedInt(String text) {
        if (text == null || text.isEmpty() || text.length() > 10) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            long value = Long.parseLong(text);
            return value <= 0xFFFFFFFFL ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Request buildRequest(HttpUrl baseHttpsUrl, String sessionIdentifier, ManualVideoSettings settings) {
        if (baseHttpsUrl == null || !"https".equals(baseHttpsUrl.scheme()) || baseHttpsUrl.host().isEmpty()) {
            return null;
        }
        HttpUrl.Builder builder = baseHttpsUrl.newBuilder()
                .encodedPath("/sidelantern/v1/manual-video")
                .query(null)
                .fragment(null);

        if (settings != null) {
            Long sid = parseUnsignedInt(sessionIdentifier);
            if (sid == null || !settings.isValid()) {
                return null;
            }
            builder.addQueryParameter("session_id", Long.toString(sid))
                    .addQueryParameter("width", Integer.toString(settings.width))
                    .addQueryParameter("height", Integer.toString(settings.height))
                    .addQueryParameter("fps", Integer.toString(settings.frameRate))
                    .addQueryParameter("bitrate_kbps", Integer.toString(settings.bitrate));
            return new Request.Builder()
                    .url(builder.build())
                    .post(RequestBody.create(new byte[0]))
                    .header("Cache-Control", "no-store")
                    .build();
        } else {
            return new Request.Builder()
                    .url(builder.build())
                    .get()
                    .header("Cache-Control", "no-store")
                    .build();
        }
    }

    public static int getErrorMessageResId(String reason) {
        if (reason == null) {
            return R.string.sl_manual_error_connection;
        }
        switch (reason) {
            case "invalid_video_settings":
                return R.string.sl_manual_error_invalid_video_settings;
            case "host_video_limit":
                return R.string.sl_manual_error_host_video_limit;
            case "stale_session":
                return R.string.sl_manual_error_stale_session;
            case "video_change_pending":
                return R.string.sl_manual_error_video_change_pending;
            case "encoder_rejected":
                return R.string.sl_manual_error_encoder_rejected;
            case "unconfirmed":
                return R.string.sl_manual_error_unconfirmed;
            case "unavailable":
                return R.string.sl_manual_error_unavailable;
            default:
                return R.string.sl_manual_error_connection;
        }
    }
}
