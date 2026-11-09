---
status: accepted
date: 2026-11-09
---

# Monitoring triggers and opt-in

A check is point-in-time, so a company that passed on Monday still shows `PASS` if its director is listed on Tuesday. A company is now watched after an explicit opt-in, and is re-checked when a sanctions import could affect it, with a full re-check every 30 days as a fallback. An alert is recorded only when the decision or the set of matches changes.

## Opt-in

Monitoring is a relationship, while a check is a single event, and it keeps personal data for as long as it runs: the names of officers and owners, held so they can be searched. UK GDPR's data minimisation points the same way, so nothing is watched unless someone asks for that company. Stopping deletes the index of names at once. Alerts and checks stay, because they are the append-only audit record, and they contain the names of anyone they concern.

A subscription watches the subjects of its company's latest check, including owners found by look-through, and is refreshed by every new check. If that check used an older sanctions list, subscribing runs a new one, as later deltas could never report an entity listed in between.

## Triggers

1. **Each sanctions import** that activates a new dataset. The trigger is a row written in the transaction that activates it, so a crash between the two cannot lose it.
2. **A periodic re-check, every 30 days by default.** This catches what the first trigger cannot see, above all a change at Companies House, such as a new director. It runs a limited number of re-checks per pass, which spreads them out and keeps them inside the Companies House budget.

A manual check of a subscribed company also compares against the previous check, and raises an alert if the outcome changed. Otherwise it would move the baseline and hide the change from monitoring.

A re-check that fails because Companies House is down stays queued and is tried again later. So does one that completes on stale cached data, which is stored and flagged but does not count as having looked again. Neither is ever treated as "no change".

## Alerts

A hit runs a full, ordinary check, linked to the subscription and the previous check, and so inherits the existing audit trail, snapshots and replay. Alerts have no status, because a lifecycle of open, assigned and closed would be the analyst case workflow, which ADR-0001 leaves out.

The match set compares subject, entity id and band across every match on a check, not only the best one. Similarity is left out, so a small score change raises nothing, while `POSSIBLE` becoming `STRONG` does.

## Considered Options

- **Monitoring every company ever checked.** Rejected. It keeps personal data about companies nobody asked to watch, and spends the Companies House budget on them.
- **Re-checking everyone on each import.** Rejected as the normal path, and kept only as the forced re-screen in ADR-0005. At 3 or more requests a company, it does not scale with subscriptions.
- **The Companies House Streaming API.** Designed and deferred. It would catch a new director within minutes rather than up to 30 days later. A consumer would hold a long-lived stream, record a resume point in Postgres, and enqueue a re-check for any event on a subscribed company, using the same job table. It is deferred because it needs a separate key and a long-running consumer, while the periodic re-check closes the gap at far lower cost.
- **An alert status, such as open and closed.** Rejected, as above.

## Consequences

- A change at Companies House can take up to 30 days to raise an alert, unless someone runs a check.
- Stored checks grow by one for each re-check, at about 33 kB of snapshots each, and are kept indefinitely.
