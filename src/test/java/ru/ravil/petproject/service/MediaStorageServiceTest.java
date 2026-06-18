package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ravil.petproject.TestcontainersConfiguration;
import ru.ravil.petproject.domain.StoredMedia;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MediaStorageServiceTest {

    @Autowired
    private MediaStorageService mediaStorageService;

    @Test
    void storesAndRetrievesBytes() {
        UUID id = UUID.randomUUID();
        byte[] data = {1, 2, 3, 4, 5, -1, -2};
        mediaStorageService.store(id, "image/png", data);

        Optional<StoredMedia> stored = mediaStorageService.get(id);
        assertThat(stored).isPresent();
        assertThat(stored.get().getContentType()).isEqualTo("image/png");
        assertThat(stored.get().getBytes()).containsExactly(data);
    }

    @Test
    void getReturnsEmptyWhenMissing() {
        assertThat(mediaStorageService.get(UUID.randomUUID())).isEmpty();
    }
}
