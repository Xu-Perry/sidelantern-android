package com.limelight.nvstream;

import com.limelight.nvstream.http.ManualVideoSettings;

/**
 * Serial state machine for SideLantern live manual-video adjustments.
 * Coalesces rapid changes, polls 202 pending states up to 40 times / 15s,
 * and rejects stale session IDs without reconnecting.
 */
public class ManualVideoController {
    public enum State {
        UNAVAILABLE,
        LOADING,
        READY,
        APPLYING,
        FAILED
    }

    public interface Callback {
        void onResponse(ManualVideoSettings.Response response);
    }

    public interface Transport {
        void execute(String sessionIdentifier, ManualVideoSettings settings, Callback callback);
    }

    public interface Scheduler {
        void schedule(long delayMs, Runnable operation);
    }

    public interface Clock {
        long nowMs();
    }

    public interface Listener {
        void onStateChanged(ManualVideoController controller);
    }

    private static final long DEADLINE_MS = 15_000L;
    private static final long POLL_INTERVAL_MS = 250L;
    private static final int MAX_POLLS = 40;

    private final Transport transport;
    private final Scheduler scheduler;
    private final Clock clock;

    private Listener listener;
    private State state = State.UNAVAILABLE;
    private ManualVideoSettings.Response response;
    private int appliedFields;
    private String errorReason;

    private String sessionIdentifier;
    private ManualVideoSettings queued;
    private int queuedFields;
    private ManualVideoSettings sent;
    private int sentFields;
    private int generation;
    private int polls;
    private boolean busy;
    private boolean cancelled;
    private int initialBitrateBudget;
    private int audioChannels;
    private ManualVideoSettings.BitrateBudget bitrateBudget;
    private long deadlineMs;

    public ManualVideoController(Transport transport, Scheduler scheduler, Clock clock) {
        this.transport = transport;
        this.scheduler = scheduler;
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    public boolean isAvailable() {
        return !cancelled && response != null && response.isValidSession() && state != State.UNAVAILABLE;
    }

    public ManualVideoSettings.Response getResponse() {
        return response;
    }

    public int getAppliedFields() {
        return appliedFields;
    }

    public String getErrorReason() {
        return errorReason;
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onStateChanged(this);
        }
        appliedFields = 0;
    }

    public synchronized void refresh() {
        if (cancelled || busy) {
            return;
        }
        busy = true;
        polls = 0;
        errorReason = null;
        state = State.LOADING;
        deadlineMs = clock.nowMs() + DEADLINE_MS;
        notifyChanged();
        query(generation);
    }

    public synchronized void refreshWithBitrateBudget(int bitrate, int audioChannels) {
        if (cancelled || busy) {
            return;
        }
        this.initialBitrateBudget = bitrate;
        this.audioChannels = audioChannels;
        this.bitrateBudget = null;
        refresh();
    }

    public synchronized void requestSettings(ManualVideoSettings settings, int fields, int bitrateBudgetKbps) {
        if (!isAvailable() || fields == 0 || settings == null) {
            return;
        }
        ManualVideoSettings effective = settings;
        if ((fields & ManualVideoSettings.FIELD_BITRATE) != 0) {
            int encoded = bitrateBudget != null ? bitrateBudget.encoderBitrateForBudget(bitrateBudgetKbps) : 0;
            if (encoded < 500 || encoded > 250000) {
                fail("invalid_video_settings", false);
                return;
            }
            effective = new ManualVideoSettings(settings.width, settings.height, settings.frameRate, encoded);
        }
        requestSettings(effective, fields);
    }

    public synchronized void requestSettings(ManualVideoSettings settings, int fields) {
        if (!isAvailable() || fields == 0 || settings == null) {
            return;
        }
        ManualVideoSettings base = response.settings;
        if (sent != null) {
            base = base.replacingFields(sentFields, sent);
        }
        if (queued != null) {
            base = base.replacingFields(queuedFields, queued);
        }
        ManualVideoSettings desired = base.replacingFields(fields, settings);
        if (desired.equals(base)) {
            return;
        }
        queued = desired;
        queuedFields |= fields;
        errorReason = null;
        if (!busy) {
            busy = true;
            polls = 0;
            deadlineMs = clock.nowMs() + DEADLINE_MS;
            state = State.APPLYING;
            notifyChanged();
            query(generation);
        }
    }

