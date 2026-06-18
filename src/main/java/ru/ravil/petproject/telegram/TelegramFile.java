package ru.ravil.petproject.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of {@code getFile}: {@code filePath} is a relative path used to download the bytes from
 * {@code https://api.telegram.org/file/bot<token>/<file_path>}.
 */
public record TelegramFile(
        @JsonProperty("file_id") String fileId,
        @JsonProperty("file_unique_id") String fileUniqueId,
        @JsonProperty("file_size") Integer fileSize,
        @JsonProperty("file_path") String filePath
) {
}
