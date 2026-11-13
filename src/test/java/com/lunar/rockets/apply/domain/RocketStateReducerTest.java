package com.lunar.rockets.apply.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RocketStateReducerTest {

    private final RocketStateReducer reducer = new RocketStateReducer();
    private final UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void launchSetsTypeSpeedMission() {
        RocketState next = reducer.reduce(
                RocketState.empty(id),
                new ApplyCommand.Launch(1L, "Falcon-9", 500L, "ARTEMIS"));

        assertThat(next.type()).isEqualTo("Falcon-9");
        assertThat(next.speed()).isEqualTo(500L);
        assertThat(next.mission()).isEqualTo("ARTEMIS");
        assertThat(next.exploded()).isFalse();
        assertThat(next.lastAppliedNumber()).isEqualTo(1L);
    }

    @Test
    void speedIncreaseAndDecreaseAreArithmetic() {
        RocketState launched = reducer.reduce(
                RocketState.empty(id),
                new ApplyCommand.Launch(1L, "Falcon-9", 100L, "M"));

        RocketState up = reducer.reduce(launched, new ApplyCommand.SpeedIncreased(2L, 40L));
        assertThat(up.speed()).isEqualTo(140L);

        RocketState down = reducer.reduce(up, new ApplyCommand.SpeedDecreased(3L, 30L));
        assertThat(down.speed()).isEqualTo(110L);
    }

    @Test
    void speedMayGoZeroOrNegativeWithoutClamp() {
        RocketState launched = reducer.reduce(
                RocketState.empty(id),
                new ApplyCommand.Launch(1L, "Falcon-9", 10L, "M"));

        RocketState zero = reducer.reduce(launched, new ApplyCommand.SpeedDecreased(2L, 10L));
        assertThat(zero.speed()).isZero();

        RocketState negative = reducer.reduce(zero, new ApplyCommand.SpeedDecreased(3L, 5L));
        assertThat(negative.speed()).isEqualTo(-5L);
    }

    @Test
    void explodeSetsStickyFlagAndReason() {
        RocketState launched = reducer.reduce(
                RocketState.empty(id),
                new ApplyCommand.Launch(1L, "Falcon-9", 10L, "M"));

        RocketState exploded = reducer.reduce(launched, new ApplyCommand.Exploded(2L, "boom"));
        assertThat(exploded.exploded()).isTrue();
        assertThat(exploded.explosionReason()).isEqualTo("boom");
    }

    @Test
    void speedAndMissionAfterExplodeStillApplyWhileExplodeSticks() {
        RocketState launched = reducer.reduce(
                RocketState.empty(id),
                new ApplyCommand.Launch(1L, "Falcon-9", 10L, "M1"));
        RocketState exploded = reducer.reduce(launched, new ApplyCommand.Exploded(2L, "boom"));

        RocketState afterSpeed = reducer.reduce(exploded, new ApplyCommand.SpeedIncreased(3L, 7L));
        assertThat(afterSpeed.speed()).isEqualTo(17L);
        assertThat(afterSpeed.exploded()).isTrue();
        assertThat(afterSpeed.explosionReason()).isEqualTo("boom");

        RocketState afterMission = reducer.reduce(afterSpeed, new ApplyCommand.MissionChanged(4L, "M2"));
        assertThat(afterMission.mission()).isEqualTo("M2");
        assertThat(afterMission.exploded()).isTrue();
        assertThat(afterMission.explosionReason()).isEqualTo("boom");
    }

    @Test
    void missionChangeUpdatesMissionOnly() {
        RocketState launched = reducer.reduce(
                RocketState.empty(id),
                new ApplyCommand.Launch(1L, "Falcon-9", 10L, "OLD"));

        RocketState next = reducer.reduce(launched, new ApplyCommand.MissionChanged(2L, "NEW"));
        assertThat(next.mission()).isEqualTo("NEW");
        assertThat(next.type()).isEqualTo("Falcon-9");
        assertThat(next.speed()).isEqualTo(10L);
    }

    @Test
    void nonLaunchBeforeRocketExistsFailsClosed() {
        assertThatThrownBy(() -> reducer.reduce(
                        RocketState.empty(id),
                        new ApplyCommand.SpeedIncreased(1L, 1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before RocketLaunched");
    }
}
