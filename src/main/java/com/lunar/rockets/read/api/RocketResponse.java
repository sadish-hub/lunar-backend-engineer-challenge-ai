package com.lunar.rockets.read.api;

import java.util.UUID;

public record RocketResponse(
        UUID id,
        String type,
        long speed,
        String mission,
        boolean exploded,
        String explosionReason) {
}
