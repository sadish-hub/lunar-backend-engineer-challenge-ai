package com.lunar.rockets.ingest.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lunar.rockets.ingest.api.MessageEnvelope;
import com.lunar.rockets.ingest.persistence.ChannelWorkRepository;
import com.lunar.rockets.ingest.persistence.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Service
public class MessageIngestService {

    private final MessageRepository messages;
    private final ChannelWorkRepository channelWork;
    private final ObjectMapper objectMapper;

    public MessageIngestService(
            MessageRepository messages,
            ChannelWorkRepository channelWork,
            ObjectMapper objectMapper) {
        this.messages = messages;
        this.channelWork = channelWork;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void ingest(MessageEnvelope envelope) {
        UUID channel = UUID.fromString(envelope.metadata().channel());
        long messageNumber = envelope.metadata().messageNumber();
        String messageType = envelope.metadata().messageType();
        Instant messageTime = parseMessageTime(envelope.metadata().messageTime());
        String payloadJson = toJson(envelope);

        int inserted = messages.insertIgnoreConflict(
                channel, messageNumber, messageType, messageTime, payloadJson);
        if (inserted == 1) {
            channelWork.upsertDirty(channel);
        }
    }

    private String toJson(MessageEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize accepted envelope", ex);
        }
    }

    private static Instant parseMessageTime(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
