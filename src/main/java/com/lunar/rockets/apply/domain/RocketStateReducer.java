package com.lunar.rockets.apply.domain;

import org.springframework.stereotype.Component;

/**
 * Pure reducer: current rocket state + contiguous message → next state.
 * Sticky explode: once exploded, later speed/mission still apply; explode fields stick.
 * Speed may go ≤ 0; no clamp.
 */
@Component
public class RocketStateReducer {

    public RocketState reduce(RocketState current, ApplyCommand command) {
        return switch (command) {
            case ApplyCommand.Launch launch -> applyLaunch(current, launch);
            case ApplyCommand.SpeedIncreased inc -> applySpeed(current, inc.messageNumber(), current.speed() + inc.by());
            case ApplyCommand.SpeedDecreased dec -> applySpeed(current, dec.messageNumber(), current.speed() - dec.by());
            case ApplyCommand.Exploded exploded -> applyExplode(current, exploded);
            case ApplyCommand.MissionChanged mission -> applyMission(current, mission);
        };
    }

    private RocketState applyLaunch(RocketState current, ApplyCommand.Launch launch) {
        if (current.exists()) {
            // Harness launches once; do not un-explode or invent a second launch overwrite.
            return withProgress(current, launch.messageNumber());
        }
        return new RocketState(
                current.id(),
                launch.type(),
                launch.launchSpeed(),
                launch.mission(),
                false,
                null,
                launch.messageNumber(),
                current.version());
    }

    private RocketState applySpeed(RocketState current, long messageNumber, long newSpeed) {
        requireExisting(current, "speed");
        return new RocketState(
                current.id(),
                current.type(),
                newSpeed,
                current.mission(),
                current.exploded(),
                current.explosionReason(),
                messageNumber,
                current.version());
    }

    private RocketState applyExplode(RocketState current, ApplyCommand.Exploded exploded) {
        requireExisting(current, "explode");
        return new RocketState(
                current.id(),
                current.type(),
                current.speed(),
                current.mission(),
                true,
                exploded.reason(),
                exploded.messageNumber(),
                current.version());
    }

    private RocketState applyMission(RocketState current, ApplyCommand.MissionChanged mission) {
        requireExisting(current, "mission");
        return new RocketState(
                current.id(),
                current.type(),
                current.speed(),
                mission.newMission(),
                current.exploded(),
                current.explosionReason(),
                mission.messageNumber(),
                current.version());
    }

    private static RocketState withProgress(RocketState current, long messageNumber) {
        return new RocketState(
                current.id(),
                current.type(),
                current.speed(),
                current.mission(),
                current.exploded(),
                current.explosionReason(),
                messageNumber,
                current.version());
    }

    private static void requireExisting(RocketState current, String action) {
        if (!current.exists()) {
            throw new IllegalStateException(
                    "cannot apply " + action + " for channel " + current.id() + " before RocketLaunched");
        }
    }
}
