package com.cypherz01.kyb.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * L1: Caffeine (in-process, fast). L2: Postgres {@code cache_entry} (survives restarts, shared by
 * replicas). Reads go L1, then L2 (promoting hits to L1). Writes go to both.
 *
 * <p>Freshness is the caller's decision ({@link CacheEntry#isFresh}): both tiers hold expired
 * entries so they can serve as stale fallbacks during an outage.
 */
public class TwoTierSourceCache implements SourceCache {

    private static final String SELECT =
            "SELECT cache_key, source, payload::text AS payload, fetched_at, expires_at FROM cache_entry WHERE cache_key = ?";
    private static final String UPSERT =
            """
            INSERT INTO cache_entry (cache_key, source, payload, fetched_at, expires_at)
            VALUES (?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (cache_key) DO UPDATE
               SET source = EXCLUDED.source, payload = EXCLUDED.payload,
                   fetched_at = EXCLUDED.fetched_at, expires_at = EXCLUDED.expires_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Cache<String, CacheEntry> local;
    private final RowMapper<CacheEntry> rowMapper;

    public TwoTierSourceCache(JdbcTemplate jdbc, ObjectMapper objectMapper, long maxLocalEntries) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.local = Caffeine.newBuilder().maximumSize(maxLocalEntries).build();
        this.rowMapper = (rs, i) -> {
            try {
                return new CacheEntry(
                        rs.getString("cache_key"),
                        rs.getString("source"),
                        objectMapper.readTree(rs.getString("payload")),
                        rs.getObject("fetched_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Corrupt cache_entry payload for " + rs.getString("cache_key"), e);
            }
        };
    }

    @Override
    public Optional<CacheEntry> find(String key) {
        CacheEntry hit = local.getIfPresent(key);
        if (hit != null) {
            return Optional.of(hit);
        }
        Optional<CacheEntry> durable =
                jdbc.query(SELECT, rowMapper, key).stream().findFirst();
        durable.ifPresent(e -> local.put(key, e));
        return durable;
    }

    @Override
    public void put(CacheEntry entry) {
        try {
            jdbc.update(
                    UPSERT,
                    entry.key(),
                    entry.source(),
                    objectMapper.writeValueAsString(entry.payload()),
                    OffsetDateTime.ofInstant(entry.fetchedAt(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(entry.expiresAt(), ZoneOffset.UTC));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload not serialisable for " + entry.key(), e);
        }
        local.put(entry.key(), entry);
    }

    @Override
    public void clearLocal() {
        local.invalidateAll();
    }

    @Override
    public void clearAll() {
        local.invalidateAll();
        jdbc.update("DELETE FROM cache_entry");
    }
}
