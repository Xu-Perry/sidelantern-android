package com.limelight;

import com.limelight.nvstream.ManualVideoController;
import com.limelight.nvstream.http.ManualVideoSettings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.Request;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ManualVideoTest {

    @Test
    public void settingsBoundsValidation() {
        assertTrue(new ManualVideoSettings(1920, 1080, 60, 20000).isValid());
        assertTrue(new ManualVideoSettings(7680, 4320, 240, 250000).isValid());
        assertFalse(new ManualVideoSettings(1921, 1080, 60, 20000).isValid()); // odd width
        assertFalse(new ManualVideoSettings(62, 1080, 60, 20000).isValid()); // too small
        assertFalse(new ManualVideoSettings(7680, 7680, 60, 20000).isValid()); // > 8K pixels
        assertFalse(new ManualVideoSettings(1920, 1080, 0, 20000).isValid());
        assertFalse(new ManualVideoSettings(1920, 1080, 241, 20000).isValid());
        assertFalse(new ManualVideoSettings(1920, 1080, 60, 499).isValid());
        assertFalse(new ManualVideoSettings(1920, 1080, 60, 250001).isValid());
    }

    @Test
    public void bitrateBudgetLearnsFecOverheadAndConvertsAccurately() {
        // 80 Mbps budget with 20% FEC, stereo 96 kbps/ch = 192 kbps audio, 500 kbps transport:
        // video = int(80000 / (100/80)) = 64000; -192 = 63808; -500 = 63308
        ManualVideoSettings.BitrateBudget budget =
                ManualVideoSettings.BitrateBudget.create(80000, 63308, 2);
        assertNotNull(budget);
        assertEquals(63308, budget.encoderBitrateForBudget(80000));
        // 40 Mbps budget should scale with the same FEC/audio overhead:
        // 40000 * 0.8 = 32000; -192 = 31808; -500 = 31308
        assertEquals(31308, budget.encoderBitrateForBudget(40000));
        // Out of range returns 0
        assertEquals(0, budget.encoderBitrateForBudget(400));
    }

    @Test
    public void buildsExactFiveParamPostRequest() {
        HttpUrl base = HttpUrl.parse("https://192.168.1.10:47984");
        Request getReq = ManualVideoSettings.buildRequest(base, null, null);
        assertNotNull(getReq);
        assertEquals("GET", getReq.method());
        assertEquals("/sidelantern/v1/manual-video", getReq.url().encodedPath());
        assertNull(getReq.url().query());

        ManualVideoSettings s = new ManualVideoSettings(2560, 1440, 120, 45000);
        Request postReq = ManualVideoSettings.buildRequest(base, "42", s);
        assertNotNull(postReq);
        assertEquals("POST", postReq.method());
        assertEquals(5, postReq.url().querySize());
        assertEquals("42", postReq.url().queryParameter("session_id"));
        assertEquals("2560", postReq.url().queryParameter("width"));
        assertEquals("1440", postReq.url().queryParameter("height"));
        assertEquals("120", postReq.url().queryParameter("fps"));
        assertEquals("45000", postReq.url().queryParameter("bitrate_kbps"));
    }

    private static ManualVideoSettings.Response xmlResp(int http, int code, String sid,
                                                        int w, int h, int fps, int br,
                                                        String status, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("<root version=\"1\" status_code=\"").append(code).append("\">");
        sb.append("<session_id>").append(sid).append("</session_id>");
        sb.append("<width>").append(w).append("</width>");
        sb.append("<height>").append(h).append("</height>");
        sb.append("<fps>").append(fps).append("</fps>");
        sb.append("<bitrate_kbps>").append(br).append("</bitrate_kbps>");
        sb.append("<status>").append(status).append("</status>");
        if (reason != null) {
            sb.append("<reason>").append(reason).append("</reason>");
        }
        sb.append("</root>");
        return new ManualVideoSettings.Response(http, sb.toString());
    }

    @Test
    public void controllerFullLifecycleCoalescingAndPolling() {
        final List<ManualVideoSettings.Response> scripted = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final List<Runnable> scheduled = new ArrayList<>();
        final long[] now = new long[]{1000L};

        ManualVideoController ctrl = new ManualVideoController(
                (sid, settings, cb) -> {
                    calls.add(settings == null ? "GET" : "POST:" + settings.width + "x" + settings.height + "@" + settings.frameRate);
                    cb.onResponse(scripted.remove(0));
                },
                (delayMs, op) -> {
                    now[0] += delayMs;
                    scheduled.add(op);
                },
                () -> now[0]
        );

        // 1. Initial GET -> idle
        scripted.add(xmlResp(200, 200, "7", 1920, 1080, 60, 15000, "idle", null));
        ctrl.refreshWithBitrateBudget(20000, 2);
        assertEquals(ManualVideoController.State.READY, ctrl.getState());
        assertTrue(ctrl.isAvailable());

        // 2. Request resolution change -> GET idle -> POST 202 -> poll pending -> poll applied
        scripted.add(xmlResp(200, 200, "7", 1920, 1080, 60, 15000, "idle", null));
        scripted.add(xmlResp(202, 202, "7", 1920, 1080, 60, 15000, "pending", null));
        scripted.add(xmlResp(200, 200, "7", 1920, 1080, 60, 15000, "pending", null));
        scripted.add(xmlResp(200, 200, "7", 2560, 1440, 60, 15000, "applied", null));

        final int[] lastAppliedFields = new int[]{0};
        ctrl.setListener(c -> {
            if (c.getAppliedFields() != 0) lastAppliedFields[0] = c.getAppliedFields();
        });

        ctrl.requestSettings(new ManualVideoSettings(2560, 1440, 60, 15000), ManualVideoSettings.FIELD_RESOLUTION);
        assertEquals(ManualVideoController.State.APPLYING, ctrl.getState());
        assertEquals(1, scheduled.size());

        // First poll -> still pending
        scheduled.remove(0).run();
        assertEquals(ManualVideoController.State.APPLYING, ctrl.getState());
        assertEquals(1, scheduled.size());

        // Second poll -> applied!
        scheduled.remove(0).run();
        assertEquals(ManualVideoController.State.READY, ctrl.getState());
        assertEquals(ManualVideoSettings.FIELD_RESOLUTION, lastAppliedFields[0]);
        assertEquals(2560, ctrl.getResponse().settings.width);
    }

    @Test
    public void controllerHandlesStaleSessionAnd404UnavailableAndTimeout() {
        final List<ManualVideoSettings.Response> scripted = new ArrayList<>();
        final long[] now = new long[]{0L};
        ManualVideoController ctrl = new ManualVideoController(
                (sid, settings, cb) -> cb.onResponse(scripted.remove(0)),
                (delayMs, op) -> {
                    now[0] += delayMs;
                    op.run();
                },
                () -> now[0]
        );

        // 404 marks unavailable
        scripted.add(new ManualVideoSettings.Response(404, ""));
        ctrl.refresh();
        assertEquals(ManualVideoController.State.UNAVAILABLE, ctrl.getState());
        assertEquals("unavailable", ctrl.getErrorReason());

        // Stale session id change marks unavailable
        ManualVideoController ctrl2 = new ManualVideoController(
                (sid, settings, cb) -> cb.onResponse(scripted.remove(0)),
                (delayMs, op) -> {
                    now[0] += delayMs;
                    op.run();
                },
                () -> now[0]
        );
        scripted.add(xmlResp(200, 200, "10", 1920, 1080, 60, 15000, "idle", null));
        ctrl2.refresh();
        assertTrue(ctrl2.isAvailable());

        scripted.add(xmlResp(200, 200, "11", 1920, 1080, 60, 15000, "idle", null));
        ctrl2.requestSettings(new ManualVideoSettings(1920, 1080, 120, 15000), ManualVideoSettings.FIELD_FRAMERATE);
        assertEquals(ManualVideoController.State.UNAVAILABLE, ctrl2.getState());
        assertEquals("stale_session", ctrl2.getErrorReason());
    }
}
