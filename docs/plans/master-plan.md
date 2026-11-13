# Master plan — Lunar Rockets

Spec-driven implementation roadmap derived from `docs/SPEC.md`, `AGENTS.md`, and `docs/REQUIREMENTS_CHECKLIST.md`. **No code until this master plan is Driver-approved**, then a detailed **P0 plan** is written and approved before scaffold work starts.

**Status:** awaiting Driver approval before any per-phase plan or implementation.

---

## Process (every phase)

After each **phase plan** is written:

1. **Driver approve** the phase plan (direction OK).
2. **Implement** (TDD: failing tests first; unit + integration + e2e as scoped).
3. **Independent review** (separate pass / reviewer; agents propose fixes).
4. **Driver assess** the slice against SPEC / checklist.
5. **Commit** — agents **propose** a commit message; **commit only after Driver OK**.
6. **No push** (and no public hand-in) without Driver OK.

Also:

- Prefer plan commit → review OK → separate implementation commit **when committing is authorized**.
- Keep `docs/REQUIREMENTS_CHECKLIST.md` current as slices land.
- Keep `AI_USAGE.md` honest (point time estimates) once that deliverable exists.
- YAGNI: do not reopen DECIDED architecture; ask before extra frameworks / auth / CORS / OWASP / uniqueness beyond channel-as-ID.

**Gate:** detailed per-phase plans come next. This master plan needs **Driver approval before the P0 plan**.

---

## Locked architecture (do not reopen)

| Area | Decision |
| --- | --- |
| Stack | Java 21, Spring Boot 3.x, Maven |
| Deploy | One Spring Boot deployable; packages `ingest` / `apply` / `read`; scale by replicas |
| Storage | Postgres (Docker): `messages` + `rockets` (+ `version`); not event sourcing / Kafka |
| Gap / apply | Durable insert → **200**; async contiguous apply; multi-pod via `FOR UPDATE SKIP LOCKED`; optional in-memory wake = hint only |
| `POST /messages` | **200** on accept (incl. duplicates); **400** bad/unknown; **5xx** transient |
| Read API | `GET /rockets`, `GET /rockets/{id}`; sort; **404** unknown; no pagination; port **8088** |
| Domain | Sticky explode + still apply later; first-wins duplicates; negative `by` → **400**; speed may be ≤ 0 |

**Still OPEN (resolve in phase plans / Driver asks, not by guessing):** response JSON field names / timestamps; missing/late `RocketLaunched`; apply knobs (poll cadence, batch size, trigger shape, txn boundaries); huge `by` / overflow (prefer `long` + document).

---

## Phase overview

| Phase | Name | Intent |
| --- | --- | --- |
| **P0** | Scaffold & DB | Runnable skeleton + migrations + claim surface |
| **P1** | Ingest `POST /messages` | Schema validation, idempotent durable accept |
| **P2** | Apply scheduler | `SKIP LOCKED` claim, contiguous apply, multi-pod safe |
| **P3** | Read APIs | `GET /rockets`, `GET /rockets/{id}` with sort |
| **P4** | Verification + readiness | Coverage >90%, harness, README, `AI_USAGE`, checklist |

---

## P0 — Scaffold & DB

### Goal

Stand up a lean Spring Boot 3 / Java 21 / Maven service that builds and runs locally with Postgres in Docker, listens on **8088**, and owns the persistence surface needed by later phases: `messages`, `rockets` (with `version`), and a claimable dirty-channel / unapplied-work surface. Migrations via Flyway, Liquibase, or equivalent.

### Out of scope

- Business apply / reducer logic beyond empty stubs if needed for compile.
- Real ingest validation, HTTP handlers that accept production payloads, read APIs, coverage bar.
- Auth, CORS, multi-module Maven, Kafka, in-memory-as-primary.

### Deliverables

- Project scaffold (Maven, Spring Boot 3.x, Java 21); package seams `ingest` / `apply` / `read`.
- `Dockerfile`, `Makefile`, `.gitignore`, `docker-compose` (Postgres + app as needed), `.dockerignore`.
- DB migrations: `messages` (unique `(channel, message_number)`), `rockets` (current-state + `version`), claim surface (dedicated dirty-channels table **or** claim over unapplied `messages` — same contract: `FOR UPDATE SKIP LOCKED`).
- Service boots on port **8088**; health/smoke enough to prove DB connectivity.
- Minimal README notes for local up (full runbook deferred to P4 if preferred).

### Test focus

- Context loads / smoke against Testcontainers or compose Postgres.
- Migration applies cleanly on empty DB; unique constraint on `(channel, message_number)` enforced.

### Exit criteria

- `make` (or documented) path brings app + Postgres up; app reachable on **8088**.
- Schema matches SPEC §6 shape; claim surface exists and is queryable.
- Driver-approved P0 plan implemented, reviewed, assessed; commit only after Driver OK.
- Checklist: scaffold / migrations items marked when landed.

---

## P1 — Ingest `POST /messages`

### Goal

Accept Lunar harness messages at `POST /messages`: validate envelope + per-type payload, durable idempotent insert on `(channel, messageNumber)`, return **200** / **400** / **5xx** per SPEC. Concurrent-safe writes under duplicate and parallel POSTs. Mark work available for apply in the same durability boundary as accept (dirty channel / unapplied row) so a crash after **200** never loses owed apply.

### Out of scope

