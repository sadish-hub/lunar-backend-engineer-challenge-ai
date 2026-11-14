# Architecture — Lunar Rockets (as built)

System overview of the **implemented** design. Decisions come from [`SPEC.md`](SPEC.md) (option **3 + B**), [`plans/master-plan.md`](plans/master-plan.md), and the code under `src/main/java/com/lunar/rockets/`. Deferred work (same-pod wake) lives in [`NEXT_STEPS.md`](NEXT_STEPS.md) — not built.

For runbook, APIs, and harness commands see the repo [`README.md`](../README.md).

---

## 1. Locked shape

| Area | As built |
| --- | --- |
| Strategy | **Option 3 + B**: durable ingest → **200**; async contiguous apply via Postgres claim |
| Stack | Java 21, Spring Boot 3.x, Maven; port **8088** |
| Deployable | One Spring Boot app; packages `ingest` / `apply` / `read` (+ shared `api`) |
| Storage | Postgres: `messages`, `rockets` (+ `version`), `channel_work` |
| Claim | `SELECT … FOR UPDATE SKIP LOCKED` on `channel_work` |
| State writes | `rockets.version` optimistic CAS |
| Domain | Sticky explode + continue apply; first-wins duplicates; speed may be ≤ 0 |
| Validation | JSON Schema (networknt) under `classpath:schemas/` |
| Apply trigger | **Poll-only** `@Scheduled`; wake-on-ingest deferred |
| Threads | `spring.threads.virtual.enabled=true` (Tomcat request threads) |
| Errors | Client / ingest transient → `ApiError` JSON |

**Not built (do not assume):** Kafka / event sourcing, microservices split, auth/CORS, pagination, same-pod wake-on-ingest, inventing rockets before `RocketLaunched`.

---

## 2. Package seams

Single artifact, logical packages (not multi-module):

```
com.lunar.rockets
├── LunarRocketsApplication
├── api/          # shared ApiError, ApiExceptionHandler, ApiErrorAttributes
├── ingest/       # POST /messages — validate, durable accept, dirty channel_work
├── apply/        # poll → claim → contiguous reducer → rockets CAS
└── read/         # GET /rockets, GET /rockets/{id}
```

| Package | Owns | Does not own |
| --- | --- | --- |
| `ingest` | Envelope + per-type JSON Schema; short insert txn; first-wins on unique key | Contiguous apply / rocket projection |
| `apply` | Scheduler, SKIP LOCKED claim, reducer, version CAS, `messages.applied` | HTTP ingest / read shaping |
| `read` | List/get + sort over `rockets` projection | Apply progress / claim |
| `api` | Uniform `{"status","error","message"}` error bodies | Business rules |

Replicas scale the same deployable: any pod can ingest; any pod can claim apply work via Postgres.

---

## 3. Persistence

Flyway: `V1__messages_rockets_channel_work.sql`, `V2__messages_applied.sql`.

```
┌─────────────────────┐     dirty upsert      ┌──────────────────┐
│      messages       │◄──────────────────────│   channel_work   │
│ PK (channel, mn)    │                       │ PK channel       │
│ payload JSONB       │                       │ dirty_at         │
│ applied BOOLEAN     │                       │ claimed_at/by    │
└──────────┬──────────┘                       └────────┬─────────┘
           │ apply contiguous                          │ FOR UPDATE
           │ mark applied                              │ SKIP LOCKED
           ▼                                           │
┌─────────────────────┐                                │
│       rockets       │◄───────────────────────────────┘
│ PK id (= channel)   │   version CAS on update
│ type, speed, mission│
│ exploded, reason    │
│ last_applied_number │
│ version             │
└─────────────────────┘
```

- **`messages`**: durable accept store. Unique `(channel, message_number)` enforces first-wins. `applied` tracks projection progress.
- **`channel_work`**: claim surface. Ingest upserts dirty on **new** insert only. Apply claims with lease / skip-locked.
- **`rockets`**: current-state projection. `last_applied_number` + `version` for contiguous progress and CAS.

---

## 4. Request + apply flow

### 4.1 End-to-end (happy / gap / catch-up)

```mermaid
sequenceDiagram
  participant H as Harness / client
  participant I as ingest
  participant DB as Postgres
  participant A as apply (any pod)
  participant R as read

  H->>I: POST /messages
  I->>I: JSON Schema validate
  alt invalid
    I-->>H: 400 ApiError
  else valid
    I->>DB: INSERT … ON CONFLICT DO NOTHING<br/>if inserted: UPSERT channel_work dirty
    I-->>H: 200
  end

  loop every lunar.apply.poll-interval-ms
    A->>DB: claim channel_work<br/>FOR UPDATE SKIP LOCKED
    alt claimed
      A->>DB: apply next contiguous mn<br/>CAS rockets.version<br/>mark messages.applied
      Note over A,DB: gap → stop; stay dirty<br/>caught up → delete work row
    end
  end

  H->>R: GET /rockets[/{id}]
  R->>DB: SELECT rockets
  R-->>H: 200 projection / 404 unknown
```

### 4.2 Ingest path (detail)

1. `MessagesController` → `IngestValidator` (envelope + type schema).
2. Negative `by` / unknown type / malformed → **400** `ApiError`.
3. `MessageIngestService` short `@Transactional`:
   - `INSERT … ON CONFLICT DO NOTHING` into `messages` (first payload wins).
   - If row inserted → `channel_work` dirty upsert.
   - Duplicate `(channel, messageNumber)` → no overwrite, no re-dirty required for correctness of already-stored payload; HTTP still **200**.
4. Transient DB failures → **503** `ApiError` (harness retries).

