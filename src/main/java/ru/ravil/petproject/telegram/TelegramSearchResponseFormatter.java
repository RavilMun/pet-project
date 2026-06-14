package ru.ravil.petproject.telegram;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.dto.InboxItemResponse;

@Component
public class TelegramSearchResponseFormatter {

    private final Clock clock;

    public TelegramSearchResponseFormatter() {
        this(Clock.systemDefaultZone());
    }

    TelegramSearchResponseFormatter(Clock clock) {
        this.clock = clock;
    }

    public String format(String query, List<InboxItemResponse> items) {
        if (items.isEmpty()) {
            return formatEmpty(query);
        }

        StringBuilder builder = new StringBuilder("Нашёл ")
                .append(items.size())
                .append(" ")
                .append(pluralizeItems(items.size()))
                .append(":\n");

        for (int i = 0; i < items.size(); i++) {
            InboxItemResponse item = items.get(i);
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

    private String displayText(InboxItemResponse item) {
        String text = StringUtils.hasText(item.title()) ? item.title() : item.rawText();
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 77) + "...";
    }

    private String formatCreatedAt(InboxItemResponse item) {
        if (item.createdAt() == null) {
            return "-";
        }

        ZoneId zoneId = clock.getZone();
        LocalDate createdDate = item.createdAt().atZoneSameInstant(zoneId).toLocalDate();
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
