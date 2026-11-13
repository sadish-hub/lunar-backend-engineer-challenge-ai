package com.lunar.rockets.ingest.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceEntitiesTest {

    @Test
    void messageIdEqualsHashCodeAndAccessors() {
        UUID channel = UUID.randomUUID();
        MessageId a = new MessageId(channel, 7L);
        MessageId b = new MessageId(channel, 7L);
        MessageId other = new MessageId(channel, 8L);

        assertThat(a.getChannel()).isEqualTo(channel);
        assertThat(a.getMessageNumber()).isEqualTo(7L);
        assertThat(a).isEqualTo(a);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(other);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-an-id");
    }

    @Test
    void messageEntityExposesConstructorFields() {
        UUID channel = UUID.randomUUID();
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        MessageId id = new MessageId(channel, 1L);
        MessageEntity entity = new MessageEntity(id, "RocketLaunched", time, "{\"ok\":true}");

        assertThat(entity.getId()).isSameAs(id);
        assertThat(entity.getMessageType()).isEqualTo("RocketLaunched");
        assertThat(entity.getMessageTime()).isEqualTo(time);
        assertThat(entity.getPayload()).isEqualTo("{\"ok\":true}");
        assertThat(entity.isApplied()).isFalse();
        assertThat(entity.getAcceptedAt()).isNull();
    }

    @Test
    void channelWorkEntityExposesConstructorFields() {
        UUID channel = UUID.randomUUID();
        Instant dirtyAt = Instant.parse("2026-01-01T00:00:00Z");
        ChannelWorkEntity entity = new ChannelWorkEntity(channel, dirtyAt);

        assertThat(entity.getChannel()).isEqualTo(channel);
        assertThat(entity.getDirtyAt()).isEqualTo(dirtyAt);
        assertThat(entity.getClaimedAt()).isNull();
        assertThat(entity.getClaimedBy()).isNull();
    }
}
