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
- [ ] **1.2** Async-обработка ИИ-шагов (зависит от 1.1): классификация/извлечение/эмбеддинг в фон с ретраями; мгновенный отклик в Telegram.
- [ ] **1.3** Устойчивость OpenAI: retry+backoff на 429/5xx, таймауты, лог причины.

## Фаза 2 — Замкнуть петлю напоминаний (M) — *данные уже есть, фича не работает*

- [ ] **2.1** Шедулер дедлайнов: `@Scheduled`-джоба находит наступившие `dueAt` (TASK/REMINDER), пушит в Telegram, отмечает `reminded_at` (миграция).
- [ ] **2.2** Управление задачами из бота: `/done`, `/snooze <время>`, листинг открытых (зависит от 2.1).

## Фаза 3 — Качество поиска и ответов (M, итеративно, мерить eval'ом)

- [ ] **3.1** Вынести веса ранкера (`score()`, ~700 строк магических констант) в `@ConfigurationProperties`.
- [ ] **3.2** Тюнинг reranker'а на реальном eval (окно, порог, модель).
- [ ] **3.3** Морфология вместо `commonPrefixLength` для русского (стемминг в скоринге).

## Фаза 4 — Жизненный цикл памяти (M)

- [ ] **4.1** Правка/удаление/«забыть»: `/forget`, `/edit`, пометка факта неверным.
- [ ] **4.2** Дедуп/консолидация близких фактов (офлайн-джоба по эмбеддингам). M–L.

## Фаза 5 — Наблюдаемость и инфра (S–M, фоном)

- [ ] **5.1** `memoryEval` в CI / регулярно — авто-ловля регрессий ранжирования.
- [ ] **5.2** Метрики: доля поисков с ответом, доля `canAnswer=false`, латентность ИИ-шагов, расход токенов.
- [ ] **5.3** Хардненинг REST (аутентификация) — если API выйдет наружу; сейчас `/api/inbox-items` открыт.