No apply work runs on the request thread. No in-memory wake (see §6).

### 4.3 Apply path (detail)

1. `ApplyClaimScheduler` (`@Scheduled` fixed delay, default **250ms**) → `ApplyWorker.runOnce`.
2. `ClaimService` / `ChannelClaimJdbc`: pick dirty (or expired lease) rows **`FOR UPDATE SKIP LOCKED`**, set `claimed_at` / `claimed_by`.
3. Per claimed channel, `ChannelApplyService.applyContiguous`:
   - Load `rockets` (or empty state); expect next `messageNumber = last_applied_number + 1`.
   - If missing → **gap**: stop; release claim but leave dirty.
   - Else parse → `RocketStateReducer` → write with **version CAS** → mark `messages.applied`.
   - Cap per claim: `lunar.apply.max-messages-per-claim`.
4. If unapplied remain → clear claim (stay dirty). Else delete `channel_work` row.
5. Version conflict → retry up to `lunar.apply.version-conflict-max-retries` (claim exclusivity is primary; CAS is safety net).

Different channels may apply in parallel across pods; the same channel is exclusive via the claim lock.

---

## 5. Domain rules (reducer)

Implemented in `apply.domain.RocketStateReducer`:

| Event | Effect |
| --- | --- |
| `RocketLaunched` | Create projection; set type / speed / mission. Re-launch number on existing rocket advances progress only (no second overwrite / un-explode). |
| `RocketSpeedIncreased` / `Decreased` | `speed ± by` (`long`). Result may be **≤ 0** (no clamp). |
| `RocketExploded` | Set `exploded` + reason (**sticky**). Later contiguous messages still apply. |
| `RocketMissionChanged` | Update mission. |

Non-launch before launch **fails closed** at apply (no invent-from-telemetry). Missing/late launch remains a SPEC OPEN; this build does not invent a rocket from speed/mission alone.

**First-wins** is enforced at ingest unique key: divergent redelivery keeps the first stored payload and never double-applies.

---

## 6. Poll-only apply; wake deferred

P2 ships **poll + DB claim only**. Correctness does not depend on same-pod hints.

Optional **wake-on-ingest** (latency only) is documented in [`NEXT_STEPS.md`](NEXT_STEPS.md): after a successful **new** insert, a same-pod nudge could run an early claim cycle still through SKIP LOCKED. Not implemented; poll interval bounds apply latency today.

---

## 7. Runtime knobs (as configured)

From `application.yml` (`lunar.apply.*`):

| Knob | Default | Role |
| --- | --- | --- |
| `enabled` | `true` | Gate scheduler |
| `poll-interval-ms` | `250` | Fixed delay between poll cycles |
| `claim-batch-size` | `8` | Channels claimed per tick |
| `max-messages-per-claim` | `100` | Contiguous apply cap per channel per claim |
| `claim-lease-seconds` | `60` | Stale claim reclaim |
| `version-conflict-max-retries` | `3` | CAS retry budget |

Virtual threads: `spring.threads.virtual.enabled=true` — request handling on Java 21 virtual threads. Apply remains the scheduled poller above (not a wake-driven executor).

---

## 8. HTTP surface & errors

| Method | Path | Success / notes |
| --- | --- | --- |
| `POST` | `/messages` | **200** accept (incl. duplicates); **400** schema/malformed; **503** transient |
| `GET` | `/rockets` | **200** list (`[]` if empty); sort `sort` + `order` (default `last_applied_number`); invalid → **400** |
| `GET` | `/rockets/{id}` | **200** projection; unknown → **404** |

Error body (`com.lunar.rockets.api.ApiError`):

```json
{"status":400,"error":"Bad Request","message":"…"}
```

Used for ingest validation / malformed / **503**, read invalid query / bad id / **404**, and shared handler paths for unknown resource / method-not-allowed. Success responses are not wrapped in `ApiError`.

Read projection fields: `id`, `type`, `speed`, `mission`, `exploded`, `explosionReason`.

---

## 9. Validation (JSON Schema)

Schemas under `src/main/resources/schemas/`:

- `envelope.json` — metadata + message object presence
- Per type: `RocketLaunched.json`, `RocketSpeedIncreased.json`, `RocketSpeedDecreased.json`, `RocketExploded.json`, `RocketMissionChanged.json`

`IngestValidator` loads them via networknt (JSON Schema 2020-12). Speed `by` non-negative is schema-enforced → **400** on violation.

---

## 10. Consistency model (interview)

- **Ingest:** at-least-once POST → durable first-wins row; HTTP **200** means “accepted for apply,” not “already projected.”
- **Apply:** eventual projection per channel once numbers are contiguous from 1 (and launch exists for non-launch events).
- **Read:** sees applied `rockets` only — after harness runs, wait until unapplied contiguous work drains before trusting GETs.
- **Multi-pod:** Postgres claim is source of truth; optional wake would never replace it.
- **Prod note (docs only):** Aurora-style writer for ingest/apply + readers for GET when eventual consistency is OK — not implemented here.

---

## 11. Related docs

| Doc | Role |
| --- | --- |
| [`SPEC.md`](SPEC.md) | DECIDED option 3+B, domain, API, concurrency |
| [`plans/master-plan.md`](plans/master-plan.md) | Phase roadmap; locked architecture table |
| [`NEXT_STEPS.md`](NEXT_STEPS.md) | Deferred wake-on-ingest |
| [`REQUIREMENTS_CHECKLIST.md`](REQUIREMENTS_CHECKLIST.md) | Living implementation checklist |
| [`../README.md`](../README.md) | Runbook, APIs, harness, trade-off summary |
