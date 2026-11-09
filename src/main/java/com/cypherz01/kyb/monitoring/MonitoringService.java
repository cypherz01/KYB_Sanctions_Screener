package com.cypherz01.kyb.monitoring;

import com.cypherz01.kyb.check.CheckAuditService;
import com.cypherz01.kyb.check.CheckOrigin;
import com.cypherz01.kyb.check.CheckRepository;
import com.cypherz01.kyb.check.CheckService;
import com.cypherz01.kyb.check.Subject;
import com.cypherz01.kyb.companieshouse.CompanyNumber;
import com.cypherz01.kyb.sanctions.DatasetVersion;
import com.cypherz01.kyb.sanctions.SanctionsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/** Opting in and out, and reading alerts (ADR-0004). */
public class MonitoringService {

    /** @param created false if the company was already being watched */
    public record Subscribed(Subscription subscription, boolean created) {}

    private final MonitoringRepository repository;
    private final CheckService checkService;
    private final CheckAuditService audit;
    private final CheckRepository checks;
    private final SanctionsRepository datasets;
    private final MonitoringProperties props;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public MonitoringService(
            MonitoringRepository repository,
            CheckService checkService,
            CheckAuditService audit,
            CheckRepository checks,
            SanctionsRepository datasets,
            MonitoringProperties props,
            TransactionTemplate tx,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.checkService = checkService;
        this.audit = audit;
        this.checks = checks;
        this.datasets = datasets;
        this.props = props;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Watches the subjects of the company's latest check, running a new one if it has none, or if that
     * check used an older sanctions list.
     *
     * @throws com.cypherz01.kyb.companieshouse.InvalidCompanyNumberException 400
     * @throws com.cypherz01.kyb.companieshouse.CompanyNotFoundException 404
     */
    public Subscribed subscribe(String companyNumber) {
        String number = CompanyNumber.parse(companyNumber).value();
        Optional<Subscription> existing = repository.findActive(number);
        if (existing.isPresent()) {
            return new Subscribed(existing.get(), false);
        }

        UUID checkId =
                checks.findLatestId(number).filter(this::usedTheActiveDataset).orElse(null);
        List<Subject> subjects =
                checkId == null ? null : audit.subjectsOf(checkId, number).orElse(null);
        if (subjects == null) {
            checkId = checkService
                    .run(number, new CheckOrigin("SUBSCRIBE", null, null))
                    .checkId();
            subjects = audit.subjectsOf(checkId, number).orElseThrow();
        }

        Instant now = clock.instant();
        Subscription subscription =
                new Subscription(UUID.randomUUID(), number, now, checkId, now.plus(props.recheckInterval()));
        Set<String> matched = matchedBy(checkId);
        List<Subject> watched = subjects;
        try {
            tx.executeWithoutResult(status -> {
                repository.insertSubscription(subscription);
                repository.replaceIndex(subscription.subscriptionId(), watched, matched);
            });
        } catch (DuplicateKeyException e) {
            // Someone else subscribed in the meantime, which is the same outcome.
            return new Subscribed(repository.findActive(number).orElseThrow(), false);
        }
        return new Subscribed(subscription, true);
    }

    /** @throws SubscriptionNotFoundException the company is not being watched */
    public void unsubscribe(String companyNumber) {
        String number = CompanyNumber.parse(companyNumber).value();
        Subscription subscription =
                repository.findActive(number).orElseThrow(() -> new SubscriptionNotFoundException(number));
        tx.executeWithoutResult(status -> repository.end(subscription.subscriptionId(), clock.instant()));
    }

    /** @param companyNumber narrows the list, or null for every company */
    public List<Alert> alerts(String companyNumber) {
        return repository.alerts(
                companyNumber == null
                        ? null
                        : CompanyNumber.parse(companyNumber).value());
    }

    /**
     * A check on an older list cannot be the baseline. Later deltas start from now, so nothing would ever
     * report an entity listed between that check and this subscription.
     */
    private boolean usedTheActiveDataset(UUID checkId) {
        Optional<Long> active = datasets.findActive().map(DatasetVersion::id);
        return active.isPresent()
                && checks.find(checkId)
                        .map(check -> check.datasetVersionId() == active.get())
                        .orElse(false);
    }

    private Set<String> matchedBy(UUID checkId) {
        try {
            return AlertRules.matchedEntities(
                    json.readTree(checks.findResponse(checkId).orElseThrow()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored check " + checkId + " is not valid JSON", e);
        }
    }
}
