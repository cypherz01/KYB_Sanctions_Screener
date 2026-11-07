# UK KYB & Sanctions Screening Service

An automated Know Your Business (KYB) check for UK companies. It combines Companies House, the FCA Register, and a locally hosted, explainable sanctions matcher built on OpenSanctions bulk data.

> **Status:** work in progress. The project is built milestone by milestone, test-first. Each milestone is a pull request with two commits: the failing tests (red), then the implementation that makes them pass (green).

## Quick start (so far)

Requires Docker. Java and Maven are only needed for running tests outside Docker (the Maven wrapper is included).

```sh
cp .env.example .env
docker compose up -d --build --wait
curl http://localhost:8080/actuator/health
```

Run the tests (needs JDK 21 and a running Docker daemon for Testcontainers):

```sh
./mvnw verify
```

No test calls a real external API; WireMock stands in for them. To check against the real Companies House API manually (needs a free key from the [developer hub](https://developer.company-information.service.gov.uk)):

```sh
COMPANIES_HOUSE_API_KEY=your-key ./mvnw -Psmoke test
```

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

- **Atomic and versioned:** each import loads under a new dataset version that screening cannot see until a single transaction makes it active. A failed or interrupted import never touches the active data. The last 3 completed versions are kept so past checks can be replayed against the exact data they used.
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
3. **Score** each candidate in Java. Four weighted components are combined: Jaro-Winkler on aligned, token-sorted names; exact token overlap; phonetic agreement; and per-token fuzzy alignment. A small penalty applies for unmatched extra tokens. Birth date and nationality then adjust the score: a birth year more than one year apart costs 0.25, which is what separates two different John Smiths. A match that rests on an initial or a single token is capped below `STRONG`.

All lists, weights and thresholds live in `application.yml`.

### Accuracy on the fixture set

`src/test/resources/matching/name-pairs.psv` holds 56 labelled pairs covering spec §8.4. They include word order, diacritics, transliteration, missing middle names, initials, typos, common names disambiguated by birth date, legal suffixes, `&`/`and`, and near-misses that share a token. The table is regenerated by `MatchingFixturesTest` (`target/matching-accuracy.md`). Five "weak at most" pairs are excluded from the counts.

| Threshold | Precision | Recall | TP | FP | FN |
|---|---|---|---|---|---|
| 0.70 | 0.951 | 1.000 | 39 | 2 | 0 |
| 0.75 | 0.951 | 1.000 | 39 | 2 | 0 |
| **0.80 (POSSIBLE)** | **1.000** | **1.000** | 39 | 0 | 0 |
| 0.85 | 1.000 | 0.974 | 38 | 0 | 1 |
| 0.90 | 1.000 | 0.872 | 34 | 0 | 5 |
| 0.95 | 1.000 | 0.615 | 24 | 0 | 15 |

The trade-off shows clearly. Lowering the threshold to 0.75 lets near-misses through (false positives, which mean analyst workload). Raising it to 0.90 misses real variants such as typos and romanisations (false negatives, which in sanctions screening are the costlier error). The fixture set is small and was written by hand, so these figures show the matcher behaves as designed. They are not a benchmark of real-world accuracy.

The set grew from real data. On the full dataset, "John Smith" first came out STRONG against "John B. Pridmore-Smith", which in a KYB check would automatically fail every John Smith. That pair and a similar one are now fixtures, and the extra-token penalty was raised to keep them below STRONG.

### Performance

Measured by `ScreeningPerformanceSmokeTest` against the full dataset (52,019 entities, 268,537 names; Postgres 16 in Docker on a desktop, warm cache): 90 single-name screens, **p50 23.9 ms, p95 45.5 ms**, max 305 ms. The target is under 100 ms.

To run the full real import as a manual check (downloads the whole file):

```sh
OPENSANCTIONS_SMOKE=true ./mvnw -Psmoke test -Dtest=SanctionsImportSmokeTest
```

> **Disclaimer:** a portfolio project, not a compliance product. Do not use it for real onboarding decisions. Sanctions data from [OpenSanctions](https://www.opensanctions.org) is licensed CC BY-NC 4.0, **non-commercial use only**.
