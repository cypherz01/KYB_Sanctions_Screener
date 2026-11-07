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

    public BackoffPolicy(Duration base, Duration max, DoubleSupplier random) {}

    /**
     * @param attempt 1-based number of the attempt that just failed
     * @param upstreamHint server-provided wait, if any
     */
    public Duration delayFor(int attempt, Optional<Duration> upstreamHint) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
