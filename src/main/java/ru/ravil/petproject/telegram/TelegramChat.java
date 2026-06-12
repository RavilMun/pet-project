package ru.ravil.petproject.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramChat(
        Long id,
        String type,
        @JsonProperty("first_name") String firstName,
        String username
) {
}
