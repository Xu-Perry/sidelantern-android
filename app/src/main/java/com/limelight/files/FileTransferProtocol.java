package com.limelight.files;

import com.limelight.R;
import com.limelight.utils.DeviceNameHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Wire protocol models, strict JSON validators, and HTTPS request builder for
 * SideLantern /sidelantern/v1/files bidirectional file transfer.
 */
public final class FileTransferProtocol {
    public static final long MAX_UPLOAD_CHUNK_BYTES = 48L * 1024L;
    public static final long MAX_DOWNLOAD_CHUNK_BYTES = 1024L * 1024L;
    public static final long MAX_UPLOAD_BYTES = 8L * 1024L * 1024L * 1024L;

    private static final Set<String> ALLOWED_ROUTES = new HashSet<>(Arrays.asList(
            "", "/list", "/download", "/uploads", "/uploads/complete"));
    private static final Set<String> ALLOWED_METHODS = new HashSet<>(Arrays.asList(
            "GET", "POST", "PUT", "DELETE"));

    private FileTransferProtocol() {}

    public static final class FileTransferException extends Exception {
        public final String reason;
        public final int httpStatus;
        public final boolean networkError;

        public FileTransferException(String reason, int httpStatus) {
            this(reason, httpStatus, false, null);
        }

        public FileTransferException(String reason, int httpStatus, boolean networkError, Throwable cause) {
            super(reason, cause);
            this.reason = reason != null ? reason : "request_failed";
            this.httpStatus = httpStatus;
            this.networkError = networkError;
        }
    }

