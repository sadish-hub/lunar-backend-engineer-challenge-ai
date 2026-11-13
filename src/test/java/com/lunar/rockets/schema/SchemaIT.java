package com.lunar.rockets.schema;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SchemaIT {

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
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayCreatesExpectedTables() {
        List<String> tables = jdbc.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('messages', 'rockets', 'channel_work')
                ORDER BY table_name
                """,
                String.class);

        assertThat(tables).containsExactly("channel_work", "messages", "rockets");
    }

    @Test
    void rocketsHasVersionColumn() {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'rockets'
                  AND column_name = 'version'
                """,
                Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void messagesHasAppliedColumn() {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'messages'
                  AND column_name = 'applied'
                """,
                Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void messagesUniqueOnChannelAndMessageNumber() {
        UUID channel = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO messages (channel, message_number, message_type, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                channel, 1L, "RocketLaunched", "{\"type\":\"Falcon-9\"}");

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO messages (channel, message_number, message_type, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                channel, 1L, "RocketLaunched", "{\"type\":\"Falcon-9\"}"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void channelWorkAndRocketsAcceptRoundTrip() {
        UUID channel = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO rockets (id, type, speed, mission, version)
                VALUES (?, ?, ?, ?, ?)
                """,
                channel, "Falcon-9", 0L, "ARTEMIS", 0L);

        jdbc.update(
                """
                INSERT INTO channel_work (channel)
                VALUES (?)
                """,
                channel);

        Map<String, Object> rocket = jdbc.queryForMap(
                "SELECT id, version FROM rockets WHERE id = ?", channel);
        assertThat(rocket.get("version")).isEqualTo(0L);

        Integer workRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_work WHERE channel = ?",
                Integer.class,
                channel);
        assertThat(workRows).isEqualTo(1);
    }
}
