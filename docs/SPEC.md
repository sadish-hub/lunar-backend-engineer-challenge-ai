# Lunar Rockets — Product / Tech Spec (proposal)

Resolved implementation discussion spec derived from `docs/BRIEF.md`. Anything not decided by Driver is marked **OPEN** with a short recommendation. This document does **not** invent Lunar requirements; it interprets the brief for design talk. No plans, no code until Driver decides the OPEN checklist.

---

## 1. Goals

- Accept rocket lifecycle / telemetry messages via HTTP POST so Lunar’s `./rockets` test program can drive the system.
- Maintain per-rocket state derived from those messages (channel = rocket ID).
- Expose REST read APIs for:
  - current state of a given rocket (type, speed, mission, etc.);
  - list of all rockets, preferably with sorting.
- Stay within ~6 hour budget mindset: clear trade-offs, verifiable correctness, explainable design.
- Document AI usage deliberately for interview (process expectation).

## 2. Non-goals

Unless Driver later marks them in-scope:

- Building a dashboard / UI.
- Auth, CORS, OWASP hardening, rate limiting.
- Uniqueness constraints beyond channel-as-rocket-ID.
- Datepickers or client UX concerns.
- Extra frameworks beyond the agreed lean stack.
- Guaranteeing exactly-once processing at the broker (brief is at-least-once; app must tolerate duplicates).
- Hand-in / push / public repo until Driver says so.

---

## 3. Domain model

### 3.1 Rocket identity

- **Rocket ID** = message `metadata.channel` (UUID string in examples).
- Channel is unique per rocket per brief; treat as primary key.

### 3.2 Rocket state fields (derived)

Minimum fields implied by message types and “type, speed, mission, etc.”:

| Field | Source | Notes |
| --- | --- | --- |
| `id` / `channel` | `metadata.channel` | Stable identity |
| `type` | `RocketLaunched.message.type` | e.g. `"Falcon-9"` |
| `speed` | `launchSpeed` then ± `by` | Integer arithmetic from launch + increases − decreases |
| `mission` | launch `mission`, then `newMission` | Latest applied mission |
| `exploded` | presence of applied `RocketExploded` | Boolean or equivalent |
| `explosionReason` | `RocketExploded.message.reason` | Set when exploded |
| `lastMessageNumber` | highest **applied** `messageNumber` | Useful for ordering / gaps |
| `lastMessageTime` | from last applied message | **OPEN** whether to expose |

**OPEN — expose `messageTime` / last-seen timestamps on read APIs?**  
Recommendation: include `lastMessageTime` (and optionally `lastAppliedMessageNumber`) for debugging/demo; not required by brief wording.

### 3.3 How events apply (once a message is accepted for application)

| `messageType` | Effect |
| --- | --- |
| `RocketLaunched` | Create rocket if new; set `type`, `speed = launchSpeed`, `mission`. Brief: sent once at first launch. |
| `RocketSpeedIncreased` | `speed += by` |
| `RocketSpeedDecreased` | `speed -= by` |
| `RocketExploded` | Mark exploded; store `reason`. Brief: sent once. |
| `RocketMissionChanged` | `mission = newMission` |

**OPEN — behavior if `RocketLaunched` is missing / late relative to other numbers?**  
Recommendation: buffer out-of-order messages per channel until contiguous from 1 (or until launch is applied); do not invent a rocket from speed/mission alone without launch if that would invent state the brief doesn’t define. Alternative (simpler): apply only when prior numbers are present; return non-2xx to force redelivery (see §4). Driver must pick buffering vs. redelivery-on-gap.

**DECIDED — speed `by` schema + resulting speed (no clamp)**

- On `RocketSpeedIncreased` / `RocketSpeedDecreased`, **`by` must be a non-negative integer** (schema / ingest validation).
- **Reject** a negative `by` with **400** (permanently bad payload; do not 2xx+ignore).
- **Resulting `speed` after apply may be ≤ 0** (including negative). **Do not clamp** to zero or reject solely because arithmetic produced ≤ 0.
- Rationale: constrain the delta field at the envelope (clear producer bug); do not invent a floor the brief never stated for projected speed.

**DECIDED — updates after explode (sticky flag, still apply)**

