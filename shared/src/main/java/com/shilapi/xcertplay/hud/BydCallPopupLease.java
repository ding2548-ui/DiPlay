package com.shilapi.xcertplay.hud;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Package-only overlay lease. A missing heartbeat restores the original raw app-op mode. */
public final class BydCallPopupLease {
    public interface Ops {
        int query() throws Exception;
        void set(int mode) throws Exception;
    }

    private final Ops ops;
    private final LongSupplier clock;
    private final Consumer<String> report;
    private final long createdAt;
    private Integer original;
    private boolean started, owned, stopping, done;
    private long sequence, heartbeatAt, queryAt, retryAt;
    private int restoreAttempts;

    public BydCallPopupLease(Ops ops, LongSupplier clock, Consumer<String> report) {
        this.ops = ops;
        this.clock = clock;
        this.report = report;
        createdAt = clock.getAsLong();
    }

    public synchronized void accept(String line) {
        tick();
        if (done) return;
        if (line == null || !line.matches("[1-9][0-9]{0,14} (START|PING|STOP)")) {
            stop();
            return;
        }
        String[] parts = line.split(" ");
        long incoming = Long.parseLong(parts[0]);
        if (parts[1].equals("STOP")) {
            sequence = Math.max(sequence, incoming);
            stop();
            return;
        }
        if (incoming <= sequence) return; // An unchanged control file is not a heartbeat.
        sequence = incoming;
        if (parts[1].equals("START") && incoming == 1 && !started) start();
        else if (parts[1].equals("PING") && owned && !stopping) {
            heartbeatAt = clock.getAsLong();
            report.accept("HIDDEN");
        } else stop();
    }

    private void start() {
        started = true;
        try {
            original = ops.query();
            if (original < 0 || original > 4) throw new IllegalStateException();
        } catch (Exception error) {
            done = true;
            report.accept("ERROR QUERY_FAILED");
            return;
        }
        if (original == 1) {
            done = true;
            report.accept("ALREADY_HIDDEN");
            return;
        }
        owned = true; // A throwing Binder setter may still have changed the mode.
        try {
            ops.set(1); // MODE_IGNORED; never a UID-wide app-op.
            if (ops.query() != 1) throw new IllegalStateException();
            heartbeatAt = queryAt = clock.getAsLong();
            report.accept("HIDDEN");
        } catch (Exception error) {
            stop();
        }
    }

    public synchronized void tick() {
        if (done) return;
        long now = clock.getAsLong();
        if (stopping) {
            if (now >= retryAt) restore();
        } else if (!started && now - createdAt >= 10_000) stop();
        else if (owned) {
            if (now - heartbeatAt >= 10_000) stop();
            else if (now - queryAt >= 2_000) {
                queryAt = now;
                try {
                    if (ops.query() != 1) finish("EXTERNAL_CHANGE");
                } catch (Exception error) {
                    stop();
                }
            }
        }
    }

    public synchronized void stop() {
        if (done || stopping) return;
        if (!owned) {
            finish("CANCELLED");
            return;
        }
        stopping = true;
        restore();
    }

    private void restore() {
        restoreAttempts++;
        try {
            if (ops.query() != 1) {
                finish("EXTERNAL_CHANGE");
                return;
            }
            ops.set(original);
            if (ops.query() != original) throw new IllegalStateException();
            finish("RESTORED");
        } catch (Exception error) {
            if (restoreAttempts >= 4) finish("ERROR RESTORE_FAILED");
            else retryAt = clock.getAsLong() + (1L << (restoreAttempts - 1)) * 1_000;
        }
    }

    private void finish(String status) {
        owned = false;
        done = true;
        report.accept(status);
    }

    public synchronized boolean finished() { return done; }
    public synchronized long sequence() { return sequence; }
}
