# UK KYB & Sanctions Screening Service

An automated Know Your Business (KYB) check for UK companies. It combines Companies House, the FCA Register, and a locally hosted, explainable sanctions matcher built on OpenSanctions bulk data.

> **Status:** work in progress. The project is built milestone by milestone, test-first. Each milestone is a pull request with two commits: the failing tests (red), then the implementation that makes them pass (green).

## Quick start

Requires Docker. Java and Maven are only needed for running tests outside Docker (the Maven wrapper is included).

```sh
cp .env.example .env     # then set KYB_API_KEY (any long random string) and the source keys
docker compose up -d --build --wait
curl http://localhost:8080/actuator/health

curl -X POST http://localhost:8080/api/v1/checks -H "X-Api-Key: $KYB_API_KEY" \
     -H 'Content-Type: application/json' -d '{"companyNumber": "09740322"}'
```

Every `/api/**` call needs the `X-Api-Key` header. If `KYB_API_KEY` is not set, the API refuses every call (fails closed) rather than running open. Health (`/actuator/health`), metrics (`/actuator/prometheus`) and the API docs (`/swagger-ui.html`, `/v3/api-docs`) stay open, since the service is meant to run locally.

Running the jar outside Docker? A native Postgres already listening on `localhost:5432` will take the connection, and the app then fails with `password authentication failed for user "kyb"`. Point `DB_URL` at another port instead.

Run the tests (needs JDK 21 and a running Docker daemon for Testcontainers):

```sh
./mvnw verify
```

