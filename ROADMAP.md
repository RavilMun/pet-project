# ROADMAP

Развитие pet-project (личный «второй мозг»: Telegram + REST, ingest → classify → extract → embed → hybrid search → LLM answer).

**Принцип сортировки:** сначала защита уже захваченных данных и дыры в основном цикле «захватил → нашёл → ответил», затем качество, затем масштаб. Любое изменение ранжирования валидируется `memoryEval`.

Идём строго по порядку фаз сверху вниз. Объём: S/M/L.

---

## ✅ Сделано (база перед фазами)

- Версионирование эмбеддингов: `embedding_model`/`embedded_at` пишутся, backfill переэмбеддит устаревшие (`findIdsMissingOrStaleEmbedding`).
- Reranker над top-N (`MemoryRerankService`), гейт `openai.rerank-enabled` (default `false`).
- Temporal-поиск: date-range матчит `created_at` OR `occurred_at` (только при явном периоде, без no-date fallback).
- N+1 в ранкере убран через `@BatchSize`.

---

## Фаза 0 — Закрыть начатое (S)

- [x] **0.1** Провалидировано через `memoryEval` (accumulated, judge on): baseline 0.8647 → temporal 0.9000 → +rerank 0.9082. Вывод: temporal оставляем ON; reranker оставляем OFF по умолчанию (выигрыш в пределах шума судьи — нужен чистый A/B в `isolated`). Регрессии кучкуются в синтезе ответа по неоднозначному контексту → приоритет Фазы 3. Артефакты: pre-temporal baseline затёрся (копировать ДО прогона); accumulated даёт кросс-кейсовый шум — для per-feature валидации гонять `isolated`.

## Фаза 1 — Надёжность захвата (M) — *фундамент, потеря данных дороже фич*

- [x] **1.1** Degraded save — **сделано** (компилируется, весь тест-набор зелёный). Сырьё сохраняется сразу (`PENDING_AI` + fallback `MemoryUnit`), ИИ в отдельной транзакции (`process`), при сбое → `FAILED_AI` без потери; `reprocess`/`reprocessPending` + REST `POST /{id}/reprocess`. Отложено в 1.2: Telegram-формулировка «сохранил, разберу позже» + удаление мёртвого catch в `TelegramBotPollingService`, батч-шедулер.

  **Принятый дизайн (1.1):**
  - **Статусы** (`InboxItemStatus`): + `PENDING_AI` (сырьё сохранено, ИИ не отработал), + `FAILED_AI` (ИИ падал, retryable). `PROCESSED`/`NEW`/`ARCHIVED` без изменений.
  - **Схема** (changeset `010`, только addColumn): `processing_attempts INT NOT NULL DEFAULT 0`, `last_processing_error TEXT`, `next_attempt_at TIMESTAMPTZ` (задел под backoff в 1.2). Индекс `(status, next_attempt_at)`.
  - **Две независимые транзакции** (иначе шаг обработки не увидит несохранённое сырьё): `create()` НЕ транзакционный — `repository.save(rawItem)` коммитит сырьё (+ минимальный fallback `MemoryUnit` типа NOTE, чтобы запись сразу была в лексическом FTS), затем `process(id)` (отдельная `@Transactional`, вызов через self-proxy) гоняет ИИ. Self-invocation решаем `ObjectProvider<InboxItemService>`.
  - **`process(id, request?)`**: classify→extract→embed; **успех** → fallback-юнит заменяется реальными, `status=PROCESSED`; **ошибка** (в т.ч. `AiProcessingUnavailableException`) → `catch`: `status=FAILED_AI`, `attempts++`, `last_processing_error`, **fallback-юнит остаётся** (запись ищется), исключение НЕ пробрасывается.
  - **Reprocess:** `reprocess(id)` = `process(id, null)`; `reprocessPending(limit)` по `findByStatusInOrderByCreatedAtAsc([PENDING_AI, FAILED_AI])` — seam для шедулера 1.2. REST `POST /api/inbox-items/{id}/reprocess`.
  - **Совместимость:** меняется инвариант (`create` больше не кидает `AiProcessingUnavailableException` наружу) — переписать соответствующие тесты и шаг 2 пайплайна в CLAUDE.md. Telegram-формулировку «сохранил, разберу позже» и батч-reprocess можно оставить заготовками под 1.2.
