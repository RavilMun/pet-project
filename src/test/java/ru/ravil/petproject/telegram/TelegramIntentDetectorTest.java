package ru.ravil.petproject.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TelegramIntentDetectorTest {

    private final TelegramIntentDetector detector = new TelegramIntentDetector();

    @Test
    void detectSearchByPrefix() {
        TelegramIntent intent = detector.detect("найди pgvector");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("pgvector");
    }

    @Test
    void detectSearchQuestion() {
        TelegramIntent intent = detector.detect("что я сохранял про кресло?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isEqualTo("кресло");
    }

    @Test
    void detectRecent() {
        assertThat(detector.detect("покажи последние").type()).isEqualTo(TelegramIntentType.RECENT);
    }

    @Test
    void detectToday() {
        assertThat(detector.detect("что я добавлял сегодня?").type()).isEqualTo(TelegramIntentType.TODAY);
    }

    @Test
    void detectTodayWithPerfectiveVerb() {
        assertThat(detector.detect("что я добавил сегодня?").type()).isEqualTo(TelegramIntentType.TODAY);
    }

    @Test
    void detectHelp() {
        assertThat(detector.detect("что ты умеешь?").type()).isEqualTo(TelegramIntentType.HELP);
    }

    @Test
    void defaultsToCapture() {
        assertThat(detector.detect("хочу посмотреть фильм Мгла").type()).isEqualTo(TelegramIntentType.CAPTURE);
    }
}
