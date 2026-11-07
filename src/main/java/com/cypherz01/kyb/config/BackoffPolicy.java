package com.cypherz01.kyb.config;

import java.time.Duration;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * Decides how long to wait before retry attempt {@code n}.
 *
 * <ul>
 *   <li>If the upstream gave a hint (Retry-After / rate-limit reset), use it, capped at {@code max}.
 *   <li>Otherwise exponential backoff ({@code base * 2^(n-1)}) with "equal jitter": half fixed, half
 *       random, so concurrent clients don't retry in lockstep. Capped at {@code max}.
 * </ul>
 *
 * The cap matters because a Companies House 429 lasts for the rest of its 5-minute window: waiting
 * minutes inside a synchronous request is worse than failing over to cached data.
 */
public class BackoffPolicy {

    /** 2^30 x any sane base delay already exceeds any sane cap; stops the shift overflowing. */
    private static final int MAX_EXPONENT = 30;

    private final long baseMillis;
    private final long maxMillis;
    private final DoubleSupplier random;

    public BackoffPolicy(Duration base, Duration max, DoubleSupplier random) {
        this.baseMillis = base.toMillis();
        this.maxMillis = max.toMillis();
        this.random = random;
    }

    /**
     * @param attempt 1-based number of the attempt that just failed
     * @param upstreamHint server-provided wait, if any
     */
    public Duration delayFor(int attempt, Optional<Duration> upstreamHint) {
        if (upstreamHint.isPresent()) {
            return Duration.ofMillis(Math.min(upstreamHint.get().toMillis(), maxMillis));
        }
        int exponent = Math.min(Math.max(attempt, 1) - 1, MAX_EXPONENT);
        long exponential = Math.min(saturatingShift(baseMillis, exponent), maxMillis);
        long fixedHalf = exponential / 2;
        double r = Math.clamp(random.getAsDouble(), 0.0, 1.0);
        return Duration.ofMillis(fixedHalf + Math.round((exponential - fixedHalf) * r));
    }

    private static long saturatingShift(long value, int exponent) {
        return value > (Long.MAX_VALUE >> exponent) ? Long.MAX_VALUE : value << exponent;
    }
}
