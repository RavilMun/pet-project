package ru.ravil.petproject.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TelegramSendMessageRequest(
        @JsonProperty("chat_id") long chatId,
        String text
) {
}
