# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Java 21 / Spring Boot 3.5 backend for a personal "second brain": a Telegram bot and REST API that ingest free-form notes, classify and decompose them into structured memories via OpenAI, and answer natural-language questions over them using hybrid (full-text + vector) search plus an LLM answer-synthesis step.

Stack: Spring Boot 3.5, Gradle Kotlin DSL, Spring Data JPA, Liquibase, PostgreSQL + pgvector, Lombok, JUnit 5, Testcontainers.

Keep package names under `ru.ravil.petproject`.

## Common Commands

Use the Gradle wrapper from the repository root (PowerShell on this machine).

```powershell
.\gradlew.bat test           # unit + integration tests (excludes live-openai and memory-eval tags)
.\gradlew.bat bootRun
.\gradlew.bat test --tests "ru.ravil.petproject.service.InboxItemSearchServiceTest"
```

`JAVA_HOME` may need to point at an installed Java 21 JDK:

```powershell
$env:JAVA_HOME='C:\Users\Ravil\.jdks\ms-21.0.11'
```

Local Postgres (with pgvector) for `bootRun`/manual testing: `docker compose up -d` (see `compose.yaml`, port 5432, db/user/pass `pet_project`). The test suite instead spins up its own Testcontainers Postgres (`pgvector/pgvector:pg16`) automatically — Docker must be running or `contextLoads()`/integration tests fail with "Could not find a valid Docker environment".

### Special test tasks (excluded from the default `test` task)

```powershell
# OpenAI-backed live smoke tests against the real API — requires a real key
$env:OPENAI_API_KEY='sk-...'; $env:RUN_LIVE_GPT_TESTS='true'; .\gradlew.bat liveTest

# Memory/search quality evaluation harness — runs YAML scenarios in src/test/resources/memory-eval
# and writes a report to build/reports/memory-eval. LLM-as-judge scoring is optional.
.\gradlew.bat memoryEval
.\gradlew.bat memoryEval -PmemoryEvalJudgeEnabled=true -PmemoryEvalJudgeModel=gpt-4.1-mini -PmemoryEvalLimit=10 -PmemoryEvalDatabaseMode=accumulated
```

`memoryEvalDatabaseMode` is `isolated` (truncate inbox tables before every case, default) or `accumulated` (truncate once before the whole run, so later cases see earlier saved memories).

## Architecture

### Ingestion pipeline (`InboxItemService.create`)

Every incoming text (Telegram message or REST POST) goes through:
1. `LinkExtractor` pulls out URLs and adds a `link` tag + domain tags.
2. The raw `InboxItem` is persisted **immediately** (status `PENDING_AI`) with a minimal lexically-searchable fallback `MemoryUnit`, in its own committed transaction — so a capture is never lost, even when OpenAI is down or `openai.enabled=false` (degraded save).
3. AI enrichment then runs in a **separate** transaction (`InboxItemService.process`, invoked via a self-`ObjectProvider` so it gets its own committed tx): `AiClassificationService` (OpenAI chat completion, JSON mode) classifies title/summary/`InboxItemType`/priority/actionable/tags. On success the item becomes `PROCESSED` and the fallback unit is replaced by extracted units; on failure (incl. `AiProcessingUnavailableException`) it is marked `FAILED_AI` (recording `processing_attempts`/`last_processing_error`/`next_attempt_at`) and kept — `InboxItemService.reprocess(id)` / `reprocessPending(limit)` (and `POST /api/inbox-items/{id}/reprocess`) retry it on demand, while `InboxItemRetryScheduler` (`@Scheduled`, gated by `inbox.processing.retry.enabled`, off in the eval profile) auto-retries `FAILED_AI` items on an exponential backoff and gives up after `MAX_PROCESSING_ATTEMPTS` (`next_attempt_at=null`).
4. `AiMemoryUnitExtractionService` decomposes the same raw text into zero or more `MemoryUnit`s (a single message can yield several discrete memories, each with its own `MemoryUnitType`, `MemorySlot`s, tags, confidence). If extraction returns nothing, a single fallback `MemoryUnit` is built from the classification result instead. Extraction also resolves relative dates/times in the note (the current datetime is passed in the prompt) into absolute `occurredAt`/`dueAt` timestamps — these drive temporal search and the reminder scheduler.
5. If `AiEmbeddingService` is available (`openai.enabled=true`), each new `MemoryUnit`'s search document is embedded (`text-embedding-3-small`, 1536 dims) and stored via `MemoryUnitRepository.updateEmbedding`, which records the `embedding_model` and `embedded_at` alongside the vector. `InboxItemEmbeddingBackfillService` re-embeds rows whose stored `embedding_model` differs from the currently-configured one (`findIdsMissingOrStaleEmbedding`), so changing `openai.embedding-model` + running `/embeddings backfill` migrates vectors incrementally instead of all-or-nothing.

