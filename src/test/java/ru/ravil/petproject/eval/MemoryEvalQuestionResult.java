package ru.ravil.petproject.eval;

import java.util.List;
import java.util.Map;
import ru.ravil.petproject.dto.MemoryUnitResponse;

public record MemoryEvalQuestionResult(
        String caseId,
        String category,
        List<String> saveMessages,
        String question,
        List<String> expectedFacts,
        List<String> forbiddenFacts,
        boolean mustNotSayUnknown,
        boolean mustSayUnknown,
        String actualAnswer,
        String intentQuery,
        String retrievalDiagnosis,
        String answerDiagnosis,
        List<MemoryUnitResponse> sources,
        MemoryEvalJudgeResult judge,
        Map<String, Object> error
) {
}
