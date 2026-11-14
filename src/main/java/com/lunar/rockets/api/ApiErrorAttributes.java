package com.lunar.rockets.api;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps Spring Boot's {@code /error} JSON to the same {@code status}/{@code error}/{@code message}
 * shape used by {@link ApiError} handlers (so unknown paths / 405 are not a different body).
 */
@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> source = super.getErrorAttributes(
                webRequest, options.including(ErrorAttributeOptions.Include.MESSAGE));
        int status = (Integer) source.getOrDefault("status", 500);
        String error = String.valueOf(source.getOrDefault("error", "Error"));
        Object rawMessage = source.get("message");
        String message = (rawMessage == null
                || rawMessage.toString().isBlank()
                || "No message available".equals(rawMessage.toString()))
                ? error
                : rawMessage.toString();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", error);
        body.put("message", message);
        return body;
    }
}