`InboxItem` and `MemoryUnit` are two layers of the same domain: `InboxItem` is the raw inbox record (what arrived); `MemoryUnit`s are the AI-extracted, queryable facts/events/tasks/etc. derived from it (`item.memoryUnits`, cascade `ALL`/orphan-removal). Search and Q&A operate on `MemoryUnit`, not `InboxItem`. `MemorySlot`s (role/value/normalizedValue, see `MemorySlotRole`) attach structured attributes (SUBJECT, ACTOR, ACTION, PLACE, TIME, PRICE, ...) to a `MemoryUnit`.

All AI integrations (`AiClassificationService`, `AiMemoryUnitExtractionService`, `AiEmbeddingService`, `OpenAiClient`) are conditional beans gated by `openai.enabled` — see `OpenAiClientConfiguration`. Services that depend on them optionally (search, embeddings, Telegram answer synthesis) inject them via `ObjectProvider<T>` and degrade gracefully (`getIfAvailable() == null` → skip that enhancement) rather than failing.

### Search (`InboxItemSearchService`)

A hand-rolled hybrid ranker over `MemoryUnit`, not a vector-only or pure-FTS search:
1. Lexical candidates from Postgres full-text search (`MemoryUnitRepository.searchAdvanced*`), tried in order: strict `tsquery`, then a relaxed prefix-matching `tsquery` (`token:*` OR'd, stopwords stripped), then a plain `ILIKE`-style contains query — first non-empty result set wins.
2. If the query has enough content tokens (or a date range + type filter is present), vector candidates are added via `AiEmbeddingService` + pgvector cosine-distance (HNSW index) search.
3. Lexical and vector candidates are merged and scored by `score(...)` (field/token exact vs. prefix matches across title/summary/sourceQuote/tags/slots, weighted), with vector hits getting a rank-decaying bonus instead of replacing lexical scoring.
4. Cutoffs (`MIN_RELEVANCE_SCORE`, `lexicalRelevanceCutoff`, `vectorRelevanceCutoff`) and `hasRequiredAnchorMatch` (token-overlap requirement against title/sourceQuote/tags/slots) filter out weakly-related hits — this is what keeps vector search from returning topically-similar-but-wrong memories.
5. `QUESTION`-type memory units are excluded unless the caller explicitly filtered by that type (`isImplicitQuestionMemory`).
6. Optionally, the top-`RERANK_WINDOW` ranked candidates are passed through `MemoryRerankService` (LLM rerank) before the final `limit`. It is gated by `openai.rerank-enabled` (default `false`); when off or unavailable it is a no-op. It only reorders — candidates the model omits are re-appended in original order, so reranking never reduces recall.

Date-range filtering (TODAY/YESTERDAY periods, see `SearchPeriod`) prefers a `MemoryUnit`'s own `occurred_at` when set, and falls back to its source `item.created_at` only when `occurred_at IS NULL`: `(occurred_at is not null and occurred_at in range) or (occurred_at is null and created_at in range)`. So "what happened in March" finds events dated to March even if recorded later, **and** a yesterday-event recorded today does not leak into a TODAY query via its `created_at` (the bug behind eval `long_diary_002`). This only applies when an explicit period is requested; there is intentionally no date fallback for date-less queries.

When tuning ranking, prefer adjusting the cutoffs/weights over restructuring the lexical→vector→rank pipeline; the eval harness (below) is the way to validate changes empirically rather than reasoning about it in the abstract. The top-level dials (vector bonus/penalty, min-relevance & weak-lexical cutoffs, rerank window, lexical-cutoff ratios) are exposed as `search.ranking.*` (`SearchRankingProperties`) so they can be swept without recompiling; the fine-grained per-field `score()` weights are still in-code constants.

### Question answering (`MemoryAnswerService`)

Given a query that looks like a question (ends in `?` or starts with a Russian question word) plus the `MemoryUnit`s `InboxItemSearchService` retrieved, sends them as JSON context to OpenAI with a strict system prompt (`SYSTEM_PROMPT`) that forbids outside knowledge and requires `sourceIndexes` pointing back into the supplied context. Returns `Optional.empty()` unless the model claims `canAnswer=true`, confidence ≥ `MIN_CONFIDENCE` (0.55), and at least one source index — i.e. retrieval and answering are decoupled: this service only synthesizes/cites, it never searches.

### Telegram bot (`telegram` package)

`TelegramBotPollingService` polls `getUpdates` on a fixed schedule (only active when `telegram.bot.enabled=true`) and restricts to `telegram.bot.allowed-chat-id`. Intent detection is layered, controlled by `telegram.bot.intent-mode` (`TelegramIntentMode`):
- `CommandTelegramIntentDetector` always runs first (slash-style commands).
- `COMMAND_ONLY`: nothing else.
- `HYBRID_SAFE` (default): `AiTelegramIntentDetector` runs first only for ambiguous-looking text (`shouldUseAiForIntent`), else `RuleBasedTelegramIntentDetector` runs first and falls back to AI.
- `AI_FIRST`: AI detector tries everything first, rule-based is the fallback.

Recognized intents: HELP, RECENT, TODAY, SEARCH, CAPTURE/UNKNOWN (falls through to saving the message via `InboxItemService.captureAsync` — persists the raw item and acks instantly with "Сохранил, разберу позже", AI enrichment runs on a background thread). `/type <TYPE>` retroactively retypes the chat's last item; `/embeddings backfill` triggers `InboxItemEmbeddingBackfillService`. `/tasks` lists open TASK/REMINDER units for the chat (remembering the ordered list per chat), `/done <n>` / `/snooze <n> <30m|2h|1d>` act on that list (`MemoryTaskService`). `/forget <n>` / `/edit <n> <text>` act on the **last shown SEARCH list** per chat (the bot remembers exactly the numbered list it rendered — cited sources when an answer was shown, else all hits) via `MemoryEditService`: forget is a reversible soft-delete (`memory_units.forgotten_at`), edit rewrites the unit's title+summary and re-embeds (no re-extraction). `/duplicates` lists near-duplicate pairs (`MemoryDeduplicationService`, Phase 4.2) and remembers the redundant side so `/forget <n>` drops it. Due TASK/REMINDER units (`MemoryUnit.dueAt`, populated by extraction from the note's date/time) are delivered to their originating chat by `TelegramReminderScheduler` (`@Scheduled`, only when the bot is enabled), marking `reminded_at` after a successful send. SEARCH responses combine `InboxItemSearchService` results with an optional `MemoryAnswerService` answer via `TelegramSearchResponseFormatter`, then re-send the actual photos behind any image-backed hits (up to 3) via `sendPhoto(file_id)`.

**Media (MVP, Telegram-only, no binary storage):** photo and voice messages are intercepted in `handleUpdate` (before the text-empty rejection) and handed to `@Async` ingestion services (gated by `telegram.bot.enabled`); the bot acks instantly ("Сохранил картинку/голосовое, разберу позже"). The trick for both: turn the media into **text** so the existing text pipeline indexes it unchanged.
- **Images** (`TelegramImageIngestionService`): downloads the largest `PhotoSize` (`TelegramApiClient.getFile` + `downloadFile`), runs `AiVisionService.describe` → `OpenAiClient.describeImage` (vision on the same `openai.model`) → description + OCR text; saves via `InboxItemService.create(request, fileId, "image/jpeg")` with an `image` tag. `rawText` = caption + description (or placeholder "Изображение"). The photo is re-sent on SEARCH retrieval via `sendPhoto(file_id)`.
- **Voice** (`TelegramVoiceIngestionService`): downloads the OGG voice, runs `AiTranscriptionService.transcribe` → `OpenAiClient.transcribe` (Whisper `whisper-1`, multipart) → transcript; saves via `create(request, fileId, "audio/ogg")` with a `voice` tag. `rawText` = transcript (or placeholder "Голосовое сообщение"). Audio is **not** re-sent (the transcript carries the value).

Both store the Telegram `file_id` on `inbox_items.media_file_id` (+ `media_type` discriminator, migrations `013`/`015`); `MemoryUnitResponse.imageFileId` is populated by the mapper **only for `image/*` media** (for photo re-send). When OpenAI is disabled, ingestion degrades to placeholder text but still captures the `file_id`. All AI media calls reuse the 1.3 retry layer.

### Observability (`metrics` package)

`MetricsService` is a dependency-free in-memory metrics sink (Phase 5.2): named counters, timers (count/total/avg millis) and per-operation token totals, exposed as a snapshot via `GET /api/metrics` (`MetricsController`). Instrumented: `OpenAiClient` (AI-step latency `ai.<op>.ms` + token spend `ai.<op>` from the response `usage`, via an optional 4-arg constructor — the 3-arg one delegates with `null` so it stays a no-op in tests), `MemoryAnswerService` (`answer.produced`/`answer.empty` → answer rate), `InboxItemSearchService` (`search.requested`, `search.ms`). Deliberately not Micrometer/Actuator — personal-scale only.

### REST API (`InboxItemController`, `/api/inbox-items`)

`GET /search?q=` runs the query through `NaturalLanguageSearchQueryParser` first (recognizes "recent"/"today"-style natural language before falling back to full search), mirroring what the Telegram SEARCH intent does.

### Data model / migrations

Liquibase changelog at `src/main/resources/db/changelog/db.changelog-master.yaml`, applied automatically on startup (`spring.jpa.hibernate.ddl-auto=validate` — JPA never auto-generates schema, every column change needs a changeset). Core tables: `inbox_items` (+ `inbox_item_tags`, `inbox_item_links`) → `memory_units` (+ `memory_unit_tags`) → `memory_slots`. Both `inbox_items` and `memory_units` carry a `search_text` column (denormalized, refreshed in `@PrePersist`/`@PreUpdate` on the entities) indexed with two GIN `tsvector` indexes each (`russian` config for stemmed search, `simple` config for the relaxed/prefix fallback), plus an `embedding vector(1536)` column with an HNSW cosine-distance index. When adding a new searchable text field, update the entity's `refreshSearchText()` and consider whether both FTS configs need it. `memory_units.forgotten_at` is a reversible soft-delete marker (Phase 4.1): every retrieval query (`searchAdvanced(/Between)`, `searchNearestByEmbedding(/Between)`, `findBySourceCreatedAtBetween`, `findAllBySourceCreatedAtDesc`, `findOpenTasks`, `findDueReminders`) filters `forgotten_at is null`, so forgetting a unit removes it from search, answers, tasks and reminders without deleting the row — any **new** retrieval query must add the same filter.

### Testing structure

- `src/test/java/.../*Test.java` — unit tests, most repository/integration tests using Testcontainers Postgres (`@Import(TestcontainersConfiguration.class)`).
- `eval/` — two regression layers for the search/answer pipeline:
  - `RetrievalRegressionGuardTest` — **untagged, runs in the normal `test` task on every build.** A cheap, deterministic ranking guard: seeds curated units into Testcontainers Postgres and asserts `InboxItemSearchService.search` recall / similar-object disambiguation / type & tag filters with OpenAI off (lexical path). No API, no judge — the free safety net against `score()`/cutoff/FTS-query regressions (Phase 5.1).
  - the `memory-eval` Gradle task's harness (`memory-eval`-tagged, excluded from `test`): loads YAML scenarios (`src/test/resources/memory-eval/*.yaml`, each with `save` messages and `questions` with expected/forbidden facts), replays them through the real ingestion+search+answer pipeline, and optionally judges answers with `OpenAiMemoryEvalJudge` (else `DisabledMemoryEvalJudge`). Run nightly / on demand via `.github/workflows/memory-eval.yml` (paid: real OpenAI + judge).
- `live/` — `live-openai`-tagged smoke tests that hit the real OpenAI API; only runnable via the `liveTest` task with `RUN_LIVE_GPT_TESTS=true` and a real `OPENAI_API_KEY`.

### Configuration

`telegram.bot.*` and `openai.*` properties (see `application.properties`) are off by default (`enabled=false`) so the app boots without secrets. `openai.rerank-enabled` (default `false`) additionally gates the LLM rerank step in search — it requires `openai.enabled=true` to have any effect. Don't commit local secrets, DB passwords, or machine-specific paths — use the `${ENV_VAR:default}` placeholders already in place.
