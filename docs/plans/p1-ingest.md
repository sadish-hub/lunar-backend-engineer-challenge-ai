# P1 — Ingest `POST /messages` Implementation Plan

> **For agentic workers:** Prefer TDD (failing tests first). Combined plan+impl commit after Driver OK (per preferences).

**Status:** Driver-approved and implemented (P1). Ready for P1.1 harness smoke.

**Goal:** Accept Lunar harness messages at `POST /messages` with schema validation, idempotent durable insert on `(channel, messageNumber)`, HTTP **200** / **400** / **5xx** per SPEC, concurrent-safe writes, and mark `channel_work` dirty in the same durability boundary as accept — so a crash after **200** never loses owed apply. **No apply loop and no read APIs in this phase.**

**Architecture:** Build on P0 (`messages`, `rockets`, `channel_work` via Flyway). Logic lives under package `com.lunar.rockets.ingest`. Ingest = short Postgres transaction: validate → insert `messages` (first-wins on PK) → upsert `channel_work` dirty → commit → **200**. Contiguous apply to `rockets` is **P2**. Live vendor harness smoke is **P1.1** (follows this phase). Read APIs are **P3**.

**Tech stack (locked):** Java 21 · Spring Boot 3.x · Maven · Spring Web · Spring JDBC (or minimal Data JDBC) · Flyway schema from P0 · PostgreSQL · Testcontainers for IT · **networknt json-schema-validator** + classpath schemas under `src/main/resources/schemas/`

**Depends on:** P0 scaffold + DB (schema + port **8088** + empty `ingest` package).

**Follows:** **P1.1** — live `./rockets/<platform>/rockets` against `http://localhost:8088/messages` (defaults + non-default delay/concurrency). Do **not** fold harness runs into P1 impl scope; plan/implement P1.1 after P1 lands.

---

## Global constraints

- Path exactly `POST /messages` on port **8088** (not `/api/v1/...`).
- Success status is always **200** (never **202** / **204**), including idempotent duplicates.
- Idempotency key: `(channel, messageNumber)` ↔ DB `PRIMARY KEY (channel, message_number)`.
- First-wins: keep first accepted payload; divergent redelivery → **200**, ignore second body, no **409**.
- Negative `by` on speed events → **400** (schema); do not invent apply/clamp behavior here.
- Mark apply owed via `channel_work` upsert in the **same** durability boundary as the message insert.
- YAGNI: no auth, CORS, Kafka, apply workers, GET rockets, coverage >90% chase (P4).
- Process: plan → Driver OK → implement (TDD) → review → Driver assess → commit (msg OK first).
- Do not guess remaining SPEC OPENs (missing/late launch apply policy, response field names for reads, overflow UX beyond preferring `long` for numeric fields).

---

## 1. Goals

1. Implement `POST /messages` that accepts the BRIEF/SPEC JSON envelope (`metadata` + `message`).
2. Validate required metadata and per-`messageType` payload shapes; reject permanently bad input with **400**.
3. Persist accepted messages into `messages` in a **short** durable transaction; return **200** once committed.
4. Enforce idempotent accept on `(channel, messageNumber)`: duplicate POST → **200**, no second row, first payload retained.
5. Upsert `channel_work` for the channel (set/refresh dirty) in the **same** txn as a successful new insert so apply is owed after crash-following-200.
6. Map transient failures (DB down, unexpected infra) to **5xx** (e.g. **503**) so the harness retries.
7. Prove concurrent-safe behavior under parallel distinct keys and parallel duplicate keys via TDD integration tests.
8. Leave `rockets` untouched; leave apply/read packages without business endpoints.

---

## 2. Non-goals (out of scope for P1)

- Contiguous apply / reducer / state machine / `SKIP LOCKED` claim workers (**P2**).
- Writing or updating `rockets` rows from ingest.
- `GET /rockets` / `GET /rockets/{id}` / sort (**P3**).
- Live vendor harness runs (`./rockets/<platform>/rockets`) — that is **P1.1** immediately after P1.
- Coverage >90%, full README, `AI_USAGE.md` polish (**P4**); P4 also **repeats** harness + validates reads.
- Auth, CORS, OWASP, rate limiting, pagination, Kafka, event sourcing, multi-module.
- Resolving OPEN missing/late `RocketLaunched` invent-vs-buffer (apply concern); P1 only stores validated envelopes.
- Optional same-pod wake hint (latency only) — may be added in P2; not required for P1 correctness.
- Git commit / push / hand-in (Driver gates).

