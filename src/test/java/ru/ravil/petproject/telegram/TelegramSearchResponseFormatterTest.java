package ru.ravil.petproject.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.service.MemoryAnswer;

class TelegramSearchResponseFormatterTest {

    private final TelegramSearchResponseFormatter formatter = new TelegramSearchResponseFormatter(
            Clock.fixed(Instant.parse("2026-06-13T09:00:00Z"), ZoneId.of("Europe/Moscow"))
    );

    @Test
    void formatFoundItems() {
        String result = formatter.format("посмотреть", List.of(
                response("хочу посмотреть Мглу", "Мгла", MemoryUnitType.MOVIE, Set.of("кино"), "2026-06-13T08:00:00Z"),
                response("почитать Clean Code", null, MemoryUnitType.BOOK, Set.of(), "2026-06-12T08:00:00Z")
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

    @Test
    void formatAnswerWithSources() {
        MemoryUnitResponse source = response(
                "Купил USB-C кабель в DNS",
                "Куплен USB-C кабель в DNS",
                MemoryUnitType.NOTE,
                Set.of("dns"),
                "2026-06-13T08:00:00Z"
        );

        String result = formatter.format(
                "где я купил кабель?",
                List.of(source),
                Optional.of(new MemoryAnswer("USB-C кабель куплен в DNS.", List.of(source), 0.9))
        );

        assertThat(result).isEqualTo("""
                Ответ: USB-C кабель куплен в DNS.

                Источники:
                1. Куплен USB-C кабель в DNS
                   Тип: NOTE
                   Добавлено: сегодня
                   Теги: dns""");
    }

    private static MemoryUnitResponse response(
            String rawText,
            String title,
            MemoryUnitType type,
            Set<String> tags,
            String createdAt
    ) {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        OffsetDateTime dateTime = OffsetDateTime.parse(createdAt);
        return new MemoryUnitResponse(
                id,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                rawText,
                title,
                null,
                type,
                tags,
                Set.of(),
                false,
                1.0,
                rawText,
                null,
                null,
                dateTime,
                dateTime,
                dateTime
        );
    }
}
