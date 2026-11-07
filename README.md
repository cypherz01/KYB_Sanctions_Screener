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

> **Disclaimer:** a portfolio project, not a compliance product. Do not use it for real onboarding decisions. Sanctions data from [OpenSanctions](https://www.opensanctions.org) is licensed CC BY-NC 4.0, **non-commercial use only**.
