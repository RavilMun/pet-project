package ru.ravil.petproject.telegram;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Re-sends a previously received photo by its Telegram {@code file_id} (no byte upload needed).
 * {@code caption} is optional and omitted from the JSON when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelegramSendPhotoRequest(
        @JsonProperty("chat_id") long chatId,
        String photo,
        String caption
) {
}
