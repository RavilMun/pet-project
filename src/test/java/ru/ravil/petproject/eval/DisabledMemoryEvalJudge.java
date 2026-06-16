package ru.ravil.petproject.eval;

public class DisabledMemoryEvalJudge implements MemoryEvalJudge {

    @Override
    public MemoryEvalJudgeResult judge(MemoryEvalJudgeContext context) {
        return MemoryEvalJudgeResult.notJudged("LLM judge disabled by memory.eval.judge.enabled=false");
    }
}
