---
status: accepted
date: 2026-11-09
---

# Review of v1 and plan for v2

Milestones M1 to M7 were planned together and are now finished. This is the first decision recorded after using the finished system, and it asks one question: where would a v1 result mislead someone relying on it? There are two such places, a company's ultimate owners and the time after a check has run, so v2 adds two milestones. M8 follows ownership up the chain, and M9 keeps watching a company after its check. Decisions made in v1 stay in the README's "Design decisions" section and are not backfilled as ADRs.

## What v1 got right

The foundations did not need rework, and both milestones build directly on them:

- **One evaluator for live checks and replays.** `CheckEvaluator` takes all its inputs as arguments, so new evidence can be added without creating a second code path.
- **Raw source snapshots on every check.** Parent companies can be stored the same way, which keeps look-through replayable.
- **Versioned sanctions datasets.** Each import keeps its own rows and the newest three are retained, so two versions can be compared.
- **Stable entity ids.** `external_id` carries across versions, so that comparison gives a delta, and a match keeps its identity from one check to the next.
- **Append-only audit, enforced by the database.** A monitoring alert can link to an ordinary check with its full evidence, rather than needing its own record of why it fired.

## The gaps

**No ownership look-through.** v1 screens the company, its officers and its declared PSCs, then stops. A corporate PSC is screened by name, but nobody asks who owns it. A sanctioned person who holds a company through one UK holding company is therefore never screened, and the check can return `PASS`. That is the structure sanctions rules are written to catch: OFAC's 50% rule and the UK's "owned or controlled" test both look through intermediaries. v1 has also already parsed the data it would need, since `CompaniesHouseDtos` reads a corporate PSC's registration number and `CompaniesHouseMapper` then drops it.

**Decisions are one-shot.** A check is correct as of the moment it ran and never changes afterwards. If a director is listed on Tuesday, the nightly import loads the entry, but the company that passed on Monday still shows `PASS` and nobody is told. Replay does not help, because it deliberately reproduces the past. Real onboarding needs ongoing screening, and v1 can only offer "run it again and see".

## The plan

**M8: ownership look-through.** Follow UK-registered corporate PSCs recursively, within fixed limits, and screen each intermediate company and its PSCs. Work out each owner's effective ownership and control as a range, because Companies House publishes only bands, and base the decision on that range. A chain that cannot be followed to its end raises a factor of its own, so it is never read as clean.

**M9: ongoing monitoring.** A company is monitored only after an explicit opt-in. Each sanctions import compares the old and new versions and screens only what changed against the monitored subjects, with a periodic full re-check as a fallback. A hit runs a normal check, reusing the existing audit and replay. If the outcome changed, an immutable alert is recorded and delivered by webhook through a Postgres outbox.

**M8 comes first** because monitoring watches the subjects of a company's latest check. If M9 came first, it would watch an incomplete list and need revisiting.

Each milestone has a GitHub issue holding its spec, and a pull request with a red commit and then a green commit, as in v1. The decisions behind them are recorded alongside the work:

| ADR | Decision | Milestone |
|---|---|---|
| 0002 | Look-through scope | M8 |
| 0003 | Effective ownership and control | M8 |
| 0004 | Monitoring triggers and opt-in | M9 |
| 0005 | Reverse delta screening | M9 |
| 0006 | Webhook outbox | M9 |

## Constraints carried over

v2 keeps Java 21, Spring Boot and Postgres, and adds no infrastructure: Postgres provides the queues and the outbox, with no Kafka and no frontend. Data sources stay free and the project stays non-commercial, as OpenSanctions is CC BY-NC. The keyless demo must keep working and must show both new features. The 80% coverage gate and the existing CI stay in place, and there is still one shared API key and one tenant.

## Considered and left out

- **An analyst case workflow.** This is the natural next step: assigning, investigating and closing an alert. It is left out because it is a product of its own with users and permissions, while the gaps above can be closed without it. This is why alerts have no status: a status would be the start of that workflow.
- **OpenSanctions `Ownership` entities.** They would add ownership links from a second source, including outside the UK, but they raise the problem of merging two conflicting ownership pictures. Recorded in ADR-0002 and not built.
- **The Companies House Streaming API.** It would detect company changes within minutes rather than at the next periodic re-check, but it needs a long-running consumer, and the fallback closes the gap at far lower cost. Designed in ADR-0004 and deferred.
- **Multi-tenancy, per-client keys, a live deployment and general async checks.** None of these closes either gap.
- **Retro ADRs for v1.** Rewriting those decisions now would imply a paper trail that did not exist at the time. The README already explains them.

## Consequences

- A replay of a check made before M8 will differ from the original, because its parent companies were never snapshotted. It runs with look-through at depth 0, adds a note saying so, and reports the difference, explained by the engine version moving to 2.0.0. Refusing the replay would lose the ability to review old decisions, and keeping old code paths would break the rule that replay runs today's code.
- Checks cost more Companies House requests. The per-check company cap exists to keep this inside the client's budget of 550 requests per 5 minutes.
- More personal data is held, for longer: upstream owners are screened, and monitoring keeps subjects' names for as long as a company is subscribed. This is the main reason monitoring is opt-in, and ADR-0004 covers it.
