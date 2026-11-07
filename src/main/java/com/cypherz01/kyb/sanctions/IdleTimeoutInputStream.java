package com.cypherz01.kyb.sanctions;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Closes the underlying stream if no bytes arrive for {@code idle}, which unblocks a reader stuck
 * on a stalled connection. A long download that keeps making progress is never cut off. A total
 * request timeout would kill a healthy 380 MB transfer; this only stops one that has stalled.
 */
final class IdleTimeoutInputStream extends FilterInputStream {

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sanctions-download-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final Duration idle;
    private volatile long lastActivity = System.nanoTime();
    private volatile boolean timedOut;
    private volatile ScheduledFuture<?> check;

    IdleTimeoutInputStream(InputStream in, Duration idle) {
        super(in);
        this.idle = idle;
        long periodMs = Math.max(idle.toMillis() / 4, 10);
        this.check = WATCHDOG.scheduleAtFixedRate(this::closeIfIdle, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private void closeIfIdle() {
        if (System.nanoTime() - lastActivity > idle.toNanos()) {
            timedOut = true;
            stopWatching();
            try {
                in.close();
            } catch (IOException ignored) {
                // closing is the point; the reader sees the failure
            }
        }
    }

    @Override
    public int read() throws IOException {
        try {
            int b = super.read();
            lastActivity = System.nanoTime();
            return b;
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        try {
            int n = super.read(b, off, len);
            lastActivity = System.nanoTime();
            return n;
        } catch (IOException e) {
            throw translate(e);
        }
    }

    @Override
    public void close() throws IOException {
        stopWatching();
        super.close();
    }

    private void stopWatching() {
        ScheduledFuture<?> c = check;
        if (c != null) {
            c.cancel(false);
        }
    }

    private IOException translate(IOException e) {
        return timedOut ? new IOException("no data received for " + idle + " (download stalled)", e) : e;
    }
}