---

## 3. API contract

### 3.1 Endpoint

| Item | Contract |
| --- | --- |
| Method / path | `POST /messages` |
| Port | **8088** |
| Content-Type | `application/json` |
| Success | **200** (body optional/empty OK; do not use 202/204) |
| Permanent client error | **400** |
| Transient server error | **5xx** (prefer **503** when signaling “retry later”) |

### 3.2 Request envelope (minimum)

```json
{
  "metadata": {
    "channel": "<uuid-string>",
    "messageNumber": <positive integer>,
    "messageTime": "<string>",
    "messageType": "<one of five types>"
  },
  "message": { /* type-specific object */ }
}
```

**Required metadata (all must be present):**

| Field | Rule |
| --- | --- |
| `metadata.channel` | Present; parseable UUID string (rocket ID) |
| `metadata.messageNumber` | Present; integer (store as `BIGINT`); reject missing/non-integer |
| `metadata.messageTime` | Present as string (parse to timestamptz **optional**; store null if unparsable is OK per SPEC “parse optional”) |
| `metadata.messageType` | One of: `RocketLaunched`, `RocketSpeedIncreased`, `RocketSpeedDecreased`, `RocketExploded`, `RocketMissionChanged` |

**Per-type `message` validation:**

| `messageType` | Required fields | Extra rules |
| --- | --- | --- |
| `RocketLaunched` | `type` (string), `launchSpeed` (integer), `mission` (string) | Prefer `long`-capable integer for `launchSpeed` |
| `RocketSpeedIncreased` | `by` (integer) | **`by` must be ≥ 0**; negative → **400** |
| `RocketSpeedDecreased` | `by` (integer) | **`by` must be ≥ 0**; negative → **400** |
| `RocketExploded` | `reason` (string) | |
| `RocketMissionChanged` | `newMission` (string) | |

Unknown `messageType` → **400**. Malformed JSON / missing `metadata` or `message` / missing required fields → **400**.

### 3.3 Success / idempotency semantics

| Case | HTTP | Persistence |
| --- | --- | --- |
| First accept of `(channel, messageNumber)` | **200** | Insert `messages` row; upsert `channel_work` dirty for channel; commit |
| Redelivery same key, same or divergent payload | **200** | **No** overwrite of stored payload; **no** second row; first-wins |
| Bad schema / unknown type / negative `by` | **400** | No durable accept |
| Transient failure after/during write | **5xx** | No silent success; harness may retry |

**Note:** Returning **200** on duplicate does **not** require re-dirtying `channel_work` if the message was already accepted (apply already owed or applied). Prefer: on unique-key conflict, treat as idempotent success without mutating first payload. Optionally no-op `channel_work` on pure duplicate; if a no-op is simpler to keep “always touch dirty on any 200 path,” document that it must not create a second `messages` row.

### 3.4 What P1 does **not** promise

- That `rockets` reflects the message (apply is P2).
- That out-of-order numbers are rejected (they are **accepted** and stored; gaps are fine).
- A particular JSON response body shape for POST (YAGNI unless tests need a minimal ack).

---

## 4. Durability & concurrency design

### 4.1 Short ingest transaction (DECIDED B)

In one DB transaction:

1. Attempt `INSERT` into `messages` (`channel`, `message_number`, `message_type`, `message_time` nullable, `payload` JSONB of accepted envelope or `message` fragment — pick one and keep consistent; recommend full envelope or at least type + message object needed for later apply).
2. On insert success: `INSERT … ON CONFLICT (channel) DO UPDATE` into `channel_work` setting `dirty_at = now()` (and clear claim fields only if P2 contract requires; for P1, refreshing `dirty_at` is enough — do **not** invent apply claim logic).
3. Commit → HTTP **200**.
4. On unique violation / conflict for `(channel, message_number)`: abort insert path for that row; return **200** without changing stored payload (first-wins).

### 4.2 Concurrent safety

| Scenario | Expected |
| --- | --- |
| Parallel POSTs, distinct `(channel, messageNumber)` | All durable; all **200** |
| Parallel POSTs, same key | Exactly one `messages` row; all callers **200**; first payload retained |
| Parallel POSTs, same channel, different numbers | Multiple rows; single `channel_work` row for channel (upsert) |

