package com.cypherz01.kyb.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1: Caffeine (in-process, fast). L2: Postgres {@code cache_entry} (survives restarts, shared by
 * replicas). Reads go L1, then L2 (promoting hits to L1). Writes go to both.
 */
public class TwoTierSourceCache implements SourceCache {

    public TwoTierSourceCache(JdbcTemplate jdbc, ObjectMapper objectMapper, long maxLocalEntries) {}

    @Override
    public Optional<CacheEntry> find(String key) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void put(CacheEntry entry) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void clearLocal() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void clearAll() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