- [x] **1.2** Async-обработка ИИ-шагов (зависит от 1.1) — **сделано**:
  - [x] **1.2a** `@EnableAsync` + `InboxItemService.captureAsync` (сохраняет сырьё, возвращает `PENDING_AI`-снимок сразу, обработка в фоне через `@Async scheduleProcessing`). Telegram-капча → мгновенный «Сохранил, разберу позже», мёртвый catch убран. REST `create` оставлен синхронным.
  - [x] **1.2b** `InboxItemRetryScheduler` (`@Scheduled`, загейчен `inbox.processing.retry.enabled`, off в eval): авто-ретрай `FAILED_AI` с экспоненциальным backoff по `next_attempt_at`, cap `MAX_PROCESSING_ATTEMPTS=6` (дальше — только ручной `reprocess`). Весь тест-набор зелёный + юнит-тесты на backoff/due.
- [x] **1.3** Устойчивость OpenAI — **сделано**: `OpenAiClient` оборачивает chat/embeddings в retry (до 3 попыток) с экспоненциальным backoff + jitter на 429/5xx и I/O-ошибки, уважает `Retry-After`, логирует причину; не ретраит 4xx (кроме 429). Таймауты (connect 10s / read 60s) сохранены. Юнит-тесты на `isRetryableStatus`/`backoffMillis`.

## Фаза 2 — Замкнуть петлю напоминаний (M) — *данные уже есть, фича не работает*

