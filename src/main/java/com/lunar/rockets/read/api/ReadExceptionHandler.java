package com.lunar.rockets.read.api;

import org.springframework.core.convert.ConversionFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.lunar.rockets.read")
public class ReadExceptionHandler {

    @ExceptionHandler(InvalidReadQueryException.class)
    public ResponseEntity<String> invalidQuery(InvalidReadQueryException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ConversionFailedException.class})
    public ResponseEntity<String> badPathVariable(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid rocket id");
    }
}
