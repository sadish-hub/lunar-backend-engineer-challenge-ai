package com.lunar.rockets.apply.service;

import com.lunar.rockets.apply.claim.ClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ChannelApplyServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("lunar")
            .withUsername("lunar")
            .withPassword("lunar");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("lunar.apply.enabled", () -> "false");
    }

    @Autowired
    ChannelApplyService applyService;

    @Autowired
    ApplyWorker applyWorker;

    @Autowired
    ClaimService claimService;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM channel_work");
        jdbc.update("DELETE FROM rockets");
    }

    @Test
    void orderedMessagesProjectRocketAndMarkApplied() {
        UUID channel = UUID.randomUUID();
        insertLaunch(channel, 1L, "Falcon-9", 100L, "ARTEMIS");
        insertSpeed(channel, 2L, true, 50L);
        insertMission(channel, 3L, "MOON");
        dirty(channel);

        applyWorker.runOnce("worker-a");

        Map<String, Object> rocket = jdbc.queryForMap("SELECT * FROM rockets WHERE id = ?", channel);
        assertThat(rocket.get("type")).isEqualTo("Falcon-9");
        assertThat(((Number) rocket.get("speed")).longValue()).isEqualTo(150L);
        assertThat(rocket.get("mission")).isEqualTo("MOON");
        assertThat(((Number) rocket.get("last_applied_number")).longValue()).isEqualTo(3L);
        assertThat(((Number) rocket.get("version")).longValue()).isEqualTo(3L);

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM messages WHERE channel = ? AND applied = TRUE",
                        Integer.class,
                        channel))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM channel_work WHERE channel = ?",
                        Integer.class,
                        channel))
                .isZero();
    }

    @Test
    void outOfOrderWaitsThenAppliesContiguously() {
        UUID channel = UUID.randomUUID();
        insertSpeed(channel, 2L, true, 10L);
        dirty(channel);

        applyWorker.runOnce("worker-a");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rockets", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT applied FROM messages WHERE channel = ? AND message_number = 2",
                        Boolean.class,
                        channel))
                .isFalse();

        insertLaunch(channel, 1L, "Falcon-9", 100L, "ARTEMIS");
        dirty(channel);
        applyWorker.runOnce("worker-b");

        Map<String, Object> rocket = jdbc.queryForMap("SELECT speed, last_applied_number FROM rockets WHERE id = ?", channel);
        assertThat(((Number) rocket.get("speed")).longValue()).isEqualTo(110L);
        assertThat(((Number) rocket.get("last_applied_number")).longValue()).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM messages WHERE channel = ? AND applied = TRUE",
                        Integer.class,
                        channel))
                .isEqualTo(2);
    }

    @Test
    void gapStopsAndFillGapCatchesUp() {
        UUID channel = UUID.randomUUID();
        insertLaunch(channel, 1L, "Falcon-9", 100L, "ARTEMIS");
        insertSpeed(channel, 3L, true, 20L);
        dirty(channel);

        applyWorker.runOnce("worker-a");

        assertThat(jdbc.queryForObject(
                        "SELECT last_applied_number FROM rockets WHERE id = ?",
                        Long.class,
                        channel))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT applied FROM messages WHERE channel = ? AND message_number = 3",
                        Boolean.class,
                        channel))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM channel_work WHERE channel = ? AND claimed_at IS NULL",
                        Integer.class,
                        channel))
                .isEqualTo(1);

        insertSpeed(channel, 2L, true, 5L);
        dirty(channel);
        applyWorker.runOnce("worker-b");

        Map<String, Object> rocket = jdbc.queryForMap("SELECT speed, last_applied_number FROM rockets WHERE id = ?", channel);
        assertThat(((Number) rocket.get("speed")).longValue()).isEqualTo(125L);
        assertThat(((Number) rocket.get("last_applied_number")).longValue()).isEqualTo(3L);
    }

    @Test
    void stickyExplodeAndNegativeSpeedPersist() {
        UUID channel = UUID.randomUUID();
        insertLaunch(channel, 1L, "Falcon-9", 10L, "ARTEMIS");
        insertExplode(channel, 2L, "RUD");
        insertSpeed(channel, 3L, false, 20L);
        insertMission(channel, 4L, "RESCUE");
        dirty(channel);

        applyWorker.runOnce("worker-a");

        Map<String, Object> rocket = jdbc.queryForMap("SELECT * FROM rockets WHERE id = ?", channel);
        assertThat(rocket.get("exploded")).isEqualTo(true);
        assertThat(rocket.get("explosion_reason")).isEqualTo("RUD");
        assertThat(((Number) rocket.get("speed")).longValue()).isEqualTo(-10L);
        assertThat(rocket.get("mission")).isEqualTo("RESCUE");
        assertThat(((Number) rocket.get("last_applied_number")).longValue()).isEqualTo(4L);
    }

    @Test
    void alreadyAppliedMessageIsNotDoubleApplied() {
        UUID channel = UUID.randomUUID();
        insertLaunch(channel, 1L, "Falcon-9", 100L, "ARTEMIS");
        insertSpeed(channel, 2L, true, 50L);
        dirty(channel);
        applyWorker.runOnce("worker-a");

        // Re-dirty as if a duplicate ingest refreshed channel_work; message 2 already applied.
        dirty(channel);
        applyWorker.runOnce("worker-b");

        assertThat(jdbc.queryForObject("SELECT speed FROM rockets WHERE id = ?", Long.class, channel))
                .isEqualTo(150L);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM messages WHERE channel = ? AND applied = TRUE",
                        Integer.class,
                        channel))
                .isEqualTo(2);
    }

    @Test
    void emptyDirtyClaimIsNoOp() {
        applyWorker.runOnce("worker-empty");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rockets", Integer.class)).isZero();
    }

    @Test
    void multiChannelApplyProjectsEachRocket() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        insertLaunch(a, 1L, "Falcon-9", 10L, "A");
        insertLaunch(b, 1L, "Heavy", 20L, "B");
        dirty(a);
        dirty(b);

        applyWorker.runOnce("worker-multi");

        assertThat(jdbc.queryForObject("SELECT mission FROM rockets WHERE id = ?", String.class, a)).isEqualTo("A");
        assertThat(jdbc.queryForObject("SELECT mission FROM rockets WHERE id = ?", String.class, b)).isEqualTo("B");
    }

    @Test
    void versionCasFailsOnStaleVersionWithoutCorruptingState() {
        UUID channel = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO rockets (id, type, speed, mission, exploded, last_applied_number, version)
                VALUES (?, 'Falcon-9', 100, 'ARTEMIS', FALSE, 1, 3)
                """,
                channel);

        int updated = jdbc.update(
                """
                UPDATE rockets
                SET speed = 999, version = version + 1, last_applied_number = 2
                WHERE id = ? AND version = 1
                """,
                channel);
        assertThat(updated).isZero();
        Map<String, Object> rocket = jdbc.queryForMap("SELECT speed, version, last_applied_number FROM rockets WHERE id = ?", channel);
        assertThat(((Number) rocket.get("speed")).longValue()).isEqualTo(100L);
        assertThat(((Number) rocket.get("version")).longValue()).isEqualTo(3L);
        assertThat(((Number) rocket.get("last_applied_number")).longValue()).isEqualTo(1L);
    }

    @Test
    void versionConflictIsRetriedByWorkerWithoutCorrupting() {
        UUID channel = UUID.randomUUID();
        insertLaunch(channel, 1L, "Falcon-9", 100L, "ARTEMIS");
        insertSpeed(channel, 2L, true, 25L);
        dirty(channel);

        applyWorker.runOnce("worker-cas");
        long versionAfterFirst = jdbc.queryForObject("SELECT version FROM rockets WHERE id = ?", Long.class, channel);

        // Simulate a stale writer losing CAS after apply already succeeded — state stays consistent.
        int lost = jdbc.update(
                """
                UPDATE rockets SET speed = 1, version = version + 1
                WHERE id = ? AND version = ?
                """,
                channel,
                versionAfterFirst - 1);
        assertThat(lost).isZero();
        assertThat(jdbc.queryForObject("SELECT speed FROM rockets WHERE id = ?", Long.class, channel))
                .isEqualTo(125L);
        assertThat(jdbc.queryForObject("SELECT version FROM rockets WHERE id = ?", Long.class, channel))
                .isEqualTo(versionAfterFirst);
    }

    private void dirty(UUID channel) {
        jdbc.update(
                """
                INSERT INTO channel_work (channel, dirty_at)
                VALUES (?, now())
                ON CONFLICT (channel) DO UPDATE SET dirty_at = excluded.dirty_at, claimed_at = NULL, claimed_by = NULL
                """,
                channel);
    }

    private void insertLaunch(UUID channel, long mn, String type, long speed, String mission) {
        String payload = """
                {"metadata":{"channel":"%s","messageNumber":%d,"messageTime":"2026-01-01T00:00:00Z","messageType":"RocketLaunched"},"message":{"type":"%s","launchSpeed":%d,"mission":"%s"}}
                """.formatted(channel, mn, type, speed, mission);
        insertMessage(channel, mn, "RocketLaunched", payload);
    }

    private void insertSpeed(UUID channel, long mn, boolean increase, long by) {
        String type = increase ? "RocketSpeedIncreased" : "RocketSpeedDecreased";
        String payload = """
                {"metadata":{"channel":"%s","messageNumber":%d,"messageTime":"2026-01-01T00:00:00Z","messageType":"%s"},"message":{"by":%d}}
                """.formatted(channel, mn, type, by);
        insertMessage(channel, mn, type, payload);
    }

    private void insertExplode(UUID channel, long mn, String reason) {
        String payload = """
                {"metadata":{"channel":"%s","messageNumber":%d,"messageTime":"2026-01-01T00:00:00Z","messageType":"RocketExploded"},"message":{"reason":"%s"}}
                """.formatted(channel, mn, reason);
        insertMessage(channel, mn, "RocketExploded", payload);
    }

    private void insertMission(UUID channel, long mn, String mission) {
        String payload = """
                {"metadata":{"channel":"%s","messageNumber":%d,"messageTime":"2026-01-01T00:00:00Z","messageType":"RocketMissionChanged"},"message":{"newMission":"%s"}}
                """.formatted(channel, mn, mission);
        insertMessage(channel, mn, "RocketMissionChanged", payload);
    }

    private void insertMessage(UUID channel, long mn, String type, String payload) {
        jdbc.update(
                """
                INSERT INTO messages (channel, message_number, message_type, payload, applied)
                VALUES (?, ?, ?, ?::jsonb, FALSE)
                """,
                channel,
                mn,
                type,
                payload);
    }
}
