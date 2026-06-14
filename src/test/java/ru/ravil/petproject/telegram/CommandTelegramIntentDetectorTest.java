package ru.ravil.petproject.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommandTelegramIntentDetectorTest {

    private final CommandTelegramIntentDetector detector = new CommandTelegramIntentDetector();

    @Test
    void detectRecentCommand() {
        assertThat(detector.detect("/recent").type()).isEqualTo(TelegramIntentType.RECENT);
    }

    @Test
    void detectTodayCommand() {
        assertThat(detector.detect("/today").type()).isEqualTo(TelegramIntentType.TODAY);
    }

    @Test
    void detectSearchCommand() {
        TelegramIntent intent = detector.detect("/search кресло");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("кресло");
    }

    @Test
    void returnsUnknownForEmptySearchQuery() {
        assertThat(detector.detect("/search").type()).isEqualTo(TelegramIntentType.UNKNOWN);
    }

    @Test
    void returnsUnknownForRegularText() {
        assertThat(detector.detect("найди кресло").type()).isEqualTo(TelegramIntentType.UNKNOWN);
    }
}
