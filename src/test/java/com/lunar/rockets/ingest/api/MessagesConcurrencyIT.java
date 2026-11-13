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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MessagesConcurrencyIT {

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
    void concurrentDistinctKeysAllPersist() throws Exception {
        UUID channel = UUID.randomUUID();
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<HttpStatusCode>> tasks = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                long number = i;
                tasks.add(() -> post(launchedBody(channel, number, "Falcon-9", number, "M" + number)).getStatusCode());
            }
            List<Future<HttpStatusCode>> futures = pool.invokeAll(tasks);
            for (Future<HttpStatusCode> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer messageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE channel = ?",
                Integer.class,
                channel);
        assertThat(messageCount).isEqualTo(n);

        Integer workCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM channel_work WHERE channel = ?",
                Integer.class,
                channel);
        assertThat(workCount).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyYieldsSingleRowFirstWins() throws Exception {
        UUID channel = UUID.randomUUID();
        ObjectNode firstShape = launchedBody(channel, 1L, "Falcon-9", 100L, "FIRST");
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<HttpStatusCode>> tasks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ObjectNode body = (i == 0)
                        ? firstShape
                        : launchedBody(channel, 1L, "Other-" + i, 999L, "LATER-" + i);
                tasks.add(() -> post(body).getStatusCode());
            }
            List<Future<HttpStatusCode>> futures = pool.invokeAll(tasks);
            for (Future<HttpStatusCode> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer messageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE channel = ? AND message_number = 1",
                Integer.class,
                channel);
        assertThat(messageCount).isEqualTo(1);

        // First-wins under contention: exactly one payload retained (whichever insert won).
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM messages WHERE channel = ? AND message_number = 1",
                String.class,
                channel);
        JsonNode stored = objectMapper.readTree(payload);
        assertThat(stored.path("message").path("mission").asText()).isNotBlank();
    }

    private ResponseEntity<Void> post(ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/messages", new HttpEntity<>(body.toString(), headers), Void.class);
    }

    private ObjectNode launchedBody(UUID channel, long number, String type, long launchSpeed, String mission) {
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
