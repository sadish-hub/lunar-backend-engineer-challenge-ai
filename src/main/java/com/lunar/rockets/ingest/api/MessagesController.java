package com.lunar.rockets.ingest.api;

import com.lunar.rockets.ingest.service.MessageIngestService;
import com.lunar.rockets.ingest.validation.IngestValidator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MessagesController {

    private final IngestValidator validator;
    private final MessageIngestService ingestService;

    public MessagesController(IngestValidator validator, MessageIngestService ingestService) {
        this.validator = validator;
        this.ingestService = ingestService;
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.OK)
    public void postMessage(@RequestBody MessageEnvelope envelope) {
        validator.validate(envelope);
        ingestService.ingest(envelope);
    }
}
