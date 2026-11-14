package com.lunar.rockets.api;

import org.springframework.http.HttpStatus;

public record ApiError(int status, String error, String message) {

    public static ApiError of(HttpStatus httpStatus, String message) {
        return new ApiError(httpStatus.value(), httpStatus.getReasonPhrase(), message);
    }
}
