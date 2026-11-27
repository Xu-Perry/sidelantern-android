package com.limelight.files;

import android.content.Context;

import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OkHttp-backed Transport for SideLantern file transfer over the paired HTTPS
 * endpoint with strict server certificate pinning and redirect blocking.
 */
public class FileTransferClient implements FileTransferProtocol.Transport {
    private static final Map<String, Holder> MANAGERS = new HashMap<>();

    private static final class Holder {
        final FileTransferManager manager;
        final FileTransferClient client;

        Holder(FileTransferManager manager, FileTransferClient client) {
            this.manager = manager;
            this.client = client;
        }
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SideLantern-FileConnection");
        t.setDaemon(true);
        return t;
    });

    private ComputerDetails host;
    private ComputerDetails.AddressTuple cachedAddress;
    private int cachedHttpsPort;
    private X509Certificate cachedCert;
    private HttpUrl baseHttpsUrl;
    private OkHttpClient httpClient;

    public FileTransferClient(Context context, ComputerDetails host) {
        this.appContext = context.getApplicationContext();
        this.host = host;
    }

    public void updateHost(final ComputerDetails updatedHost) {
        executor.execute(() -> this.host = updatedHost);
    }

    @Override
    public void perform(final String method, final String route, final Map<String, String> query,
                        final byte[] body, final String contentType, final FileTransferProtocol.Reply reply) {
        executor.execute(() -> {
            ComputerDetails currentHost = this.host;
            if (currentHost == null || currentHost.activeAddress == null || currentHost.serverCert == null) {
                reply.onComplete(new FileTransferProtocol.ResponseData(
                        0, null, null, null, null,
                        new FileTransferProtocol.FileTransferException("connection_unavailable", 0)));
                return;
            }

            try {
                ensureClient(currentHost);
            } catch (IOException e) {
                reply.onComplete(new FileTransferProtocol.ResponseData(
                        0, null, null, null, null, e));
                return;
            }

            Request request = FileTransferProtocol.buildRequest(baseHttpsUrl, method, route, query, body, contentType);
            if (request == null) {
                reply.onComplete(new FileTransferProtocol.ResponseData(
                        0, null, null, null, null,
                        new FileTransferProtocol.FileTransferException("connection_unavailable", 0)));
                return;
            }

            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody respBody = response.body();
                byte[] bytes = respBody != null ? respBody.bytes() : new byte[0];
                reply.onComplete(new FileTransferProtocol.ResponseData(
                        response.code(),
                        bytes,
                        response.header("Content-Type"),
                        response.header("X-SideLantern-File-Size"),
                        response.header("X-SideLantern-File-Version"),
                        null));
            } catch (IOException e) {
                reply.onComplete(new FileTransferProtocol.ResponseData(0, null, null, null, null, e));
            }
        });
    }

    private void ensureClient(ComputerDetails currentHost) throws IOException {
        boolean same = httpClient != null && baseHttpsUrl != null
                && currentHost.activeAddress.equals(cachedAddress)
                && currentHost.httpsPort == cachedHttpsPort
                && currentHost.serverCert.equals(cachedCert);
        if (same) {
            return;
        }
        NvHTTP nvHttp = new NvHTTP(
                currentHost.activeAddress,
                currentHost.httpsPort,
                null,
                currentHost.serverCert,
                PlatformBinding.getCryptoProvider(appContext));
        baseHttpsUrl = nvHttp.getHttpsUrl(true);
        // Never follow redirects (including HTTP downgrades) on file transfer endpoints.
        httpClient = nvHttp.createPairedHttpsClient(15, false);
        cachedAddress = currentHost.activeAddress;
        cachedHttpsPort = currentHost.httpsPort;
        cachedCert = currentHost.serverCert;
    }

    public static FileTransferManager managerForHost(Context context, ComputerDetails host) {
        if (host == null || host.uuid == null || host.uuid.isEmpty()) {
            return null;
        }
        synchronized (MANAGERS) {
            Holder holder = MANAGERS.get(host.uuid);
            if (holder == null) {
                Context appCtx = context.getApplicationContext();
                File baseDir = new File(appCtx.getFilesDir(), "SideLanternTransfers");
                File hostDir = new File(baseDir, toHex(host.uuid));
                FileTransferClient client = new FileTransferClient(appCtx, host);
                FileTransferManager manager = new FileTransferManager(client, hostDir);
                holder = new Holder(manager, client);
                MANAGERS.put(host.uuid, holder);
            } else {
                holder.client.updateHost(host);
            }
            return holder.manager;
        }
    }

    public static String toHex(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        final char[] hex = "0123456789abcdef".toCharArray();
        for (byte b : bytes) {
            sb.append(hex[(b >>> 4) & 0x0F]);
            sb.append(hex[b & 0x0F]);
        }
        return sb.toString();
    }
}
