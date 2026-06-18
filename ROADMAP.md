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
- [ ] **3.2** Тюнинг reranker'а на реальном eval (окно, порог, модель).
- [ ] **3.3** Морфология вместо `commonPrefixLength` для русского (стемминг в скоринге).

## Фаза 4 — Жизненный цикл памяти (M)

- [ ] **4.1** Правка/удаление/«забыть»: `/forget`, `/edit`, пометка факта неверным.
- [ ] **4.2** Дедуп/консолидация близких фактов (офлайн-джоба по эмбеддингам). M–L.

## Фаза 5 — Наблюдаемость и инфра (S–M, фоном)

- [ ] **5.1** `memoryEval` в CI / регулярно — авто-ловля регрессий ранжирования.
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
