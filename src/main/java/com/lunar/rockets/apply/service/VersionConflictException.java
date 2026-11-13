package com.lunar.rockets.apply.service;

public class VersionConflictException extends RuntimeException {

    public VersionConflictException(String message) {
        super(message);
    }
}
