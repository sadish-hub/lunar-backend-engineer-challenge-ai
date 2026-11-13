package com.lunar.rockets.apply.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplySchedulerIT {

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
        registry.add("lunar.apply.enabled", () -> "true");
        registry.add("lunar.apply.poll-interval-ms", () -> "50");
    }

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM channel_work");
        jdbc.update("DELETE FROM rockets");
    }

    @Test
    void schedulerPollAppliesContiguousMessagesWithoutWake() throws Exception {
        UUID channel = UUID.randomUUID();
        insertLaunch(channel, 1L, "Falcon-9", 40L, "POLL");
        insertSpeed(channel, 2L, 10L);
        jdbc.update(
                """
                INSERT INTO channel_work (channel, dirty_at)
                VALUES (?, now())
                ON CONFLICT (channel) DO UPDATE SET dirty_at = excluded.dirty_at
                """,
                channel);

        Map<String, Object> rocket = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM rockets WHERE id = ?", Integer.class, channel);
            if (count != null && count == 1) {
                rocket = jdbc.queryForMap("SELECT speed, last_applied_number FROM rockets WHERE id = ?", channel);
                break;
            }
            Thread.sleep(50L);
        }

        assertThat(rocket).isNotNull();
        assertThat(((Number) rocket.get("speed")).longValue()).isEqualTo(50L);
        assertThat(((Number) rocket.get("last_applied_number")).longValue()).isEqualTo(2L);
    }

    private void insertLaunch(UUID channel, long mn, String type, long speed, String mission) {
        String payload = """
                {"metadata":{"channel":"%s","messageNumber":%d,"messageTime":"2026-01-01T00:00:00Z","messageType":"RocketLaunched"},"message":{"type":"%s","launchSpeed":%d,"mission":"%s"}}
                """.formatted(channel, mn, type, speed, mission);
        jdbc.update(
                """
                INSERT INTO messages (channel, message_number, message_type, payload, applied)
                VALUES (?, ?, 'RocketLaunched', ?::jsonb, FALSE)
                """,
                channel,
                mn,
                payload);
    }

    private void insertSpeed(UUID channel, long mn, long by) {
        String payload = """
                {"metadata":{"channel":"%s","messageNumber":%d,"messageTime":"2026-01-01T00:00:00Z","messageType":"RocketSpeedIncreased"},"message":{"by":%d}}
                """.formatted(channel, mn, by);
        jdbc.update(
                """
                INSERT INTO messages (channel, message_number, message_type, payload, applied)
                VALUES (?, ?, 'RocketSpeedIncreased', ?::jsonb, FALSE)
                """,
                channel,
                mn,
                payload);
    }
}
