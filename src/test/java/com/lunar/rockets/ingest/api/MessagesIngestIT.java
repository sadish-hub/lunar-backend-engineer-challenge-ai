package com.lunar.rockets.ingest.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MessagesIngestIT {

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
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM channel_work");
        jdbc.update("DELETE FROM rockets");
    }

    @Test
    void firstAcceptReturns200AndPersistsMessageAndDirtyWork() throws Exception {
        UUID channel = UUID.randomUUID();
        ObjectNode body = launchedBody(channel, 1L, "Falcon-9", 500L, "ARTEMIS");

        ResponseEntity<Void> response = post(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer messageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE channel = ? AND message_number = ?",
                Integer.class,
                channel,
                1L);
        assertThat(messageCount).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT message_type, payload FROM messages WHERE channel = ? AND message_number = ?",
                channel,
                1L);
        assertThat(row.get("message_type")).isEqualTo("RocketLaunched");
        JsonNode stored = objectMapper.readTree(row.get("payload").toString());
        assertThat(stored.path("metadata").path("channel").asText()).isEqualTo(channel.toString());
        assertThat(stored.path("message").path("mission").asText()).isEqualTo("ARTEMIS");

        Integer workCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_work WHERE channel = ? AND dirty_at IS NOT NULL",
                Integer.class,
                channel);
        assertThat(workCount).isEqualTo(1);

        Integer rockets = jdbc.queryForObject("SELECT COUNT(*) FROM rockets", Integer.class);
        assertThat(rockets).isZero();
    }

    @Test
    void duplicateSamePayloadReturns200WithoutSecondRow() throws Exception {
        UUID channel = UUID.randomUUID();
        ObjectNode body = launchedBody(channel, 2L, "Falcon-9", 500L, "ARTEMIS");

        assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer messageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE channel = ?",
                Integer.class,
                channel);
        assertThat(messageCount).isEqualTo(1);
    }

    @Test
    void duplicateDivergentPayloadKeepsFirstWins() throws Exception {
        UUID channel = UUID.randomUUID();
        ObjectNode first = launchedBody(channel, 3L, "Falcon-9", 500L, "FIRST");
        ObjectNode second = launchedBody(channel, 3L, "Falcon-Heavy", 999L, "SECOND");

        assertThat(post(first).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Void> duplicate = post(second);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT payload FROM messages WHERE channel = ? AND message_number = ?",
                channel,
                3L);
        JsonNode stored = objectMapper.readTree(row.get("payload").toString());
        assertThat(stored.path("message").path("mission").asText()).isEqualTo("FIRST");
        assertThat(stored.path("message").path("type").asText()).isEqualTo("Falcon-9");

        Integer messageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE channel = ?",
                Integer.class,
                channel);
        assertThat(messageCount).isEqualTo(1);
    }

    @Test
    void outOfOrderMessageNumberIsAcceptedWithoutTouchingRockets() throws Exception {
        UUID channel = UUID.randomUUID();
        ObjectNode body = launchedBody(channel, 3L, "Falcon-9", 100L, "GAP");

        assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer messageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE channel = ? AND message_number = 3",
                Integer.class,
                channel);
        assertThat(messageCount).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rockets", Integer.class)).isZero();
    }

    @Test
    void badPayloadReturns400AndDoesNotPersist() throws Exception {
        UUID channel = UUID.randomUUID();
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("channel", channel.toString());
        metadata.put("messageNumber", 1);
        metadata.put("messageTime", "2026-01-01T00:00:00Z");
        metadata.put("messageType", "RocketSpeedIncreased");
        body.putObject("message").put("by", -1);

        ResponseEntity<Void> response = post(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM channel_work", Integer.class)).isZero();
    }

    @Test
    void malformedJsonReturns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/messages",
                new HttpEntity<>("{not-json", headers),
                Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownMessageTypeReturns400() throws Exception {
        UUID channel = UUID.randomUUID();
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("channel", channel.toString());
        metadata.put("messageNumber", 1);
        metadata.put("messageTime", "2026-01-01T00:00:00Z");
        metadata.put("messageType", "NotARealType");
        body.putObject("message").put("x", 1);

        assertThat(post(body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Void> post(ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/messages", new HttpEntity<>(body.toString(), headers), Void.class);
    }

    private ObjectNode launchedBody(UUID channel, long number, String type, long launchSpeed, String mission)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("channel", channel.toString());
        metadata.put("messageNumber", number);
        metadata.put("messageTime", "2026-11-13T12:00:00Z");
        metadata.put("messageType", "RocketLaunched");
        ObjectNode message = body.putObject("message");
        message.put("type", type);
        message.put("launchSpeed", launchSpeed);
        message.put("mission", mission);
        return body;
    }
}
