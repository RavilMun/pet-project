package ru.ravil.petproject.telegram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import ru.ravil.petproject.ai.AiVisionService;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.service.InboxItemService;

class TelegramImageIngestionServiceTest {

    private TelegramApiClient telegramApiClient;
    private AiVisionService aiVisionService;
    private InboxItemService inboxItemService;
    private TelegramImageIngestionService service;

    @BeforeEach
    void setUp() {
        telegramApiClient = Mockito.mock(TelegramApiClient.class);
        aiVisionService = Mockito.mock(AiVisionService.class);
        inboxItemService = Mockito.mock(InboxItemService.class);
        service = new TelegramImageIngestionService(telegramApiClient, aiVisionService, inboxItemService);

        when(telegramApiClient.getFile("file_1")).thenReturn(new TelegramFile("file_1", "u", 100, "photos/f.jpg"));
        when(telegramApiClient.downloadFile("photos/f.jpg")).thenReturn(new byte[]{1, 2, 3});
    }

    @Test
    void combinesCaptionAndDescription() {
        when(aiVisionService.describe(any(), eq("image/jpeg"))).thenReturn(Optional.of("на чеке итого 1500"));

        service.ingest(42L, "file_1", "покупка в Ленте", 7L);

        CreateInboxItemRequest request = captureCreatedRequest();
        Assertions.assertThat(request.rawText()).isEqualTo("покупка в Ленте\n\nна чеке итого 1500");
        Assertions.assertThat(request.tags()).containsExactly("image");
        Assertions.assertThat(request.telegramChatId()).isEqualTo(42L);
        Assertions.assertThat(request.telegramMessageId()).isEqualTo(7L);
    }

    @Test
    void usesCaptionOnlyWhenVisionUnavailable() {
        when(aiVisionService.describe(any(), any())).thenReturn(Optional.empty());

        service.ingest(42L, "file_1", "просто подпись", 7L);

        Assertions.assertThat(captureCreatedRequest().rawText()).isEqualTo("просто подпись");
    }

    @Test
    void usesDescriptionWhenNoCaption() {
        when(aiVisionService.describe(any(), any())).thenReturn(Optional.of("скриншот расписания"));

        service.ingest(42L, "file_1", null, 7L);

        Assertions.assertThat(captureCreatedRequest().rawText()).isEqualTo("скриншот расписания");
    }

    @Test
    void fallsBackToPlaceholderWhenNothingAvailable() {
        when(aiVisionService.describe(any(), any())).thenReturn(Optional.empty());

        service.ingest(42L, "file_1", "  ", 7L);

        Assertions.assertThat(captureCreatedRequest().rawText()).isEqualTo("Изображение");
    }

    @Test
    void skipsVisionWhenFileUnavailableButStillCaptures() {
        when(telegramApiClient.getFile("file_1")).thenReturn(null);

        service.ingest(42L, "file_1", "подпись", 7L);

        verify(aiVisionService, never()).describe(any(), any());
        Assertions.assertThat(captureCreatedRequest().rawText()).isEqualTo("подпись");
    }

    private CreateInboxItemRequest captureCreatedRequest() {
        ArgumentCaptor<CreateInboxItemRequest> captor = ArgumentCaptor.forClass(CreateInboxItemRequest.class);
        verify(inboxItemService).create(captor.capture(), eq("file_1"), eq("image/jpeg"));
        return captor.getValue();
    }
}
