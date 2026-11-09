package com.cypherz01.kyb.monitoring;

import com.cypherz01.kyb.check.CheckCompletedListener;
import com.cypherz01.kyb.check.CheckOrigin;
import com.cypherz01.kyb.check.CheckRepository;
import com.cypherz01.kyb.check.CheckResponse;
import com.cypherz01.kyb.check.Subject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compares every check of a subscribed company with the one before it, whatever asked for it, and moves
 * the subscription on. It runs inside the check's transaction, so a check, its alert and its webhook are
 * stored together or not at all (ADR-0006).
 */
public class MonitoringCheckListener implements CheckCompletedListener {

    private final MonitoringRepository repository;
    private final CheckRepository checks;
    private final MonitoringProperties props;
    private final ObjectMapper json;
    private final Clock clock;

    public MonitoringCheckListener(
            MonitoringRepository repository,
            CheckRepository checks,
            MonitoringProperties props,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.checks = checks;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void onCheckCompleted(CheckResponse response, List<Subject> subjects, CheckOrigin origin) {
        repository.lockActive(response.companyNumber()).ifPresent(subscription -> {
            JsonNode current = json.valueToTree(response);
            AlertRules.compare(read(subscription.latestCheckId()), current)
                    .ifPresent(change -> record(change, subscription, response, origin));
            Instant now = clock.instant();
            repository.refresh(
                    subscription.subscriptionId(),
                    response.checkId(),
                    now.plus(props.recheckInterval()),
                    subjects,
                    AlertRules.matchedEntities(current));
        });
    }

    private void record(AlertChange change, Subscription subscription, CheckResponse response, CheckOrigin origin) {
        Alert alert = new Alert(
                UUID.randomUUID(),
                subscription.subscriptionId(),
                subscription.companyNumber(),
                response.checkId(),
                subscription.latestCheckId(),
                change.type().name(),
                origin.trigger(),
                change.decisionBefore(),
                change.decisionAfter(),
                json.valueToTree(change.added()),
                json.valueToTree(change.removed()),
                clock.instant());
        repository.insertAlert(alert);
        if (props.webhook().configured()) {
            repository.insertOutbox(alert.alertId(), webhookBody(alert, change), alert.occurredAt());
        }
    }

    /** IDs and decisions only. Names of people stay behind the API key (ADR-0006). */
    private String webhookBody(Alert alert, AlertChange change) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", alert.alertId());
        body.put("type", alert.type());
        body.put("companyNumber", alert.companyNumber());
        body.put("checkId", alert.checkId());
        body.put("previousCheckId", alert.previousCheckId());
        body.put("decisionBefore", alert.decisionBefore());
        body.put("decisionAfter", alert.decisionAfter());
        body.put("entitiesAdded", ids(change.added()));
        body.put("entitiesRemoved", ids(change.removed()));
        body.put("occurredAt", alert.occurredAt());
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Webhook body not serialisable", e);
        }
    }

    private ArrayNode ids(List<AlertChange.MatchKey> keys) {
        ArrayNode ids = json.createArrayNode();
        keys.stream().map(AlertChange.MatchKey::sanctionsEntityId).distinct().forEach(ids::add);
        return ids;
    }

    private JsonNode read(UUID checkId) {
        try {
            return json.readTree(checks.findResponse(checkId).orElseThrow());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored check " + checkId + " is not valid JSON", e);
        }
    }
}
