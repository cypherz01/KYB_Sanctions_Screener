package com.cypherz01.kyb.api;

import com.cypherz01.kyb.api.SanctionsAdminDtos.ImportAccepted;
import com.cypherz01.kyb.api.SanctionsAdminDtos.StatusResponse;
import com.cypherz01.kyb.sanctions.SanctionsAttribution;
import com.cypherz01.kyb.sanctions.SanctionsImportService;
import com.cypherz01.kyb.sanctions.SanctionsStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Sanctions dataset administration (spec §7). The {@code X-Api-Key} check is added in M5. */
@RestController
@RequestMapping(SanctionsAdminController.BASE)
public class SanctionsAdminController {

    static final String BASE = "/api/v1/admin/sanctions";
    static final String STATUS = BASE + "/status";

    private final SanctionsImportService service;

    public SanctionsAdminController(SanctionsImportService service) {
        this.service = service;
    }

    /** Starts an import in the background. A full import takes minutes, too long to hold a request. */
    @PostMapping("/import")
    public ResponseEntity<ImportAccepted> startImport() {
        long versionId = service.startAsync();
        return ResponseEntity.accepted()
                .location(ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path(STATUS)
                        .build()
                        .toUri())
                .body(new ImportAccepted(versionId, STATUS));
    }

    @GetMapping("/status")
    public StatusResponse status() {
        SanctionsStatus s = service.status();
        return new StatusResponse(
                s.active(),
                s.lastImport(),
                s.importRunning(),
                s.activeEntitiesBySchema(),
                SanctionsAttribution.OPENSANCTIONS);
    }
}
