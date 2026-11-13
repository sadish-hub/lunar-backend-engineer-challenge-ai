package com.lunar.rockets.ingest.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lunar.rockets.ingest.api.MessageEnvelope;
import com.lunar.rockets.ingest.api.MessageMetadata;
import com.lunar.rockets.ingest.persistence.ChannelWorkRepository;
import com.lunar.rockets.ingest.persistence.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageIngestServiceTest {

    @Mock
    MessageRepository messages;

    @Mock
    ChannelWorkRepository channelWork;

    @Mock
    ObjectMapper objectMapper;

    @InjectMocks
    MessageIngestService service;

    @Test
    void parsesOffsetDateTimeMessageTimeAndDirtiesOnInsert() throws Exception {
        UUID channel = UUID.randomUUID();
        MessageEnvelope envelope = envelope(channel, "2026-01-01T00:00:00+00:00");
        when(objectMapper.writeValueAsString(envelope)).thenReturn("{\"ok\":true}");
        when(messages.insertIgnoreConflict(any(), anyLong(), anyString(), any(), anyString())).thenReturn(1);

        service.ingest(envelope);

        ArgumentCaptor<Instant> time = ArgumentCaptor.forClass(Instant.class);
        verify(messages).insertIgnoreConflict(
                eq(channel), eq(1L), eq("RocketLaunched"), time.capture(), eq("{\"ok\":true}"));
        assertThat(time.getValue()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        verify(channelWork).upsertDirty(channel);
    }

    @Test
    void unparsableMessageTimeStoresNullAndSkipsDirtyOnDuplicate() throws Exception {
        UUID channel = UUID.randomUUID();
        MessageEnvelope envelope = envelope(channel, "not-a-time");
        when(objectMapper.writeValueAsString(envelope)).thenReturn("{}");
        when(messages.insertIgnoreConflict(any(), anyLong(), anyString(), any(), anyString())).thenReturn(0);

        service.ingest(envelope);

        verify(messages).insertIgnoreConflict(eq(channel), eq(1L), eq("RocketLaunched"), isNull(), eq("{}"));
        verify(channelWork, never()).upsertDirty(any());
    }

    @Test
    void serializationFailureFailsClosed() throws Exception {
        UUID channel = UUID.randomUUID();
        MessageEnvelope envelope = envelope(channel, "2026-01-01T00:00:00Z");
        when(objectMapper.writeValueAsString(envelope))
                .thenThrow(new JsonProcessingException("boom") {});

        assertThatThrownBy(() -> service.ingest(envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to serialize");
    }

    private static MessageEnvelope envelope(UUID channel, String messageTime) {
        return new MessageEnvelope(
                new MessageMetadata(channel.toString(), 1L, messageTime, "RocketLaunched"),
                new ObjectMapper().createObjectNode()
                        .put("type", "Falcon-9")
                        .put("launchSpeed", 100)
                        .put("mission", "M"));
    }
}
