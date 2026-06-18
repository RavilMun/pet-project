package ru.ravil.petproject.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A Telegram voice message (recorded in-app, OGG/Opus). {@code fileId} is reusable for download
 * (via {@code getFile}) and for re-sending the audio later if needed.
 */
public record TelegramVoice(
        @JsonProperty("file_id") String fileId,
        @JsonProperty("file_unique_id") String fileUniqueId,
        Integer duration,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("file_size") Integer fileSize
) {
}
