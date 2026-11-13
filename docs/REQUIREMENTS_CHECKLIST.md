# Requirements checklist — Lunar Rockets

Living checklist derived from `docs/BRIEF.md` + `docs/SPEC.md`.

**Hygiene:** After each implementation slice lands, mark matching items `- [x]` and ~~strike~~ the text. Design/SPEC decisions already **DECIDED** are checked where appropriate; implementation items stay unchecked until code lands.

---

## Process / workflow

- [x] ~~Driver/Car: agents propose; wait for OK on direction, commits, push, hand-in~~
- [x] ~~No push / hand-in until Driver authorizes~~
- [x] ~~Spec-driven: plans under `docs/plans/` before implementation~~
- [x] ~~TDD + unit/integration/e2e with edge cases expected~~
- [x] ~~YAGNI: no auth/CORS/OWASP/extra frameworks unless Driver opens~~
- [x] ~~Living checklist exists (`docs/REQUIREMENTS_CHECKLIST.md`)~~
- [ ] Master + per-phase plans written under `docs/plans/` and approved
- [ ] `AI_USAGE.md` with honest point time estimates
- [ ] README runbook (build, run on 8088, APIs, tests/harness)

---

## Stack & deployment (DECIDED)

- [x] ~~Java 21 + Spring Boot 3.x + Maven~~
- [x] ~~Single Spring Boot deployable; packages ingest / apply / read~~
- [x] ~~Scale by replicas of same artifact; no multi-module / microservices for exercise~~
- [ ] Scaffold / phase 0 project created
- [ ] Service listens on port **8088**

---

## Persistence (DECIDED)

- [x] ~~Postgres in Docker (exercise storage)~~
- [x] ~~Not full event sourcing; no Kafka~~
- [x] ~~Schema shape: `messages` + `rockets` (current-state) + `version` on rockets~~
- [x] ~~Idempotency key `(channel, messageNumber)`; track applied numbers per channel~~
- [x] ~~Prod note (docs only): Aurora-style writer + readers for GET~~
- [ ] Postgres schema / migrations implemented
- [ ] Durable message insert on accept
- [ ] Rockets current-state projection updates on apply

---

## Ingest & gap / apply (DECIDED option 3 + B)

- [x] ~~Option 3: durable insert → 200; apply only contiguous per channel~~
- [x] ~~DECIDED B: short ingest txn; async apply workers~~
- [x] ~~Multi-pod: Postgres claim `FOR UPDATE SKIP LOCKED`; per-channel exclusive apply~~
- [x] ~~`rockets.version` optimistic concurrency~~
- [x] ~~In-memory wake optional hint only (not correctness)~~
- [ ] Implement durable ingest path
- [ ] Implement async contiguous apply (poll/claim)
- [ ] Multi-pod-safe claim path verified (or documented test)
- [ ] Optional same-pod wake hint (if chosen)

**OPEN (knobs — plan-level, not coordination):** poll cadence, batch size, Spring trigger shape, exact apply txn boundaries.

---

## HTTP / API (DECIDED)

- [x] ~~Paths: `POST /messages`, `GET /rockets`, `GET /rockets/{id}` (not `/api/v1/...`)~~
- [x] ~~POST success always **200** (incl. idempotent duplicates); not 202/204~~
- [x] ~~**400** malformed JSON / unknown type / missing fields / negative `by`~~
- [x] ~~**5xx** transient (e.g. 503) so harness retries~~
- [x] ~~List sort: `sort=speed|mission|type` (or `sortBy`) + `order=asc|desc` (default asc); default by id/channel~~
- [x] ~~**404** for unknown `GET /rockets/{id}`~~
- [x] ~~No pagination~~
- [ ] Implement `POST /messages`
- [ ] Implement `GET /rockets` (empty → `200` + `[]`)
- [ ] Implement `GET /rockets/{id}`
- [ ] Implement list sorting query params

**OPEN:** response JSON field names / whether to expose `lastMessageTime`, `lastAppliedMessageNumber`, etc.

---

## Domain rules (DECIDED + OPEN)

- [x] ~~Rocket ID = `metadata.channel`~~
- [x] ~~Apply effects: Launched / Speed± / Exploded / MissionChanged~~
- [x] ~~Sticky explode + still apply later contiguous messages~~
- [x] ~~First-wins duplicates `(channel, messageNumber)` — 200, no re-apply, no 409~~
- [x] ~~Negative `by` → 400; resulting speed may be ≤ 0 (no clamp)~~
- [ ] Apply reducer / state machine implemented
- [ ] Duplicate first-wins behavior implemented
- [ ] Post-explode sticky + continue-apply implemented
- [ ] Speed `by` validation + no-clamp arithmetic implemented

**OPEN:** missing / late `RocketLaunched` (buffer vs invent); huge `by` / overflow (use long; document).

---

## Message envelope / types (BRIEF)

- [ ] Accept JSON envelope with `metadata` + `message`
- [ ] Required metadata: `channel`, `messageNumber`, `messageTime`, `messageType`
- [ ] `RocketLaunched` payload: `type`, `launchSpeed`, `mission`
- [ ] `RocketSpeedIncreased` / `RocketSpeedDecreased` payload: `by`
- [ ] `RocketExploded` payload: `reason`
- [ ] `RocketMissionChanged` payload: `newMission`
- [ ] Out-of-order within channel handled via contiguous apply
- [ ] At-least-once / redelivery on non-2xx respected

---

## Harness / verification

- [ ] Compatible with `./rockets launch "http://localhost:8088/messages"` (defaults)
- [ ] Unit tests: apply/reducer, idempotency, transitions
- [ ] Integration tests: POST + GET against Spring / Testcontainers as needed
- [ ] E2E / harness-oriented: out-of-order + duplicates (+ optional `./rockets`)
- [x] ~~Coverage bar **> 90%** (DECIDED)~~
- [ ] Measured coverage > 90%

---

## Assessment / interview readiness (BRIEF process)

- [ ] Design choices & trade-offs documented (README / plans)
- [ ] Depth: concurrency, persistence, error handling explainable
- [ ] Verification story clear for interview (~10 min breadth-first)
