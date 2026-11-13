package com.lunar.rockets.ingest.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lunar.rockets.ingest.api.MessageEnvelope;
import com.lunar.rockets.ingest.persistence.MessageIngestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Service
public class MessageIngestService {

    private final MessageIngestRepository repository;
    private final ObjectMapper objectMapper;

    public MessageIngestService(MessageIngestRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void ingest(MessageEnvelope envelope) {
        UUID channel = UUID.fromString(envelope.metadata().channel());
        long messageNumber = envelope.metadata().messageNumber();
        String messageType = envelope.metadata().messageType();
        Timestamp messageTime = parseMessageTime(envelope.metadata().messageTime());
        String payloadJson = toJson(envelope);

        int inserted = repository.insertMessage(channel, messageNumber, messageType, messageTime, payloadJson);
        if (inserted == 1) {
            repository.markChannelDirty(channel);
        }
    }

    private String toJson(MessageEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize accepted envelope", ex);
        }
    }

    private static Timestamp parseMessageTime(String raw) {
        try {
            return Timestamp.from(Instant.parse(raw));
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(raw).toInstant());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
