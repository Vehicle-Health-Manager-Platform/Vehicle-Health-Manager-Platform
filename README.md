# Vehicle Health Manager Platform

**English** | [简体中文](README.zh-CN.md)

[![CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/workflows/ci.yml/badge.svg)](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/workflows/ci.yml)
![License](https://img.shields.io/badge/License-MIT-blue.svg)
![Java](https://img.shields.io/badge/Java-17-ED8B00.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F.svg)
![Node.js](https://img.shields.io/badge/Node.js-22.x-339933.svg)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1.svg)
![uni-app](https://img.shields.io/badge/uni--app-3-2B9939.svg)

One WeChat Mini Program (three roles + a restricted operator entry) on one shared Java backend, carrying
a car's after-sales record from the first symptom to the archived history:
**book → receive → dispatch → repair → quality check → redeem → archive → review.**

Today the vehicle's history lives in paper invoices, chat threads and each shop's own system. This
project puts it in one model, with every step executed by a different role under a server-side
permission boundary.

<img src="docs/assets/flow-main.svg" alt="Seven stages: booking, reception inspection, owner sign-off, service work, redemption, review, archive, with a dispute branch back to service work" width="100%">

## What it does

| | |
| --- | --- |
| **Vehicles & archive** | Manual entry, licence-scan OCR, VIN candidates or four-level model selection; maintenance, repair, insurance, inspection, violation and modification records; six health reading classes with history and five reminder classes |
| **Service & booking** | Catalogue, shop quotes and quote versions, booking slots with row-locked capacity, idempotent order creation, payment, cancellation and refund |
| **Fulfilment** | Reception inspection (multi-photo + mileage), owner confirmation or objection, dispatch, technician acceptance, protection and work reporting, QC signature, redemption, automatic archiving, reviews |
| **Benefits** | Coupons, points and exchange, monthly invitation tiers with two-sided rewards, shop monthly assessment — all switch-controlled and off by default |
| **Content & AI** | Private Q&A, consent-gated public Q&A, same-model experience cards, archive-grounded AI assistant with a documented degradation path |
| **Recognition** | Mileage OCR, licence candidates, VIN, speech ASR, image/PDF extraction — four isolated Python services, each off by default |

## Stack

| Layer | Choice |
| --- | --- |
| Client | uni-app 3 + Vue 3, target `mp-weixin`; H5 build kept for browser debugging |
| Backend | Java 17, Spring Boot 3.5, one modular monolith (packages, not microservices) |
| Data | MySQL 8.0, `init.sql` + 65 ordered migrations, raw JDBC so slot locking is explicit |
| Files | MinIO private buckets + ClamAV scanning + short-lived signed URLs |
| Inference | Python services for OCR / ASR / documents / embeddings, called over an intranet URL |
| Gating | 8 GitHub Actions jobs: builds, real-MySQL regression, schema, recovery drill, Compose smoke, performance baseline |

## Quick start

Needs Node 22, Java 17, Maven 3.9, Python 3.11+ and a Docker Linux engine.

```bash
npm ci
python -m venv .cache/qa-runtime
.cache/qa-runtime/Scripts/python.exe -m pip install -r services/ocr/requirements.txt   # bin/python on macOS/Linux
.cache/qa-runtime/Scripts/python.exe -m pip install -r scripts/requirements-export-qa.txt
```

```bash
python scripts/run_local_delivery.py      # tests, builds, OpenAPI, migrations - no WeChat account needed
npm run build:miniapp                     # -> apps/miniapp/dist/build/mp-weixin, import this in WeChat DevTools
```

```bash
cp .env.example .env                      # replace every placeholder
docker compose --env-file .env -f deploy/compose/compose.yml up -d mysql   # host port 3307
mvn -f backend/pom.xml spring-boot:run    # health at :8080/actuator/health
```

Full backend regression needs Docker and takes a while: `python scripts/run_local_delivery.py --backend`.
It uses a disposable Testcontainers database and never touches a database you care about.

## Scope and status

This repository documents only what its code, configuration and scripts actually contain.

**Verified locally**, on synthetic data with real infrastructure: the full backend suite against real
MySQL, 12-group default regression, 65 migrations → 134 tables with a backup/restore drill, isolated
end-to-end journeys per domain, and both mini program build targets.

**Not verified, and deliberately not claimed:** real WeChat code exchange and phone binding, SMS
delivery, the real payment channel and refund reconciliation, three real-device journeys,
500-concurrency P95 latency, and production deployment. Each has an owner and a required precondition in
[CURRENT_STATUS.md](docs/progress/CURRENT_STATUS.md); **the aggregate all-API acceptance flag stays
`false`.**

Features are off by default and return an explicit `503` rather than fabricated data. A switch that is
off is a configuration state, not a missing feature.

## Documentation

| Entry | Content |
| --- | --- |
| [docs/delivery/LOCAL_SETUP.md](docs/delivery/LOCAL_SETUP.md) | Exact start-up, configuration, common errors |
| [docs/api/](docs/api/README.md) | 92 contract documents + generated `openapi.json` (324 paths / 362 operations) |
| [docs/operations/](docs/operations/README.md) | Switches, accounts, rollback per capability |
| [docs/user-guides/](docs/user-guides/README.md) | Owner / shop / technician / operator manuals |
| [docs/testing/](docs/testing/README.md) | Acceptance reports and coverage reconciliation |
| [docs/progress/CURRENT_STATUS.md](docs/progress/CURRENT_STATUS.md) | Current progress and open gaps |
| [docs/STRUCTURE.md](docs/STRUCTURE.md) | Directory and package responsibilities |
| [docs/progress/README_FULL_ARCHIVE_2026-11-03.md](docs/progress/README_FULL_ARCHIVE_2026-11-03.md) | Previous long-form README: role-by-role detail, auth, error codes, WeChat release steps, deployment |

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first. One rule shapes the rest: a change is complete only when
its specification, contract, runbook and reproduction steps land with it — and when the claim matches
what was actually executed.

[Code of Conduct](CODE_OF_CONDUCT.md) · [Security policy](SECURITY.md) — report vulnerabilities
privately, never in a public issue.

## License

[MIT](LICENSE) © 2026 Vehicle-Health-Manager-Platform
