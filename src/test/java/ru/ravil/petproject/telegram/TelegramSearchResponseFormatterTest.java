package ru.ravil.petproject.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.ravil.petproject.domain.InboxItemPriority;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemStatus;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.dto.InboxItemResponse;

class TelegramSearchResponseFormatterTest {

    private final TelegramSearchResponseFormatter formatter = new TelegramSearchResponseFormatter(
            Clock.fixed(Instant.parse("2026-06-13T09:00:00Z"), ZoneId.of("Europe/Moscow"))
    );

    @Test
    void formatFoundItems() {
        String result = formatter.format("посмотреть", List.of(
                response("хочу посмотреть Мглу", "Мгла", InboxItemType.MOVIE, Set.of("кино"), "2026-06-13T08:00:00Z"),
                response("почитать Clean Code", null, InboxItemType.BOOK, Set.of(), "2026-06-12T08:00:00Z")
        ));

        assertThat(result).isEqualTo("""
                Нашёл 2 записи:

                1. Мгла
                   Тип: MOVIE
                   Добавлено: сегодня
                   Теги: кино
                2. почитать Clean Code
                   Тип: BOOK
                   Добавлено: вчера""");
    }

    @Test
    void formatEmptyItems() {
        String result = formatter.format("кресло", List.of());

        assertThat(result).isEqualTo("Ничего не нашёл по \"кресло\".\nПопробуй: найди кресло, покажи последние, что сохранил сегодня");
    }

    private static InboxItemResponse response(
            String rawText,
            String title,
            InboxItemType type,
            Set<String> tags,
            String createdAt
    ) {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        OffsetDateTime dateTime = OffsetDateTime.parse(createdAt);
        return new InboxItemResponse(
                id,
                rawText,
                title,
                null,
                type,
                InboxItemStatus.NEW,
                InboxItemSource.TELEGRAM,
                InboxItemPriority.MEDIUM,
                false,
                42L,
                1L,
                tags,
                Set.of(),
                null,
                dateTime,
                dateTime
        );
    }
}
