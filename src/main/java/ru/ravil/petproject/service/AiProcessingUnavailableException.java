package ru.ravil.petproject.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class AiProcessingUnavailableException extends RuntimeException {

    public AiProcessingUnavailableException() {
        super("AI processing is unavailable");
    }
}
