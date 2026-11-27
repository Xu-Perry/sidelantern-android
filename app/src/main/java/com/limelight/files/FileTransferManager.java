package com.limelight.files;

import com.limelight.files.FileTransferProtocol.Capabilities;
import com.limelight.files.FileTransferProtocol.Entry;
import com.limelight.files.FileTransferProtocol.FileTransferException;
import com.limelight.files.FileTransferProtocol.Listing;
import com.limelight.files.FileTransferProtocol.ResponseData;
import com.limelight.files.FileTransferProtocol.Transport;
import com.limelight.files.FileTransferProtocol.UploadStatus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Per-host bidirectional file transfer queue with disk-backed chunked I/O,
 * pause/resume/cancel, offset recovery, and JSON persistence across restarts.
 */
public class FileTransferManager {
    public enum State {
        PREPARING,
        QUEUED,
        RUNNING,
        PAUSED,
        FAILED,
        COMPLETED,
        CANCELLED
    }

    public static final class Task {
        public final String identifier;
        public final String name;
        public final boolean upload;
        public final State state;
        public final long size;
        public final long offset;
        public final long dateMs;
        public final FileTransferException error;
        public final File localFile;

        Task(String identifier, String name, boolean upload, State state,
             long size, long offset, long dateMs, FileTransferException error, File localFile) {
            this.identifier = identifier;
            this.name = name;
            this.upload = upload;
            this.state = state;
            this.size = size;
            this.offset = offset;
            this.dateMs = dateMs;
            this.error = error;
            this.localFile = localFile;
        }
    }

    private static final class Record {
        String identifier;
        String name;
        String root = "";
        String path = "";
        String version = "";
        String uploadIdentifier;
        boolean upload;
        boolean needsStatus;
        int recoveries;
        State state;
        long size;
        long offset;
        long dateMs;
        FileTransferException error;
        File localFile;

        Task snapshot() {
            return new Task(identifier, name, upload, state, size, offset, dateMs, error, localFile);
        }
    }

    public interface Listener {
        void onFileTransferChanged(FileTransferManager manager);
    }

    public interface ListCallback {
        void onResult(Listing listing, FileTransferException error);
    }

    public interface UploadEnqueuedCallback {
        void onComplete(FileTransferException error);
    }

    public interface SourceOpener {
        InputStream open() throws IOException;
    }

