package com.cypherz01.kyb.api;

import com.cypherz01.kyb.monitoring.Alert;
import com.cypherz01.kyb.monitoring.MonitoringService;
import com.cypherz01.kyb.monitoring.Subscription;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ongoing monitoring: explicit opt-in, and the alerts it raises (ADR-0004). */
@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    public record SubscribeRequest(String companyNumber) {}

    private final MonitoringService monitoring;

    public MonitoringController(MonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @Operation(
            summary = "Start watching a company",
            description = "Watches the subjects of its latest check, running one if it has none. Returns 201, or 200"
                    + " if it is already being watched. Names are kept until the subscription stops.")
    @PostMapping("/subscriptions")
    public ResponseEntity<Subscription> subscribe(@RequestBody SubscribeRequest body) {
        MonitoringService.Subscribed result = monitoring.subscribe(body.companyNumber());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.subscription());
    }

    @Operation(
            summary = "Stop watching a company",
            description = "Deletes the names being watched. Alerts and checks stay, as they are the audit record.")
    @DeleteMapping("/subscriptions/{companyNumber}")
    public ResponseEntity<Void> unsubscribe(@PathVariable String companyNumber) {
        monitoring.unsubscribe(companyNumber);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List alerts, newest first", description = "Immutable events, with no status.")
    @GetMapping("/alerts")
    public List<Alert> alerts(@RequestParam(required = false) String companyNumber) {
        return monitoring.alerts(companyNumber);
    }
}
