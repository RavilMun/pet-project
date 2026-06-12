package ru.ravil.petproject.service;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class InboxItemNotFoundException extends RuntimeException {

    public InboxItemNotFoundException(UUID id) {
        super("Inbox item not found: " + id);
    }
}
