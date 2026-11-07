package com.cypherz01.kyb.fca;

import com.cypherz01.kyb.fca.model.FcaFirmDetails;
import com.cypherz01.kyb.fca.model.FcaSearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * Maps the Register's JSON envelope ({@code Status}, {@code Message}, {@code ResultInfo}, {@code
 * Data}) to domain records. Field names have spaces ({@code "Reference Number"}), so they are read
 * by name rather than through bound DTOs. Unknown fields are ignored.
 */
public class FcaMapper {

    /** @return firm hits from a search envelope; empty when {@code Data} is missing or null */
    public List<FcaSearchHit> toSearchHits(JsonNode envelope) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    /** @return the first {@code Data} entry of a {@code /Firm/{FRN}} envelope, if any */
    public Optional<FcaFirmDetails> toFirmDetails(JsonNode envelope) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
