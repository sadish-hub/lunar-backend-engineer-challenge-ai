package com.lunar.rockets.apply.domain;

import java.util.UUID;

/**
 * Immutable current-state snapshot used by the pure apply reducer.
 */
public record RocketState(
        UUID id,
        String type,
        long speed,
        String mission,
        boolean exploded,
        String explosionReason,
        long lastAppliedNumber,
        long version) {

    public static RocketState empty(UUID id) {
        return new RocketState(id, null, 0L, null, false, null, 0L, 0L);
    }

    public boolean exists() {
        return type != null || lastAppliedNumber > 0L;
    }
}
