package ru.ravil.petproject.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ravil.petproject.TestcontainersConfiguration;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.domain.InboxItemType;
import ru.ravil.petproject.domain.MemorySlot;
import ru.ravil.petproject.domain.MemorySlotRole;
import ru.ravil.petproject.domain.MemoryUnit;
import ru.ravil.petproject.domain.MemoryUnitType;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MemoryUnitSearchIntegrationTest {

    @Autowired
    private InboxItemRepository inboxItemRepository;

    @Autowired
    private MemoryUnitRepository memoryUnitRepository;

    @BeforeEach
    void setUp() {
        inboxItemRepository.deleteAll();
    }

    @Test
    void fullTextSearchReturnsMemoryUnits() {
        MemoryUnit kafka = unit("Посмотреть доклад про Kafka", "Доклад про Kafka", MemoryUnitType.LEARNING, Set.of("kafka"));
        unit("Хочу посмотреть фильм Мгла", "Посмотреть фильм Мгла", MemoryUnitType.MOVIE, Set.of("мгла"));

        List<MemoryUnit> results = memoryUnitRepository.searchAdvanced(
                        "kafka",
                        true,
                        "",
                        false,
                        "",
                        false,
                        Set.of("__no_types__"),
                        false,
                        Set.of("__no_tags__"),
                        false,
                        PageRequest.of(0, 10)
                )
                .stream()
                .toList();

        assertThat(results).extracting(MemoryUnit::getId).contains(kafka.getId());
        assertThat(results).extracting(MemoryUnit::getTitle).contains("Доклад про Kafka");
    }

    @Test
    void fullTextSearchReturnsMemoryUnitsBySlotValues() {
        MemoryUnit purchase = unit(
                "Покупка аксессуаров",
                "Покупка аксессуаров",
                MemoryUnitType.PURCHASE_RESEARCH,
                Set.of("покупка"),
                new SlotSpec(MemorySlotRole.OBJECT, "USB-C кабель", "usb-c кабель"),
                new SlotSpec(MemorySlotRole.PLACE, "магазин DNS", "dns")
        );
        unit("Купить лампочки для дома", "Купить лампочки", MemoryUnitType.PURCHASE_RESEARCH, Set.of("покупка"));

        List<MemoryUnit> results = memoryUnitRepository.searchAdvanced(
                        "кабель dns",
                        true,
                        "",
                        false,
                        "",
                        false,
                        Set.of("__no_types__"),
                        false,
                        Set.of("__no_tags__"),
                        false,
                        PageRequest.of(0, 10)
                )
                .stream()
                .toList();

        assertThat(results).extracting(MemoryUnit::getId).contains(purchase.getId());
        assertThat(results).extracting(MemoryUnit::getTitle).contains("Покупка аксессуаров");
    }

    @Test
    void storesAndSearchesMemoryUnitEmbeddingsWithPgvector() {
        MemoryUnit kafka = unit("Посмотреть доклад про Kafka", "Доклад про Kafka", MemoryUnitType.LEARNING, Set.of("kafka"));
        MemoryUnit movie = unit("Хочу посмотреть фильм Мгла", "Посмотреть фильм Мгла", MemoryUnitType.MOVIE, Set.of("мгла"));

        assertThat(memoryUnitRepository.findIdsMissingEmbedding(PageRequest.of(0, 10)))
                .containsExactlyInAnyOrder(kafka.getId(), movie.getId());

        int updatedKafka = memoryUnitRepository.updateEmbedding(
                kafka.getId(),
                vectorLiteral(0.9),
                "test-embedding",
                OffsetDateTime.now()
        );
        int updatedMovie = memoryUnitRepository.updateEmbedding(
                movie.getId(),
                vectorLiteral(-0.9),
                "test-embedding",
                OffsetDateTime.now()
        );

        assertThat(updatedKafka).isEqualTo(1);
        assertThat(updatedMovie).isEqualTo(1);
        assertThat(memoryUnitRepository.findIdsMissingEmbedding(PageRequest.of(0, 10))).isEmpty();

        List<MemoryUnit> results = memoryUnitRepository.searchNearestByEmbedding(
                vectorLiteral(0.85),
                Set.of("__no_types__"),
                false,
                PageRequest.of(0, 2)
        );

        assertThat(results).extracting(MemoryUnit::getTitle)
                .containsExactly("Доклад про Kafka", "Посмотреть фильм Мгла");
    }

    @Test
    void vectorSearchCanFilterByMemoryUnitType() {
        MemoryUnit kafka = unit("Посмотреть доклад про Kafka", "Доклад про Kafka", MemoryUnitType.LEARNING, Set.of("kafka"));
        MemoryUnit movie = unit("Хочу посмотреть фильм Мгла", "Посмотреть фильм Мгла", MemoryUnitType.MOVIE, Set.of("мгла"));
        memoryUnitRepository.updateEmbedding(kafka.getId(), vectorLiteral(0.9), "test-embedding", OffsetDateTime.now());
        memoryUnitRepository.updateEmbedding(movie.getId(), vectorLiteral(0.8), "test-embedding", OffsetDateTime.now());

        List<MemoryUnit> results = memoryUnitRepository.searchNearestByEmbedding(
                vectorLiteral(0.85),
                Set.of("MOVIE"),
                true,
                PageRequest.of(0, 10)
        );

        assertThat(results).extracting(MemoryUnit::getType).containsExactly(MemoryUnitType.MOVIE);
    }

    @Test
    void forgottenUnitsAreExcludedFromSearchAndListingAndRestoredOnRecall() {
        MemoryUnit kafka = unit("Посмотреть доклад про Kafka", "Доклад про Kafka", MemoryUnitType.LEARNING, Set.of("kafka"));

        assertThat(searchKafka()).extracting(MemoryUnit::getId).contains(kafka.getId());
        assertThat(memoryUnitRepository.findAllBySourceCreatedAtDesc(PageRequest.of(0, 10)))
                .extracting(MemoryUnit::getId).contains(kafka.getId());

        assertThat(memoryUnitRepository.markForgotten(kafka.getId(), OffsetDateTime.now())).isEqualTo(1);

        assertThat(searchKafka()).extracting(MemoryUnit::getId).doesNotContain(kafka.getId());
        assertThat(memoryUnitRepository.findAllBySourceCreatedAtDesc(PageRequest.of(0, 10)))
                .extracting(MemoryUnit::getId).doesNotContain(kafka.getId());

        assertThat(memoryUnitRepository.unforget(kafka.getId())).isEqualTo(1);
        assertThat(searchKafka()).extracting(MemoryUnit::getId).contains(kafka.getId());
    }

    @Test
    void findsNearDuplicatePairsAcrossDifferentItems() {
        MemoryUnit a = unit("Купил кресло Markus в IKEA", "кресло Markus", MemoryUnitType.PURCHASE_RESEARCH, Set.of("кресло"));
        MemoryUnit b = unit("Приобрёл кресло Markus в ИКЕА", "кресло Markus", MemoryUnitType.PURCHASE_RESEARCH, Set.of("кресло"));
        MemoryUnit far = unit("Посмотреть фильм Дюна", "Дюна", MemoryUnitType.MOVIE, Set.of("дюна"));
        memoryUnitRepository.updateEmbedding(a.getId(), vectorLiteral(0.9), "test-embedding", OffsetDateTime.now());
        memoryUnitRepository.updateEmbedding(b.getId(), vectorLiteral(0.9), "test-embedding", OffsetDateTime.now());
        memoryUnitRepository.updateEmbedding(far.getId(), vectorLiteral(-0.9), "test-embedding", OffsetDateTime.now());

        List<ru.ravil.petproject.repository.DuplicatePairProjection> pairs =
                memoryUnitRepository.findDuplicatePairs(0.05, PageRequest.of(0, 10));

        assertThat(pairs).hasSize(1);
        assertThat(Set.of(pairs.getFirst().getUnitAId(), pairs.getFirst().getUnitBId()))
                .containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(pairs.getFirst().getDistance()).isLessThanOrEqualTo(0.05);
    }

    @Test
    void doesNotPairForgottenUnits() {
        MemoryUnit a = unit("Купил кресло Markus в IKEA", "кресло Markus", MemoryUnitType.PURCHASE_RESEARCH, Set.of("кресло"));
        MemoryUnit b = unit("Приобрёл кресло Markus в ИКЕА", "кресло Markus", MemoryUnitType.PURCHASE_RESEARCH, Set.of("кресло"));
        memoryUnitRepository.updateEmbedding(a.getId(), vectorLiteral(0.9), "test-embedding", OffsetDateTime.now());
        memoryUnitRepository.updateEmbedding(b.getId(), vectorLiteral(0.9), "test-embedding", OffsetDateTime.now());
        memoryUnitRepository.markForgotten(b.getId(), OffsetDateTime.now());

        assertThat(memoryUnitRepository.findDuplicatePairs(0.05, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void findsRelatedToItemAcrossItemsExcludingSelfAndForgotten() {
        MemoryUnit source = unit("Купил кресло Markus в IKEA", "кресло Markus", MemoryUnitType.PURCHASE_RESEARCH, Set.of("кресло"));
        MemoryUnit related = unit("Присматриваю кресло для кабинета", "кресло кабинет", MemoryUnitType.PREFERENCE, Set.of("кресло"));
        MemoryUnit unrelated = unit("Посмотреть фильм Дюна", "Дюна", MemoryUnitType.MOVIE, Set.of("дюна"));
        memoryUnitRepository.updateEmbedding(source.getId(), vectorLiteral(0.9), "test-embedding", OffsetDateTime.now());
        memoryUnitRepository.updateEmbedding(related.getId(), vectorLiteral(0.9), "test-embedding", OffsetDateTime.now());
        memoryUnitRepository.updateEmbedding(unrelated.getId(), vectorLiteral(-0.9), "test-embedding", OffsetDateTime.now());

        UUID sourceItemId = source.getItem().getId();
        assertThat(memoryUnitRepository.findRelatedToItem(sourceItemId, 0.30, PageRequest.of(0, 10)))
                .extracting(MemoryUnit::getId)
                .contains(related.getId())
                .doesNotContain(source.getId(), unrelated.getId());

        memoryUnitRepository.markForgotten(related.getId(), OffsetDateTime.now());
        assertThat(memoryUnitRepository.findRelatedToItem(sourceItemId, 0.30, PageRequest.of(0, 10)))
                .extracting(MemoryUnit::getId)
                .doesNotContain(related.getId());
    }

    private List<MemoryUnit> searchKafka() {
        return memoryUnitRepository.searchAdvanced(
                        "kafka", true, "", false, "", false,
                        Set.of("__no_types__"), false, Set.of("__no_tags__"), false,
                        PageRequest.of(0, 10))
                .stream()
                .toList();
    }

    @Test
    void periodFilterPrefersOccurredAtOverCreatedAt() {
        // All three rows are created "today" (auto @PrePersist). occurred_at is what should decide
        // membership in the TODAY period — a yesterday event recorded today must NOT match.
        MemoryUnit todayEvent = unitWithOccurredAt("Обед в Birch", OffsetDateTime.now());
        MemoryUnit yesterdayEvent = unitWithOccurredAt("Обед в Tokyo City", OffsetDateTime.now().minusDays(1));
        MemoryUnit noDate = unitWithOccurredAt("Заметка без даты", null);

        OffsetDateTime start = OffsetDateTime.now().toLocalDate().atStartOfDay(OffsetDateTime.now().getOffset()).toOffsetDateTime();
        OffsetDateTime end = start.plusDays(1);

        List<MemoryUnit> results = memoryUnitRepository.searchAdvancedBetween(
                        "", false,
                        "", false,
                        "", false,
                        Set.of("__no_types__"), false,
                        Set.of("__no_tags__"), false,
                        start, end,
                        PageRequest.of(0, 10)
                )
                .stream()
                .toList();

        assertThat(results).extracting(MemoryUnit::getId)
                .contains(todayEvent.getId(), noDate.getId())
                .doesNotContain(yesterdayEvent.getId());
    }

    private MemoryUnit unitWithOccurredAt(String title, OffsetDateTime occurredAt) {
        InboxItem item = new InboxItem(title, InboxItemSource.MANUAL);
        item.setTitle(title);
        item.setType(InboxItemType.NOTE);
        MemoryUnit unit = new MemoryUnit(item, MemoryUnitType.EVENT, title);
        unit.setSummary(title);
        unit.setSourceQuote(title);
        unit.setOccurredAt(occurredAt);
        item.addMemoryUnit(unit);
        inboxItemRepository.saveAndFlush(item);
        return unit;
    }

    private MemoryUnit unit(String rawText, String title, MemoryUnitType type, Set<String> tags) {
        return unit(rawText, title, type, tags, new SlotSpec[0]);
    }

    private MemoryUnit unit(String rawText, String title, MemoryUnitType type, Set<String> tags, SlotSpec... slots) {
        InboxItem item = new InboxItem(rawText, InboxItemSource.MANUAL);
        item.setTitle(title);
        item.setType(type == MemoryUnitType.MOVIE ? InboxItemType.MOVIE : InboxItemType.NOTE);
        MemoryUnit unit = new MemoryUnit(item, type, title);
        unit.setSummary(rawText);
        unit.setSourceQuote(rawText);
        unit.setTags(tags);
        for (SlotSpec slotSpec : slots) {
            MemorySlot slot = new MemorySlot(unit, slotSpec.role(), slotSpec.value());
            slot.setNormalizedValue(slotSpec.normalizedValue());
            unit.addSlot(slot);
        }
        item.addMemoryUnit(unit);
        inboxItemRepository.saveAndFlush(item);
        return unit;
    }

    private static String vectorLiteral(double seed) {
        return IntStream.range(0, 1536)
                .mapToObj(index -> Double.toString(seed + (index * 0.000001)))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private record SlotSpec(MemorySlotRole role, String value, String normalizedValue) {
    }
}
