# P0 — Scaffold & DB Implementation Plan

> **For agentic workers:** Implement only after Driver approves this plan. Prefer TDD (failing smoke tests first). Do **not** commit until Driver OK on the proposed plan-commit one-liner below. No push without Driver OK.

**Status:** implemented locally — awaiting Driver assess / combined commit OK (do not push without Driver).

**Goal:** Stand up a lean Spring Boot 3 / Java 21 / Maven service that builds, runs with Postgres in Docker on port **8088**, applies Flyway migrations for `messages` + `rockets` (with `version`) + a claimable work surface, and proves health/DB connectivity via TDD smoke tests — with empty package seams `ingest` / `apply` / `read` ready for P1–P3.

**Architecture:** Single Spring Boot deployable (not multi-module). Persistence is Postgres; Flyway owns schema. Claim surface is a dedicated `channel_work` table claimable via `SELECT … FOR UPDATE SKIP LOCKED` (same contract as SPEC §6; apply workers land in P2). No real ingest/apply/read business logic in P0.

**Tech stack:** Java 21 · Spring Boot 3.x · Maven · Spring Web · Spring JDBC or Spring Data JDBC (minimal) · Flyway · PostgreSQL · Testcontainers (or compose-backed integration) · Docker / docker-compose · Makefile

## Global constraints

- Port **8088** (harness-compatible).
- Packages: `ingest` / `apply` / `read` (logical seams only in P0).
- Schema shape per SPEC §6: `messages` + `rockets` (+ `version`) + claim surface.
- YAGNI: no auth, CORS, Kafka, H2-as-primary, multi-module, microservices, real domain handlers.
- Process: plan → Driver OK → implement → review → Driver assess → commit (msg OK first).
- Coverage bar >90% is a **P4** concern; P0 only needs smoke / migration tests.

---

## 1. Goals

1. Create a Maven + Spring Boot 3.x + Java 21 project that compiles and boots.
2. Establish package layout with empty (or compile-only stub) packages: `…ingest`, `…apply`, `…read`.
3. Ship ops files: `Dockerfile`, `.dockerignore`, `docker-compose.yml` (Postgres + app as needed), `Makefile`, `.gitignore`.
4. Add Flyway migrations that create:
   - `messages` with unique `(channel, message_number)`;
   - `rockets` current-state projection including `version`;
   - claim surface (`channel_work`) suitable for later `FOR UPDATE SKIP LOCKED`.
5. Configure server port **8088**; expose Actuator (or equivalent) health that reflects DB connectivity.
6. Prove via TDD smoke tests: context loads, migrations apply on empty DB, unique constraint on `(channel, message_number)` enforced, health/DB up.
7. Minimal local-up notes (full README runbook deferred to P4 if preferred).

---

## 2. Non-goals (out of scope for P0)

- Real `POST /messages` validation, durable ingest txn, or first-wins idempotency logic (**P1**).
- Live vendor harness runs against `localhost:8088/messages` (vary `--message-delay` / `--concurrency-level`) — that is **P1.1** after P1; P0 does not run `./rockets/<platform>/rockets`.
- Apply scheduler, contiguous apply, reducer / state machine, multi-pod claim workers (**P2**).
- `GET /rockets` / `GET /rockets/{id}` / sort (**P3**).
- Coverage >90%, harness re-run + read-API validation, full README, `AI_USAGE.md` polish (**P4**).
- Auth, CORS, OWASP, rate limiting, pagination, Kafka, event sourcing, multi-module Maven.
- Resolving remaining SPEC OPENs (response JSON field names, missing/late `RocketLaunched`, apply knobs, overflow) — not needed for scaffold.
- Git commit / push / hand-in (Driver gates).

---

## 3. File / layout sketch

Proposed root layout (illustrative; adjust package root name if Driver prefers `com.lunar.rockets` vs `io.lunar.rockets` — **propose `com.lunar.rockets`**):

```
.
├── .gitignore
├── .dockerignore
├── Dockerfile
├── Makefile
├── docker-compose.yml
├── pom.xml
├── README.md                    # minimal local-up notes only
├── src
│   ├── main
│   │   ├── java/com/lunar/rockets
│   │   │   ├── LunarRocketsApplication.java
│   │   │   ├── ingest/          # empty package (optional package-info.java)
│   │   │   ├── apply/           # empty package
│   │   │   └── read/            # empty package
│   │   └── resources
│   │       ├── application.yml  # server.port=8088, datasource, flyway
│   │       └── db/migration
│   │           └── V1__messages_rockets_channel_work.sql
│   └── test
│       ├── java/com/lunar/rockets
│       │   ├── SmokeIT.java              # context + health / DB
│       │   └── schema/SchemaIT.java      # migrations + unique constraint
│       └── resources
│           └── application-test.yml      # Testcontainers / override as needed
└── docs/plans/…                 # already present; not rewritten by impl
```

