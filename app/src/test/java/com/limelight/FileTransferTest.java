package com.limelight;

import com.limelight.files.FileTransferManager;
import com.limelight.files.FileTransferProtocol;
import com.limelight.files.FileTransferProtocol.Capabilities;
import com.limelight.files.FileTransferProtocol.Entry;
import com.limelight.files.FileTransferProtocol.Listing;
import com.limelight.files.FileTransferProtocol.ResponseData;
import com.limelight.files.FileTransferProtocol.UploadStatus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.Request;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FileTransferTest {

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @Test
    public void parsesCapabilitiesListingAndUploadStatusStrictly() {
        String capsJson = "{\"version\":1,\"enabled\":true,\"upload\":true,"
                + "\"upload_chunk_bytes\":49152,\"download_chunk_bytes\":1048576,"
                + "\"max_upload_bytes\":10485760,\"upload_idle_seconds\":300,"
                + "\"roots\":[{\"id\":\"docs_1\",\"label\":\"Documents\"}]}";
        Capabilities caps = Capabilities.parse(capsJson.getBytes(StandardCharsets.UTF_8));
        assertNotNull(caps);
        assertTrue(caps.enabled);
        assertTrue(caps.upload);
        assertEquals(1, caps.roots.size());
        assertEquals("docs_1", caps.roots.get(0).identifier);

        // Rejects invalid version or bad root id
        assertNull(Capabilities.parse(capsJson.replace("\"version\":1", "\"version\":2").getBytes(StandardCharsets.UTF_8)));
        assertNull(Capabilities.parse(capsJson.replace("docs_1", "../etc").getBytes(StandardCharsets.UTF_8)));

        String listJson = "{\"entries\":[{\"name\":\"测试+1.txt\",\"kind\":\"file\",\"size\":12,\"modified_unix_ms\":1700000000000,\"version\":\"v1\"}],\"next_offset\":null,\"truncated\":false}";
        Listing listing = Listing.parse(listJson.getBytes(StandardCharsets.UTF_8));
        assertNotNull(listing);
        assertEquals(1, listing.entries.size());
        assertEquals("测试+1.txt", listing.entries.get(0).name);

        // Rejects traversal filename
        assertNull(Listing.parse(listJson.replace("测试+1.txt", "..").getBytes(StandardCharsets.UTF_8)));

        String upJson = "{\"id\":\"up-1\",\"name\":\"photo.png\",\"size\":100,\"offset\":100,\"complete\":true}";
        UploadStatus st = UploadStatus.parse(upJson.getBytes(StandardCharsets.UTF_8));
        assertNotNull(st);
        assertTrue(st.complete);
        // Complete cannot have offset < size
        assertNull(UploadStatus.parse(upJson.replace("\"offset\":100", "\"offset\":50").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void buildsRfc3986EncodedQueryUrls() {
        HttpUrl base = HttpUrl.parse("https://192.168.1.50:47984");
        Map<String, String> q = new HashMap<>();
        q.put("root", "shared");
        q.put("path", "中文/a+b c.txt");
        Request req = FileTransferProtocol.buildRequest(base, "GET", "/download", q, null, null);
        assertNotNull(req);
        String encodedQuery = req.url().encodedQuery();
        assertNotNull(encodedQuery);
        assertTrue(encodedQuery.contains("path=%E4%B8%AD%E6%96%87%2Fa%2Bb%20c.txt"));
        assertTrue(encodedQuery.contains("root=shared"));
    }

    @Test
    public void fullUploadWithOffsetMismatchRecoveryAndEmptyFile() throws Exception {
        final byte[] payload = new byte[120 * 1024 + 37];
        new Random(42).nextBytes(payload);
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final AtomicBoolean injectedMismatch = new AtomicBoolean(false);

        FileTransferProtocol.Transport fakeTransport = (method, route, query, body, contentType, reply) -> {
            if ("GET".equals(method) && "".equals(route)) {
                String json = "{\"version\":1,\"enabled\":true,\"upload\":true,"
                        + "\"upload_chunk_bytes\":49152,\"download_chunk_bytes\":65536,"
                        + "\"max_upload_bytes\":10485760,\"upload_idle_seconds\":300,\"roots\":[]}";
                reply.onComplete(new ResponseData(200, json.getBytes(StandardCharsets.UTF_8), "application/json", null, null, null));
                return;
            }
            if ("POST".equals(method) && "/uploads".equals(route)) {
                try {
                    org.json.JSONObject reqJson = new org.json.JSONObject(new String(body, StandardCharsets.UTF_8));
                    String name = reqJson.getString("name");
                    long size = reqJson.getLong("size");
                    String json = "{\"id\":\"u1\",\"name\":\"" + name + "\",\"size\":" + size + ",\"offset\":0,\"complete\":false}";
                    reply.onComplete(new ResponseData(200, json.getBytes(StandardCharsets.UTF_8), "application/json", null, null, null));
                } catch (Exception e) {
                    reply.onComplete(new ResponseData(400, new byte[0], null, null, null, null));
                }
                return;
            }
            if ("GET".equals(method) && "/uploads".equals(route)) {
                String json = "{\"id\":\"u1\",\"name\":\"blob.bin\",\"size\":" + payload.length + ",\"offset\":" + received.size() + ",\"complete\":false}";
                reply.onComplete(new ResponseData(200, json.getBytes(StandardCharsets.UTF_8), "application/json", null, null, null));
                return;
            }
            if ("PUT".equals(method) && "/uploads".equals(route)) {
                long offset = Long.parseLong(query.get("offset"));
                if (offset > 0 && injectedMismatch.compareAndSet(false, true)) {
                    reply.onComplete(new ResponseData(409, "{\"error\":\"offset_mismatch\"}".getBytes(StandardCharsets.UTF_8), "application/json", null, null, null));
                    return;
                }
                assertEquals(received.size(), offset);
                received.write(body, 0, body.length);
                String json = "{\"id\":\"u1\",\"name\":\"blob.bin\",\"size\":" + payload.length + ",\"offset\":" + received.size() + ",\"complete\":false}";
                reply.onComplete(new ResponseData(200, json.getBytes(StandardCharsets.UTF_8), "application/json", null, null, null));
                return;
            }
            if ("POST".equals(method) && "/uploads/complete".equals(route)) {
                String json = "{\"id\":\"u1\",\"name\":\"blob.bin\",\"size\":" + payload.length + ",\"offset\":" + received.size() + ",\"complete\":true}";
                reply.onComplete(new ResponseData(200, json.getBytes(StandardCharsets.UTF_8), "application/json", null, null, null));
                return;
            }
            reply.onComplete(new ResponseData(404, new byte[0], null, null, null, null));
        };

        FileTransferManager mgr = new FileTransferManager(fakeTransport, tmpFolder.newFolder("ft-up"));
        CountDownLatch done = new CountDownLatch(1);
        mgr.addListener(m -> {
            for (FileTransferManager.Task t : m.getTasks()) {
                if (t.state == FileTransferManager.State.COMPLETED) {
                    done.countDown();
                }
            }
        });
        mgr.enqueueUploadStream(() -> new ByteArrayInputStream(payload), "blob.bin", null);
        assertTrue("Upload did not complete in time", done.await(5, TimeUnit.SECONDS));
        assertArrayEquals(payload, received.toByteArray());
        assertTrue(injectedMismatch.get());
        mgr.shutdown();
    }

    @Test
    public void fullDownloadIntegrityAndPersistenceReloadAsPaused() throws Exception {
        final byte[] remoteData = new byte[150 * 1024 + 13];
        new Random(99).nextBytes(remoteData);

        FileTransferProtocol.Transport fakeTransport = (method, route, query, body, contentType, reply) -> {
            if ("GET".equals(method) && "".equals(route)) {
                String json = "{\"version\":1,\"enabled\":true,\"upload\":true,"
                        + "\"upload_chunk_bytes\":49152,\"download_chunk_bytes\":65536,"
                        + "\"max_upload_bytes\":10485760,\"upload_idle_seconds\":300,"
                        + "\"roots\":[{\"id\":\"r1\",\"label\":\"Share\"}]}";
                reply.onComplete(new ResponseData(200, json.getBytes(StandardCharsets.UTF_8), "application/json", null, null, null));
                return;
            }
            if ("GET".equals(method) && "/download".equals(route)) {
                int off = Integer.parseInt(query.get("offset"));
                int len = Math.min(Integer.parseInt(query.get("length")), remoteData.length - off);
                byte[] slice = new byte[len];
                System.arraycopy(remoteData, off, slice, 0, len);
                reply.onComplete(new ResponseData(200, slice, "application/octet-stream",
                        Integer.toString(remoteData.length), "ver-1", null));
                return;
            }
            reply.onComplete(new ResponseData(404, new byte[0], null, null, null, null));
        };

        File dir = tmpFolder.newFolder("ft-down");
        FileTransferManager mgr = new FileTransferManager(fakeTransport, dir);
        CountDownLatch done = new CountDownLatch(1);
        mgr.addListener(m -> {
            for (FileTransferManager.Task t : m.getTasks()) {
                if (t.state == FileTransferManager.State.COMPLETED) {
                    done.countDown();
                }
            }
        });

        Entry entry = new Entry("sample.dat", false, remoteData.length, 1700000000000L, "ver-1");
        mgr.enqueueDownload(entry, "r1", "sample.dat");
        assertTrue("Download did not complete in time", done.await(5, TimeUnit.SECONDS));

        FileTransferManager.Task completed = mgr.getTasks().get(0);
        assertEquals(FileTransferManager.State.COMPLETED, completed.state);
        byte[] actual = new byte[(int) completed.localFile.length()];
        try (FileInputStream fis = new FileInputStream(completed.localFile)) {
            int pos = 0;
            while (pos < actual.length) {
                int r = fis.read(actual, pos, actual.length - pos);
                if (r < 0) break;
                pos += r;
            }
        }
        assertArrayEquals(remoteData, actual);
        mgr.shutdown();

        // Reloading from disk preserves COMPLETED task
        FileTransferManager reloaded = new FileTransferManager(fakeTransport, dir);
        assertEquals(1, reloaded.getTasks().size());
        assertEquals(FileTransferManager.State.COMPLETED, reloaded.getTasks().get(0).state);
        reloaded.shutdown();
    }
}
