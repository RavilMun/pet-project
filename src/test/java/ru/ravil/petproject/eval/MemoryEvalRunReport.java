package ru.ravil.petproject.eval;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record MemoryEvalRunReport(
        OffsetDateTime createdAt,
        String databaseMode,
        int totalCases,
        int totalQuestions,
        Map<MemoryEvalVerdict, Long> verdictCounts,
        double accuracy,
        List<MemoryEvalQuestionResult> results
) {
}
