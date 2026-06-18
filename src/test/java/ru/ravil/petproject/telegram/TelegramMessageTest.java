package ru.ravil.petproject.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramMessageTest {

    @Test
    void largestPhotoPicksHighestFileSize() {
        TelegramMessage message = new TelegramMessage(1L, null, null,
                List.of(
                        new TelegramPhotoSize("small", "u1", 90, 90, 1000),
                        new TelegramPhotoSize("big", "u2", 800, 800, 50000),
                        new TelegramPhotoSize("mid", "u3", 320, 320, 8000)
                ),
                "caption");

        assertThat(message.largestPhoto()).isNotNull();
        assertThat(message.largestPhoto().fileId()).isEqualTo("big");
    }

    @Test
    void largestPhotoFallsBackToAreaWhenSizeMissing() {
        TelegramMessage message = new TelegramMessage(1L, null, null,
                List.of(
                        new TelegramPhotoSize("small", "u1", 90, 90, null),
                        new TelegramPhotoSize("big", "u2", 800, 800, null)
                ),
                null);

        assertThat(message.largestPhoto().fileId()).isEqualTo("big");
    }

    @Test
    void largestPhotoIsNullWhenNoPhoto() {
        TelegramMessage textOnly = new TelegramMessage(1L, null, "hi", null, null);
        assertThat(textOnly.largestPhoto()).isNull();

        TelegramMessage emptyPhoto = new TelegramMessage(1L, null, null, List.of(), null);
        assertThat(emptyPhoto.largestPhoto()).isNull();
    }
}
