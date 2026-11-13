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

---

## Other future trade-offs

*(Reserved — add Driver-decided prod/readiness items here as they come up.)*
