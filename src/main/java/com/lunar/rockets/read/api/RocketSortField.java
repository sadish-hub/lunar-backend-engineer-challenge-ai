package com.lunar.rockets.read.api;

import java.util.Locale;
import java.util.Optional;

public enum RocketSortField {
    ID("id"),
    LAST_APPLIED_NUMBER("lastAppliedNumber"),
    SPEED("speed"),
    MISSION("mission"),
    TYPE("type");

    private final String property;

    RocketSortField(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }

    /**
     * Parses query {@code sort} values. Missing → empty (controller defaults to {@link #LAST_APPLIED_NUMBER}).
     * Allowed tokens: {@code speed}, {@code mission}, {@code type} (exact lowercase).
     * {@link #LAST_APPLIED_NUMBER} and {@link #ID} are not query tokens.
     */
    public static Optional<RocketSortField> parseQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return switch (raw) {
            case "speed" -> Optional.of(SPEED);
            case "mission" -> Optional.of(MISSION);
            case "type" -> Optional.of(TYPE);
            default -> throw new InvalidReadQueryException("Invalid sort: " + raw);
        };
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
