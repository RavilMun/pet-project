package ru.ravil.petproject.telegram;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.service.MemoryAnswer;

@Component
public class TelegramSearchResponseFormatter {

    private final Clock clock;

    public TelegramSearchResponseFormatter() {
        this(Clock.systemDefaultZone());
    }

    TelegramSearchResponseFormatter(Clock clock) {
        this.clock = clock;
    }

    public String format(String query, List<MemoryUnitResponse> items) {
        return format(query, items, Optional.empty());
    }

    public String format(String query, List<MemoryUnitResponse> items, Optional<MemoryAnswer> answer) {
        if (items.isEmpty()) {
            return formatEmpty(query);
        }

        if (answer.isPresent() && answer.get().hasText()) {
            return formatAnswer(answer.get());
        }

        return formatItems(items);
    }

    private String formatAnswer(MemoryAnswer answer) {
        List<MemoryUnitResponse> sources = answer.sources().isEmpty() ? List.of() : answer.sources();
        if (sources.isEmpty()) {
            return "Ответ: " + answer.text();
        }

        return new StringBuilder("Ответ: ")
                .append(answer.text())
                .append("\n\nИсточники:\n")
                .append(formatNumberedItems(sources).stripLeading())
                .toString();
    }

    private String formatItems(List<MemoryUnitResponse> items) {
        StringBuilder builder = new StringBuilder("Нашёл ")
                .append(items.size())
                .append(" ")
                .append(pluralizeItems(items.size()))
                .append(":\n");

        builder.append(formatNumberedItems(items));
        return builder.toString();
    }

    private String formatNumberedItems(List<MemoryUnitResponse> items) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            MemoryUnitResponse item = items.get(i);
            builder.append("\n")
                    .append(i + 1)
                    .append(". ")
                    .append(displayText(item))
                    .append("\n   Тип: ")
                    .append(item.type())
                    .append("\n   Добавлено: ")
                    .append(formatCreatedAt(item));

            if (!item.tags().isEmpty()) {
                builder.append("\n   Теги: ").append(String.join(", ", item.tags()));
            }
        }

        return builder.toString();
    }

    private String formatEmpty(String query) {
        StringBuilder builder = new StringBuilder("Ничего не нашёл");
        if (StringUtils.hasText(query)) {
            builder.append(" по \"").append(query.trim()).append("\"");
        }
        return builder
                .append(".\nПопробуй: найди кресло, покажи последние, что сохранил сегодня")
                .toString();
    }

    private String displayText(MemoryUnitResponse item) {
        String text = StringUtils.hasText(item.title()) ? item.title() : item.sourceRawText();
        if (!StringUtils.hasText(text)) {
            return "-";
        }
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 77) + "...";
    }

    private String formatCreatedAt(MemoryUnitResponse item) {
        if (item.sourceCreatedAt() == null) {
            return "-";
        }

        ZoneId zoneId = clock.getZone();
        LocalDate createdDate = item.sourceCreatedAt().atZoneSameInstant(zoneId).toLocalDate();
        LocalDate today = LocalDate.now(clock);
        if (createdDate.equals(today)) {
            return "сегодня";
        }
        if (createdDate.equals(today.minusDays(1))) {
            return "вчера";
        }
        return createdDate.toString();
    }

    private String pluralizeItems(int count) {
        int lastTwoDigits = count % 100;
        if (lastTwoDigits >= 11 && lastTwoDigits <= 14) {
            return "записей";
        }

        return switch (count % 10) {
            case 1 -> "запись";
            case 2, 3, 4 -> "записи";
            default -> "записей";
        };
    }
}
