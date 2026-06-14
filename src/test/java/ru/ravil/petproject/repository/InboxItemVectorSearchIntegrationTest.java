package ru.ravil.petproject.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InboxItemVectorSearchIntegrationTest {

    @Autowired
    private InboxItemRepository inboxItemRepository;

    @BeforeEach
    void setUp() {
        inboxItemRepository.deleteAll();
    }

    @Test
    void storesAndSearchesEmbeddingsWithPgvector() {
        InboxItem kafka = item("Посмотреть доклад про Kafka", InboxItemType.LEARNING);
        InboxItem movie = item("Хочу посмотреть фильм Мгла", InboxItemType.MOVIE);
        inboxItemRepository.saveAllAndFlush(List.of(kafka, movie));

        assertThat(inboxItemRepository.findIdsMissingEmbedding(PageRequest.of(0, 10)))
                .containsExactlyInAnyOrder(kafka.getId(), movie.getId());

        int updatedKafka = inboxItemRepository.updateEmbedding(
                kafka.getId(),
                vectorLiteral(0.9),
                "test-embedding",
                OffsetDateTime.now()
        );
        int updatedMovie = inboxItemRepository.updateEmbedding(
                movie.getId(),
                vectorLiteral(-0.9),
                "test-embedding",
                OffsetDateTime.now()
        );

        assertThat(updatedKafka).isEqualTo(1);
        assertThat(updatedMovie).isEqualTo(1);
        assertThat(inboxItemRepository.findIdsMissingEmbedding(PageRequest.of(0, 10))).isEmpty();

        List<InboxItem> results = inboxItemRepository.searchNearestByEmbedding(
                vectorLiteral(0.85),
                Set.of("__no_types__"),
                false,
                PageRequest.of(0, 2)
        );

        assertThat(results).extracting(InboxItem::getRawText)
                .containsExactly("Посмотреть доклад про Kafka", "Хочу посмотреть фильм Мгла");
    }

    @Test
    void vectorSearchCanFilterByType() {
        InboxItem kafka = item("Посмотреть доклад про Kafka", InboxItemType.LEARNING);
        InboxItem movie = item("Хочу посмотреть фильм Мгла", InboxItemType.MOVIE);
        inboxItemRepository.saveAllAndFlush(List.of(kafka, movie));
        inboxItemRepository.updateEmbedding(kafka.getId(), vectorLiteral(0.9), "test-embedding", OffsetDateTime.now());
        inboxItemRepository.updateEmbedding(movie.getId(), vectorLiteral(0.8), "test-embedding", OffsetDateTime.now());

        List<InboxItem> results = inboxItemRepository.searchNearestByEmbedding(
                vectorLiteral(0.85),
                Set.of("MOVIE"),
                true,
                PageRequest.of(0, 10)
        );

        assertThat(results).extracting(InboxItem::getType).containsExactly(InboxItemType.MOVIE);
    }

    private static InboxItem item(String rawText, InboxItemType type) {
        InboxItem item = new InboxItem(rawText, InboxItemSource.MANUAL);
        item.setType(type);
        return item;
    }

    private static String vectorLiteral(double seed) {
        return IntStream.range(0, 1536)
                .mapToObj(index -> Double.toString(seed + (index * 0.000001)))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
