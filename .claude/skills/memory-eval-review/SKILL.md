---
name: memory-eval-review
description: Review the latest memoryEval report (build/reports/memory-eval/report.json), group FAIL questions by root cause, find the code responsible, and propose a minimal fix. Never edits code without explicit user confirmation. Use when the user asks to analyze memory-eval results, investigate FAIL verdicts, or "why did the eval fail".
---

# memory-eval-review

## 0. Предпосылки
- Источник правды: `build/reports/memory-eval/report.json` (то же, что `report.md`, но JSON удобнее группировать).
  Путь фиксированный — отчёт каждый раз перезаписывается, "последний" = единственный текущий файл.
- Если файла нет — попросить запустить `.\gradlew.bat memoryEval` (и сказать, что прогон дёргает OpenAI,
  если `memoryEvalJudgeEnabled=true` — не запускать самостоятельно без согласия пользователя).
- Проверить mtime отчёта: если он явно старше последних изменений в `src/main/java`, предупредить,
  что отчёт может не отражать текущий код.

## 1. Прочитать отчёт
Читать `report.json` целиком (Read). Из каждого результата с `judge.verdict in {FAIL, PARTIAL}` достаточно полей:
`caseId, category, question, intentQuery, retrievalDiagnosis, answerDiagnosis,
mustNotSayUnknown, mustSayUnknown, judge.reason, judge.missingFacts, judge.wrongFacts,
judge.hallucinations, error`.
PARTIAL включать в группировку, но в отдельный раздел — задача про FAIL, PARTIAL это контекст.

## 2. Сгруппировать FAIL по причине
Сначала группировать по **сигнатуре**, не по тексту `reason` (он free-text от judge и для каждого кейса разный):

- `error != null` → подгруппа по `error.type` (исключение, не логическая ошибка).
- `retrievalDiagnosis == "retrieval_empty"` → "поиск не нашёл ничего".
- `retrievalDiagnosis` непустой, `answerDiagnosis == "answer_not_generated"`, `mustNotSayUnknown == true`
  или `expectedFacts` непуст → "нашли, но не ответили".
- `judge.hallucinations` непуст → "придумал факты".
- `judge.wrongFacts` непуст → "перепутал факты/источник".
- `judge.missingFacts` непуст и verdict FAIL/PARTIAL → "ответ неполный".
- `mustSayUnknown == true` и `answerDiagnosis == "answer_generated"` → "ответил, хотя должен был сказать 'не знаю'".
- `intentQuery` пуст/не похож на вопрос при `retrieval_empty` → возможно проблема на уровне intent-детекции, а не поиска.

Внутри каждой сигнатуры — подкластеризовать по `category` и здравому смыслу по тексту `judge.reason`
(не выдумывать новые категории сверх вышеперечисленных, просто схожие reason ставить рядом).

Для каждой группы вывести: сколько кейсов, список `caseId` + `question` (коротко), общий `reason`-паттерн.

## 3. Найти код, который вызывает каждую группу
Использовать `reference.md` как стартовую гипотезу, затем подтвердить чтением реального кода
(Grep/Read) — таблица в reference.md описывает архитектуру на момент написания skill,
код может уйти вперёд.

Для retrieval-группы — обязательно прогнать в голове реальный запрос (`intentQuery` из отчёта)
через логику `InboxItemSearchService` (cutoffs, anchor-match, vector toggle) и проверить,
не отрезало ли кандидата раньше времени.

Для answer-группы — смотреть `MemoryAnswerService.MIN_CONFIDENCE`, `shouldTryAnswer` (префиксы вопроса),
`SYSTEM_PROMPT`.

Не ограничиваться одним файлом-виновником — если паттерн встречается в нескольких группах,
указать все вовлечённые места.

## 4. Предложить минимальный набор изменений
Для каждой подтверждённой причины — ОДНО точечное предложение:
- файл:строка (или константа/метод),
- что изменить (текст, не diff-применение),
- почему это закроет именно эти FAIL-кейсы (сослаться на caseId),
- риск регрессии для других кейсов в отчёте (если виден).

Сортировать предложения по количеству закрываемых FAIL (сначала самое окупаемое).
Не предлагать рефакторинг/чистку сверх того, что нужно для конкретных FAIL.
Если причина непонятна (нужны живые логи/доп. данные) — явно сказать "недостаточно данных", а не гадать.

## 5. Подтверждение перед правками
Показать список предложений и СТОП. Спросить пользователя explicitly (AskUserQuestion или прямым
вопросом), какие из предложений применять — "все", "выбранные", "ни одного".
Только после явного "да"/выбора — вносить изменения Edit-ом, точно в рамках того, что было показано.
Никогда не запускать `gradlew memoryEval` или `liveTest` самостоятельно для проверки фикса —
это стоит денег (реальные вызовы OpenAI); предложить пользователю запустить и прислать новый отчёт.
