package com.cypherz01.kyb.support;

import com.cypherz01.kyb.cache.CacheEntry;
import com.cypherz01.kyb.cache.SourceCache;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link SourceCache} for unit tests that need no database. Keeps expired entries, like L2. */
public class InMemorySourceCache implements SourceCache {

    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<CacheEntry> find(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public void put(CacheEntry entry) {
        entries.put(entry.key(), entry);
    }

    @Override
    public void clearLocal() {
        // single tier: nothing separate to clear
    }

    @Override
    public void clearAll() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
