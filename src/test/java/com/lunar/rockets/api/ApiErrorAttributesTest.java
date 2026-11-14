package com.lunar.rockets.api;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorAttributesTest {

    @Test
    void mapsBootErrorPageToApiErrorShape() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rockets");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.METHOD_NOT_ALLOWED.value());
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "Request method 'POST' is not supported");

        Map<String, Object> body = new ApiErrorAttributes()
                .getErrorAttributes(new ServletWebRequest(request), ErrorAttributeOptions.defaults());

        assertThat(body).containsOnlyKeys("status", "error", "message");
        assertThat(body.get("status")).isEqualTo(405);
        assertThat(body.get("error")).isEqualTo("Method Not Allowed");
        assertThat(body.get("message")).isEqualTo("Request method 'POST' is not supported");
    }

    @Test
    void fallsBackToErrorPhraseWhenMessageMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/nope");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.NOT_FOUND.value());

        Map<String, Object> body = new ApiErrorAttributes()
                .getErrorAttributes(new ServletWebRequest(request), ErrorAttributeOptions.defaults());

        assertThat(body.get("status")).isEqualTo(404);
        assertThat(body.get("error")).isEqualTo("Not Found");
        assertThat(body.get("message")).isEqualTo("Not Found");
        assertThat(body).doesNotContainKeys("timestamp", "path");
    }
}
