package ru.ravil.petproject.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramMessage(
        @JsonProperty("message_id") Long messageId,
        TelegramChat chat,
        String text
) {
}
