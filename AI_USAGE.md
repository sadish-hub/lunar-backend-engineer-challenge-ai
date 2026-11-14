# AI usage

## Summary

- **Tools / agents:** Cursor cloud/self-hosted agents (coordinator + phase workers) under Driver direction (Sadishkumar). Plans under `docs/plans/`; Driver OK gates direction, commits, and push/hand-in.

| Role | What |
| --- | --- |
| Driver | <ul><li>Owned **trade-off decisions** (agents propose; Driver approves): **one** Spring Boot service vs microservices now; Postgres `messages`/`rockets`/`channel_work` vs Kafka/event sourcing; **option 3 + B** (durable 200 + async contiguous apply) vs sync-on-ingest or in-memory-only queue; poll `@Scheduled` + `SKIP LOCKED` (wake deferred); claim primary + version CAS safety net; domain/HTTP policy (first-wins, sticky explode, 200/400/5xx, no pagination/auth)</li><li>Process: phase plan (P0→P4), YAGNI scope, commit messages, no phase PRs (combined commits on `main`), no `package-info.java`</li><li>**2026-11-14:** OK on virtual threads; JSON error shape; full-stack compose path; all-channel harness e2e asserts; architecture doc / flow diagram; same-pod channel parallelism noted in `NEXT_STEPS` (not built)</li></ul> |
| AI | <ul><li>Drafted SPEC/checklist/master + per-phase plans</li><li>Implemented P0–P4 (scaffold, ingest, harness smoke, apply scheduler, read APIs, Jacoco/gap tests, harness re-run + read validation, README/checklist polish) with TDD-style suites and Testcontainers ITs</li><li>On **2026-11-14**: compose `make up` full stack; shared JSON `ApiError` bodies; harness e2e covering **all** channels with colored PASS/FAIL; Spring virtual threads; `docs/ARCHITECTURE.md` plus diagram/flow discussion</li></ul> |

## Point estimates (hours)

Best-effort **active** agent-assisted time (Europe/Stockholm). Not billable precision; Driver may edit. Heavier weight on requirements / architecture discussion vs mechanical impl.

### 2026-11-13

Session window ~**13:30–19:08** (~5.6h wall) with breaks.

| Phase / activity | Estimate | Notes |
| --- | --- | --- |
| Spec / checklist / architecture discussion | ~2.3 | Requirements, DECIDED architecture, SPEC + checklist + phase map |
| P0 scaffold + Postgres | ~0.3 | App, Flyway, compose, smoke IT |
| P1 durable ingest | ~0.3 | JSON Schema validation, idempotent POST, ITs |
| P1.1 harness smoke | ~0.15 | Live darwin harness defaults + stress (ingest only) |
| P2 apply scheduler | ~0.45 | Contiguous apply, SKIP LOCKED claim, reducer |
| P3 read APIs | ~0.2 | GET list/detail/sort + apply→GET ITs |
| P4 verification + readiness | ~0.3 | Jacoco >90%, gap tests, harness+GET, README/`AI_USAGE` |
| Reviews / discuss loops / harness re-runs | ~.3 | Driver assess, cross-phase review, evidence capture |
| **Subtotal (active)** | **~4.3** | 2.3+0.3+0.3+0.15+0.45+0.2+0.3+0.3; wall 13:30–19:08, breaks excluded |

### 2026-11-14 (Sat)

Session window ~**10:54–12:35** (~1h 40m wall). Active ~**1.2h**.

| Activity | Estimate | Notes |
| --- | --- | --- |
| Compose `make up` full stack | ~0.2 | App + Postgres path; Makefile/README local-up clarity |
| Manual testing | ~0.3 | found some modifications |
| JSON `ApiError` (400/404/503 bodies) | ~0.15 | Shared error shape + handler/tests |
| Harness e2e all channels + colored PASS/FAIL | ~0.2 | Recording proxy, every-channel asserts, green/red output |
| Spring virtual threads | ~0.1 | `spring.threads.virtual.enabled`; README note; retest |
| `docs/ARCHITECTURE.md` + diagram/flow discussion | ~0.25 | Architecture write-up, sequence/flow review with Driver |
| **Subtotal (active)** | **~1.2** | 0.2+0.3+0.15+0.2+0.1+0.25 |

### Cumulative

| Day | Active |
| --- | --- |
| 2026-11-13 | ~4.3h |
| 2026-11-14 | ~1.2h |
| **Total (active)** | **~5.5h** |

## Reflections

- **What worked:** Spec-driven plans + Driver OK before code kept architecture stable; Testcontainers + Jacoco check made the >90% bar enforceable; compose app stayed reliable under agent cleanup where host `spring-boot:run` did not; all-channel harness e2e made live PASS/FAIL evidence cheap to re-run.
- **What needed Driver OK:** All major architecture trade-offs (single service, storage, option 3+B, poll/claim, domain/HTTP); phase boundaries; commit-on-main (no phase PRs); Jacoco exclusions; wake / same-pod channel parallelism deferred to `docs/NEXT_STEPS.md`; virtual threads; JSON error contract; architecture doc depth.
- **Interview takeaway:** Prefer measured verification (coverage gate + live harness + projection-consistent GETs) over claiming “done” from unit tests alone; keep AI_USAGE honest and plans auditable.
