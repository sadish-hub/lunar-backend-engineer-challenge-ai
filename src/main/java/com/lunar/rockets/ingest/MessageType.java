package com.lunar.rockets.ingest;

import java.util.Arrays;
import java.util.Optional;

enum MessageType {
    RocketLaunched,
    RocketSpeedIncreased,
    RocketSpeedDecreased,
    RocketExploded,
    RocketMissionChanged;

    static Optional<MessageType> from(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equals(value))
                .findFirst();
    }
}
