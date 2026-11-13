package com.lunar.rockets.read.api;

import com.lunar.rockets.read.service.RocketReadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RocketsController.class)
@Import(ReadExceptionHandler.class)
class RocketsControllerWebTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RocketReadService readService;

    @Test
    void emptyListReturnsJsonArray() throws Exception {
        when(readService.list(RocketSortField.ID, SortOrder.ASC)).thenReturn(List.of());

        mockMvc.perform(get("/rockets").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void getUnknownMapsTo404() throws Exception {
        UUID id = UUID.randomUUID();
        when(readService.get(id)).thenThrow(new ResponseStatusException(NOT_FOUND));

        mockMvc.perform(get("/rockets/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void getKnownReturnsDtoFields() throws Exception {
        UUID id = UUID.randomUUID();
        when(readService.get(id)).thenReturn(new RocketResponse(
                id, "Falcon-9", 42L, "ARTEMIS", true, "boom"));

        mockMvc.perform(get("/rockets/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.type").value("Falcon-9"))
                .andExpect(jsonPath("$.speed").value(42))
                .andExpect(jsonPath("$.mission").value("ARTEMIS"))
                .andExpect(jsonPath("$.exploded").value(true))
                .andExpect(jsonPath("$.explosionReason").value("boom"));
    }

    @Test
    void sortAndOrderAreParsedAndPassedToService() throws Exception {
        when(readService.list(RocketSortField.SPEED, SortOrder.DESC)).thenReturn(List.of());

        mockMvc.perform(get("/rockets").param("sort", "speed").param("order", "desc"))
                .andExpect(status().isOk());

        verify(readService).list(eq(RocketSortField.SPEED), eq(SortOrder.DESC));
    }

    @Test
    void omittedSortDefaultsToIdAsc() throws Exception {
        when(readService.list(RocketSortField.ID, SortOrder.ASC)).thenReturn(List.of());

        mockMvc.perform(get("/rockets"))
                .andExpect(status().isOk());

        verify(readService).list(eq(RocketSortField.ID), eq(SortOrder.ASC));
    }

    @Test
    void invalidSortReturns400() throws Exception {
        mockMvc.perform(get("/rockets").param("sort", "nope"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidOrderReturns400() throws Exception {
        mockMvc.perform(get("/rockets").param("order", "sideways"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedUuidReturns400() throws Exception {
        mockMvc.perform(get("/rockets/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
