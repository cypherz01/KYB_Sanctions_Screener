---
status: accepted
date: 2026-11-09
---

# Reverse delta screening

When a sanctions import arrives, the obvious way to find who it affects is to re-screen every monitored subject, which costs the same however little the list changed. Instead, the two dataset versions are compared by `external_id`, and only the entities that changed are matched against an index of monitored subjects. The work then scales with the size of the change rather than the number of subscriptions. A hit only triggers a full, ordinary check, so reverse screening decides who to look at, never what the answer is.

## How it stays honest

An entity counts as changed if any field screening reads differs: names, birth dates, nationalities, countries, topics or schema. All names of a changed entity are re-scored, not only the ones that changed. Forward screening keeps the best name per entity and adjusts the score using birth date and nationality, so a new birth date changes the score without changing any name.

It uses the same normaliser and matcher, and the same orientation of subject compared with listed name, so asymmetric rules such as "a one-word listed person alias is discarded" still apply. It also uses the same retrieval rule as forward screening: trigram threshold or exact phonetic key.

Subscriptions holding a match on a removed or changed entity are re-checked as well, which is what raises "match cleared".

CI holds this to account: against the fixture datasets, reverse screening must raise exactly the same set of new matches as a full forward re-screen.

## When it is not used

A full re-screen is forced when:

- the engine version or the matcher configuration changes, detected by a stored fingerprint, because the same list could then give different answers. The fingerprint hashes `kyb.matching.*`, which the engine version does not capture.
- the version last screened has been pruned, because a worker missed more imports than `keep-versions`, so there is nothing to compare against.

## A known limit

Forward retrieval is capped at 50 candidates per subject, so the two can differ for a very common name:

- A reverse hit beyond the cap costs one extra check, which finds nothing and raises no alert. This is harmless.
- A removal can free a slot and let an *unchanged* entity into the top 50, and reverse screening cannot see that. The 30-day re-check catches it.

So the equivalence holds below the cap, which is what the test shows. This record does not claim it in general.

## Considered Options

- **Re-screening every subject on each import.** Rejected, as above. Run nightly against the full dataset, it would spend most of its time confirming nothing changed.
- **Comparing file checksums or raw JSON per entity.** Rejected. A change to a source list or a caption would count as a change, and trigger checks that cannot alter a result.
- **Using the reverse match as the answer, and raising an alert from it directly.** Rejected. It would be a second implementation of screening, which could drift from the first, and it would not have the audit trail or replay a decision needs.

## Consequences

- OpenSanctions can merge or re-identify an entity, which shows here as one removal and one addition. The full check that follows compares by entity id, so it may report a match as changed when only its id did. That is a noisy alert rather than a missed one.
- The delta compares every entity in two versions, so that one query still scales with the dataset, at about 52,000 entities today. Only the matching after it scales with the change.
