package com.lunar.rockets.read.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lunar.rockets.apply.service.ApplyWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RocketsReadIT {

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
    void emptyListReturns200EmptyArray() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/rockets", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    void getUnknownRocketReturns404() {
        UUID unknown = UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.getForEntity("/rockets/" + unknown, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getKnownRocketReturnsFields() throws Exception {
        UUID id = UUID.randomUUID();
        seedRocket(id, "Falcon-9", 100L, "ARTEMIS", false, null);

        ResponseEntity<String> response = restTemplate.getForEntity("/rockets/" + id, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "type", "speed", "mission", "exploded", "explosionReason");
        assertThat(body.path("id").asText()).isEqualTo(id.toString());
        assertThat(body.path("type").asText()).isEqualTo("Falcon-9");
        assertThat(body.path("speed").asLong()).isEqualTo(100L);
        assertThat(body.path("mission").asText()).isEqualTo("ARTEMIS");
        assertThat(body.path("exploded").asBoolean()).isFalse();
        assertThat(body.path("explosionReason").isNull()).isTrue();
        assertThat(body.has("version")).isFalse();
        assertThat(body.has("lastAppliedMessageNumber")).isFalse();
    }

    @Test
    void getExplodedRocketIncludesReason() throws Exception {
        UUID id = UUID.randomUUID();
        seedRocket(id, "Falcon-9", -5L, "ARTEMIS", true, "engine failure");

        ResponseEntity<String> response = restTemplate.getForEntity("/rockets/" + id, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("speed").asLong()).isEqualTo(-5L);
        assertThat(body.path("exploded").asBoolean()).isTrue();
        assertThat(body.path("explosionReason").asText()).isEqualTo("engine failure");
    }

    @Test
    void getMalformedUuidReturns400() {
        ResponseEntity<String> response = restTemplate.getForEntity("/rockets/not-a-uuid", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void defaultListOrderIsByIdAscending() throws Exception {
        UUID a = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID b = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID c = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        seedRocket(c, "C", 30L, "C", false, null);
        seedRocket(a, "A", 10L, "A", false, null);
        seedRocket(b, "B", 20L, "B", false, null);

        JsonNode body = objectMapper.readTree(restTemplate.getForEntity("/rockets", String.class).getBody());

        assertThat(ids(body)).containsExactly(a.toString(), b.toString(), c.toString());
    }

    @Test
    void sortBySpeedAscWithIdTieBreak() throws Exception {
        UUID slow = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID fastA = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID fastB = UUID.fromString("33333333-3333-3333-3333-333333333333");
        seedRocket(fastB, "Falcon-9", 200L, "M2", false, null);
        seedRocket(slow, "Falcon-9", 50L, "M1", false, null);
        seedRocket(fastA, "Falcon-Heavy", 200L, "M3", false, null);

        JsonNode body = objectMapper.readTree(
                restTemplate.getForEntity("/rockets?sort=speed&order=asc", String.class).getBody());

        assertThat(ids(body)).containsExactly(slow.toString(), fastA.toString(), fastB.toString());
        assertThat(speeds(body)).containsExactly(50L, 200L, 200L);
    }

    @Test
    void sortBySpeedDesc() throws Exception {
        UUID a = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID b = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        seedRocket(a, "Falcon-9", 10L, "A", false, null);
        seedRocket(b, "Falcon-9", 99L, "B", false, null);

        JsonNode body = objectMapper.readTree(
                restTemplate.getForEntity("/rockets?sort=speed&order=desc", String.class).getBody());

        assertThat(speeds(body)).containsExactly(99L, 10L);
    }

    @Test
    void sortByMissionAndType() throws Exception {
        UUID alpha = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID beta = UUID.fromString("22222222-2222-2222-2222-222222222222");
        seedRocket(beta, "Z-Type", 1L, "ALPHA", false, null);
        seedRocket(alpha, "A-Type", 1L, "ZULU", false, null);

        JsonNode byMission = objectMapper.readTree(
                restTemplate.getForEntity("/rockets?sort=mission", String.class).getBody());
        assertThat(missions(byMission)).containsExactly("ALPHA", "ZULU");

        JsonNode byType = objectMapper.readTree(
                restTemplate.getForEntity("/rockets?sort=type&order=desc", String.class).getBody());
        assertThat(types(byType)).containsExactly("Z-Type", "A-Type");
    }

    @Test
    void omittedOrderDefaultsToAsc() throws Exception {
        UUID a = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID b = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        seedRocket(b, "Falcon-9", 200L, "B", false, null);
        seedRocket(a, "Falcon-9", 50L, "A", false, null);

        JsonNode body = objectMapper.readTree(
                restTemplate.getForEntity("/rockets?sort=speed", String.class).getBody());

        assertThat(speeds(body)).containsExactly(50L, 200L);
    }

    @Test
    void invalidSortReturns400() {
        ResponseEntity<String> response = restTemplate.getForEntity("/rockets?sort=version", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidOrderReturns400() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/rockets?sort=speed&order=up", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void postAndApplyThenGetMatchesProjection() throws Exception {
        UUID channel = UUID.randomUUID();
        insertLaunch(channel, 1L, "Falcon-9", 100L, "ARTEMIS");
        insertMission(channel, 2L, "MOON");
        dirty(channel);

        applyWorker.runOnce("read-it-worker");

        ResponseEntity<String> get = restTemplate.getForEntity("/rockets/" + channel, String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(get.getBody());
        assertThat(body.path("type").asText()).isEqualTo("Falcon-9");
        assertThat(body.path("speed").asLong()).isEqualTo(100L);
        assertThat(body.path("mission").asText()).isEqualTo("MOON");

        JsonNode list = objectMapper.readTree(restTemplate.getForEntity("/rockets", String.class).getBody());
        assertThat(ids(list)).contains(channel.toString());
    }

    @Test
    void acceptedButNotYetAppliedChannelStill404() {
        UUID channel = UUID.randomUUID();
        insertLaunch(channel, 1L, "Falcon-9", 100L, "ARTEMIS");
        dirty(channel);
        // no applyWorker.runOnce

        ResponseEntity<String> response = restTemplate.getForEntity("/rockets/" + channel, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void stickyExplodePlusLaterMissionVisibleOnGet() throws Exception {
        UUID channel = UUID.randomUUID();
        insertLaunch(channel, 1L, "Falcon-9", 100L, "ARTEMIS");
        insertExplode(channel, 2L, "kaboom");
        insertMission(channel, 3L, "SALVAGE");
        dirty(channel);

        applyWorker.runOnce("read-it-worker");

        JsonNode body = objectMapper.readTree(
                restTemplate.getForEntity("/rockets/" + channel, String.class).getBody());
        assertThat(body.path("exploded").asBoolean()).isTrue();
        assertThat(body.path("explosionReason").asText()).isEqualTo("kaboom");
        assertThat(body.path("mission").asText()).isEqualTo("SALVAGE");
    }

    private void seedRocket(
            UUID id, String type, long speed, String mission, boolean exploded, String reason) {
        jdbc.update(
                """
                INSERT INTO rockets (id, type, speed, mission, exploded, explosion_reason, last_applied_number, version)
                VALUES (?, ?, ?, ?, ?, ?, 1, 1)
                """,
                id,
                type,
                speed,
                mission,
                exploded,
                reason);
    }

    private static List<String> ids(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path("id").asText()));
        return out;
    }

    private static List<Long> speeds(JsonNode array) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path("speed").asLong()));
        return out;
    }

    private static List<String> missions(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path("mission").asText()));
        return out;
    }

    private static List<String> types(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path("type").asText()));
        return out;
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