- Once a `RocketExploded` is **applied**, `exploded` (and `explosionReason`) are **sticky** for that rocket.
- Later contiguous numbered messages for the same channel are **still applied** (speed / mission / etc.) when they arrive in order — explode is not a hard stop on the apply loop.
- Rationale: at-least-once / out-of-order delivery; state machine records applied history; “live” product story can still treat exploded as terminal for UX without refusing later valid sequence numbers.

**DECIDED — duplicate `(channel, messageNumber)` first-wins**

- Idempotency key remains `(channel, messageNumber)`.
- On a **second delivery** of an already-accepted number: return **200**, **do not re-apply**, and **keep the first accepted payload** even if a later delivery’s body diverges (first-wins; no 409 for payload mismatch).
- Applies to all types, including duplicate `RocketLaunched` with a divergent body.

---

## 4. Message ingestion semantics

### 4.1 Transport

- Test program: `POST` JSON to `http://localhost:8088/messages` (default).
- Lunar runs the program with **default values** (`--message-delay=500ms`, `--concurrency-level=1` in the example; defaults are what they use).
- Service should listen so that URL works (host/port **8088**, path **`/messages`** unless Driver changes and documents run instructions).

### 4.2 Ordering

- Messages arrive **out of order** within a channel.
- `messageNumber` is the order within a channel.
- Cross-channel: independent; no global order required by brief.

### 4.3 Duplicates

- **At-least-once**: same `(channel, messageNumber)` may appear more than once.
- Idempotent apply: second delivery of an already-applied message must not double-apply speed deltas / etc.
- Treat `(channel, messageNumber)` as the idempotency key for application.

**DECIDED — first-wins on duplicate `(channel, messageNumber)` with different payload**

- First durable accept of a `(channel, messageNumber)` wins.
- A later POST with the **same** `(channel, messageNumber)` but a **different** payload is still **200** (idempotent success); **ignore** the second payload — do not overwrite stored message content, do not re-apply with the divergent body, do not return **409**.
- Same payload redelivery: also **200**, no double apply (unchanged).

### 4.4 Gaps

**DECIDED — option 3 (hybrid durable accept + ordered apply)**

- Persist each accepted message to Postgres `messages` immediately.
- Return **200** once durably accepted (idempotent on `(channel, messageNumber)`).
- Apply to `rockets` only in contiguous `messageNumber` order (apply loop).
- Rejected for this decision: pure in-memory buffer without durable accept; non-2xx until predecessors applied.

**DECIDED B — apply strategy (durable short txn + async contiguous apply, multi-pod safe)**

Trade-off we are buying: ingest stays cheap and idempotent; correctness of ordered apply is owned by Postgres, not by which JVM happens to hold an in-memory queue.

- **Ingest:** durable insert of the accepted message in a **short transaction**, then return **200** (idempotent on `(channel, messageNumber)`). Mark work available for apply in the same durability boundary (e.g. insert into `messages` and/or upsert a dirty-channel row) so a crash after 200 never loses the fact that apply is owed.
- **Apply:** async workers (one or more replicas of the same Spring Boot deployable) poll Postgres, **claim** pending work with `SELECT … FOR UPDATE SKIP LOCKED`, and apply contiguous `messageNumber`s per channel. Poll/claim against the DB is the **source of truth** for who owns apply work.
- **Per-channel exclusive apply:** only one worker applies a given channel at a time, enforced by a DB lock on the claim (dirty-channel row and/or the unapplied message rows being claimed) — not by a JVM-local mutex alone.
- **Local wake hint (optional):** after ingest, a same-pod nudge (in-memory queue, condition, executor) may reduce poll latency. It is a **hint only** — correctness must not depend on it. Another pod (or the same pod after restart) must still discover and claim work via Postgres.
- **Optimistic concurrency on state:** `rockets.version` (see §6) guards concurrent state writes; claim exclusivity is the primary apply serialisation; version is the safety net / explicit CAS for the rocket row.

**Rejected**

- **A — sync giant-txn apply in the ingest request:** ties HTTP latency and lock duration to apply work; collapses under prod-like RPS and multi-pod load; wrong shape for at-least-once redelivery.
- **In-memory-only work queue as sole coordinator:** fine for a single-process demo; **not** sufficient across pods — work and ownership evaporate on restart / land on the wrong replica / cannot be claimed by peers.

