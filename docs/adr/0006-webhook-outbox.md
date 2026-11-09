---
status: accepted
date: 2026-11-09
---

# Webhook outbox

An alert nobody hears about does little, so alerts are also pushed to a webhook. Sending from inside the transaction would make a slow receiver hold up the database, and sending after it would lose the webhook if the process died in between. So the webhook is written to an outbox table in the same transaction as the alert, and a background worker delivers it. An alert and its delivery therefore exist together or not at all, with no new infrastructure.

## Delivery

- **At least once.** A receiver can see a webhook twice, and uses the event ID, which is the alert's ID, to ignore a repeat.
- **Signed.** The signature is an HMAC-SHA256 over the timestamp and the body, sent in `X-KYB-Signature`, with the timestamp in `X-KYB-Timestamp`. Covering the timestamp lets a receiver refuse an old message replayed later. A URL with no secret stops the service starting, because an unsigned webhook cannot be told apart from a forged one.
- **Retried with backoff,** using the existing `BackoffPolicy`, for up to 10 attempts. The row is then marked failed and counted in a metric. The alert stays listed through the API.
- **Stored as sent.** The body is written once, so every retry sends the same bytes.
- **No names of people.** The body holds IDs, the decisions and the entity IDs, and the receiver fetches the detail through the API. Personal data then stays in one place, behind the API key.

With no `KYB_WEBHOOK_URL` set, alerts are still recorded and listed, and nothing is queued.

## Workers

Workers claim rows with `SELECT … FOR UPDATE SKIP LOCKED`, so several replicas can run without delivering a webhook twice or running a re-check twice. A sanctions hit that arrives while that company's re-check is running waits for it and queues another, as the running one may have used an older list. Locks are always taken job first and then subscription, so stopping a subscription mid-check waits rather than deadlocking. This covers the monitoring workers only. `SanctionsImportService.recoverInterruptedImports` still assumes a single instance, as it says, because deployment is local-only.

## Considered Options

- **A URL per subscription.** Rejected. There is one tenant, so there is nobody to route between. Accepting URLs through the API would also open server-side request forgery: anyone with the key could make the service call internal addresses. A single URL set in configuration avoids both.
- **Kafka or another broker.** Rejected. The constraint is no new infrastructure, and Postgres already gives a transactional outbox for a volume this low.
- **Calling the webhook inside the transaction.** Rejected, as above.
- **Listing alerts through the API only.** Rejected as the only route, as it leaves every client to poll. The API listing remains the record, and the webhook is how a client finds out.

## Consequences

- A row lock is held while one delivery is in flight. The 5-second timeout bounds that, and a low volume makes it cheap.
- The worker runs on a schedule, so a webhook can arrive up to one pass after its alert.
