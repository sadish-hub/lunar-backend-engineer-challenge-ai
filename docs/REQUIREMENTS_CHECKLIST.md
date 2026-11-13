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
- [x] ~~Master + per-phase plans written under `docs/plans/` and approved (P0–P2 approved; P3–P4 phase plans TBD)~~
- [ ] `AI_USAGE.md` with honest point time estimates
- [ ] README runbook (build, run on 8088, APIs, tests/harness) — minimal local-up notes in P0; full runbook P4

---

## Stack & deployment (DECIDED)

- [x] ~~Java 21 + Spring Boot 3.x + Maven~~
- [x] ~~Single Spring Boot deployable; packages ingest / apply / read~~
- [x] ~~Scale by replicas of same artifact; no multi-module / microservices for exercise~~
- [x] ~~Scaffold / phase 0 project created~~
- [x] ~~Service listens on port **8088**~~

---

## Persistence (DECIDED)

- [x] ~~Postgres in Docker (exercise storage)~~
- [x] ~~Not full event sourcing; no Kafka~~
- [x] ~~Schema shape: `messages` + `rockets` (current-state) + `version` on rockets~~
- [x] ~~Idempotency key `(channel, messageNumber)`; track applied numbers per channel~~
- [x] ~~Prod note (docs only): Aurora-style writer + readers for GET~~
- [x] ~~Postgres schema / migrations implemented~~
- [x] ~~Durable message insert on accept~~
- [x] ~~Rockets current-state projection updates on apply~~

---

## Ingest & gap / apply (DECIDED option 3 + B)

- [x] ~~Option 3: durable insert → 200; apply only contiguous per channel~~
- [x] ~~DECIDED B: short ingest txn; async apply workers~~
- [x] ~~Multi-pod: Postgres claim `FOR UPDATE SKIP LOCKED`; per-channel exclusive apply~~
- [x] ~~`rockets.version` optimistic concurrency~~
- [x] ~~In-memory wake optional hint only (not correctness)~~
- [x] ~~Implement durable ingest path~~
- [x] ~~Implement async contiguous apply (poll/claim)~~
- [x] ~~Multi-pod-safe claim path verified (or documented test)~~
- [ ] Optional same-pod wake hint (if chosen) — deferred; see `docs/NEXT_STEPS.md`

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
- [x] ~~Implement `POST /messages`~~
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
- [x] ~~Apply reducer / state machine implemented~~
- [x] ~~Duplicate first-wins behavior implemented~~
- [x] ~~Post-explode sticky + continue-apply implemented~~
- [x] ~~Speed `by` validation + no-clamp arithmetic implemented~~

**OPEN:** missing / late `RocketLaunched` (buffer vs invent); huge `by` / overflow (use long; document).

---

## Message envelope / types (BRIEF)

- [x] ~~Accept JSON envelope with `metadata` + `message`~~
- [x] ~~Required metadata: `channel`, `messageNumber`, `messageTime`, `messageType`~~
- [x] ~~`RocketLaunched` payload: `type`, `launchSpeed`, `mission`~~
- [x] ~~`RocketSpeedIncreased` / `RocketSpeedDecreased` payload: `by`~~
- [x] ~~`RocketExploded` payload: `reason`~~
- [x] ~~`RocketMissionChanged` payload: `newMission`~~
- [x] ~~Ingest validation via JSON Schema (`src/main/resources/schemas/`, networknt)~~
- [x] ~~Out-of-order within channel handled via contiguous apply~~
- [x] ~~At-least-once / redelivery on non-2xx respected~~

---

## Harness / verification

- [x] ~~Compatible with `./rockets/<platform>/rockets launch "http://localhost:8088/messages"` (defaults)~~ (P1.1: `darwin_arm64`, BRIEF-style `500ms`/`1` + stress `50ms`/`8`; see `docs/plans/p1.1-harness-smoke.md` §3.7)
- [x] ~~Unit tests: apply/reducer, idempotency, transitions~~ (apply/reducer + apply ITs in P2; ingest covered in P1)
- [ ] Integration tests: POST + GET against Spring / Testcontainers as needed (GET remains P3)
- [ ] E2E / harness-oriented: out-of-order + duplicates (+ optional `./rockets/<platform>/rockets`)
- [x] ~~Coverage bar **> 90%** (DECIDED)~~
- [ ] Measured coverage > 90%

---

## Assessment / interview readiness (BRIEF process)

- [ ] Design choices & trade-offs documented (README / plans)
- [ ] Depth: concurrency, persistence, error handling explainable
- [ ] Verification story clear for interview (~10 min breadth-first)