### 4.5 HTTP status for success / retry

**DECIDED — HTTP status policy for `POST /messages`**

- Brief: messages **redelivered** on **non-2xx**.
- **200** always when the server has accepted responsibility — including durable accept of a new message **and** idempotent duplicates of an already-accepted `(channel, messageNumber)`. Do **not** use `202` / `204` for success.
- **400** for permanently bad payloads: malformed JSON, unknown `messageType`, missing required fields, **negative `by` on speed events** (so bugs surface; do not 2xx+ignore). See §3.3.
- **5xx** (e.g. **503**) for transient failures so the harness retries.

### 4.6 Envelope validation (minimum)

Required on ingest (recommendation):

- `metadata.channel`, `metadata.messageNumber`, `metadata.messageType`
- `metadata.messageTime` present as string (parse optional)
- `message` object shape matching type

**OPEN — strict schema validation depth?**  
Recommendation: validate required fields per type; reject otherwise with 400. **In scope and DECIDED for speed events:** `by` must be non-negative; negative `by` → **400** (see §3.3).

---

## 5. REST API sketch

Base: service on port **8088** for harness compatibility.

**DECIDED — API paths, list sort, unknown rocket, pagination**

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/messages` | Ingest one message (test program) |
| `GET` | `/rockets` | List all rockets |
| `GET` | `/rockets/{id}` | Current state of one rocket (`{id}` = channel UUID string) |

- Paths are exactly `/messages`, `/rockets`, `/rockets/{id}` — **not** `/api/v1/...`.
- List sorting (brief: “preferably with some kind of sorting”):
  - `GET /rockets?sort=speed|mission|type` (or `sortBy`)
  - `order=asc|desc` (default `asc`)
  - Default sort: by `id` / channel for stability
- **404** for `GET /rockets/{id}` when the rocket is unknown (not empty body / 200).
- **No pagination** for this challenge scale.

**OPEN — response JSON field names.**  
Recommendation: clear camelCase mirroring domain (`id`, `type`, `speed`, `mission`, `exploded`, `explosionReason`, …). Document in README when implementing.

**YAGNI:** no auth headers, no CORS middleware.

---

## 6. Persistence & concurrency

**DECIDED — persistence store**

- **Not** full event sourcing; **no Kafka**.
- **One current-state record per rocket** (channel); update columns as messages apply.
- **Track applied `messageNumber`s per channel** for dedupe / order.
- **Exercise storage:** Postgres in Docker.
- **Prod scale note (docs only, not implemented):** Aurora-style writer for writes; readers for `GET` list/get when eventual consistency is OK.
- **Rejected for this exercise:** in-memory as primary; H2 as prod story; DynamoDB (stayed shortlisted but not chosen).

**DECIDED — schema shape (`messages` + `rockets`, with `version`)**

Logical model (column names illustrative; migration details belong in plan/impl):

| Table | Role |
| --- | --- |
| `messages` | Durable accept store. Unique on `(channel, message_number)`. Payload + metadata retained so apply can be replayed idempotently after gaps close. |
| `rockets` | Current-state projection per channel (type, speed, mission, exploded, …). Includes **`version`** for optimistic concurrency on state updates. |
| Dirty-channel / claim surface | Either a dedicated dirty-channels (or work-queue) table, or claim directly over unapplied `messages` rows — implementation pick, same contract: claimable via `FOR UPDATE SKIP LOCKED`. |

- Idempotency key for ingest/apply: `(channel, messageNumber)`.
- Applied progress: highest contiguous applied number per channel (column on `rockets` and/or derived from applied flags) — exact representation is plan-level, not an OPEN on coordination strategy.

**DECIDED — concurrency & multi-pod apply coordination**

Harness defaults use `--concurrency-level=1`; the design still targets **prod-ready multi-replica** behaviour, not a junior single-process shortcut.

| Concern | Decision |
| --- | --- |
| Work coordinator | **Postgres** — poll + `FOR UPDATE SKIP LOCKED` claim on dirty channels and/or unapplied messages. DB claim is source of truth. |
| Per-channel apply | **Exclusive** via the DB claim lock for that channel’s work. Concurrent POSTs across channels OK; parallel apply across channels OK; never two appliers on the same channel. |
| State write safety | **`rockets.version`** optimistic concurrency (CAS / version check on update). |
| Cross-pod | In-memory queue alone is **not** sufficient. Optional same-pod wake after ingest is latency-only; peers and restarts must progress via poll/claim. |
| Ingest vs apply | Short durable ingest txn; apply async. Concurrent ingest of different `(channel, messageNumber)` pairs is fine; duplicates collapse on the unique key. |

**Rejected:** in-memory-only work queue as sole coordinator; sync giant-txn apply on the ingest path (see §4.4).

**DECIDED — stack**

- **Java 21**
- **Spring Boot 3.x**
- **Maven**
- No extra frameworks unless needed for tests; scaffold = phase 0 once implementation starts.

**DECIDED — deployment topology**

- **Single** Spring Boot deployable (ingest + apply + read in one artifact / logical packages `ingest` / `apply` / `read`).
- **Scale by replicas** of that same deployable — not by splitting into microservices for this exercise.
- Multi-pod correctness comes from §4.4 / concurrency decisions above (DB claim + `version`), not from sticky sessions or “run one replica only.”
- **No** multi-module Maven/Gradle split and **no** microservices for this exercise.
- README **may** note a future split along package seams; do not implement that split here.

**OPEN — apply-worker knobs (non-coordination)**

Coordination strategy is **DECIDED** (Postgres claim / `SKIP LOCKED` / per-channel exclusive / optional wake hint). Remaining plan-level knobs:

- Poll cadence, batch size, backoff under empty claim
- Exact Spring trigger (`@Scheduled` vs loop/executor) — any option is fine if it polls the DB claim path
- Exact txn boundaries per claimed batch (keep short; commit applied progress + version bump together)

These are tuning/implementation detail, not a reopening of in-memory-as-coordinator.

---

## 7. Errors & edge cases

| Case | Expected handling |
| --- | --- |
| Duplicate `(channel, messageNumber)` (same or different payload) | **DECIDED** (§4.3): first-wins; **200**; ignore second payload; no double apply; no **409** |
| Out-of-order / gap | Per §4.4 DECIDED (durable accept + contiguous apply via Postgres claim / `SKIP LOCKED`; DECIDED B) |
| Malformed JSON | **400** |
| Unknown `messageType` | **400** |
| `RocketLaunched` (or any type) redelivered same number | Covered by first-wins duplicate rule above |
| List when empty | `200` + `[]` |
| Get unknown rocket | **404** (§5 DECIDED) |
| Exploded then further events | **DECIDED** (§3.3): sticky `exploded`; still apply later contiguous messages |
| Negative `by` on speed increase/decrease | **DECIDED** (§3.3): **400** schema validation |
| Resulting `speed` ≤ 0 after apply | **DECIDED** (§3.3): **allowed**; no clamp |
| Huge `by` / overflow | **OPEN** — use long; document |

Do **not** assume OWASP/auth/CORS unless Driver opens them.

---

## 8. Verification expectations

Assessment values automated tests and that you can defend correctness.

Recommended test types (process preference: TDD; unit + integration + e2e with edge cases):

- **Unit**: event apply / reducer pure logic; idempotency; speed/mission/explode transitions.
- **Integration**: HTTP `POST /messages` + `GET` state against Spring context / MockMvc or Testcontainers if DB chosen.
- **E2E / harness-oriented**: scripted sequence including out-of-order + duplicate; optional run against `./rockets` when binary available.
- Edge cases: gaps, duplicates, explode, mission change, list sort.

**DECIDED — coverage bar**

- Automated test coverage **greater than 90%**.
- Prioritize critical paths (ingest idempotency, ordering, read APIs) within that bar.
- Living `docs/REQUIREMENTS_CHECKLIST.md` expected by process when implementation starts.

---

## 9. AI_USAGE / docs deliverables (process)

Expected by Driver/Car process (not Lunar README invent, but submission/interview readiness):

| Deliverable | Purpose |
| --- | --- |
| `docs/BRIEF.md` | Challenge capture (this pass) |
| `docs/SPEC.md` | This resolved/OPEN spec |
| `docs/plans/` | Master + per-phase plans **later** — after Driver clarity; **not created in this pass** |
| `docs/REQUIREMENTS_CHECKLIST.md` | Living checklist during implementation |
| `AI_USAGE.md` | Honest log of AI use with point time estimates; interview prep |
| README (runbook) | How to build, run on 8088, hit APIs, run tests / harness |

Interview (from brief): describe AI use at high level; reflect on agentic coding; present breadth-first ~10 minutes.

---

## 10. Open decisions checklist for Driver (decide-before-code)

Numbered list — please decide before implementation / master plan:

1. **Stack** — **DECIDED**: Java 21, Spring Boot 3.x, Maven; no extra frameworks unless needed for tests. See §6.
2. **Persistence** — **DECIDED**: not full event sourcing; no Kafka; Postgres `messages` + `rockets` (current-state, including **`version`**); track applied `messageNumber`s per channel for dedupe/order; exercise storage = Postgres in Docker; prod scale note (docs only) = Aurora-style writer + readers for GET when eventual consistency OK; rejected = in-memory as primary, H2 as prod story, DynamoDB. See §6.
3. **Gap handling / apply strategy** — **DECIDED** (option 3 + **B**): durable insert → **200**; contiguous apply async via **Postgres poll/claim (`FOR UPDATE SKIP LOCKED`)** on dirty channels and/or unapplied messages; per-channel exclusive apply; optional local wake hint only. **Rejected:** sync giant-txn apply on ingest; **in-memory-only work queue as sole coordinator** (not multi-pod safe). See §4.4 / §6. **OPEN (knobs only):** poll cadence, batch size, Spring trigger shape, exact apply txn boundaries — not coordination strategy.
4. **HTTP success codes** — **DECIDED**: always **200** for accepted `POST /messages` (including idempotent duplicates); **400** for malformed / unknown type; **5xx** for transient. See §4.5.
5. **Deployment topology** — **DECIDED**: single Spring Boot deployable; logical packages `ingest` / `apply` / `read`; **scale by replicas**; no multi-module or microservices for the exercise; README may note a future split. See §6.
6. **API paths, list sort, 404, pagination** — **DECIDED**: `POST /messages`, `GET /rockets`, `GET /rockets/{id}`; sort via `sort=speed|mission|type` (or `sortBy`) + `order=asc|desc` (default `asc`), default sort by `id`/channel; **404** for unknown rocket; **no pagination**. See §5.
7. **Response shape**: fields to expose (`exploded`, timestamps, `lastMessageNumber`, etc.).
8. **Post-explode / first-wins / speed `by` & resulting speed / missing launch** — partially decided:
   - **DECIDED — post-explode:** sticky `exploded` flag; still apply later contiguous events. See §3.3 / §7.
   - **DECIDED — first-wins:** duplicate `(channel, messageNumber)` with different payload → **200**, ignore second (no 409). See §3.3 / §4.3 / §7.
   - **DECIDED — speed `by` / resulting speed:** reject negative `by` with **400**; allow resulting `speed` ≤ 0 (no clamp). See §3.3 / §7.
   - **OPEN — missing / late `RocketLaunched`:** buffer vs invent-from-non-launch still open. See §3.3.
9. **Concurrency approach** — **DECIDED**: multi-pod via Postgres `FOR UPDATE SKIP LOCKED` claim; per-channel exclusive apply; `rockets.version` optimistic concurrency; in-memory wake optional/non-authoritative. See §4.4 / §6.
10. **Test coverage bar** — **DECIDED**: greater than **90%** coverage; unit + integration + e2e as recommended in §8.
11. **Phases**: Driver-owned; agents may suggest after this SPEC is approved — **no master plan until requirements clarity / remaining OPEN decisions**.
12. **Hand-in**: still no push/ZIP until Driver authorizes.

---

## 11. Implementation stance (for later plans only)

- YAGNI unless an OPEN above is explicitly accepted.
- Spec-driven: master plan then phase plans under `docs/plans/` after Driver decisions.
- Scaffold is phase 0 (stack DECIDED); remaining domain OPENs in checklist §10 still need Driver before full implementation plans.
- Agents propose; Driver OKs direction, commits, push, and hand-in.
