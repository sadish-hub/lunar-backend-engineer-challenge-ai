package com.lunar.rockets.ingest.api;

import com.lunar.rockets.api.ApiError;
import com.lunar.rockets.ingest.validation.IngestValidationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IngestExceptionHandler {

    @ExceptionHandler(IngestValidationException.class)
    public ResponseEntity<ApiError> validation(IngestValidationException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST, "Malformed JSON"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> transientDataAccess(DataAccessException ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable"));
    }
}
