package ru.ravil.petproject.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ravil.petproject.TestcontainersConfiguration;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.repository.InboxItemRepository;
import ru.ravil.petproject.service.InboxItemSearchService;
import ru.ravil.petproject.service.SearchPeriod;

/**
 * Cheap, deterministic ranking-regression guard (Phase 5.1). Seeds a small curated set of memory units
 * into a real Postgres (Testcontainers) and asserts the public {@link InboxItemSearchService#search}
 * behaviour for a handful of unambiguous queries — recall of the obviously-relevant unit, correct
 * disambiguation between similar objects, and type/tag filtering.
 *
 * <p>Runs with OpenAI disabled (the default test profile), so search takes the lexical path only — no
 * API calls, no LLM judge, runs in the normal {@code test} task on every build. This is the free safety
 * net that catches regressions in FTS query building, {@code score()} weights and cutoffs without the
 * slow/paid {@code memoryEval} harness; the latter stays for full semantic evaluation.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RetrievalRegressionGuardTest {

    @Autowired
    private InboxItemRepository inboxItemRepository;

    @Autowired
    private InboxItemSearchService searchService;

    @BeforeEach
    void setUp() {
        inboxItemRepository.deleteAll();
        seed("Выбрать кресло для кабинета", MemoryUnitType.PREFERENCE, Set.of("кресло", "мебель"));
        seed("Купил кабель Anker в DNS", MemoryUnitType.PURCHASE_RESEARCH, Set.of("anker", "dns", "кабель"));
        seed("Смотрел кабель Ugreen в МВидео", MemoryUnitType.PURCHASE_RESEARCH, Set.of("ugreen", "мвидео", "кабель"));
        seed("Доклад про Kafka на конференции", MemoryUnitType.LEARNING, Set.of("kafka"));
        seed("Купил молоко и хлеб", MemoryUnitType.NOTE, Set.of("продукты"));
    }

    @Test
    void recallsTheRelevantUnit() {
        assertThat(titles(searchService.search("кресло", Set.of(), Set.of(), SearchPeriod.ALL, 10)))
                .contains("Выбрать кресло для кабинета");
    }

    @Test
    void disambiguatesSimilarObjectsByDistinguishingToken() {
        List<String> results = titles(searchService.search("кабель Anker", Set.of(), Set.of(), SearchPeriod.ALL, 10));
        assertThat(results).isNotEmpty();
        // the distinguishing token "Anker" must put the Anker cable on top, not the Ugreen one
        assertThat(results.getFirst()).isEqualTo("Купил кабель Anker в DNS");
    }

    @Test
    void doesNotReturnUnrelatedUnits() {
        assertThat(titles(searchService.search("кресло", Set.of(), Set.of(), SearchPeriod.ALL, 10)))
                .doesNotContain("Купил молоко и хлеб");
    }

    @Test
    void filtersByType() {
        assertThat(titles(searchService.search("", Set.of(InboxItemType.LEARNING), Set.of(), SearchPeriod.ALL, 10)))
                .containsExactly("Доклад про Kafka на конференции");
    }

    @Test
    void filtersByTag() {
        assertThat(titles(searchService.search("", Set.of(), Set.of("kafka"), SearchPeriod.ALL, 10)))
                .contains("Доклад про Kafka на конференции");
    }

    private List<String> titles(List<MemoryUnitResponse> results) {
        return results.stream().map(MemoryUnitResponse::title).toList();
    }

    private void seed(String title, MemoryUnitType type, Set<String> tags) {
        InboxItem item = new InboxItem(title, InboxItemSource.MANUAL);
        item.setTitle(title);
        item.setType(InboxItemType.NOTE);
        MemoryUnit unit = new MemoryUnit(item, type, title);
        unit.setSummary(title);
        unit.setSourceQuote(title);
        unit.setTags(tags);
        item.addMemoryUnit(unit);
        inboxItemRepository.saveAndFlush(item);
    }
}
