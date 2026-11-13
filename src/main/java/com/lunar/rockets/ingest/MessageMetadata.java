package com.lunar.rockets.ingest;

record MessageMetadata(
        String channel,
        Long messageNumber,
        String messageTime,
        String messageType
) {
}