**Ops notes**

| File | Role in P0 |
| --- | --- |
| `pom.xml` | Spring Boot 3 parent, Java 21, web, jdbc, flyway, postgresql, actuator, testcontainers |
| `Dockerfile` | Multi-stage or JRE image; expose 8088; runs fat jar |
| `docker-compose.yml` | `postgres` service + optional `app` service wired to it |
| `Makefile` | `up` / `down` / `test` / `build` / `run` (see §5) |
| `.gitignore` | `target/`, IDE, `.env`, OS junk |
| `.dockerignore` | `target/`, `.git`, docs noise as appropriate |

**Package seams:** no controllers/services with business logic. Empty packages (or `package-info.java`) are enough so P1–P3 have a clear home.

---

## 4. Schema DDL sketch

**Recommendation (plan-level pick):** dedicated `channel_work` table as the claim surface. Apply (P2) claims rows with `FOR UPDATE SKIP LOCKED` for per-channel exclusive apply. Unapplied message rows remain the durable payload store; `channel_work` marks “apply owed” without scanning all messages.

Illustrative Flyway `V1__messages_rockets_channel_work.sql` (column names may be refined in impl; semantics locked):

```sql
-- messages: durable accept store (P1 writes; P2 reads for apply)
CREATE TABLE messages (
    channel           UUID         NOT NULL,
    message_number    BIGINT       NOT NULL,
    message_type      TEXT         NOT NULL,
    message_time      TIMESTAMPTZ  NULL,          -- parse optional at ingest
    payload           JSONB        NOT NULL,      -- full message body / envelope fragment
    accepted_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (channel, message_number)
);

-- rockets: current-state projection (P2 writes; P3 reads)
CREATE TABLE rockets (
    id                  UUID         PRIMARY KEY,  -- = channel
    type                TEXT         NULL,
    speed               BIGINT       NOT NULL DEFAULT 0,
    mission             TEXT         NULL,
    exploded            BOOLEAN      NOT NULL DEFAULT FALSE,
    explosion_reason    TEXT         NULL,
    last_applied_number BIGINT       NOT NULL DEFAULT 0,  -- contiguous progress
    version             BIGINT       NOT NULL DEFAULT 0,  -- optimistic concurrency
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- channel_work: claim surface for async apply (P1 upserts; P2 claims)
CREATE TABLE channel_work (
    channel       UUID         PRIMARY KEY,
    dirty_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    claimed_at    TIMESTAMPTZ  NULL,
    claimed_by    TEXT         NULL
);

CREATE INDEX channel_work_dirty_idx ON channel_work (dirty_at)
    WHERE claimed_at IS NULL;
```

**Contracts this enables later**

| Concern | How schema supports it |
| --- | --- |
| Idempotent ingest | `PRIMARY KEY (channel, message_number)` |
| Contiguous apply progress | `rockets.last_applied_number` |
| Optimistic concurrency | `rockets.version` |
| Multi-pod claim | `channel_work` + `SELECT … FOR UPDATE SKIP LOCKED` (P2) |
| Crash after 200 | P1 upserts `channel_work` in same durability boundary as insert |

**P0 does not** insert production rows or run claim SQL in app code — only migrate and verify schema exists / constraints hold in tests.

**Alternative considered:** claim directly over unapplied `messages` (SPEC allows). Rejected for P0 default in favor of explicit dirty-channel rows (clearer exclusive per-channel claim). Do not implement both.

---

## 5. Makefile targets

Proposed targets (names may vary slightly; behavior is the contract):

| Target | Intent |
| --- | --- |
| `make build` | `./mvnw -DskipTests package` (or `mvn`) |
| `make test` | Run unit + IT smoke (Testcontainers); must pass for exit |
| `make up` | `docker compose up -d` (Postgres; app optional) |
| `make down` | `docker compose down -v` (careful with volumes in docs) |
| `make run` | Boot app locally against compose Postgres on **8088** |
| `make migrate` | Optional: rely on Flyway-on-startup; or explicit flyway goal |
| `make smoke` | Alias: hit health endpoint after `up`+`run` (curl) |

Minimum documented happy path for Driver assess:

```text
make up && make test && make run
# app listens on http://localhost:8088
# GET /actuator/health (or /health) shows UP + DB
```

---

## 6. Configuration sketch (not implementation)

`application.yml` intent:

- `server.port: 8088`
- Datasource URL/user/password via env (`SPRING_DATASOURCE_*`) with compose defaults for local.
- `spring.flyway.enabled: true`
- Actuator health with DB indicator enabled.

