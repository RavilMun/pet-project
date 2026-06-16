package ru.ravil.petproject.eval;

import java.util.List;

public record MemoryEvalJudgeResult(
        MemoryEvalVerdict verdict,
        double score,
        String reason,
        List<String> missingFacts,
        List<String> wrongFacts,
        List<String> hallucinations
) {

    public static MemoryEvalJudgeResult notJudged(String reason) {
        return new MemoryEvalJudgeResult(
                MemoryEvalVerdict.NOT_JUDGED,
                0.0d,
                reason,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
