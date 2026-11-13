# Lunar Backend Engineer Challenge

Challenge materials and working notes live under [`docs/`](docs/).

- [Requirements](docs/REQUIREMENTS.md) — original challenge brief
- [Brief](docs/BRIEF.md)
- [Spec](docs/SPEC.md)
- [Requirements checklist](docs/REQUIREMENTS_CHECKLIST.md)
- [Next steps](docs/NEXT_STEPS.md) — deferred prod trade-offs (e.g. wake-on-ingest)

See also [`AGENTS.md`](AGENTS.md) for agent guidance.

## Local up (P0 scaffold)

Requires Java 21, Docker, and Maven Wrapper (`./mvnw`).

```bash
make up          # Postgres on localhost:5432 (user/db/password: lunar)
make test        # Testcontainers smoke + schema ITs
make run         # Spring Boot on http://localhost:8088
curl -s http://localhost:8088/actuator/health
```

All-in-Docker (optional): `docker compose up --build` (app + Postgres; app on **8088**).

Default datasource: `jdbc:postgresql://localhost:5432/lunar` (override with `SPRING_DATASOURCE_*`).

## Architecture / Apply concurrency

Ingest is durable and first-wins; apply is async. Workers claim dirty channels with Postgres `FOR UPDATE SKIP LOCKED` so **one channel has one claimer**, while different channels can run in parallel across pods. Out-of-order message numbers stay buffered until the contiguous next MN is present (gap → release claim, stay dirty).

```mermaid
sequenceDiagram
  participant H as Harness
  participant P1 as Pod A
  participant P2 as Pod B
  participant DB as Postgres

  Note over H,DB: Same channel id1 — OOO: MN=2 then MN=1

  H->>P1: POST MN=2
  P1->>DB: INSERT messages(id1,2)<br/>UPSERT channel_work dirty
  P1-->>H: 200

  par Both pods poll claim
    P1->>DB: SELECT channel_work<br/>FOR UPDATE SKIP LOCKED
    DB-->>P1: claim id1
    P2->>DB: SELECT … SKIP LOCKED
    DB-->>P2: skip id1 (locked)
  end

  P1->>DB: need MN=1? missing
  Note over P1: gap → stop apply
  P1->>DB: release claim (stay dirty)

  H->>P2: POST MN=1
  P2->>DB: INSERT messages(id1,1)<br/>dirty channel_work
  P2-->>H: 200

  P2->>DB: claim id1 (SKIP LOCKED)
  DB-->>P2: claim ok
  Note over P1: Pod A cannot claim same channel

  P2->>DB: apply MN=1 → rockets<br/>mark applied
  P2->>DB: apply MN=2 → rockets<br/>mark applied
  P2->>DB: clear dirty / release claim

  Note over DB: One channel = one claimer.<br/>Different channels can run on A and B in parallel.
```

## Test harness

Platform trees under [`rockets/`](rockets/) (`darwin_*`, `linux_*`, `windows_*`) ship the official `rockets` challenge binaries from the assessment ZIP (~9MB each). They are committed so the harness is available without re-downloading.

Example (Apple Silicon):

```bash
./rockets/darwin_arm64/rockets launch "http://localhost:8088/messages"
```
