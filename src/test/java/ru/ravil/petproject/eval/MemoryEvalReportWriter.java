package ru.ravil.petproject.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import ru.ravil.petproject.dto.MemoryUnitResponse;

public class MemoryEvalReportWriter {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public void write(MemoryEvalRunReport report) {
        Path dir = Path.of("build", "reports", "memory-eval");
        try {
            Files.createDirectories(dir);
            objectMapper.writeValue(dir.resolve("report.json").toFile(), report);
            Files.writeString(dir.resolve("report.md"), markdown(report));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write memory eval report", exception);
        }
    }

    private String markdown(MemoryEvalRunReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Memory Eval Report\n\n");
        builder.append("- Created at: ").append(report.createdAt()).append("\n");
        builder.append("- Database mode: ").append(report.databaseMode()).append("\n");
        builder.append("- Cases: ").append(report.totalCases()).append("\n");
        builder.append("- Questions: ").append(report.totalQuestions()).append("\n");
        builder.append("- Accuracy: ").append(String.format(java.util.Locale.ROOT, "%.3f", report.accuracy())).append("\n");
        builder.append("- Verdicts: ").append(report.verdictCounts()).append("\n\n");

        appendSection(builder, "FAIL", filter(report, MemoryEvalVerdict.FAIL));
        appendSection(builder, "PARTIAL", filter(report, MemoryEvalVerdict.PARTIAL));
        appendSection(builder, "NOT_JUDGED", filter(report, MemoryEvalVerdict.NOT_JUDGED));
        return builder.toString();
    }

    private List<MemoryEvalQuestionResult> filter(MemoryEvalRunReport report, MemoryEvalVerdict verdict) {
        return report.results().stream()
                .filter(result -> result.judge().verdict() == verdict)
                .toList();
    }

    private void appendSection(StringBuilder builder, String title, List<MemoryEvalQuestionResult> results) {
        builder.append("## ").append(title).append(" (").append(results.size()).append(")\n\n");
        for (MemoryEvalQuestionResult result : results) {
            builder.append("### ").append(result.caseId()).append(" / ").append(result.category()).append("\n\n");
            builder.append("Question: ").append(result.question()).append("\n\n");
            builder.append("Expected facts:\n").append(lines(result.expectedFacts())).append("\n");
            builder.append("Forbidden facts:\n").append(lines(result.forbiddenFacts())).append("\n");
            builder.append("Actual answer:\n\n```text\n").append(nullToEmpty(result.actualAnswer())).append("\n```\n\n");
            builder.append("Verdict: `").append(result.judge().verdict()).append("`, score `")
                    .append(result.judge().score()).append("`\n\n");
            builder.append("Reason: ").append(result.judge().reason()).append("\n\n");
            builder.append("Retrieval diagnosis: ").append(result.retrievalDiagnosis()).append("\n\n");
            builder.append("Answer diagnosis: ").append(result.answerDiagnosis()).append("\n\n");
            builder.append("Sources:\n").append(sourceLines(result.sources())).append("\n");
        }
    }

    private String lines(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "- none\n";
        }
        return values.stream().map(value -> "- " + value).collect(Collectors.joining("\n")) + "\n";
    }

    private String sourceLines(List<MemoryUnitResponse> sources) {
        if (sources == null || sources.isEmpty()) {
            return "- none\n";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < sources.size(); index++) {
            MemoryUnitResponse source = sources.get(index);
            builder.append("- ").append(index + 1).append(". ")
                    .append(source.title())
                    .append(" [").append(source.type()).append("]")
                    .append(" tags=").append(source.tags())
                    .append("\n");
        }
        return builder.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
