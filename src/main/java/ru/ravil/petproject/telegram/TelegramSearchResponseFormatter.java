package ru.ravil.petproject.telegram;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
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
            return formatAnswer(query, answer.get());
        }

        return formatItems(items);
    }

    /**
     * Conversational answer: just the answer text by default. Sources are appended only when the user
     * explicitly asks (query mentions "источник"), and then as the original raw record(s) + date,
     * deduplicated by source message (so one big note doesn't list every extracted unit) and without
     * type/tags.
     */
    private String formatAnswer(String query, MemoryAnswer answer) {
        if (!wantsSources(query) || answer.sources().isEmpty()) {
            return answer.text();
        }
        String sources = formatSources(answer.sources());
        return sources.isBlank() ? answer.text() : answer.text() + "\n\n" + sources;
    }

    private boolean wantsSources(String query) {
        return query != null && query.toLowerCase(Locale.ROOT).contains("источник");
    }

    private String formatSources(List<MemoryUnitResponse> sources) {
        LinkedHashMap<UUID, MemoryUnitResponse> byMessage = new LinkedHashMap<>();
        for (MemoryUnitResponse source : sources) {
            UUID key = source.inboxItemId() != null ? source.inboxItemId() : source.id();
            byMessage.putIfAbsent(key, source);
        }

        StringBuilder builder = new StringBuilder(byMessage.size() == 1 ? "Источник:" : "Источники:");
        for (MemoryUnitResponse source : byMessage.values()) {
            builder.append("\n• ").append(sourceText(source));
            String date = formatCreatedAt(source);
            if (!"-".equals(date)) {
                builder.append(" — ").append(date);
            }
        }
        return builder.toString();
    }

    private String sourceText(MemoryUnitResponse source) {
        String raw = StringUtils.hasText(source.sourceRawText()) ? source.sourceRawText() : source.title();
        if (!StringUtils.hasText(raw)) {
            return "(запись)";
        }
        raw = raw.strip();
        return raw.length() <= 200 ? "«" + raw + "»" : "«" + raw.substring(0, 197) + "...»";
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
