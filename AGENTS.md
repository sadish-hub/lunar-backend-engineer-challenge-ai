# AGENTS.md — Lunar Rockets take-home

Concise operating guide for agents on this project. Prefer `docs/SPEC.md` and `preferences.md` over inventing requirements.

## Driver / Car

- **Driver:** Sadishkumar. Agents propose; wait for OK on direction, commit messages, push, and hand-in.
- Ask before directional decisions. Do **not** guess when the brief or SPEC is ambiguous.
- **No push, commit, or public hand-in** without Driver OK.

## Spec-driven workflow

1. Read `docs/BRIEF.md` (challenge capture) and `docs/SPEC.md` (resolved design).
2. Write / update plans under `docs/plans/` (master + per-phase) **before** implementation. Implement only after plan approval.
3. Prefer: plan commit → review OK → separate implementation commit (when committing is authorized).
4. Keep `docs/REQUIREMENTS_CHECKLIST.md` current while implementing.
5. Keep `AI_USAGE.md` honest (point time estimates) when that deliverable exists.

## Quality bar

- **TDD** — write failing tests first; unit + integration + e2e with edge cases.
- **Coverage > 90%** on critical paths (ingest idempotency, ordering, read APIs).
- **YAGNI** — ask before extra frameworks, uniqueness beyond channel-as-ID, auth, CORS, datepickers, OWASP, etc.
- Production-ready mindset within ~6h challenge scope; clear trade-offs.

## Locked architecture (DECIDED — do not reopen without Driver)

| Area | Decision |
| --- | --- |
| Stack | Java 21, Spring Boot 3.x, Maven |
| Deploy | **One** Spring Boot service; packages `ingest` / `apply` / `read`; scale by replicas; no multi-module / microservices |
| Storage | Postgres (Docker): `messages` + `rockets` current-state (+ `version`); **not** full event sourcing / Kafka |
| Gap / apply | **Option 3 + B**: durable insert → **200**; async contiguous apply; multi-pod via `FOR UPDATE SKIP LOCKED`; optional in-memory wake = hint only |
| HTTP POST `/messages` | Always **200** on accept (incl. duplicates); **400** bad/unknown; **5xx** transient |
| API | `POST /messages`, `GET /rockets`, `GET /rockets/{id}`; sort `sort`/`order`; **404** unknown; **no** pagination; port **8088** |
| Domain | Sticky explode + still apply later; first-wins duplicates; negative `by` → **400**; speed may be ≤ 0 (no clamp) |

## Where things live

| Path | Role |
| --- | --- |
| `docs/BRIEF.md` | Faithful challenge capture |
| `docs/SPEC.md` | Product/tech spec (DECIDED + OPEN) |
| `docs/REQUIREMENTS_CHECKLIST.md` | Living checklist — check/strike as work lands |
| `docs/plans/` | Master + phase plans (before code) |
| `preferences.md` | Reusable Driver process preferences |
| `AGENTS.md` | This guide |

## Checklist hygiene

After each implementation slice lands:

1. Open `docs/REQUIREMENTS_CHECKLIST.md`.
2. Mark the matching items done: `- [x]` and ~~strikethrough~~ the text (or equivalent struck form).
3. Leave design/SPEC DECIDED items checked; leave unfinished impl items unchecked.
4. Do not invent new Lunar requirements — only track BRIEF/SPEC items and agreed process deliverables.