Compose: Postgres 16 (or current stable) on internal network; publish 5432 for local run; app service optional for “all in Docker” path.

---

## 7. Test plan (TDD)

Write failing tests first; then scaffold just enough to pass.

### 7.1 Smoke / context

- Spring context loads with Testcontainers Postgres (preferred) or fixed compose URL in CI-less local.
- Health endpoint returns UP when DB is reachable (and fails clearly when not, if easy without flaking).

### 7.2 Schema / migration

- On empty DB, Flyway applies `V1` successfully (app start or FlywayTestUtils).
- Tables `messages`, `rockets`, `channel_work` exist.
- Inserting two rows into `messages` with the same `(channel, message_number)` fails (unique / PK enforced).
- `rockets` has a `version` column (query `information_schema` or insert/read round-trip).

### 7.3 Explicitly not in P0 tests

- HTTP `POST /messages` business cases, apply reducers, GET rockets, coverage percentage.

---

## 8. Exit criteria

P0 is done when all of the following hold:

1. **Build:** `make build` (or documented Maven) succeeds on Java 21.
2. **Runtime:** Documented path brings Postgres + app up; app reachable on **8088**.
3. **Health:** Health (or equivalent smoke) proves DB connectivity.
4. **Schema:** Flyway migrations create `messages`, `rockets` (with `version`), and `channel_work`; unique `(channel, message_number)` enforced by test.
5. **Layout:** Packages `ingest` / `apply` / `read` exist; no real business logic beyond stubs needed to compile.
6. **Ops files:** `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `Makefile`, `.gitignore` present and usable.
7. **Tests:** P0 smoke/schema tests pass under TDD narrative (failed first, then green).
8. **Process:** Independent review + Driver assess; checklist scaffold/migrations items marked when landed; **commit only after Driver OK** on message.
9. **No scope creep:** No P1–P3 business endpoints or apply workers.

---

## 9. Risks & mitigations

| Risk | Mitigation |
| --- | --- |
| Testcontainers flaky / Docker-in-Docker on worker | Prefer Testcontainers; document compose fallback for `make test` if environment blocks containers |
| Over-building claim columns (`claimed_by`, etc.) | Keep V1 minimal; P2 can add columns via `V2` if needed |
| Accidental controllers for `/messages` / `/rockets` | Non-goal; reject in review if present beyond health |
| Port conflict on 8088 | Document; fail fast in README notes |
| Choosing JSONB vs TEXT for payload | Prefer JSONB; reversible in later migration if needed |
| Package root naming bikeshed | Default `com.lunar.rockets`; change only if Driver asks |
| Treating empty packages as “incomplete” | Acceptable for P0; seams are intentional |

---

## 10. Task breakdown (post-approval implementation order)

Execute only after Driver OK on this plan. Checkbox tracking for the implementer:

### Task 1: Failing smoke / schema tests

- [ ] Add IT that expects context load + health/DB UP (will fail without app).
- [ ] Add IT that expects migrated tables + unique constraint (will fail without Flyway SQL).

### Task 2: Maven / Spring Boot scaffold

- [ ] `pom.xml` + wrapper; `LunarRocketsApplication`; `application.yml` port 8088.
- [ ] Empty `ingest` / `apply` / `read` packages.
- [ ] Wire datasource + Flyway + Actuator dependencies.

### Task 3: Flyway V1

- [ ] Add `V1__messages_rockets_channel_work.sql` per §4.
- [ ] Re-run schema IT until green.

### Task 4: Ops files

- [ ] `.gitignore`, `.dockerignore`, `Dockerfile`, `docker-compose.yml`, `Makefile`.
- [ ] Minimal README local-up notes.

### Task 5: Green smoke + Driver gate

- [ ] `make test` green; manual `make up` + health check on 8088.
- [ ] Update `docs/REQUIREMENTS_CHECKLIST.md` scaffold / migrations items when landed.
- [ ] Propose commit message; **wait for Driver OK** before committing.

---

## 11. Checklist items this phase will close (when implemented)

From `docs/REQUIREMENTS_CHECKLIST.md` (mark only after code lands):

- [ ] Scaffold / phase 0 project created
- [ ] Service listens on port **8088**
- [ ] Postgres schema / migrations implemented

Related process item remains until all phase plans approved:

- [ ] Master + per-phase plans written under `docs/plans/` and approved

---

## 12. Proposed plan-commit one-liner

*(For after Driver approves this plan — do **not** commit now.)*

```text
docs: add P0 scaffold and DB phase plan
```

---

## 13. Gate

**Next ask for Driver:** approve this P0 plan (or request changes). On approval, implement Tasks 1–5 with TDD; then independent review → Driver assess → commit using the one-liner above (or Driver-edited message). **No push** without Driver OK.
