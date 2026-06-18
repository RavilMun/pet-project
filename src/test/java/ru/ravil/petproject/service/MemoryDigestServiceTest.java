package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ru.ravil.petproject.domain.InboxItem;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.repository.InboxItemRepository;

class MemoryDigestServiceTest {

    private MemoryTaskService memoryTaskService;
    private InboxItemRepository inboxItemRepository;
    private MemoryDigestService service;

    @BeforeEach
    void setUp() {
        memoryTaskService = Mockito.mock(MemoryTaskService.class);
        inboxItemRepository = Mockito.mock(InboxItemRepository.class);
        service = new MemoryDigestService(memoryTaskService, inboxItemRepository);
    }

    @Test
    void buildsDigestWithTasksAndRecentCaptures() {
        when(memoryTaskService.listOpenTasks(eq(42L), anyInt())).thenReturn(List.of(
                new OpenTask(UUID.randomUUID(), "Оплатить интернет", OffsetDateTime.parse("2026-06-20T10:00:00Z"))));
        InboxItem item = new InboxItem("заметка про встречу", InboxItemSource.TELEGRAM);
        item.setTitle("Встреча с Катей");
        when(inboxItemRepository.findByTelegramChatIdAndCreatedAtAfterOrderByCreatedAtDesc(eq(42L), any(), any()))
                .thenReturn(List.of(item));

        Optional<String> digest = service.buildDigest(42L);

        assertThat(digest).isPresent();
        assertThat(digest.get())
                .contains("Открытые задачи (1)")
                .contains("Оплатить интернет")
                .contains("(до 2026-06-20)")
                .contains("За последние сутки (1)")
                .contains("Встреча с Катей");
    }

    @Test
    void returnsEmptyWhenNothingToReport() {
        when(memoryTaskService.listOpenTasks(eq(42L), anyInt())).thenReturn(List.of());
        when(inboxItemRepository.findByTelegramChatIdAndCreatedAtAfterOrderByCreatedAtDesc(eq(42L), any(), any()))
                .thenReturn(List.of());

        assertThat(service.buildDigest(42L)).isEmpty();
    }
}
