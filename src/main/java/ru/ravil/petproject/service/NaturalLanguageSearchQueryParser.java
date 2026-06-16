package ru.ravil.petproject.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.domain.InboxItemType;

@Component
public class NaturalLanguageSearchQueryParser {

    private static final List<String> SEARCH_PREFIXES = List.of(
            "найди ",
            "найти ",
            "поищи ",
            "поиск ",
            "искать ",
            "что я сохранял про ",
            "что сохранял про ",
            "что я сохранил про ",
            "что сохранил про ",
            "что я добавлял про ",
            "что добавлял про ",
            "покажи про ",
            "покажи записи про ",
            "покажи заметки про "
    );

    private static final List<String> QUESTION_SEARCH_PREFIXES = List.of(
            "что ",
            "че ",
            "чё ",
            "чо ",
            "шо ",
            "какие ",
            "какой ",
            "какая ",
            "какое ",
            "кто ",
            "кого ",
            "кому ",
            "кем ",
            "чем ",
            "сколько ",
            "скок ",
            "почему ",
            "зачем ",
            "куда ",
            "откуда ",
            "с кем ",
            "с чем ",
            "про что ",
            "во что ",
            "когда мне ",
            "когда ",
            "где "
    );

    private static final List<String> GENERIC_SEARCH_PREFIXES = List.of(
            "покажи ",
            "покажи записи ",
            "покажи заметки ",
            "сохраненные ",
            "сохранённые ",
            "сохраненный ",
            "сохранённый ",
            "сохраненное ",
            "сохранённое ",
            "сохраненная ",
            "сохранённая ",
            "сегодняшние ",
            "сегодняшний ",
            "сегодняшняя ",
            "сегодняшнее ",
            "мои "
    );

    public SearchQuery parse(String text) {
        if (!StringUtils.hasText(text)) {
            return SearchQuery.unknown();
        }

        String normalized = normalize(text);

        if (isToday(normalized)) {
            return SearchQuery.today();
        }
        if (isYesterday(normalized)) {
            return SearchQuery.search(null, Set.of(), SearchPeriod.YESTERDAY);
        }
        if (isRecent(normalized)) {
            return SearchQuery.recent();
        }

        for (String prefix : SEARCH_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                String query = cleanQuery(normalized.substring(prefix.length()));
                if (StringUtils.hasText(query)) {
                    return search(query, normalized);
                }
            }
        }

        SearchQuery questionQuery = parseQuestionSearch(normalized);
        if (!questionQuery.isUnknown()) {
            return questionQuery;
        }