- Contiguous apply to `rockets` (P2 owns that).
- Read APIs (P3).
- Inventing rockets from non-launch without Driver decision on missing/late launch OPEN.

### Deliverables

- `POST /messages` handler + schema validation (required metadata; type-specific `message`; negative `by` → **400**).
- Short durable ingest transaction: insert `messages` (first-wins on unique key); idempotent redelivery → **200**, ignore divergent second payload.
- Signal claim surface for apply (same durability boundary).
- Transient failures → **5xx** so harness retries.

### Test focus

- Unit: validation matrix (malformed, unknown type, missing fields, negative `by`).
- Integration: durable insert; duplicate same/different payload → **200**, first payload retained; concurrent POSTs for distinct and duplicate keys.
- No double-row for same `(channel, messageNumber)`.

### Exit criteria

- Harness-compatible URL path and status policy match SPEC §4.5.
- Idempotent concurrent-safe writes verified by tests.
- Apply not required yet, but accepted messages are durable and claimable for P2.
- Checklist ingest items updated; commit only after Driver OK.

---

## P2 — Apply scheduler

### Goal

Async workers poll Postgres, **claim** pending work with `SELECT … FOR UPDATE SKIP LOCKED`, and apply contiguous `messageNumber`s per channel into `rockets`. Per-channel exclusive apply; multi-pod safe; `rockets.version` optimistic concurrency as safety net. Domain rules: launch/speed±/explode/mission; sticky explode + still apply later; first-wins already enforced at ingest (no re-apply of duplicates).

### Out of scope

- Changing ingest HTTP contract.
- Read API polish (P3) beyond whatever stubs help tests.
- Making in-memory wake authoritative (optional hint only).
- Sync giant-txn apply on the ingest request path.

### Deliverables

- Apply scheduler/worker (Spring `@Scheduled` or equivalent loop — knobs OK if DB claim remains source of truth).
- Contiguous apply per channel; progress tracked; CAS / version bump on `rockets` updates.
- Multi-pod-safe claim (documented test or dual-worker simulation).
- Optional same-pod wake after ingest (latency only).

### Test focus

- Unit: reducer / state machine (speed, mission, explode sticky + continue, speed ≤ 0 allowed).
- Integration: out-of-order durable messages become contiguous apply; gaps wait; duplicates never double-apply deltas.
- Concurrency: two workers cannot apply the same channel simultaneously; claim via `SKIP LOCKED`.

### Exit criteria

- Contiguous apply correct under gaps, duplicates, explode, mission change.
- Multi-pod claim path demonstrated or clearly tested.
- Checklist apply / domain impl items updated; commit only after Driver OK.

---

## P3 — Read APIs

### Goal

Expose current rocket state: `GET /rockets` (list, empty → `200` + `[]`) and `GET /rockets/{id}` (**404** if unknown). Support sorting: `sort=speed|mission|type` (or `sortBy`) + `order=asc|desc` (default `asc`); default sort by `id` / channel for stability. Document response JSON field names (resolve OPEN with Driver if needed).

### Out of scope

- Pagination, auth, CORS.
- Changing ingest/apply semantics.
- Dashboard / UI.

### Deliverables

- Read controllers/services over `rockets` projection.
- Sort query params per SPEC §5.
- Stable, documented camelCase (or Driver-chosen) response shape including agreed fields (`id`, `type`, `speed`, `mission`, `exploded`, `explosionReason`, …).

### Test focus

- Integration: empty list; get known/unknown; sort permutations; consistency with applied state after ingest+apply sequences.

### Exit criteria

- Paths and status codes match DECIDED API.
- Sort behavior covered by tests.
- Checklist read-API items updated; commit only after Driver OK.

---

## P4 — Verification + interview / prod readiness

### Goal

Close the quality and interview bar: measured coverage **> 90%**, harness-oriented verification, README runbook, honest `AI_USAGE.md`, living checklist complete for landed work. Document design trade-offs (incl. Aurora writer/readers prod note) for ~10 min breadth-first interview narrative.

### Out of scope

- New product features beyond SPEC.
- Push / ZIP / public hand-in until Driver authorizes (process gate, not a code deliverable).

### Deliverables

- Coverage report > 90% on agreed critical paths (ingest idempotency, ordering/apply, read APIs) and overall bar.
- Unit + integration + e2e / harness-oriented suites (out-of-order + duplicates; optional `./rockets` when binary available).
- README: build, run on **8088**, APIs, tests, harness; design/trade-off notes.
- `AI_USAGE.md` with point time estimates.
- `docs/REQUIREMENTS_CHECKLIST.md` brought current for all landed items.

### Test focus

- Fill gaps to hit coverage bar; edge-case e2e; optional live `./rockets launch "http://localhost:8088/messages"`.

### Exit criteria

- Coverage > 90% measured and recorded.
- README + `AI_USAGE.md` + checklist ready for Driver assess / interview.
- No push without Driver OK; hand-in only when Driver authorizes.
- Commit only after Driver OK.

---

## Sequencing & gates

```
Driver OK on this master plan
  → write P0 plan → Driver OK → implement → review → Driver assess → commit (msg OK)
  → P1 … P4 (same loop)
  → push / hand-in only with Driver OK
```

**Next ask for Driver:** approve this master plan (or request changes). On approval, agents write the detailed **P0** plan next — still no scaffold code until that P0 plan is approved.
