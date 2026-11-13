package com.lunar.rockets.apply.claim;

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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ChannelClaimConcurrencyIT {

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
        registry.add("lunar.apply.claim-batch-size", () -> "10");
    }

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
    void twoWorkersNeverClaimSameChannel() throws Exception {
        List<UUID> channels = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID channel = UUID.randomUUID();
            channels.add(channel);
            jdbc.update(
                    "INSERT INTO channel_work (channel, dirty_at) VALUES (?, now())",
                    channel);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<List<UUID>> workerA = () -> claimService.claimDirtyBatch("worker-a");
            Callable<List<UUID>> workerB = () -> claimService.claimDirtyBatch("worker-b");
            Future<List<UUID>> fa = pool.submit(workerA);
            Future<List<UUID>> fb = pool.submit(workerB);
            List<UUID> a = fa.get();
            List<UUID> b = fb.get();

            Set<UUID> claimed = new HashSet<>();
            claimed.addAll(a);
            claimed.addAll(b);

            Set<UUID> overlap = new HashSet<>(a);
            overlap.retainAll(b);
            assertThat(overlap).isEmpty();
            assertThat(claimed).hasSize(a.size() + b.size());
            assertThat(claimed).hasSizeLessThanOrEqualTo(channels.size());
            assertThat(a.size() + b.size()).isEqualTo(channels.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
