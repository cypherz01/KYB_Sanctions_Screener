package com.cypherz01.kyb.api;

import com.cypherz01.kyb.api.ScreeningDtos.DatasetRef;
import com.cypherz01.kyb.api.ScreeningDtos.ScreenNameRequest;
import com.cypherz01.kyb.api.ScreeningDtos.ScreenNameResponse;
import com.cypherz01.kyb.matching.InvalidScreeningRequestException;
import com.cypherz01.kyb.matching.SanctionsDataUnavailableException;
import com.cypherz01.kyb.matching.ScreeningRequest;
import com.cypherz01.kyb.matching.ScreeningResult;
import com.cypherz01.kyb.matching.ScreeningService;
import com.cypherz01.kyb.matching.SubjectType;
import com.cypherz01.kyb.sanctions.SanctionsAttribution;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Ad-hoc screening of one name (spec §7). The {@code X-Api-Key} check is added in M5. */
@RestController
public class ScreeningController {

    private final ScreeningService screening;

    public ScreeningController(ScreeningService screening) {
        this.screening = screening;
    }

    @PostMapping("/api/v1/screen/name")
    public ScreenNameResponse screen(@RequestBody ScreenNameRequest body) {
        ScreeningResult r = screening.screen(
                new ScreeningRequest(body.name(), type(body.type()), body.birthDate(), body.country()));
        return new ScreenNameResponse(
                r.query(),
                r.type().name(),
                r.normalisedQuery(),
                new DatasetRef(r.datasetVersionId(), r.datasetVersionLabel()),
                r.candidatesConsidered(),
                r.matches(),
                SanctionsAttribution.OPENSANCTIONS);
    }

    private static SubjectType type(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return SubjectType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidScreeningRequestException("type must be PERSON, ORGANISATION or ANY");
        }
    }

    @ExceptionHandler(InvalidScreeningRequestException.class)
    ProblemDetail invalid(InvalidScreeningRequestException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setTitle("Invalid screening request");
        return p;
    }

    /** 503, not an empty 200: "no matches" against no data would read as a clean result. */
    @ExceptionHandler(SanctionsDataUnavailableException.class)
    ProblemDetail unavailable(SanctionsDataUnavailableException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        p.setTitle("Sanctions data unavailable");
        return p;
    }
}
