package com.lunar.rockets.read.api;

public class InvalidReadQueryException extends RuntimeException {

    public InvalidReadQueryException(String message) {
        super(message);
    }
}