No test calls a real external API; WireMock stands in for them. To check against the real Companies House API manually (needs a free key from the [developer hub](https://developer.company-information.service.gov.uk)):

```sh
COMPANIES_HOUSE_API_KEY=your-key ./mvnw -Psmoke test
```

## KYB checks

`POST /api/v1/checks` with `{"companyNumber": "..."}` runs a full check and returns `201` with a `Location` header. `GET /api/v1/checks/{checkId}` returns the stored result byte for byte as it was first returned.

1. **Gather.** From Companies House it fetches the profile, officers, PSCs and PSC exemptions. It then pins the active sanctions dataset version and assesses the company on the FCA Register.
2. **Decide who is screened.** That is the company, every current officer and every current PSC, individual or corporate. One party holding several roles (for example a director who is also a PSC) is screened once. Super-secure PSCs cannot be screened, and the result notes this.
3. **Screen** every subject against that one dataset version, with month of birth and nationality.
4. **Score** with deterministic rules from [`scoring-rules.yml`](src/main/resources/scoring-rules.yml), whose version is recorded on every check:

| Factor | Points |
|---|---|
| Company not active (dissolved, liquidation, ...) | 50 |
| FCA clone or unauthorised-firm warning matches | 60 |
| Regulated SIC code but no FCA authorisation found | 25 |
| No PSCs declared (waived when Companies House records a PSC exemption, e.g. a listed company) | 15 |
| Corporate PSC registered outside the UK | 10 |
| Incorporated in the last 90 days / accounts overdue | 10 each |
| FCA Register unavailable | 10 |
| High officer churn (3+ changes in 365 days) | 5 |
| Possible sanctions match | 35, plus up to 15 scaled by similarity |
| **Strong sanctions match** | **automatic FAIL** |

A total of 40 or more gives `REVIEW`, and 80 or more gives `FAIL`. Each factor carries a plain-English `detail`, the source and fetch time it rests on, and for sanctions the subject, the matched entity and the matcher's evidence. Results are stored append-only with the dataset version, scoring version and engine version. Stale cached data is flagged, never hidden.

Checked on real data on 2026-11-07:

| Company | Decision | Why |
|---|---|---|
| Tesco PLC (00445790) | PASS, 0 | Listed company, so the PSC exemption explains why no PSCs are declared |
| Barclays Bank UK PLC (09740322) | PASS, 5 | Officer churn only. FCA FRN 759676 matched by Companies House number |
| Lloyds Banking Group PLC (SC095000) | PASS, 30 | Officer churn, plus "regulated SIC code but not authorised": a holding company (SIC 64205) is not itself on the Register |

Each check took about 1.5–2 s with the source caches empty.

## Audit and replay

Every check stores what it rested on, in one transaction with the result: the raw response from each source (with its fetch time and whether it was cached or stale), the sanctions dataset version, the scoring and engine versions, and a log of events.

```sh
curl http://localhost:8080/api/v1/checks/$ID/audit -H "X-Api-Key: $KYB_API_KEY"            # the full record
curl -X POST http://localhost:8080/api/v1/checks/$ID/replay -H "X-Api-Key: $KYB_API_KEY"   # re-run it
```

A replay repeats normalisation, matching and scoring from those snapshots, against the dataset version the check used, as of the date it ran. It makes no external call and reads no cache, so it gives the same answer if a director has since been delisted or a source has gone down. It runs through the same code as a live check, not a second implementation.

- **What "identical" means.** The whole result is compared field by field, not just the decision and score, and any difference is reported by path. An unchanged total could otherwise hide a changed factor.
- **Replay runs today's code and rules.** Old code is not kept, so a replay after an engine or scoring change shows the new version as a difference, along with anything that changes as a result. That is what such a replay is for: it shows what the change does to a past decision. To reproduce the original under its own rules, run the release matching its recorded `engineVersion`. Bump `kyb.checks.engine-version` whenever matching or scoring code changes, or a difference will have no version to explain it.
- **Retention.** The newest 3 sanctions dataset versions are kept. A check whose version has been pruned cannot be replayed, and asking returns `409` rather than screening against missing rows, which would look clean. Its stored result, snapshots and audit trail are kept indefinitely, and the refusal is recorded. Raise `kyb.sanctions.keep-versions` to replay further back, at about 200 MB of database per version (measured on the full dataset). Snapshots and events are never deleted, so storage grows with the number of checks, at about 33 kB of snapshots per check in the run below.
- **Append-only, enforced.** Database triggers reject `UPDATE` and `DELETE` on checks, snapshots and events. This stops mistakes, not a database administrator: `TRUNCATE` stays possible so an environment can be reset.

Checked on real data on 2026-11-08: Tesco, Barclays Bank UK, Lloyds Banking Group, HSBC Bank and Yorkshire Electricity Group, plus a repeat check served from the cache, against the full dataset. All six replayed identically, and again after restarting the application, which is when any dependence on hash ordering would show. A replay took 100–300 ms, or about 600 ms for the first after a restart.

Known limitations: replay trusts the stored data, since nothing signs it. A check that fails part-way (say, an unknown company) leaves no record, only a log line.

## FCA Register

The regulatory check uses the free [FCA Financial Services Register API](https://register.fca.org.uk/Developer/s/). Add `FCA_AUTH_EMAIL` and `FCA_AUTH_KEY` to `.env`. Without them, checks still run and report that regulatory status could not be verified.

- **Matching a company to a firm.** Companies House does not record the FCA reference number (FRN), so the company name is searched on the Register. Each hit is scored with the same normaliser and matcher used for sanctions. For the strongest authorised hits, the firm details are fetched: the same Companies House number is a certain match, and a different one rules the firm out. Otherwise the match rests on name (plus postcode), and its confidence and basis are reported.
- **Clone and unauthorised firms.** The API has no separate warning list. Entries with status `Unauthorised`, or names marked `(Clone)` / `(Clone of FCA authorised firm)`, are reported as warnings when their name matches.
- **No SLA, so built to degrade.** A client-side rate limiter stays below the documented 50 requests per 10 seconds, and a circuit breaker stops calls to a failing Register. Responses are cached for 7 days in Postgres. During an outage, expired results are served flagged `STALE` with their fetch time. If nothing is cached, the result is `UNAVAILABLE` and the check still completes.

Verified against the live Register on 2026-11-07, which the official docs (JavaScript-only) could not be. Barclays Bank UK PLC was matched to FRN 759676 by its Companies House number. Run that check yourself with `./mvnw -Psmoke test -Dtest=FcaRegisterSmokeTest`, with the two variables set.

Known limitations: only the first page of search results (20 firms) is considered. Clone detection depends on the Register's naming conventions. Permissions are not yet compared with the company's activities.

## Sanctions data

Sanctions screening runs against a local copy of the [OpenSanctions](https://www.opensanctions.org/datasets/sanctions/) `sanctions` collection, downloaded as bulk FollowTheMoney JSON (about 380 MB). The hosted API is not used. On the first `docker compose up` with an empty database, the app imports the data in the background. After that it re-imports nightly at 03:30 UK time. To trigger or inspect an import yourself:

```sh
curl -X POST http://localhost:8080/api/v1/admin/sanctions/import   # 202, runs in the background
curl http://localhost:8080/api/v1/admin/sanctions/status           # active version, counts by schema
```

- **Atomic and versioned:** each import loads under a new dataset version that screening cannot see until a single transaction makes it active. A failed or interrupted import never touches the active data. The last 3 completed versions are kept so past checks can be [replayed](#audit-and-replay) against the exact data they used.
- **Idempotent:** if the published SHA-1 matches the active version, nothing is downloaded. Downloaded files are checksum-verified.
- **What is screened:** `Person`, `Company`, `Organization` and `LegalEntity` entities tagged `sanction` or `sanction.linked`, with their names, aliases and previous names (not FtM "weak" aliases), birth dates, nationalities, countries, source lists and programmes. Relatives without a sanction, counter-sanctions, debarments and export-control-only entries are left out.

## Name matching

Screen a single name against the active dataset:

```sh
curl -X POST http://localhost:8080/api/v1/screen/name -H 'Content-Type: application/json' \
     -d '{"name": "PETROV, Ivan", "type": "PERSON", "birthDate": "1971-03", "country": "RU"}'
```

Each match carries a similarity (0 to 1), a band (`STRONG` ≥ 0.90, `POSSIBLE` ≥ 0.80; anything lower is discarded), the source lists and programmes, and an `evidence` list explaining the score, for example `Phonetic match: 'mohammed' ~ 'muhammad'`, `Birth year matches (1971)` or `'sergeyevich' appears only in the sanctions name`.

How it works:

1. **Normalise** both sides identically. ICU transliterates any script to Latin (Russian uses BGN/PCGN, as sanctions lists do, so `Жуков` becomes `zhukov`) and strips diacritics. The name is then lower-cased, `&` becomes `and`, and punctuation is removed. Titles are dropped from people and legal forms from organisations (`Ltd`, `LLC`, a leading `OOO`, ...). Each token gets a Double Metaphone key.
2. **Retrieve** up to 50 candidates in Postgres: trigram similarity (`pg_trgm`, GIN index) unioned with exact phonetic-key matches, restricted to comparable entity types. A person is never compared with a company.
3. **Score** each candidate in Java. Four weighted components are combined: Jaro-Winkler on aligned, token-sorted names; exact token overlap; phonetic agreement; and per-token fuzzy alignment. A small penalty applies for unmatched extra tokens. Birth date and nationality then adjust the score: a birth year more than one year apart costs 0.25, which is what separates two different John Smiths. A match that rests on an initial or a single token is capped below `STRONG`. A one-word listed person name (an alias such as "Adam") is never retained against a subject with a full name. Organisations are exempt, because one distinctive word can name a company.

All lists, weights and thresholds live in `application.yml`.

### Accuracy on the fixture set

`src/test/resources/matching/name-pairs.psv` holds 59 labelled pairs covering spec §8.4. They include word order, diacritics, transliteration, missing middle names, initials, typos, common names disambiguated by birth date, legal suffixes, `&`/`and`, near-misses that share a token, and one-word aliases. The table is regenerated by `MatchingFixturesTest` (`target/matching-accuracy.md`). Five "weak at most" pairs are excluded from the counts.

| Threshold | Precision | Recall | TP | FP | FN |
|---|---|---|---|---|---|
| 0.70 | 0.909 | 1.000 | 40 | 4 | 0 |
| 0.75 | 0.909 | 1.000 | 40 | 4 | 0 |
| **0.80 (POSSIBLE)** | **1.000** | **1.000** | 40 | 0 | 0 |
| 0.85 | 1.000 | 0.975 | 39 | 0 | 1 |
| 0.90 | 1.000 | 0.875 | 35 | 0 | 5 |
| 0.95 | 1.000 | 0.625 | 25 | 0 | 15 |

The trade-off shows clearly. Lowering the threshold to 0.75 lets near-misses through (false positives, which mean analyst workload). Raising it to 0.90 misses real variants such as typos and romanisations (false negatives, which in sanctions screening are the costlier error). The fixture set is small and was written by hand, so these figures show the matcher behaves as designed. They are not a benchmark of real-world accuracy.

The set grew from real data. On the full dataset, "John Smith" first came out STRONG against "John B. Pridmore-Smith", which in a KYB check would automatically fail every John Smith. That pair and a similar one are now fixtures, and the extra-token penalty was raised to keep them below STRONG. Later, the first real check on Barclays Bank UK sent it to REVIEW. Its director Adam Banks had come out POSSIBLE (0.84) against a Taliban-listed person whose list entry includes the bare alias "Adam". One-word person aliases are now discarded, and that case is a fixture.

### Performance

Measured by `ScreeningPerformanceSmokeTest` against the full dataset (52,019 entities, 268,537 names; Postgres 16 in Docker on a desktop, warm cache): 90 single-name screens, **p50 23.9 ms, p95 45.5 ms**, max 305 ms. The target is under 100 ms.

To run the full real import as a manual check (downloads the whole file):

```sh
OPENSANCTIONS_SMOKE=true ./mvnw -Psmoke test -Dtest=SanctionsImportSmokeTest
```

> **Disclaimer:** a portfolio project, not a compliance product. Do not use it for real onboarding decisions. Sanctions data from [OpenSanctions](https://www.opensanctions.org) is licensed CC BY-NC 4.0, **non-commercial use only**.
