/**
 * Message ingest seam: {@code POST /messages} validation and durable idempotent accept.
 *
 * <p>Layout: {@code api} (HTTP + DTOs), {@code validation} (JSON Schema),
 * {@code persistence} (JPA entities + native ON CONFLICT repos), {@code service} (ingest orchestration).
 */
package com.lunar.rockets.ingest;
