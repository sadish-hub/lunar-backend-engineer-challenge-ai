package com.lunar.rockets.read.api;

import org.springframework.data.domain.Sort;

public enum SortOrder {
    ASC,
    DESC;

    public Sort.Direction toDirection() {
        return this == DESC ? Sort.Direction.DESC : Sort.Direction.ASC;
    }

    /**
     * Parses query {@code order}. Missing/blank → {@link #ASC}.
     * Allowed tokens: {@code asc}, {@code desc} (exact lowercase).
     */
    public static SortOrder parseQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return ASC;
        }
        return switch (raw) {
            case "asc" -> ASC;
            case "desc" -> DESC;
            default -> throw new InvalidReadQueryException("Invalid order: " + raw);
        };
    }
}
