package com.lunar.rockets.ingest;

import com.fasterxml.jackson.databind.JsonNode;

record MessageEnvelope(
        MessageMetadata metadata,
        JsonNode message
) {
}
