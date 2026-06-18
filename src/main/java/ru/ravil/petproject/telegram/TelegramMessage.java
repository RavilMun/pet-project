package ru.ravil.petproject.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TelegramMessage(
        @JsonProperty("message_id") Long messageId,
        TelegramChat chat,
        String text,
        List<TelegramPhotoSize> photo,
        String caption,
        TelegramVoice voice
) {

    /** Largest available photo size, or {@code null} if this message carries no photo. */
    public TelegramPhotoSize largestPhoto() {
        if (photo == null || photo.isEmpty()) {
            return null;
        }
        return photo.stream()
                .max(java.util.Comparator.comparingInt(size ->
                        size.fileSize() != null ? size.fileSize()
                                : (size.width() != null && size.height() != null ? size.width() * size.height() : 0)))
                .orElse(null);
    }
}
