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

## Sanctions data

Sanctions screening runs against a local copy of the [OpenSanctions](https://www.opensanctions.org/datasets/sanctions/) `sanctions` collection, downloaded as bulk FollowTheMoney JSON (about 380 MB). The hosted API is not used. On the first `docker compose up` with an empty database, the app imports the data in the background. After that it re-imports nightly at 03:30 UK time. To trigger or inspect an import yourself:

```sh
curl -X POST http://localhost:8080/api/v1/admin/sanctions/import   # 202, runs in the background
curl http://localhost:8080/api/v1/admin/sanctions/status           # active version, counts by schema
```

- **Atomic and versioned:** each import loads under a new dataset version that screening cannot see until a single transaction makes it active. A failed or interrupted import never touches the active data. The last 3 completed versions are kept so past checks can be replayed against the exact data they used.
- **Idempotent:** if the published SHA-1 matches the active version, nothing is downloaded. Downloaded files are checksum-verified.
- **What is screened:** `Person`, `Company`, `Organization` and `LegalEntity` entities tagged `sanction` or `sanction.linked`, with their names, aliases and previous names (not FtM "weak" aliases), birth dates, nationalities, countries, source lists and programmes. Relatives without a sanction, counter-sanctions, debarments and export-control-only entries are left out.

To run the full real import as a manual check (downloads the whole file):

```sh
OPENSANCTIONS_SMOKE=true ./mvnw -Psmoke test -Dtest=SanctionsImportSmokeTest
```

> **Disclaimer:** a portfolio project, not a compliance product. Do not use it for real onboarding decisions. Sanctions data from [OpenSanctions](https://www.opensanctions.org) is licensed CC BY-NC 4.0, **non-commercial use only**.
