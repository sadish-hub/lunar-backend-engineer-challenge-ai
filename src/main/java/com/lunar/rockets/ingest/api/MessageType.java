package com.lunar.rockets.ingest.api;

import java.util.Arrays;
import java.util.Optional;

public enum MessageType {
    RocketLaunched,
    RocketSpeedIncreased,
    RocketSpeedDecreased,
    RocketExploded,
    RocketMissionChanged;

    public static Optional<MessageType> from(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equals(value))
                .findFirst();
    }
}