    public static Long parseUnsigned(Object value, long maximum) {
        if (value == null || value == JSONObject.NULL || value instanceof Boolean) {
            return null;
        }
        if (!(value instanceof Number)) {
            return null;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            long l = ((Number) value).longValue();
            return (l >= 0 && l <= maximum) ? l : null;
        }
        double d = ((Number) value).doubleValue();
        if (!Double.isFinite(d) || d < 0 || Math.floor(d) != d || d > (double) maximum) {
            return null;
        }
        long l = (long) d;
        return (l >= 0 && l <= maximum) ? l : null;
    }

    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255) {
            return false;
        }
        if (".".equals(name) || "..".equals(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == '\0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidRootId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static JSONObject parseJsonObject(byte[] data) {
        if (data == null || data.length == 0 || data.length > 1024 * 1024) {
            return null;
        }
        try {
            return new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBoolean(Object value) {
        return value instanceof Boolean;
    }

    public static FileTransferException parseResponseError(byte[] data, int statusCode, Throwable ioError) {
        if (ioError != null) {
            if (ioError instanceof FileTransferException) {
                return (FileTransferException) ioError;
            }
            return new FileTransferException("connection_unavailable", 0, true, ioError);
        }
        if (statusCode <= 0) {
            return new FileTransferException("invalid_response", 0);
        }
        if (statusCode >= 200 && statusCode < 300) {
            return null;
        }
        String reason = null;
        JSONObject obj = parseJsonObject(data);
        if (obj != null) {
            Object errVal = obj.opt("error");
            if (errVal instanceof String && !((String) errVal).isEmpty()) {
                reason = (String) errVal;
            }
        }
        if (reason == null) {
            if (statusCode == 401) reason = "device_not_paired";
            else if (statusCode == 403) reason = "device_forbidden";
            else if (statusCode == 404) reason = "not_supported";
            else reason = "request_failed";
        }
        return new FileTransferException(reason, statusCode);
    }

    public static Request buildRequest(HttpUrl baseHttpsUrl, String method, String route,
                                       Map<String, String> query, byte[] body, String contentType) {
        if (baseHttpsUrl == null || !"https".equals(baseHttpsUrl.scheme()) || baseHttpsUrl.host().isEmpty()) {
            return null;
        }
        if (route == null || !ALLOWED_ROUTES.contains(route) || method == null || !ALLOWED_METHODS.contains(method)) {
            return null;
        }

        HttpUrl.Builder urlBuilder = baseHttpsUrl.newBuilder()
                .encodedPath("/sidelantern/v1/files" + route)
                .query(null)
                .fragment(null)
                .username("")
                .password("");

        if (query != null && !query.isEmpty()) {
            List<String> keys = new ArrayList<>(query.keySet());
            Collections.sort(keys);
            StringBuilder encodedQuery = new StringBuilder();
            for (String key : keys) {
                String value = query.get(key);
                if (key == null || value == null) {
                    return null;
                }
                if (encodedQuery.length() > 0) {
                    encodedQuery.append('&');
                }
                encodedQuery.append(DeviceNameHelper.percentEncodeQueryValue(key))
                        .append('=')
                        .append(DeviceNameHelper.percentEncodeQueryValue(value));
            }
            urlBuilder.encodedQuery(encodedQuery.toString());
        }

        Request.Builder reqBuilder = new Request.Builder()
                .url(urlBuilder.build())
                .header("Cache-Control", "no-store");

        if ("GET".equals(method)) {
            reqBuilder.get();
        } else if ("DELETE".equals(method)) {
            reqBuilder.delete();
        } else {
            MediaType mt = contentType != null ? MediaType.parse(contentType) : null;
            byte[] payload = body != null ? body : new byte[0];
            reqBuilder.method(method, RequestBody.create(payload, mt));
        }

        return reqBuilder.build();
    }

    public static final class Root {
        public final String identifier;
        public final String label;

        public Root(String identifier, String label) {
            this.identifier = identifier;
            this.label = label;
        }
    }

    public static final class Capabilities {
        public final boolean enabled;
        public final boolean upload;
        public final List<Root> roots;
        public final int uploadChunkBytes;
        public final int downloadChunkBytes;
        public final long maxUploadBytes;

        public Capabilities(boolean enabled, boolean upload, List<Root> roots,
                            int uploadChunkBytes, int downloadChunkBytes, long maxUploadBytes) {
            this.enabled = enabled;
            this.upload = upload;
            this.roots = Collections.unmodifiableList(new ArrayList<>(roots));
            this.uploadChunkBytes = uploadChunkBytes;
            this.downloadChunkBytes = downloadChunkBytes;
            this.maxUploadBytes = maxUploadBytes;
        }

        public static Capabilities parse(byte[] data) {
            JSONObject json = parseJsonObject(data);
            if (json == null) return null;

            Long version = parseUnsigned(json.opt("version"), 1L);
            Long uploadChunk = parseUnsigned(json.opt("upload_chunk_bytes"), MAX_UPLOAD_CHUNK_BYTES);
            Long downloadChunk = parseUnsigned(json.opt("download_chunk_bytes"), MAX_DOWNLOAD_CHUNK_BYTES);
            Long maxUpload = parseUnsigned(json.opt("max_upload_bytes"), MAX_UPLOAD_BYTES);
            Long uploadIdle = parseUnsigned(json.opt("upload_idle_seconds"), 0xFFFFFFFFL);
            Object enabledObj = json.opt("enabled");
            Object uploadObj = json.opt("upload");
            Object rootsObj = json.opt("roots");

            if (version == null || version != 1L
                    || !isBoolean(enabledObj) || !isBoolean(uploadObj)
                    || uploadChunk == null || uploadChunk == 0L
                    || downloadChunk == null || downloadChunk == 0L
                    || maxUpload == null || uploadIdle == null
                    || !(rootsObj instanceof JSONArray)) {
                return null;
            }

            JSONArray rootsArr = (JSONArray) rootsObj;
            List<Root> parsedRoots = new ArrayList<>();
            Set<String> seenIds = new HashSet<>();
            for (int i = 0; i < rootsArr.length(); i++) {
                Object item = rootsArr.opt(i);
                if (!(item instanceof JSONObject)) return null;
                JSONObject r = (JSONObject) item;
                Object idObj = r.opt("id");
                Object labelObj = r.opt("label");
                if (!(idObj instanceof String) || !(labelObj instanceof String)) return null;
                String id = (String) idObj;
                String label = (String) labelObj;
                if (!isValidRootId(id) || seenIds.contains(id) || label.isEmpty() || label.length() > 256) {
                    return null;
                }
                seenIds.add(id);
                parsedRoots.add(new Root(id, label));
            }

            return new Capabilities(
                    (Boolean) enabledObj,
                    (Boolean) uploadObj,
                    parsedRoots,
                    uploadChunk.intValue(),
                    downloadChunk.intValue(),
                    maxUpload
            );
        }
    }

    public static final class Entry {
        public final String name;
        public final boolean directory;
        public final long size;
        public final long modifiedUnixMs;
        public final String version;

        public Entry(String name, boolean directory, long size, long modifiedUnixMs, String version) {
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.modifiedUnixMs = modifiedUnixMs;
            this.version = version;
        }
    }

    public static final class Listing {
        public final List<Entry> entries;
        public final Integer nextOffset;
        public final boolean truncated;

        public Listing(List<Entry> entries, Integer nextOffset, boolean truncated) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.nextOffset = nextOffset;
            this.truncated = truncated;
        }

        public static Listing parse(byte[] data) {
            JSONObject json = parseJsonObject(data);
            if (json == null) return null;

            Object entriesObj = json.opt("entries");
            Object truncatedObj = json.opt("truncated");
            Object nextOffsetObj = json.opt("next_offset");
            if (!(entriesObj instanceof JSONArray) || !isBoolean(truncatedObj)) {
                return null;
            }
            Integer parsedNextOffset = null;
            if (nextOffsetObj != null && nextOffsetObj != JSONObject.NULL) {
                Long off = parseUnsigned(nextOffsetObj, 100000L);
                if (off == null) return null;
                parsedNextOffset = off.intValue();
            }

            JSONArray arr = (JSONArray) entriesObj;
            List<Entry> list = new ArrayList<>();
            Set<String> seenNames = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (!(item instanceof JSONObject)) return null;
                JSONObject e = (JSONObject) item;
                Object nameObj = e.opt("name");
                Object kindObj = e.opt("kind");
                Object versionObj = e.opt("version");
                Long size = parseUnsigned(e.opt("size"), Long.MAX_VALUE);
                Long modified = parseUnsigned(e.opt("modified_unix_ms"), Long.MAX_VALUE);
                if (!(nameObj instanceof String) || !isValidName((String) nameObj)
                        || seenNames.contains(nameObj)
                        || !(kindObj instanceof String)
                        || (!"file".equals(kindObj) && !"directory".equals(kindObj))
                        || size == null || modified == null
                        || !(versionObj instanceof String)) {
                    return null;
                }
                seenNames.add((String) nameObj);
                list.add(new Entry((String) nameObj, "directory".equals(kindObj), size, modified, (String) versionObj));
            }
            return new Listing(list, parsedNextOffset, (Boolean) truncatedObj);
        }
    }

    public static final class UploadStatus {
        public final String identifier;
        public final String name;
        public final long size;
        public final long offset;
        public final boolean complete;

        public UploadStatus(String identifier, String name, long size, long offset, boolean complete) {
            this.identifier = identifier;
            this.name = name;
            this.size = size;
            this.offset = offset;
            this.complete = complete;
        }

        public static UploadStatus parse(byte[] data) {
            JSONObject json = parseJsonObject(data);
            if (json == null) return null;

            Object idObj = json.opt("id");
            Object nameObj = json.opt("name");
            Object completeObj = json.opt("complete");
            Long size = parseUnsigned(json.opt("size"), MAX_UPLOAD_BYTES);
            Long offset = parseUnsigned(json.opt("offset"), MAX_UPLOAD_BYTES);

            if (!(idObj instanceof String) || ((String) idObj).isEmpty() || ((String) idObj).length() > 128
                    || !(nameObj instanceof String) || !isValidName((String) nameObj)
                    || size == null || offset == null || offset > size
                    || !isBoolean(completeObj)) {
                return null;
            }
            boolean complete = (Boolean) completeObj;
            if (complete && !offset.equals(size)) {
                return null;
            }
            return new UploadStatus((String) idObj, (String) nameObj, size, offset, complete);
        }
    }

    public static final class ResponseData {
        public final int statusCode;
        public final byte[] body;
        public final String contentType;
        public final String fileSizeHeader;
        public final String fileVersionHeader;
        public final Throwable ioError;

        public ResponseData(int statusCode, byte[] body, String contentType,
                            String fileSizeHeader, String fileVersionHeader, Throwable ioError) {
            this.statusCode = statusCode;
            this.body = body;
            this.contentType = contentType;
            this.fileSizeHeader = fileSizeHeader;
            this.fileVersionHeader = fileVersionHeader;
            this.ioError = ioError;
        }
    }

    public interface Reply {
        void onComplete(ResponseData response);
    }

    public interface Transport {
        void perform(String method, String route, Map<String, String> query,
                     byte[] body, String contentType, Reply reply);
    }

    public static int getErrorMessageResId(String reason) {
        if (reason == null) {
            return R.string.sl_ft_error_request_failed;
        }
        switch (reason) {
            case "not_supported": return R.string.sl_ft_error_not_supported;
            case "invalid_response": return R.string.sl_ft_error_invalid_response;
            case "connection_unavailable": return R.string.sl_ft_error_connection_unavailable;
            case "device_not_paired": return R.string.sl_ft_error_device_not_paired;
            case "device_forbidden": return R.string.sl_ft_error_device_forbidden;
            case "file_transfer_disabled": return R.string.sl_ft_error_file_transfer_disabled;
            case "upload_disabled": return R.string.sl_ft_error_upload_disabled;
            case "file_too_large": return R.string.sl_ft_error_file_too_large;
            case "file_exists": return R.string.sl_ft_error_file_exists;
            case "file_changed": return R.string.sl_ft_error_file_changed;
            case "offset_mismatch": return R.string.sl_ft_error_offset_mismatch;
            case "upload_not_found": return R.string.sl_ft_error_upload_not_found;
            case "root_not_found": return R.string.sl_ft_error_root_not_found;
            case "not_found": return R.string.sl_ft_error_not_found;
            case "path_forbidden": return R.string.sl_ft_error_path_forbidden;
            case "invalid_path": return R.string.sl_ft_error_invalid_path;
            case "file_io_error": return R.string.sl_ft_error_file_io_error;
            case "local_file_unavailable": return R.string.sl_ft_error_local_file_unavailable;
            case "upload_limit": return R.string.sl_ft_error_upload_limit;
            case "invalid_configuration": return R.string.sl_ft_error_invalid_configuration;
            default: return R.string.sl_ft_error_request_failed;
        }
    }
}
