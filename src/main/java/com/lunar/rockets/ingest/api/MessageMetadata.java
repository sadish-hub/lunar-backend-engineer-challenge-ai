package com.lunar.rockets.ingest.api;

public record MessageMetadata(
        String channel,
        Long messageNumber,
        String messageTime,
        String messageType
) {
}
