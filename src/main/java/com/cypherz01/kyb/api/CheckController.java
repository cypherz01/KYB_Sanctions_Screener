package com.cypherz01.kyb.api;

import com.cypherz01.kyb.api.CheckDtos.CreateCheckRequest;
import com.cypherz01.kyb.check.AuditTrail;
import com.cypherz01.kyb.check.CheckAuditService;
import com.cypherz01.kyb.check.CheckResponse;
import com.cypherz01.kyb.check.CheckService;
import com.cypherz01.kyb.check.ReplayResult;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** KYB checks (spec §7): synchronous in v1, stored append-only. */
@RestController
@RequestMapping("/api/v1/checks")
public class CheckController {

    private final CheckService checks;
    private final CheckAuditService audit;

    public CheckController(CheckService checks, CheckAuditService audit) {
        this.checks = checks;
        this.audit = audit;
    }

    @Operation(
            summary = "Run a KYB check on a UK company",
            description = "Fetches Companies House data, "
                    + "checks the FCA Register, screens the company, officers and PSCs against OpenSanctions, and "
                    + "returns a scored, explained PASS / REVIEW / FAIL decision.")
    @PostMapping
    public ResponseEntity<CheckResponse> create(@RequestBody CreateCheckRequest body) {
        CheckResponse response = checks.run(body.companyNumber());
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.checkId())
                        .toUri())
                .body(response);
    }

    @Operation(summary = "Retrieve a stored check, exactly as it was returned")
    @GetMapping("/{checkId}")
    public JsonNode get(@PathVariable UUID checkId) {
        return checks.find(checkId);
    }

    @Operation(
            summary = "The full audit trail of a check",
            description = "What was asked, the raw data each source returned, the dataset, scoring and engine "
                    + "versions used, and every event since, replays included.")
    @GetMapping("/{checkId}/audit")
    public AuditTrail audit(@PathVariable UUID checkId) {
        return audit.trail(checkId);
    }

    @Operation(
            summary = "Re-run a check from what it stored",
            description = "Repeats normalisation, matching and scoring using the stored source data and the "
                    + "sanctions dataset version the check used, with no external call, and compares the whole "
                    + "result with the stored one. 409 if that dataset version has since been pruned.")
    @PostMapping("/{checkId}/replay")
    public ReplayResult replay(@PathVariable UUID checkId) {
        return audit.replay(checkId);
    }
}
