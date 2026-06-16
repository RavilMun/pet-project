package ru.ravil.petproject.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.TestcontainersConfiguration;
import ru.ravil.petproject.ai.OpenAiClient;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.service.InboxItemEmbeddingBackfillService;
import ru.ravil.petproject.service.InboxItemService;

@Tag("memory-eval")
@SpringBootTest(properties = {
        "telegram.bot.enabled=false"
})
@Import(TestcontainersConfiguration.class)
class MemoryEvalTest {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryEvalReportWriter reportWriter = new MemoryEvalReportWriter();

    private final JdbcTemplate jdbcTemplate;
    private final InboxItemService inboxItemService;
    private final InboxItemEmbeddingBackfillService embeddingBackfillService;
    private final MemoryEvalQuestionExecutor questionExecutor;
    private final boolean judgeEnabled;
    private final String judgeModel;
    private final String openAiApiKey;
    private final String embeddingModel;
    private final int evalLimit;
    private final String databaseMode;

    @Autowired
    MemoryEvalTest(
            JdbcTemplate jdbcTemplate,
            InboxItemService inboxItemService,
            InboxItemEmbeddingBackfillService embeddingBackfillService,
            MemoryEvalQuestionExecutor questionExecutor,
            @Value("${memory.eval.judge.enabled:false}") boolean judgeEnabled,
            @Value("${memory.eval.judge.model:gpt-4.1-mini}") String judgeModel,
            @Value("${openai.api-key:}") String openAiApiKey,
            @Value("${openai.embedding-model:text-embedding-3-small}") String embeddingModel,
            @Value("${memory.eval.limit:0}") int evalLimit,
            @Value("${memory.eval.database.mode:isolated}") String databaseMode
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.inboxItemService = inboxItemService;
        this.embeddingBackfillService = embeddingBackfillService;
        this.questionExecutor = questionExecutor;
        this.judgeEnabled = judgeEnabled;
        this.judgeModel = judgeModel;
        this.openAiApiKey = openAiApiKey;
        this.embeddingModel = embeddingModel;
        this.evalLimit = evalLimit;
        this.databaseMode = normalizeDatabaseMode(databaseMode);
    }

    @Test
    void runMemoryEvaluation() throws Exception {
        List<MemoryEvalCase> cases = limited(loadCases());
        MemoryEvalJudge judge = judge();
        List<MemoryEvalQuestionResult> results = new ArrayList<>();

        if ("accumulated".equals(databaseMode)) {
            cleanDatabase();
        }

        for (MemoryEvalCase evalCase : cases) {
            if ("isolated".equals(databaseMode)) {
                cleanDatabase();
            }
            List<String> savedMessages = evalCase.getSave();
            try {
                saveMessages(savedMessages);
                embeddingBackfillService.backfillMissingEmbeddings(100);
            } catch (Exception exception) {
                for (MemoryEvalQuestion question : evalCase.getQuestions()) {
                    results.add(errorResult(evalCase, question, exception));
                }
                continue;
            }

            for (MemoryEvalQuestion question : evalCase.getQuestions()) {
                results.add(evaluateQuestion(evalCase, question, judge));
            }
        }

        reportWriter.write(report(cases, results));
    }

    private List<MemoryEvalCase> limited(List<MemoryEvalCase> cases) {
        if (evalLimit <= 0 || evalLimit >= cases.size()) {
            return cases;
        }
        return cases.subList(0, evalLimit);
    }

    private List<MemoryEvalCase> loadCases() throws Exception {
        URI directoryUri = java.util.Objects.requireNonNull(
                getClass().getResource("/memory-eval"),
                "memory-eval resources directory not found"
        ).toURI();
        List<MemoryEvalCase> cases = new ArrayList<>();
        try (java.util.stream.Stream<Path> paths = Files.list(Path.of(directoryUri))) {
            List<Path> yamlFiles = paths
                    .filter(path -> Files.isRegularFile(path)
                            && (path.getFileName().toString().endsWith(".yaml")
                            || path.getFileName().toString().endsWith(".yml")))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            if (yamlFiles.isEmpty()) {
                throw new IllegalStateException("No memory eval yaml files found in " + directoryUri);
            }
            for (Path yamlFile : yamlFiles) {
                try (InputStream input = Files.newInputStream(yamlFile)) {
                    cases.addAll(yamlMapper.readValue(input, new TypeReference<List<MemoryEvalCase>>() {
                    }));
                }
            }
        }
        return cases;
    }