Correctness relies on Postgres PK / upsert, not JVM locks. Multi-pod ingest is safe by the same unique key.

### 4.3 Crash after 200

If the process dies after commit, `messages` row + `channel_work` dirty remain → P2 can discover owed apply. Do **not** return **200** before commit.

---

## 5. File / layout sketch

Illustrative (names may vary slightly; seams locked):

```
src/main/resources/schemas/
  envelope.json                 # metadata + message envelope
  RocketLaunched.json           # per-type message payloads
  RocketSpeedIncreased.json
  RocketSpeedDecreased.json
  RocketExploded.json
  RocketMissionChanged.json

src/main/java/com/lunar/rockets/ingest/
  MessagesController.java       # POST /messages
  MessageEnvelope.java          # request DTO / records
  MessageType.java              # enum of five types
  IngestValidator.java          # networknt JSON Schema → 400
  MessageIngestService.java     # txn: insert + channel_work upsert + first-wins
  IngestExceptionHandler.java   # map validation → 400; transient → 5xx (or @ControllerAdvice)

src/test/java/com/lunar/rockets/ingest/
  IngestValidatorTest.java      # unit validation matrix
  MessagesIngestIT.java         # MockMvc/WebTestClient + Testcontainers
  MessagesConcurrencyIT.java    # parallel POSTs (optional separate class)
```

No changes required to `apply` / `read` beyond leaving them empty. No new Flyway version unless P0 schema is insufficient (it should not be — V1 already has `messages` + `channel_work`).

---

## 6. Test plan (TDD)

Write failing tests first; implement just enough to pass.

### 6.1 Unit — validation matrix

| Case | Expect |
| --- | --- |
| Valid `RocketLaunched` envelope | Accept |
| Valid speed± / exploded / mission-changed | Accept |
| Malformed JSON (integration) | **400** |
| Missing `metadata` / `message` | **400** |
| Missing `channel` / `messageNumber` / `messageTime` / `messageType` | **400** |
| Unknown `messageType` | **400** |
| Missing type-specific fields | **400** |
| Negative `by` on increase/decrease | **400** |
| `by == 0` | Accept (non-negative) |
| Invalid channel (not UUID) | **400** |

### 6.2 Integration — durable insert & status policy

| Case | Expect |
| --- | --- |
| First POST valid message | **200**; row in `messages`; `channel_work` row dirty for channel |
| Duplicate same payload | **200**; still one row; payload unchanged |
| Duplicate divergent payload | **200**; first payload retained (first-wins); no **409** |
| Out-of-order `messageNumber` (e.g. 3 before 1) | **200**; row stored; **no** `rockets` mutation required |
| Bad payload | **400**; no `messages` row |
| DB unavailable / forced failure (if practical) | **5xx** |

### 6.3 Integration — concurrency

| Case | Expect |
| --- | --- |
| N concurrent POSTs distinct keys | N rows; all **200** |
| N concurrent POSTs same key | 1 row; all **200**; first-wins payload |

### 6.4 Explicitly not in P1 tests

- Apply/reducer transitions, sticky explode apply, speed clamp (there is none).
- `GET /rockets` assertions.
- Live `./rockets` binary (**P1.1**).
- Coverage percentage gate (**P4**).

---

## 7. Exit criteria

P1 is done when all of the following hold:

1. **Contract:** `POST /messages` on **8088** matches §3 (validation + **200**/**400**/**5xx**).
2. **Durable:** Accepted messages persist in `messages` with unique `(channel, message_number)`.
3. **Idempotent:** Duplicate / divergent redelivery → **200**, first payload kept, single row.
4. **Dirty work:** Successful **new** accept upserts `channel_work` in the same txn; crash-after-200 still leaves claimable work for P2.
5. **Concurrent-safe:** Tests cover parallel distinct and duplicate keys.
6. **No apply/read:** No contiguous apply workers; no GET rocket endpoints; `rockets` not written by ingest.
7. **TDD:** Validation + ingest ITs written failing-first and green.
8. **Checklist:** Mark landed ingest items in `docs/REQUIREMENTS_CHECKLIST.md` (see §10).
9. **Process:** Independent review + Driver assess; **commit only after Driver OK**; prefer combined plan+impl commit.
10. **Handoff:** Ready for **P1.1** harness ingest smoke (not executed as part of P1 exit unless Driver explicitly expands scope).