    public synchronized void cancel() {
        generation++;
        cancelled = true;
        busy = false;
        queued = null;
        sent = null;
        queuedFields = 0;
        sentFields = 0;
    }

    private void fail(String reason, boolean unavailable) {
        busy = false;
        queued = null;
        sent = null;
        queuedFields = 0;
        sentFields = 0;
        state = unavailable ? State.UNAVAILABLE : State.FAILED;
        errorReason = reason;
        notifyChanged();
    }

    private boolean acceptResponse(ManualVideoSettings.Response resp) {
        if (resp == null) {
            fail(null, false);
            return false;
        }
        if (resp.httpStatusCode == 404 || resp.httpStatusCode == 501) {
            fail("unavailable", true);
            return false;
        }
        if (resp.httpStatusCode != 0 && (resp.httpStatusCode < 200 || resp.httpStatusCode >= 300)) {
            fail(null, false);
            return false;
        }
        if (resp.statusCode == 404 || resp.statusCode == 501) {
            fail("unavailable", true);
            return false;
        }
        if (!resp.isValidSession()) {
            fail(null, false);
            return false;
        }
        if (sessionIdentifier != null && !sessionIdentifier.equals(resp.sessionIdentifier)) {
            fail("stale_session", true);
            return false;
        }
        sessionIdentifier = resp.sessionIdentifier;
        response = resp;
        if (bitrateBudget == null && initialBitrateBudget > 0 && resp.statusCode == 200) {
            bitrateBudget = ManualVideoSettings.BitrateBudget.create(
                    initialBitrateBudget, resp.settings.bitrate, audioChannels);
        }
        return true;
    }

    private void query(final int gen) {
        transport.execute(null, null, resp -> {
            synchronized (ManualVideoController.this) {
                if (cancelled || gen != generation || !acceptResponse(resp)) {
                    return;
                }
                if (resp.statusCode != 200) {
                    fail(resp.reason, "stale_session".equals(resp.reason));
                    return;
                }
                if ("pending".equals(resp.state)) {
                    poll(gen);
                    return;
                }
                if (sent != null) {
                    if ("rejected".equals(resp.state)) {
                        fail("encoder_rejected", false);
                        return;
                    }
                    if (!"applied".equals(resp.state) || !sent.equals(resp.settings)) {
                        poll(gen);
                        return;
                    }
                    appliedFields = sentFields;
                    sent = null;
                    sentFields = 0;
                    state = queuedFields != 0 ? State.APPLYING : State.READY;
                    notifyChanged();
                }
                if (queuedFields != 0) {
                    send(gen);
                } else {
                    busy = false;
                    state = State.READY;
                    notifyChanged();
                }
            }
        });
    }

    private void poll(final int gen) {
        if (++polls > MAX_POLLS || clock.nowMs() >= deadlineMs) {
            fail("unconfirmed", true);
            return;
        }
        state = State.APPLYING;
        notifyChanged();
        scheduler.schedule(POLL_INTERVAL_MS, () -> {
            synchronized (ManualVideoController.this) {
                if (!cancelled && gen == generation) {
                    query(gen);
                }
            }
        });
    }

    private void send(final int gen) {
        sent = response.settings.replacingFields(queuedFields, queued);
        sentFields = queuedFields;
        queued = null;
        queuedFields = 0;
        polls = 0;
        deadlineMs = clock.nowMs() + DEADLINE_MS;
        if (!sent.isValid()) {
            fail("invalid_video_settings", false);
            return;
        }
        state = State.APPLYING;
        notifyChanged();
        transport.execute(sessionIdentifier, sent, resp -> {
            synchronized (ManualVideoController.this) {
                if (cancelled || gen != generation || !acceptResponse(resp)) {
                    return;
                }
                if (resp.statusCode != 202) {
                    fail(resp.reason, "stale_session".equals(resp.reason));
                    return;
                }
                poll(gen);
            }
        });
    }
}
