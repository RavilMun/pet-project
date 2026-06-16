package ru.ravil.petproject.eval;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.service.InboxItemSearchService;
import ru.ravil.petproject.service.MemoryAnswer;
import ru.ravil.petproject.service.MemoryAnswerService;
import ru.ravil.petproject.service.NaturalLanguageSearchQueryParser;
import ru.ravil.petproject.service.SearchPeriod;
import ru.ravil.petproject.service.SearchQuery;
import ru.ravil.petproject.service.SearchQueryType;
import ru.ravil.petproject.telegram.AiTelegramIntentDetector;
import ru.ravil.petproject.telegram.RuleBasedTelegramIntentDetector;
import ru.ravil.petproject.telegram.TelegramIntent;
import ru.ravil.petproject.telegram.TelegramIntentType;
import ru.ravil.petproject.telegram.TelegramSearchResponseFormatter;

@Component
public class MemoryEvalQuestionExecutor {

    private final AiTelegramIntentDetector aiTelegramIntentDetector;
    private final RuleBasedTelegramIntentDetector ruleBasedTelegramIntentDetector;
    private final NaturalLanguageSearchQueryParser searchQueryParser;
    private final InboxItemSearchService inboxItemSearchService;
    private final MemoryAnswerService memoryAnswerService;
    private final TelegramSearchResponseFormatter searchResponseFormatter;

    public MemoryEvalQuestionExecutor(
            AiTelegramIntentDetector aiTelegramIntentDetector,
            RuleBasedTelegramIntentDetector ruleBasedTelegramIntentDetector,
            NaturalLanguageSearchQueryParser searchQueryParser,
            InboxItemSearchService inboxItemSearchService,
            MemoryAnswerService memoryAnswerService,
            TelegramSearchResponseFormatter searchResponseFormatter
    ) {
        this.aiTelegramIntentDetector = aiTelegramIntentDetector;
        this.ruleBasedTelegramIntentDetector = ruleBasedTelegramIntentDetector;
        this.searchQueryParser = searchQueryParser;
        this.inboxItemSearchService = inboxItemSearchService;
        this.memoryAnswerService = memoryAnswerService;
        this.searchResponseFormatter = searchResponseFormatter;
    }

    public Execution execute(String question) {
        TelegramIntent intent = detectIntent(question);
        if (intent.type() == TelegramIntentType.CAPTURE || intent.type() == TelegramIntentType.UNKNOWN) {
            return new Execution(
                    "INTENT_" + intent.type(),
                    null,
                    List.of(),
                    "intent=" + intent.type(),
                    "answer_not_generated"
            );
        }

        List<MemoryUnitResponse> items = search(intent, question);
        Optional<MemoryAnswer> answer = memoryAnswerService.answer(question, items);
        String actualAnswer = searchResponseFormatter.format(
                StringUtils.hasText(intent.query()) ? intent.query() : question,
                items,
                answer
        );
        String retrievalDiagnosis = items.isEmpty() ? "retrieval_empty" : "retrieval_returned_" + items.size();
        String answerDiagnosis = answer.isPresent() ? "answer_generated" : "answer_not_generated";
        return new Execution(actualAnswer, intent.query(), items, retrievalDiagnosis, answerDiagnosis);
    }

    private TelegramIntent detectIntent(String text) {
        if (aiTelegramIntentDetector.shouldUseAiForIntent(text)) {
            TelegramIntent aiIntent = aiTelegramIntentDetector.detect(text);
            if (!aiIntent.isUnknown()) {
                return aiIntent;
            }
        }

        TelegramIntent ruleIntent = ruleBasedTelegramIntentDetector.detect(text);
        if (!ruleIntent.isUnknown()) {
            return ruleIntent;
        }

        TelegramIntent aiIntent = aiTelegramIntentDetector.detect(text);
        return aiIntent.isUnknown() ? TelegramIntent.unknown() : aiIntent;
    }

    private List<MemoryUnitResponse> search(TelegramIntent intent, String originalQuestion) {
        if (intent.type() == TelegramIntentType.RECENT) {
            return inboxItemSearchService.recent(10);
        }
        if (intent.type() == TelegramIntentType.TODAY) {
            return inboxItemSearchService.today(10);
        }
        if (intent.type() == TelegramIntentType.SEARCH) {
            return inboxItemSearchService.search(
                    intent.query(),
                    intent.itemTypes(),
                    intent.tags(),
                    intent.period(),
                    10
            );
        }

        SearchQuery parsed = searchQueryParser.parse(originalQuestion);
        if (parsed.type() == SearchQueryType.RECENT) {
            return inboxItemSearchService.recent(10);
        }
        if (parsed.type() == SearchQueryType.TODAY) {
            return inboxItemSearchService.today(10);
        }
        if (parsed.type() == SearchQueryType.SEARCH) {
            return inboxItemSearchService.search(
                    parsed.text(),
                    parsed.itemTypes(),
                    parsed.tags(),
                    parsed.period(),
                    10
            );
        }
        return inboxItemSearchService.search(originalQuestion, Set.<InboxItemType>of(), Set.of(), SearchPeriod.ALL, 10);
    }

    public record Execution(
            String actualAnswer,
            String intentQuery,
            List<MemoryUnitResponse> sources,
            String retrievalDiagnosis,
            String answerDiagnosis
    ) {
    }
}
