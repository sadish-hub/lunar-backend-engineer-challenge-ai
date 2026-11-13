package com.lunar.rockets.ingest.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lunar.rockets.ingest.api.MessageEnvelope;
import com.lunar.rockets.ingest.api.MessageType;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Component
public class IngestValidator {

    private final ObjectMapper objectMapper;
    private final JsonSchema envelopeSchema;
    private final Map<MessageType, JsonSchema> messageSchemas;

    public IngestValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.envelopeSchema = load(factory, "classpath:schemas/envelope.json");
        Map<MessageType, JsonSchema> schemas = new EnumMap<>(MessageType.class);
        for (MessageType type : MessageType.values()) {
            schemas.put(type, load(factory, "classpath:schemas/" + type.name() + ".json"));
        }
        this.messageSchemas = Map.copyOf(schemas);
    }

    public void validate(MessageEnvelope envelope) {
        if (envelope == null) {
            throw new IngestValidationException("envelope required");
        }

        JsonNode node = objectMapper.valueToTree(envelope);
        assertValid(envelopeSchema, node);

        MessageType type = MessageType.from(envelope.metadata().messageType())
                .orElseThrow(() -> new IngestValidationException("unknown or missing messageType"));
        assertValid(messageSchemas.get(type), envelope.message());
    }

    private static JsonSchema load(JsonSchemaFactory factory, String location) {
        JsonSchema schema = factory.getSchema(SchemaLocation.of(location));
        schema.initializeValidators();
        return schema;
    }

    private static void assertValid(JsonSchema schema, JsonNode node) {
        Set<ValidationMessage> errors = schema.validate(
                node,
                executionContext -> executionContext.getExecutionConfig().setFormatAssertionsEnabled(true));
        if (!errors.isEmpty()) {
            throw new IngestValidationException(errors.iterator().next().getMessage());
        }
    }
}
