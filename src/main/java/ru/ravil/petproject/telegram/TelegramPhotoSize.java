package ru.ravil.petproject.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One size variant of a Telegram photo. Telegram sends an array of these (smallest → largest);
 * we keep the largest for the best vision/OCR quality. {@code fileId} is reusable for re-sending
 * the same photo via {@code sendPhoto} without re-uploading bytes.
 */
public record TelegramPhotoSize(
        @JsonProperty("file_id") String fileId,
        @JsonProperty("file_unique_id") String fileUniqueId,
        Integer width,
        Integer height,
        @JsonProperty("file_size") Integer fileSize
) {
}
