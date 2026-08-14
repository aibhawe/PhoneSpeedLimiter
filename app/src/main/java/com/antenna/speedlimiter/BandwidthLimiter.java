package com.antenna.speedlimiter;

/**
 * Thread-safe aggregate byte-rate limiter.
 * A limit of 0 means unlimited. Rate is decimal kbps (1000 bits/s).
 */
final class BandwidthLimiter {
    private long bytesPerSecond;
    private double tokens;
    private long lastNanos;

    BandwidthLimiter(long kbps) {
        setKbps(kbps);
    }

    synchronized void setKbps(long kbps) {
        refill();
        bytesPerSecond = kbps <= 0 ? 0 : Math.max(1, (kbps * 1000L) / 8L);
        tokens = bytesPerSecond == 0 ? 0 : Math.min(tokens, bucketCapacity());
        if (lastNanos == 0) lastNanos = System.nanoTime();
        notifyAll();
    }

    synchronized long getKbps() {
        return bytesPerSecond == 0 ? 0 : (bytesPerSecond * 8L) / 1000L;
    }

    void acquire(int byteCount) throws InterruptedException {
        if (byteCount <= 0) return;
        int remaining = byteCount;
        while (remaining > 0) {
            int chunk;
            synchronized (this) {
                if (bytesPerSecond == 0) return;
                chunk = (int) Math.min(remaining, Math.max(1L, bucketCapacity()));
                while (true) {
                    refill();
                    if (bytesPerSecond == 0) return;
                    if (tokens >= chunk) {
                        tokens -= chunk;
                        break;
                    }
                    double missing = chunk - tokens;
                    long waitNanos = (long) Math.ceil((missing / bytesPerSecond) * 1_000_000_000.0);
                    long waitMillis = waitNanos / 1_000_000L;
                    int waitExtraNanos = (int) (waitNanos % 1_000_000L);
                    wait(Math.max(0L, waitMillis), Math.max(0, waitExtraNanos));
                }
            }
            remaining -= chunk;
        }
    }

    private long bucketCapacity() {
        if (bytesPerSecond == 0) return Long.MAX_VALUE;
        // Up to 250 ms burst, but never smaller than 16 KiB.
        return Math.max(16 * 1024L, bytesPerSecond / 4L);
    }

    private void refill() {
        long now = System.nanoTime();
        if (lastNanos == 0) {
            lastNanos = now;
            if (bytesPerSecond > 0) tokens = bucketCapacity();
            return;
        }
        if (bytesPerSecond > 0) {
            long elapsed = now - lastNanos;
            if (elapsed > 0) {
                tokens = Math.min(bucketCapacity(), tokens + (elapsed / 1_000_000_000.0) * bytesPerSecond);
            }
        }
        lastNanos = now;
    }
}
