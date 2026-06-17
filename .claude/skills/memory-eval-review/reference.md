# memory-eval failure → code map (отправная точка, не финальная истина)

| Сигнал в отчёте                                                              | Вероятный виновник                                                                 |
|-------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| retrievalDiagnosis = retrieval_empty                                          | InboxItemSearchService (cutoffs MIN_RELEVANCE_SCORE/lexicalRelevanceCutoff/vectorRelevanceCutoff, hasRequiredAnchorMatch) или то, что MemoryUnit для этого факта вообще не был создан при сохранении (AiMemoryUnitExtractionService) |
| answerDiagnosis = answer_not_generated, retrieval непустой, expectedFacts есть | MemoryAnswerService: MIN_CONFIDENCE=0.55, shouldTryAnswer (префиксы вопроса/```?```), сам SYSTEM_PROMPT слишком консервативен |
| judge.hallucinations непуст                                                   | MemoryAnswerService SYSTEM_PROMPT (модель додумывает) либо неверные данные уже в MemoryUnit/MemorySlot (AiMemoryUnitExtractionService на этапе сохранения) |
| judge.wrongFacts непуст                                                       | InboxItemSearchService.score()/anchor-match притянул не тот MemoryUnit, либо факт неверно извлечён при сохранении |
| judge.missingFacts непуст                                                     | Либо факт не извлечён в отдельный MemoryUnit при сохранении, либо отрезан cutoff'ом при поиске |
| mustSayUnknown=true, но answerDiagnosis=answer_generated                      | Слишком мягкий anchor-match/cutoff в InboxItemSearchService (притянул нерелевантный source), либо SYSTEM_PROMPT в MemoryAnswerService недостаточно строг |
| intentQuery не похож на question / пуст                                       | AiTelegramIntentDetector / RuleBasedTelegramIntentDetector / NaturalLanguageSearchQueryParser — неверная маршрутизация intent до того, как дошло до поиска |
| error.type присутствует                                                       | Исключение в OpenAiClient (timeout/JSON) или парсинге ответа в AiClassificationService/AiMemoryUnitExtractionService/MemoryAnswerService — это баг исполнения, не качества ранжирования |

Каждую строку проверять чтением актуального кода — таблица фиксирует архитектуру на момент
создания skill (см. InboxItemSearchService, MemoryAnswerService, AiMemoryUnitExtractionService).