- [x] **2.1** Шедулер дедлайнов — **сделано**. Обнаружено: `dueAt`/`occurredAt` раньше **никогда не заполнялись** (и temporal #4 по факту матчил только `created_at`). Поэтому:
  - Extraction теперь возвращает `occurredAt`/`dueAt` абсолютным ISO (в промпт передаётся текущая дата для разрешения «завтра/в субботу»), парсится в `OffsetDateTime`, кладётся в `MemoryUnit` — **попутно по-настоящему активирован temporal-поиск #4**.
  - Миграция `011`: `memory_units.reminded_at` + индекс `due_at`.
  - `MemoryReminderService.dueReminders/markReminded` + `TelegramReminderScheduler` (`@Scheduled`, загейчен `telegram.bot.enabled` → в тестах не активен). Доставка → отметка `reminded_at` только после успешной отправки.
  - ⚠️ Нужен прогон `memoryEval`, чтобы подтвердить, что изменение extraction не просадило качество (и проверить, помог ли реальный temporal).
- [x] **2.2** Управление задачами из бота — **сделано**: `/tasks` (нумерованный список открытых TASK/REMINDER, состояние последнего списка — per-chat), `/done <n>`, `/snooze <n> <30m|2h|1d>` (по номеру из последнего `/tasks` чата; snooze сбрасывает `reminded_at`). Миграция `012` (`completed_at`), `MemoryTaskService`, `findDueReminders` исключает выполненные. Тесты на сервис/команды.

**Eval-валидация extraction-изменения (2.1):** accuracy 0.9000 → 0.8988 (в пределах шума судьи). +4 реальных temporal-улучшения («вчера»-запросы заработали); −2 — шум accumulated-режима; −3 `long_diary_*` оказались **execution_error от латентного бага** `value too long for varchar(255)` в `memory_units.title` — **исправлено** обрезкой title в `@PrePersist`/`@PreUpdate` (`MemoryUnit` + `InboxItem`). Вывод: extraction-изменение нетто-позитивно по качеству и обязательно для Фазы 2. Чистую temporal-валидацию по-прежнему лучше гонять в `isolated`.

## Фаза 3 — Качество поиска и ответов (M, итеративно, мерить eval'ом)

- [x] **3.1** Тюнинг-дайлы ранкера вынесены в `SearchRankingProperties` (`search.ranking.*`): vector bonus/penalty, min-relevance/weak-lexical cutoffs, rerank-window, lexical-cutoff ratios. Дефолты = прежним константам (тест-набор зелёный, поведение не изменилось) → теперь можно свипать eval'ом без перекомпиляции. Тонкие per-field веса `score()` пока в коде.
**Решение (после isolated-baseline 0.9765):** качество поиска/ответов — «достаточно хорошо». Перестаём гоняться за единичными eval-кейсами: правка одного кейса (LLM-синтез/семантика) дорога по времени и токенам при ROI ±1/85 и риске уронить часть из 83 PASS. Приоритет смещаем на структурные фичи (Фаза 4) и инфру дешёвой валидации (Фаза 5.1). Сделанное по качеству оставляем; нижеперечисленное — **запарковано**.

- [x] **3.1** Тюнинг-дайлы ранкера в `SearchRankingProperties` (`search.ranking.*`) — сделано.
- [x] **3.4 Темпоральная точность** — **сделано и валидировано.** Предикат периода предпочитает `occurred_at`, `created_at` — только при `occurred_at IS NULL`, во всех 4 запросах. Тест `periodFilterPrefersOccurredAtOverCreatedAt`. **post-3.4 isolated eval = accuracy 1.000 (85/85)** — регрессии нет, оба прежних фейла ушли (отчёт `report-post-3.4-isolated.json`).
- [⏸] **3.2 (паркинг)** Свип reranker'а (окно/порог/модель). Низкий ROI: фейлы baseline реранкером надёжно не лечатся; каждый прогон платный. Инфра готова (`openai.rerank-enabled`, `search.ranking.rerank-window`).
- [⏸] **3.3 (паркинг)** Морфология в `score()` — код есть (`RussianStemmer` + тест), gated-off (`search.ranking.stemming-enabled=false`). Спящая инфра; baseline показал, что токен-матчинг не узкое место → отдельный прогон не жжём.
- [x] **3.5 — закрыто фиксом 3.4.** Recall-разрыв «обед↔обедал» был артефактом заглушавшего лексического хита (вчерашний Tokyo); после 3.4 `long_diary_002` проходит, вектор поднимает Birch. Отдельный retrieval-фикс не нужен.
- [⏸] **3.6 (паркинг)** Synthesis-disambiguation (`typo_004`: «где брал» ≠ «смотрел»). Нет дешёвого детерминированного фикса (нет отдельного `PURCHASE`-типа, «брал» лексически не матчит «купил/заказал»); промпт-правка бьёт по всем вопросам и валидируется только шумным LLM-eval. Принципиальный путь — развод `PURCHASE`/`PURCHASE_RESEARCH` на классификации (см. 4.x), не сейчас.

## Фаза 4 — Жизненный цикл памяти (M)

- [x] **4.1** Правка/удаление/«забыть»: `/forget`, `/edit` — **сделано** (компилируется, весь тест-набор зелёный). Soft-delete `forgotten_at`, фильтр во всех retrieval-запросах, `MemoryEditService` (forget/recall/edit+re-embed), `MemoryUnitController` (DELETE/recall/PATCH), бот `/forget <n>`/`/edit <n>` по последнему показанному списку. Тесты: сервис, repo-интеграция (исключение+recall), бот-команды. **Дизайн (MVP):**
  - **Soft-delete, не hard.** «Забыть» = выставить `memory_units.forgotten_at` (TIMESTAMPTZ NULL). Причины: второй мозг → обратимость и аудит; всё равно нужен механизм исключения из поиска; реальное удаление необратимо. Жёсткую чистку забытых (purge-джоба) — отдельно/позже.
  - **Гранулярность — `MemoryUnit`** (один факт, т.к. поиск над юнитами). «Забыть весь InboxItem» = забыть все его юниты — добавить позже; MVP оперирует юнитом.
  - **Схема (миграция `014`):** `memory_units.forgotten_at TIMESTAMPTZ NULL` + partial-индекс `where forgotten_at is null` (поиск только по активным). Edit схему не трогает (переиспользует title/summary/sourceQuote + refresh `search_text` + re-embed).
  - **Repository:** добавить `and unit.forgotten_at is null` во **все** retrieval-запросы: `searchAdvanced(/Between)`, `searchNearestByEmbedding(/Between)`, `findBySourceCreatedAtBetween`, `findAllBySourceCreatedAtDesc`, `findOpenTasks`, `findDueReminders` (забытое не должно всплывать нигде, включая задачи/напоминания). + `markForgotten(id, ts)` / `unforget(id)`.
  - **Сервис** (`MemoryEditService`): `forget(unitId)` → `forgottenAt=now`; `recall(unitId)` → `forgottenAt=null` (undo); `edit(unitId, text)` → обновить summary/sourceQuote (+ retitle если коротко) → `refreshSearchText` → re-embed через `updateMemoryUnitEmbeddingsIfAvailable`. **Без** повторной полной extraction (MVP).
  - **Entity:** `MemoryUnit.forgottenAt` + getter/setter.
  - **Telegram:** per-chat `lastListedMemories: Map<Long, List<MemoryUnitResponse>>`, заполняется при рендере нумерованного списка SEARCH/RECENT/TODAY. `/forget <n>` → резолв n-го из последнего списка чата → soft-forget → «Забыл: <title>». `/edit <n> <текст>` → резолв → edit + re-embed → ack. (`/recall` последнего забытого — nice-to-have, позже.)
  - **REST:** новый `MemoryUnitController`: `DELETE /api/memory-units/{id}` (soft-forget), `POST /api/memory-units/{id}/recall`, `PATCH /api/memory-units/{id}` (edit text).
  - **Исключение из поиска/ответов** — целиком за счёт repo-фильтра (`forgotten_at is null`); `MemoryAnswerService` работает над уже извлечёнными → автоматически без забытых.
  - **Тесты:** repo-интеграция (забытое не в поиске/списках/задачах), сервис (forget/recall/edit + re-embed), бот-команды (резолв по номеру), контроллер.
  - **Открытые решения:** edit — только текст (MVP) vs повторная extraction (позже); forget юнит vs весь item (MVP — юнит); связь с 3.6 — отдельный `PURCHASE`-тип на классификации можно занести сюда как «правку типа».
- [ ] **4.2** Дедуп/консолидация близких фактов (офлайн-джоба по эмбеддингам). M–L.

## Фаза 5 — Наблюдаемость и инфра (S–M, фоном)

- [x] **5.1** Авто-ловля регрессий ранжирования — **сделано** (два уровня):
  - **Дешёвый guard на каждый билд:** `RetrievalRegressionGuardTest` (eval-пакет, без тега → в обычном `test`). Сидит курируемый набор юнитов в реальный Postgres (Testcontainers), гоняет настоящий `InboxItemSearchService.search()` с openai off (лексический путь) и проверяет recall / дизамбигуацию похожих объектов / фильтры по типу и тегу. **Без API, без judge, секунды** — ловит регрессии FTS-запросов, `score()`-весов и cutoffs бесплатно. Платный `memoryEval` остаётся для полной семантической оценки.
  - **Регулярный полный прогон:** GitHub Actions `.github/workflows/memory-eval.yml` — `memoryEval` (judge) ночью (cron) + ручной dispatch (выбор `isolated`/`accumulated`, judge on/off), ключ из secret `OPENAI_API_KEY`, отчёт как артефакт. Активируется при пуше в GitHub (сейчас `main` локальный).
- [ ] **5.2** Метрики: доля поисков с ответом, доля `canAnswer=false`, латентность ИИ-шагов, расход токенов.
- [ ] **5.3** Хардненинг REST (аутентификация) — если API выйдет наружу; сейчас `/api/inbox-items` открыт.

## Фаза 6 — Изображения (M; MVP — невысокая сложность)

**Цель:** сохранять изображения и по запросу получать само изображение либо текст/описание того, что на нём.

**Ключевой приём (почему ложится дёшево):** поиск/ответ работают над текстом `MemoryUnit`. Если при приёме картинку прогнать через vision-модель → описание + OCR-текст и положить как текст `MemoryUnit`, изображение становится искомым **без изменений в поиске/Q&A**. `openai.model=gpt-4.1-mini` уже vision-capable — менять модель не нужно, только форму запроса (image content-part).

- [x] **6.1 MVP (Telegram-only)** — **сделано** (компилируется, весь тест-набор зелёный):
  - `OpenAiClient.describeImage(base64, mimeType)` — vision-вызов (gpt-4.1-mini) → описание + OCR-текст, тот же retry-слой 1.3. Обёртка `AiVisionService` (ObjectProvider-гейт, degrade при `openai.enabled=false`).
  - Приём фото в Telegram: `TelegramMessage` + `photo` (массив `TelegramPhotoSize`) и `caption`; `largestPhoto()` берёт самый крупный размер. `TelegramApiClient.getFile(fileId)` + `downloadFile(filePath)` (второй RestClient на `https://api.telegram.org/file/bot<token>`).
  - **Без бинарного хранилища:** хранится Telegram `file_id` + текст-описание. На выдаче SEARCH пере-слать фото `sendPhoto(file_id)` (до 3 уникальных хитов).
  - Пайплайн: `TelegramImageIngestionService.ingest` (`@Async`, гейт `telegram.bot.enabled`) → download → base64 → vision → `InboxItemService.create(request, fileId, "image/jpeg")` с тегом `image`; `rawText` = подпись + описание (или плейсхолдер «Изображение», если оба пусты) → дальше classify→extract→embed как обычно, ищется существующим кодом. Капча мгновенная: «Сохранил картинку, разберу позже».
  - Схема: `inbox_items.image_file_id` (+ `media_type`) — миграция `013`. `raw_text NOT NULL` соблюдается за счёт плейсхолдера. `MemoryUnitResponse.imageFileId` прокинут из `item` маппером.
  - Отложено: реальный E2E-прогон с живым ботом/ключом; отдельный `MemoryUnitType`/фильтр для медиа (пока тег `image`).
- [ ] **6.2 Полный вариант (REST + хранилище):** multipart-загрузка в REST; бинарное хранилище (ФС-путь в БД или `bytea`) + эндпоинт отдачи байтов. Заметно больше MVP.
- [ ] **6.3 (на потом, опционально):** CLIP-эмбеддинги изображений для визуального «найди похожую картинку» — отдельный класс, для текстового поиска по описанию не нужен.

**Открытые решения:** где хранить байты для не-Telegram пути (ФС vs `bytea`); зависит ли retention от срока жизни `file_id`; нужен ли отдельный `MemoryUnitType`/тег для медиа.
