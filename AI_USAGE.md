# AI usage

## Summary

- **Tools / agents:** Cursor cloud/self-hosted agents (coordinator + phase workers) under Driver direction (Sadishkumar). Plans under `docs/plans/`; Driver OK gates direction, commits, and push/hand-in.
- **What AI did:** Drafted SPEC/checklist/master + per-phase plans; implemented P0–P4 (scaffold, ingest, harness smoke, apply scheduler, read APIs, Jacoco/gap tests, harness re-run + read validation, README/checklist polish) with TDD-style suites and Testcontainers ITs.
- **What Driver decided:** Stack (Java 21 / Spring Boot / Maven / Postgres), DECIDED architecture (option 3 + B, SKIP LOCKED, first-wins, sticky explode, HTTP status policy), phase boundaries, commit messages, no phase PRs (combined commits on `main`), no `package-info.java`, YAGNI scope.

## Point estimates (hours)

Best-effort wall-clock for agent-assisted work on **2026-11-13** (not billable precision; Driver may edit).

| Phase / activity | Estimate | Notes |
| --- | --- | --- |
| Spec / checklist / master plan | ~1.5 | Requirements → SPEC + living checklist + phase map |
| P0 scaffold + Postgres | ~1.0 | App, Flyway, compose, smoke IT |
| P1 durable ingest | ~2.0 | JSON Schema validation, idempotent POST, ITs |
| P1.1 harness smoke | ~0.8 | Live darwin harness defaults + stress (ingest only) |
| P2 apply scheduler | ~2.0 | Contiguous apply, SKIP LOCKED claim, reducer |
| P3 read APIs | ~1.5 | GET list/detail/sort + apply→GET ITs |
| P4 verification + readiness | ~1.5 | Jacoco >90%, gap tests, harness+GET, README/`AI_USAGE` |
| Reviews / harness re-runs / Driver assess loops | ~1.2 | Cross-phase review + evidence capture |
| **Total** | **~11.5** | Single-day agent-assisted session with Driver gates |

## Reflections

- **What worked:** Spec-driven plans + Driver OK before code kept architecture stable; Testcontainers + Jacoco check made the >90% bar enforceable; compose app stayed reliable under agent cleanup where host `spring-boot:run` did not.
- **What needed Driver OK:** Gap handling, apply shape, HTTP status policy, commit-on-main (no phase PRs), exclusions (e.g. Boot `main` from Jacoco), wake deferred to `docs/NEXT_STEPS.md`.
- **Interview takeaway:** Prefer measured verification (coverage gate + live harness + projection-consistent GETs) over claiming “done” from unit tests alone; keep AI_USAGE honest and plans auditable.
