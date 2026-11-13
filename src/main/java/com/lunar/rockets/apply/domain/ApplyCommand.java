package com.lunar.rockets.apply.domain;

/**
 * Typed apply input derived from a durable message row.
 */
public sealed interface ApplyCommand {

    long messageNumber();

    record Launch(long messageNumber, String type, long launchSpeed, String mission) implements ApplyCommand {
    }

    record SpeedIncreased(long messageNumber, long by) implements ApplyCommand {
    }

    record SpeedDecreased(long messageNumber, long by) implements ApplyCommand {
    }

    record Exploded(long messageNumber, String reason) implements ApplyCommand {
    }

    record MissionChanged(long messageNumber, String newMission) implements ApplyCommand {
    }
}
