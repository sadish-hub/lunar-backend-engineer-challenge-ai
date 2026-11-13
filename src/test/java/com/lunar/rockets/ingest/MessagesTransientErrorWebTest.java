package com.lunar.rockets.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MessagesController.class)
@Import({IngestValidator.class, IngestExceptionHandler.class})
class MessagesTransientErrorWebTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MessageIngestService ingestService;

    @Test
    void returns503WhenPersistFailsTransiently() throws Exception {
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(ingestService)
                .ingest(any());

        String channel = UUID.randomUUID().toString();
        String body = """
                {
                  "metadata": {
                    "channel": "%s",
                    "messageNumber": 1,
                    "messageTime": "2026-11-13T12:00:00Z",
                    "messageType": "RocketLaunched"
                  },
                  "message": {
                    "type": "Falcon-9",
                    "launchSpeed": 100,
                    "mission": "ARTEMIS"
                  }
                }
                """.formatted(channel);

        mockMvc.perform(post("/messages").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());
    }
}