---

## 8. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Returning 200 before commit | Structure boundary encloses insert + dirty upsert; respond after commit |
| Overwriting payload on conflict | Use insert-or-detect-conflict; never `ON CONFLICT DO UPDATE` of payload |
| Dirty not set on accept | Same txn as insert; IT asserts `channel_work` after first accept |
| Accidental apply/read scope creep | Review rejects rockets writes / GET endpoints / harness as P1 deliverables |
| Strict vs lenient `messageTime` parse | SPEC: present as string; parse optional — reject missing string, allow null DB if unparsable |
| Integer overflow on `by` / `launchSpeed` | Prefer `long` / `BIGINT`; document; do not invent clamp |
| Duplicate path re-dirties forever | Harmless for P1; P2 claim must tolerate refreshed `dirty_at` |
| Treating gap as 400 | Forbidden — gaps are durable-accept; apply waits in P2 |

---

## 9. Task breakdown (post-approval implementation order)

Execute only after Driver OK on this plan:

### Task 1: Failing validation + ingest tests

- [x] Unit validation matrix (§6.1) — red.
- [x] IT: first accept → 200 + `messages` + `channel_work` — red.
- [x] IT: duplicate same/divergent → 200 first-wins — red.
- [x] IT: 400 cases + concurrency sketches — red.

### Task 2: DTOs + validator + controller

- [x] Envelope / type models; `IngestValidator` (JSON Schema files + networknt).
- [x] `POST /messages` controller mapping validation failures to **400**.

### Task 3: Durable ingest service

- [x] Short txn: insert `messages` + upsert `channel_work`.
- [x] Conflict → idempotent **200** without payload overwrite.
- [x] Transient errors → **5xx**.

### Task 4: Green tests + checklist

- [x] All §6 tests green.
- [x] Update `docs/REQUIREMENTS_CHECKLIST.md` for landed P1 items.
- [x] Propose commit message; Driver OK to push with one-liner below.

### Task 5: Stop — hand off to P1.1 plan/impl

- [x] Do **not** run full vendor harness as a P1 gate (document readiness for P1.1).
- [ ] Next phase plan: P1.1 harness ingest smoke.

---

## 10. Checklist items this phase will close (when implemented)

From `docs/REQUIREMENTS_CHECKLIST.md` (mark only after code lands):

- [x] Durable message insert on accept
- [x] Implement durable ingest path
- [x] Implement `POST /messages`
- [x] Accept JSON envelope with `metadata` + `message`
- [x] Required metadata: `channel`, `messageNumber`, `messageTime`, `messageType`
- [x] `RocketLaunched` payload: `type`, `launchSpeed`, `mission`
- [x] `RocketSpeedIncreased` / `RocketSpeedDecreased` payload: `by`
- [x] `RocketExploded` payload: `reason`
- [x] `RocketMissionChanged` payload: `newMission`
- [x] Duplicate first-wins behavior implemented
- [x] Speed `by` validation (+ no-clamp arithmetic remains a P2 apply concern; validation half closes here)
- [x] At-least-once / redelivery on non-2xx respected (status policy)

Related process (partial):

- [x] Master + per-phase plans … (P1 plan Driver-approved; P1.1–P4 plans still TBD)

**Explicitly leave unchecked for later phases:** apply reducer, async claim, GET rockets, harness binary runs (P1.1/P4), coverage measurement.

---

## 11. P1.1 note (follows — not this plan’s impl)

After P1 is assessed/landed:

- Write/approve **P1.1** plan if not already detailed enough in master plan.
- Run real `./rockets/<platform>/rockets launch "http://localhost:8088/messages"` (arch-appropriate binary).
- Include defaults **and** at least one non-default `--message-delay` / `--concurrency-level` run.
- Prove ingest under load/reorder; do **not** require apply/read correctness until P2–P4.
- P4 will **repeat** harness and validate read APIs.

---

## 12. Proposed commit one-liner

*(For after Driver approves plan **and** impl — prefer combined plan+impl; do **not** commit now.)*

```text
feat: add durable idempotent POST /messages ingest
```

---

## 13. Gate

**Landed.** P1 TDD impl + checklist update; combined plan+impl commit/push authorized by Driver. **Next:** P1.1 harness ingest smoke plan/impl.
