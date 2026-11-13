package com.lunar.rockets.ingest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MessagesController {

    private final IngestValidator validator;
    private final MessageIngestService ingestService;

    MessagesController(IngestValidator validator, MessageIngestService ingestService) {
        this.validator = validator;
        this.ingestService = ingestService;
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.OK)
    void postMessage(@RequestBody MessageEnvelope envelope) {
        validator.validate(envelope);
        ingestService.ingest(envelope);
    }
}
