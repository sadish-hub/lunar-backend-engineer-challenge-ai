package com.lunar.rockets.read.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lunar.rockets.apply.service.ApplyWorker;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Automated half of P4 e2e/harness-oriented verification:
 * out-of-order POST + duplicate first-wins → apply → GET matches projection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OutOfOrderDuplicateApplyReadIT {

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
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ApplyWorker applyWorker;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM channel_work");
        jdbc.update("DELETE FROM rockets");
    }

    @Test
    void outOfOrderPostsPlusDuplicateThenApplyMatchGet() throws Exception {
        UUID channel = UUID.randomUUID();

        assertThat(post(speedIncreased(channel, 2L, 40L)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(launch(channel, 1L, "Falcon-9", 100L, "ARTEMIS")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post(launch(channel, 1L, "Falcon-9", 999L, "WRONG")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Integer messageRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE channel = ?", Integer.class, channel);
        assertThat(messageRows).isEqualTo(2);

        applyWorker.runOnce("e2e-worker");
        applyWorker.runOnce("e2e-worker");

        Integer unapplied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE channel = ? AND applied = false",
                Integer.class,
                channel);
        assertThat(unapplied).isZero();

        ResponseEntity<String> get = restTemplate.getForEntity("/rockets/" + channel, String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(get.getBody());
        assertThat(body.path("type").asText()).isEqualTo("Falcon-9");
        assertThat(body.path("speed").asLong()).isEqualTo(140L);
        assertThat(body.path("mission").asText()).isEqualTo("ARTEMIS");

        JsonNode list = objectMapper.readTree(
                restTemplate.getForEntity("/rockets?sort=speed&order=desc", String.class).getBody());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("id").asText()).isEqualTo(channel.toString());
        assertThat(list.get(0).path("speed").asLong()).isEqualTo(140L);
    }

    private ResponseEntity<Void> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/messages", new HttpEntity<>(json, headers), Void.class);
    }

    private static String launch(UUID channel, long mn, String type, long speed, String mission) {
        return """
                {"metadata":{"channel":"%s","messageNumber":%d,"messageTime":"2026-01-01T00:00:00Z","messageType":"RocketLaunched"},"message":{"type":"%s","launchSpeed":%d,"mission":"%s"}}
                """.formatted(channel, mn, type, speed, mission);
    }

    private static String speedIncreased(UUID channel, long mn, long by) {
        return """
                {"metadata":{"channel":"%s","messageNumber":%d,"messageTime":"2026-01-01T00:00:00Z","messageType":"RocketSpeedIncreased"},"message":{"by":%d}}
                """.formatted(channel, mn, by);
    }
}
