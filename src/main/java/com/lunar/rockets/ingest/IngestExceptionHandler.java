package com.lunar.rockets.ingest;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class IngestExceptionHandler {

    @ExceptionHandler(IngestValidationException.class)
    ResponseEntity<Void> validation(IngestValidationException ignored) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Void> unreadable(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Void> transientDataAccess(DataAccessException ignored) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
