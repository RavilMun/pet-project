package ru.ravil.petproject.eval;

import java.util.List;
import ru.ravil.petproject.dto.MemoryUnitResponse;

public record MemoryEvalJudgeContext(
        String caseId,
        String category,
        List<String> saveMessages,
        String question,
        List<String> expectedFacts,
        List<String> forbiddenFacts,
        boolean mustNotSayUnknown,
        boolean mustSayUnknown,
        String actualAnswer,
        List<MemoryUnitResponse> sources
) {
}
