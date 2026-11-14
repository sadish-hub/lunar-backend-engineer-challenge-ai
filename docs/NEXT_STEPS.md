# NEXT_STEPS

Living doc for **future / prod-readiness** trade-offs decided out of the current phase scope.
P2 ships **poll + DB claim only** — see `docs/plans/p2-apply-scheduler.md`.

Add new items below as Driver decisions land. Do not treat this file as an implementation backlog for the current phase.

---

## Wake-on-ingest (deferred)

**Status:** Out of P2. Optional future latency optimization.

### Why

Apply latency today is bounded by the scheduler **poll interval** (plus claim/apply work).
A same-pod **wake-on-ingest** hint after a successful **new** insert can nudge the apply worker sooner so channels start projecting without waiting for the next poll tick.

### Correctness already holds

Scheduler poll + `channel_work` claim (`FOR UPDATE SKIP LOCKED`) is the source of truth.
Wake is **never** required for correctness: another pod, a missed hint, process restart, or wake failure must still converge via dirty upsert + poll + claim.

### How to implement (summary)

1. After successful **new** ingest commit (not duplicates), emit a same-pod hint (`ApplicationEvent` / callback → `ApplyWakeHint.signal(channel)` or a global nudge).
2. Same-pod executor may run one claim/apply cycle early — still goes through the same DB claim path.
3. Gate behind a feature flag (e.g. `lunar.apply.wake-on-ingest`, default off until explicitly enabled).
4. **Must** survive wake failure / multi-pod: disable wake in ITs and prove poll-only still converges; never make ingest HTTP depend on wake succeeding.

### References

- P2 plan (poll + claim only): `docs/plans/p2-apply-scheduler.md`
- Coordination background: `docs/SPEC.md` §4.4 / concurrency notes
- As-built overview: `docs/ARCHITECTURE.md`

---

## Scale-out: split apply [@Scheduler] (future)

**Status:** Docs-only. Take-home stays **one** Spring Boot service (monolith with package seams).

We confirmed **apply** — poll, `FOR UPDATE SKIP LOCKED` claim, contiguous `messageNumber` reduce, `rockets.version` CAS, sticky explode — holds the core logic and is where lag shows up under load. A natural next step for scale is to peel **apply** into its own service so claim workers can grow without cloning ingest/read HTTP capacity.

- **Split apply** into a worker-only deployable that claims dirty channels and projects into `rockets`.
- **Ingest stays thin:** receive, JSON Schema validate, durable first-wins insert into `messages`, dirty `channel_work`, return **200**.
- **Read stays thin:** serve `GET /rockets` / `GET /rockets/{id}` from the projection only.
- **Claim workers** scale as N identical instances against shared Postgres; one channel, one claimer; different channels in parallel.
- **This challenge** does not implement the split — document for interview / prod narrative only.

---

## Same-pod channel parallelism in the apply scheduler (deferred)

**Status:** Not implemented. Optional future throughput tweak on one pod.

`ApplyWorker.runOnce` claims a batch of channels then applies them in a **serial** `for` loop. That is fine for the take-home: messages on one channel must stay ordered, and multi-pod `SKIP LOCKED` already parallelizes **across** channels via replicas.

The place concurrency *does* help on a single pod is that claimed-channel loop — different channels in the batch are independent after claim. Later we can fan them out with a **bounded executor** (one thread per channel, capped concurrency) instead of the serial loop. Do **not** parallelize contiguous MN apply inside a single channel.