        for (String prefix : GENERIC_SEARCH_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                String query = cleanQuery(normalized.substring(prefix.length()));
                if (StringUtils.hasText(query)) {
                    return search(query, normalized);
                }
            }
        }

        return SearchQuery.unknown();
    }

    private SearchQuery parseQuestionSearch(String text) {
        SearchPeriod period = detectPeriod(text);
        for (String prefix : QUESTION_SEARCH_PREFIXES) {
            if (text.startsWith(prefix)) {
                String query = cleanQuery(text.substring(prefix.length()));
                if (StringUtils.hasText(query)) {
                    return SearchQuery.search(query, detectTypes(query, text), Set.of(), period);
                }
            }
        }
        return SearchQuery.unknown();
    }

    private SearchQuery search(String query, String originalText) {
        return SearchQuery.search(query, detectTypes(query, originalText), Set.of(), detectPeriod(originalText));
    }

    private Set<InboxItemType> detectTypes(String query, String originalText) {
        String text = normalizeSearchText(query + " " + originalText);
        return java.util.Arrays.stream(InboxItemType.values())
                .filter(type -> matchesType(text, type))
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean matchesType(String text, InboxItemType type) {
        return switch (type) {
            case MOVIE -> containsAny(text, "фильм", "фильмы", "кино", "сериал", "сериалы", "документал", "аниме", "посмотреть");
            case BOOK -> containsAny(text, "книга", "книги", "книж", "прочитать", "почитать");
            case ARTICLE -> containsAny(text, "статья", "статьи");
            case IDEA -> containsAny(text, "идея", "идеи", "задумка");
            case PROJECT -> containsAny(text, "проект", "проекты", "pet project", "пет проект");
            case PURCHASE_RESEARCH -> containsAny(text, "купить", "покупка", "покупки", "выбрать", "подобрать", "заказать");
            case FINANCE -> containsAny(text, "финансы", "деньги", "расход", "расходы", "бюджет");
            case HEALTH -> containsAny(text, "здоровье", "здоровью");
            case TASK -> containsAny(text, "задача", "задачи", "сделать", "разобраться", "починить", "исправить");
            case REMINDER -> containsAny(text, "напоминание", "напоминания", "напомнить", "запланировано", "запланирован", "встреча", "созвон");
            case LINK -> containsAny(text, "ссылка", "ссылки", "url");
            case LEARNING -> containsAny(text, "изучить", "learning", "обучение", "доклад", "курс");
            case QUESTION -> containsAny(text, "вопрос", "вопросы");
            case NOTE, OTHER -> false;
        };
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSearchText(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private SearchPeriod detectPeriod(String text) {
        if (text.contains("сегодня") || text.contains("сегодняшн")) {
            return SearchPeriod.TODAY;
        }
        if (text.contains("вчера")) {
            return SearchPeriod.YESTERDAY;
        }
        return SearchPeriod.ALL;
    }

    private boolean isRecent(String text) {
        return text.equals("покажи последние")
                || text.equals("последние")
                || text.equals("последние записи")
                || text.equals("мои записи")
                || text.equals("мои заметки")
                || text.equals("покажи мои записи")
                || text.equals("покажи мои заметки")
                || text.equals("что я сохранял")
                || text.equals("что я сохранил")
                || text.equals("что я добавлял");
    }

    private boolean isToday(String text) {
        return text.equals("сегодня")
                || text.equals("что сегодня")
                || text.equals("что я сохранял сегодня")
                || text.equals("что я сохранил сегодня")
                || text.equals("что я сегодня сохранял")
                || text.equals("что я сегодня сохранил")
                || text.equals("что я добавлял сегодня")
                || text.equals("что я добавил сегодня")
                || text.equals("что я сегодня добавлял")
                || text.equals("что я сегодня добавил")
                || text.equals("что добавил сегодня")
                || text.equals("что сегодня добавил")
                || text.equals("покажи за сегодня")
                || text.equals("записи за сегодня")
                || text.equals("что за сегодня");
    }

    private boolean isYesterday(String text) {
        return text.equals("что я сохранил вчера")
                || text.equals("что я сохранял вчера")
                || text.equals("что я вчера сохранил")
                || text.equals("что я вчера сохранял")
                || text.equals("что сохранил вчера")
                || text.equals("что сохранял вчера")
                || text.equals("что вчера сохранил")
                || text.equals("что вчера сохранял")
                || text.equals("что я добавлял вчера")
                || text.equals("что я вчера добавлял")
                || text.equals("что добавлял вчера")
                || text.equals("что вчера добавлял")
                || text.equals("че я покупал вчера")
                || text.equals("чё я покупал вчера")
                || text.equals("че покупал вчера")
                || text.equals("чё покупал вчера");
    }

    private String cleanQuery(String query) {
        String cleaned = query.trim()
                .replace(" сегодня", "")
                .replace(" вчера", "")
                .replace("сегодняшние ", "")
                .replace("сегодняшний ", "")
                .replace("сегодняшняя ", "")
                .replace("сегодняшнее ", "")
                .replace(" я сохранял", "")
                .replace(" я сохранил", "")
                .replace(" я добавлял", "")
                .replace(" я записывал", "")
                .replace(" я записал", "")
                .replace(" мне ", " ")
                .replace(" у меня ", " ")
                .trim();
        cleaned = removeLeading(cleaned, "я ");
        cleaned = removeLeading(cleaned, "у меня ");
        cleaned = removeLeading(cleaned, "мне ");
        cleaned = removeLeading(cleaned, "к ");
        cleaned = removeLeading(cleaned, "такая ");
        cleaned = removeLeading(cleaned, "такой ");
        cleaned = removeLeading(cleaned, "такое ");
        cleaned = removeLeading(cleaned, "такие ");
        for (String prefix : GENERIC_SEARCH_PREFIXES) {
            if (cleaned.startsWith(prefix)) {
                return cleanQuery(cleaned.substring(prefix.length()));
            }
        }
        return cleaned;
    }

    private String removeLeading(String text, String prefix) {
        return text.startsWith(prefix) ? text.substring(prefix.length()).trim() : text;
    }

    private String normalize(String text) {
        return text.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("[?.!]+$", "");
    }
}