    private final Transport transport;
    private final File directory;
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SideLantern-FileTransfers");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "SideLantern-FilePrep");
        t.setDaemon(true);
        return t;
    });

    private final List<Record> records = new ArrayList<>();
    private final List<Runnable> probeCompletions = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private boolean busy;
    private boolean probing;

    private volatile List<Task> tasksSnapshot = Collections.emptyList();
    private volatile Capabilities capabilities;
    private volatile FileTransferException capabilityError;
    private volatile boolean capabilitiesProbed;

    public FileTransferManager(Transport transport, File directory) {
        this.transport = transport;
        this.directory = directory;
        if (!directory.exists()) {
            directory.mkdirs();
        }
        loadPersistedTasks();
        publish();
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public List<Task> getTasks() {
        return tasksSnapshot;
    }

    public Capabilities getCapabilities() {
        return capabilities;
    }

    public FileTransferException getCapabilityError() {
        return capabilityError;
    }

    public boolean isCapabilitiesProbed() {
        return capabilitiesProbed;
    }

    private File fileForTask(Record r) {
        File dataDir = new File(directory, "data");
        File taskDir = new File(dataDir, r.identifier);
        return new File(taskDir, r.name);
    }

    private void loadPersistedTasks() {
        File savedFile = new File(directory, "tasks.json");
        if (!savedFile.isFile()) {
            return;
        }
        try {
            byte[] bytes = readAllBytes(savedFile);
            JSONArray arr = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                Object obj = arr.opt(i);
                if (!(obj instanceof JSONObject)) continue;
                JSONObject row = (JSONObject) obj;
                String id = row.optString("id", null);
                String name = row.optString("name", null);
                Long size = FileTransferProtocol.parseUnsigned(row.opt("size"), Long.MAX_VALUE);
                Long offset = FileTransferProtocol.parseUnsigned(row.opt("offset"), Long.MAX_VALUE);
                Long stateOrd = FileTransferProtocol.parseUnsigned(row.opt("state"), State.CANCELLED.ordinal());
                Object uploadObj = row.opt("upload");
                Object dateObj = row.opt("date");
                if (id == null || !isValidUuid(id) || !FileTransferProtocol.isValidName(name)
                        || size == null || offset == null || offset > size
                        || stateOrd == null || !(uploadObj instanceof Boolean) || !(dateObj instanceof Number)) {
                    continue;
                }
                Record rec = new Record();
                rec.identifier = id;
                rec.name = name;
                rec.upload = (Boolean) uploadObj;
                rec.size = size;
                rec.offset = offset;
                rec.dateMs = ((Number) dateObj).longValue();
                rec.root = row.optString("root", "");
                rec.path = row.optString("path", "");
                rec.version = row.optString("version", "");
                String uploadId = row.optString("upload_id", "");
                rec.uploadIdentifier = uploadId.isEmpty() ? null : uploadId;
                rec.localFile = fileForTask(rec);
                State savedState = State.values()[stateOrd.intValue()];
                rec.state = (savedState == State.COMPLETED || savedState == State.CANCELLED) ? savedState : State.PAUSED;
                rec.needsStatus = true;
                if (!rec.upload && rec.state != State.COMPLETED) {
                    long diskLen = rec.localFile.isFile() ? rec.localFile.length() : 0L;
                    rec.offset = diskLen;
                    if (rec.offset > rec.size || (rec.offset > 0 && rec.version.isEmpty())) {
                        rec.offset = 0;
                    }
                }
                records.add(rec);
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isValidUuid(String id) {
        try {
            UUID.fromString(id);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void publish() {
        List<Task> snapshots = new ArrayList<>(records.size());
        JSONArray saved = new JSONArray();
        for (Record rec : records) {
            snapshots.add(rec.snapshot());
            try {
                JSONObject row = new JSONObject();
                row.put("id", rec.identifier);
                row.put("name", rec.name);
                row.put("upload", rec.upload);
                row.put("state", rec.state.ordinal());
                row.put("size", rec.size);
                row.put("offset", rec.offset);
                row.put("date", rec.dateMs);
                row.put("root", rec.root != null ? rec.root : "");
                row.put("path", rec.path != null ? rec.path : "");
                row.put("version", rec.version != null ? rec.version : "");
                row.put("upload_id", rec.uploadIdentifier != null ? rec.uploadIdentifier : "");
                saved.put(row);
            } catch (Exception ignored) {
            }
        }
        tasksSnapshot = Collections.unmodifiableList(snapshots);
        writeAtomically(new File(directory, "tasks.json"), saved.toString().getBytes(StandardCharsets.UTF_8));
        for (Listener l : listeners) {
            l.onFileTransferChanged(this);
        }
    }

    private void writeAtomically(File target, byte[] data) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(target)) {
            // Fallback on filesystems where rename over existing fails
            target.delete();
            tmp.renameTo(target);
        }
    }

    public void refreshCapabilities(final Runnable completion) {
        queue.execute(() -> {
            if (completion != null) {
                probeCompletions.add(completion);
            }
            if (probing) {
                return;
            }
            probing = true;
            transport.perform("GET", "", Collections.emptyMap(), null, null, response -> queue.execute(() -> {
                FileTransferException failure = FileTransferProtocol.parseResponseError(
                        response.body, response.statusCode, response.ioError);
                Capabilities parsed = failure == null ? Capabilities.parse(response.body) : null;
                capabilityError = failure != null ? failure : (parsed != null ? null : new FileTransferException("not_supported", 0));
                if (parsed != null) {
                    capabilities = parsed;
                }
                capabilitiesProbed = true;
                probing = false;
                List<Runnable> callbacks = new ArrayList<>(probeCompletions);
                probeCompletions.clear();
                publish();
                schedule();
                for (Runnable cb : callbacks) {
                    cb.run();
                }
            }));
        });
    }

    public void listRoot(String root, String path, Integer offset, final ListCallback callback) {
        Map<String, String> query = new HashMap<>();
        query.put("root", root != null ? root : "");
        query.put("path", path != null ? path : "");
        if (offset != null) {
            query.put("offset", Integer.toString(offset));
        }
        transport.perform("GET", "/list", query, null, null, response -> {
            FileTransferException failure = FileTransferProtocol.parseResponseError(
                    response.body, response.statusCode, response.ioError);
            Listing listing = failure == null ? Listing.parse(response.body) : null;
            if (failure == null && listing == null) {
                failure = new FileTransferException("invalid_response", 0);
            }
            if (callback != null) {
                callback.onResult(listing, failure);
            }
        });
    }

    private Record newRecord(String name, boolean upload) {
        Record r = new Record();
        r.identifier = UUID.randomUUID().toString();
        r.name = name;
        r.upload = upload;
        r.dateMs = System.currentTimeMillis();
        r.localFile = fileForTask(r);
        records.add(r);
        return r;
    }

    public void enqueueUploadFile(final File sourceFile, final String preferredName, final UploadEnqueuedCallback callback) {
        String filename = FileTransferProtocol.isValidName(preferredName)
                ? preferredName
                : (sourceFile != null ? sourceFile.getName() : null);
        enqueueUploadStream(() -> new FileInputStream(sourceFile), filename, callback);
    }

    public void enqueueUploadStream(final SourceOpener opener, final String filename, final UploadEnqueuedCallback callback) {
        if (!FileTransferProtocol.isValidName(filename)) {
            if (callback != null) {
                callback.onComplete(new FileTransferException("invalid_path", 0));
            }
            return;
        }
        queue.execute(() -> {
            final Record rec = newRecord(filename, true);
            rec.state = State.PREPARING;
            publish();
            final long maxBytes = capabilities != null ? capabilities.maxUploadBytes : FileTransferProtocol.MAX_UPLOAD_BYTES;
            ioPool.execute(() -> {
                FileTransferException copyError = null;
                long copiedSize = 0;
                File parent = rec.localFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (InputStream in = opener.open();
                     FileOutputStream out = new FileOutputStream(rec.localFile)) {
                    byte[] buf = new byte[32768];
                    int read;
                    while ((read = in.read(buf)) >= 0) {
                        if (read == 0) continue;
                        copiedSize += read;
                        if (copiedSize > maxBytes) {
                            copyError = new FileTransferException("file_too_large", 413);
                            break;
                        }
                        out.write(buf, 0, read);
                    }
                } catch (IOException e) {
                    copyError = new FileTransferException("local_file_unavailable", 0, false, e);
                }
                if (copyError != null) {
                    deleteRecursively(rec.localFile.getParentFile());
                }
                final FileTransferException finalErr = copyError;
                final long finalSize = copiedSize;
                queue.execute(() -> {
                    rec.size = finalSize;
                    if (rec.state != State.CANCELLED) {
                        rec.error = finalErr;
                        rec.state = finalErr != null ? State.FAILED : State.QUEUED;
                    } else {
                        deleteRecursively(rec.localFile.getParentFile());
                    }
                    publish();
                    schedule();
                    if (callback != null) {
                        callback.onComplete(finalErr);
                    }
                });
            });
        });
    }

    public void enqueueDownload(final Entry entry, final String root, final String path) {
        if (entry == null || entry.directory) {
            return;
        }
        queue.execute(() -> {
            Record rec = newRecord(entry.name, false);
            rec.root = root != null ? root : "";
            rec.path = path != null ? path : "";
            rec.version = entry.version != null ? entry.version : "";
            rec.size = entry.size;
            rec.state = State.QUEUED;
            publish();
            schedule();
        });
    }

    private Record findRecord(String identifier) {
        for (Record r : records) {
            if (r.identifier.equals(identifier)) {
                return r;
            }
        }
        return null;
    }

    public void pauseTask(final String identifier) {
        queue.execute(() -> {
            Record rec = findRecord(identifier);
            if (rec != null && (rec.state == State.RUNNING || rec.state == State.QUEUED)) {
                rec.state = State.PAUSED;
                rec.needsStatus = true;
                publish();
                schedule();
            }
        });
    }

    public void resumeTask(final String identifier) {
        queue.execute(() -> {
            Record rec = findRecord(identifier);
            if (rec == null || (rec.state != State.PAUSED && rec.state != State.FAILED)) {
                return;
            }
            if (rec.error != null && "file_changed".equals(rec.error.reason)) {
                rec.offset = 0;
                rec.version = "";
            }
            rec.error = null;
            rec.recoveries = 0;
            rec.needsStatus = true;
            rec.state = State.QUEUED;
            publish();
            schedule();
        });
    }

    public void cancelTask(final String identifier) {
        queue.execute(() -> {
            Record rec = findRecord(identifier);
            if (rec == null || rec.state == State.COMPLETED || rec.state == State.CANCELLED) {
                return;
            }
            rec.state = State.CANCELLED;
            deleteRecursively(rec.localFile.getParentFile());
            if (rec.uploadIdentifier != null && !rec.uploadIdentifier.isEmpty()) {
                cancelRemote(rec);
            }
            publish();
            schedule();
        });
    }

    private void cancelRemote(final Record rec) {
        Map<String, String> query = Collections.singletonMap("id", rec.uploadIdentifier);
        transport.perform("DELETE", "/uploads", query, null, null, response -> queue.execute(() -> {
            FileTransferException failure = FileTransferProtocol.parseResponseError(
                    response.body, response.statusCode, response.ioError);
            if (failure != null && "upload_already_complete".equals(failure.reason)) {
                rec.offset = rec.size;
                rec.state = State.COMPLETED;
                publish();
            }
        }));
    }

    public void clearHistory() {
        queue.execute(() -> {
            Iterator<Record> it = records.iterator();
            while (it.hasNext()) {
                Record r = it.next();
                if (r.state == State.COMPLETED || r.state == State.CANCELLED) {
                    it.remove();
                }
            }
            publish();
        });
    }

    private void schedule() {
        if (busy) {
            return;
        }
        if (capabilities == null) {
            for (Record rec : records) {
                if (rec.state == State.QUEUED) {
                    if (!probing) {
                        refreshCapabilities(null);
                    }
                    return;
                }
            }
            return;
        }
        for (Record rec : records) {
            if (rec.state == State.QUEUED) {
                rec.state = State.RUNNING;
                busy = true;
                publish();
                if (rec.upload) {
                    uploadStep(rec);
                } else {
                    downloadStep(rec);
                }
                return;
            }
        }
    }

    private void failRecord(Record rec, FileTransferException error) {
        if (rec.state != State.CANCELLED && rec.state != State.PAUSED) {
            rec.error = error;
            rec.state = State.FAILED;
        }
    }

    private interface StepHandler {
        void handle(ResponseData response, FileTransferException error);
    }

    private void requestRecord(final Record rec, String method, String route, Map<String, String> query,
                               byte[] body, String contentType, final StepHandler handler) {
        transport.perform(method, route, query, body, contentType, response -> queue.execute(() -> {
            FileTransferException failure = FileTransferProtocol.parseResponseError(
                    response.body, response.statusCode, response.ioError);
            handler.handle(response, failure);
            busy = false;
            if (rec.state == State.RUNNING) {
                rec.state = State.QUEUED;
            }
            publish();
            schedule();
        }));
    }

    private void uploadStep(final Record rec) {
        if (!capabilities.upload) {
            failRecord(rec, new FileTransferException("upload_disabled", 403));
            busy = false;
            publish();
            schedule();
            return;
        }

        final String method;
        final String route;
        Map<String, String> query = new HashMap<>();
        byte[] body = null;
        String contentType = null;
        final boolean querying = rec.uploadIdentifier != null && !rec.uploadIdentifier.isEmpty() && rec.needsStatus;

        if (rec.uploadIdentifier == null || rec.uploadIdentifier.isEmpty()) {
            method = "POST";
            route = "/uploads";
            contentType = "application/json";
            try {
                JSONObject initBody = new JSONObject();
                initBody.put("name", rec.name);
                initBody.put("size", rec.size);
                body = initBody.toString().getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                failRecord(rec, new FileTransferException("request_failed", 0));
                busy = false;
                publish();
                schedule();
                return;
            }
        } else {
            query.put("id", rec.uploadIdentifier);
            if (querying) {
                method = "GET";
                route = "/uploads";
            } else if (rec.offset == rec.size) {
                method = "POST";
                route = "/uploads/complete";
            } else {
                method = "PUT";
                route = "/uploads";
                query.put("offset", Long.toString(rec.offset));
                contentType = "application/octet-stream";
                int toRead = (int) Math.min(capabilities.uploadChunkBytes, rec.size - rec.offset);
                body = readChunk(rec.localFile, rec.offset, toRead);
                if (body == null || body.length == 0) {
                    failRecord(rec, new FileTransferException("local_file_unavailable", 0));
                    busy = false;
                    publish();
                    schedule();
                    return;
                }
            }
        }

        final long expectedOffset = rec.offset + ("PUT".equals(method) && body != null ? body.length : 0L);
        requestRecord(rec, method, route, query, body, contentType, (response, error) -> {
            if (error != null) {
                if (querying && error.httpStatus == 404) {
                    rec.uploadIdentifier = null;
                    rec.offset = 0;
                    rec.needsStatus = false;
                    return;
                }
                if (rec.uploadIdentifier != null && !rec.uploadIdentifier.isEmpty() && !querying
                        && ("offset_mismatch".equals(error.reason) || error.networkError)
                        && rec.recoveries++ < 2) {
                    rec.needsStatus = true;
                    return;
                }
                failRecord(rec, error);
                return;
            }
            UploadStatus status = UploadStatus.parse(response.body);
            if (status == null
                    || !status.name.equals(rec.name)
                    || status.size != rec.size
                    || ("/uploads/complete".equals(route) && !status.complete)
                    || (rec.uploadIdentifier != null && !rec.uploadIdentifier.isEmpty() && !rec.uploadIdentifier.equals(status.identifier))
                    || ("PUT".equals(method) && status.offset != expectedOffset)) {
                failRecord(rec, new FileTransferException("invalid_response", 0));
                return;
            }
            rec.uploadIdentifier = status.identifier;
            rec.offset = status.offset;
            rec.needsStatus = false;
            if (status.complete) {
                rec.state = State.COMPLETED;
                rec.dateMs = System.currentTimeMillis();
                deleteRecursively(rec.localFile.getParentFile());
            } else if (rec.state == State.CANCELLED) {
                cancelRemote(rec);
            }
        });
    }

    private void downloadStep(final Record rec) {
        Map<String, String> query = new HashMap<>();
        query.put("root", rec.root);
        query.put("path", rec.path);
        query.put("offset", Long.toString(rec.offset));
        query.put("length", Integer.toString(capabilities.downloadChunkBytes));
        if (rec.version != null && !rec.version.isEmpty()) {
            query.put("version", rec.version);
        }
        requestRecord(rec, "GET", "/download", query, null, null, (response, error) -> {
            if (rec.state == State.CANCELLED) {
                return;
            }
            if (error != null) {
                failRecord(rec, error);
                return;
            }
            Long parsedSize = parseHeaderUnsignedLong(response.fileSizeHeader);
            String version = response.fileVersionHeader;
            String mime = stripMimeParams(response.contentType);
            byte[] data = response.body != null ? response.body : new byte[0];

            boolean hasVersion = rec.version != null && !rec.version.isEmpty();
            if (parsedSize == null || rec.offset > parsedSize
                    || version == null || version.isEmpty()
                    || !"application/octet-stream".equalsIgnoreCase(mime)
                    || (hasVersion && rec.size != parsedSize)
                    || data.length > capabilities.downloadChunkBytes
                    || data.length > parsedSize - rec.offset
                    || (hasVersion && !version.equals(rec.version))
                    || (data.length == 0 && rec.offset < parsedSize)) {
                failRecord(rec, new FileTransferException("invalid_response", 0));
                return;
            }

            if (!writeChunk(rec.localFile, rec.offset, data, rec.offset + data.length == parsedSize ? parsedSize : -1L)) {
                failRecord(rec, new FileTransferException("file_io_error", 0));
                return;
            }
            rec.size = parsedSize;
            rec.offset += data.length;
            rec.version = version;
            if (rec.offset == parsedSize) {
                rec.state = State.COMPLETED;
                rec.dateMs = System.currentTimeMillis();
            }
        });
    }

    private static Long parseHeaderUnsignedLong(String text) {
        if (text == null || text.isEmpty()) return null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        try {
            long val = Long.parseLong(text);
            return val >= 0 ? val : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripMimeParams(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    }

    private static byte[] readChunk(File file, long offset, int length) {
        if (file == null || !file.isFile() || length <= 0) return null;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            byte[] buf = new byte[length];
            int total = 0;
            while (total < length) {
                int r = raf.read(buf, total, length - total);
                if (r < 0) break;
                total += r;
            }
            if (total == length) return buf;
            byte[] trimmed = new byte[total];
            System.arraycopy(buf, 0, trimmed, 0, total);
            return trimmed;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean writeChunk(File file, long offset, byte[] data, long truncateToSize) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(offset);
                if (data.length > 0) {
                    raf.write(data);
                }
                if (truncateToSize >= 0) {
                    raf.setLength(truncateToSize);
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) >= 0) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    private static void deleteRecursively(File target) {
        if (target == null || !target.exists()) return;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        target.delete();
    }

    public void shutdown() {
        queue.shutdown();
        ioPool.shutdown();
    }
}
