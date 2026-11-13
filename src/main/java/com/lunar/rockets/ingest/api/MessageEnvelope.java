package com.lunar.rockets.ingest.api;

import com.fasterxml.jackson.databind.JsonNode;

public record MessageEnvelope(
        MessageMetadata metadata,
        JsonNode message
) {
}