    private String normalizeDatabaseMode(String value) {
        String normalized = value == null ? "isolated" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("isolated") && !normalized.equals("accumulated")) {
            throw new IllegalArgumentException("memory.eval.database.mode must be isolated or accumulated");
        }
        return normalized;
    }

    private MemoryEvalJudge judge() {
        if (!judgeEnabled) {
            return new DisabledMemoryEvalJudge();
        }
        if (!StringUtils.hasText(openAiApiKey)) {
            throw new IllegalStateException("OPENAI_API_KEY/openai.api-key is required when memory.eval.judge.enabled=true");
        }
        return new OpenAiMemoryEvalJudge(new OpenAiClient(openAiApiKey, judgeModel, embeddingModel), objectMapper);
    }

    private void cleanDatabase() {
        jdbcTemplate.execute("truncate table inbox_items cascade");
    }

    private void saveMessages(List<String> messages) {
        for (int index = 0; index < messages.size(); index++) {
            inboxItemService.create(new CreateInboxItemRequest(
                    messages.get(index),
                    null,
                    null,
                    null,
                    InboxItemSource.TELEGRAM,
                    null,
                    null,
                    10_403_539_120L,
                    index + 1L,
                    java.util.Set.of()
            ));
        }
    }

    private MemoryEvalQuestionResult evaluateQuestion(
            MemoryEvalCase evalCase,
            MemoryEvalQuestion question,
            MemoryEvalJudge judge
    ) {
        try {
            MemoryEvalQuestionExecutor.Execution execution = questionExecutor.execute(question.getText());
            MemoryEvalJudgeContext context = new MemoryEvalJudgeContext(
                    evalCase.getId(),
                    evalCase.getCategory(),
                    evalCase.getSave(),
                    question.getText(),
                    question.getExpectedFacts(),
                    question.getForbiddenFacts(),
                    question.isMustNotSayUnknown(),
                    question.isMustSayUnknown(),
                    execution.actualAnswer(),
                    execution.sources()
            );
            MemoryEvalJudgeResult judgeResult = judge.judge(context);
            return new MemoryEvalQuestionResult(
                    evalCase.getId(),
                    evalCase.getCategory(),
                    evalCase.getSave(),
                    question.getText(),
                    question.getExpectedFacts(),
                    question.getForbiddenFacts(),
                    question.isMustNotSayUnknown(),
                    question.isMustSayUnknown(),
                    execution.actualAnswer(),
                    execution.intentQuery(),
                    execution.retrievalDiagnosis(),
                    execution.answerDiagnosis(),
                    execution.sources(),
                    judgeResult,
                    Map.of()
            );
        } catch (Exception exception) {
            return errorResult(evalCase, question, exception);
        }
    }

    private MemoryEvalQuestionResult errorResult(MemoryEvalCase evalCase, MemoryEvalQuestion question, Exception exception) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", exception.getClass().getName());
        error.put("message", exception.getMessage());
        return new MemoryEvalQuestionResult(
                evalCase.getId(),
                evalCase.getCategory(),
                evalCase.getSave(),
                question.getText(),
                question.getExpectedFacts(),
                question.getForbiddenFacts(),
                question.isMustNotSayUnknown(),
                question.isMustSayUnknown(),
                "",
                null,
                "execution_error",
                "answer_error",
                List.of(),
                new MemoryEvalJudgeResult(MemoryEvalVerdict.FAIL, 0.0d, "Execution failed", List.of(), List.of(), List.of()),
                error
        );
    }

    private MemoryEvalRunReport report(List<MemoryEvalCase> cases, List<MemoryEvalQuestionResult> results) {
        EnumMap<MemoryEvalVerdict, Long> counts = new EnumMap<>(MemoryEvalVerdict.class);
        for (MemoryEvalVerdict verdict : MemoryEvalVerdict.values()) {
            counts.put(verdict, 0L);
        }
        results.forEach(result -> counts.compute(result.judge().verdict(), (key, count) -> count == null ? 1L : count + 1L));

        List<MemoryEvalQuestionResult> judged = results.stream()
                .filter(result -> result.judge().verdict() != MemoryEvalVerdict.NOT_JUDGED)
                .toList();
        double accuracy = judged.isEmpty()
                ? 0.0d
                : judged.stream().mapToDouble(result -> result.judge().score()).average().orElse(0.0d);

        return new MemoryEvalRunReport(
                OffsetDateTime.now(),
                databaseMode,
                cases.size(),
                results.size(),
                counts,
                accuracy,
                results
        );
    }
}
