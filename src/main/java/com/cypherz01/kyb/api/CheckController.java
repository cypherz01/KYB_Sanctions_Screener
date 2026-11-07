package com.cypherz01.kyb.api;

import com.cypherz01.kyb.api.CheckDtos.CreateCheckRequest;
import com.cypherz01.kyb.check.CheckResponse;
import com.cypherz01.kyb.check.CheckService;
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

    public CheckController(CheckService checks) {
        this.checks = checks;
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
}
