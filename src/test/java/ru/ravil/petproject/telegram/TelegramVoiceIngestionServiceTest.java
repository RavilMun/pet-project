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
import ru.ravil.petproject.ai.AiTranscriptionService;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.service.InboxItemService;

class TelegramVoiceIngestionServiceTest {

    private TelegramApiClient telegramApiClient;
    private AiTranscriptionService aiTranscriptionService;
    private InboxItemService inboxItemService;
    private TelegramVoiceIngestionService service;

    @BeforeEach
    void setUp() {
        telegramApiClient = Mockito.mock(TelegramApiClient.class);
        aiTranscriptionService = Mockito.mock(AiTranscriptionService.class);
        inboxItemService = Mockito.mock(InboxItemService.class);
        service = new TelegramVoiceIngestionService(telegramApiClient, aiTranscriptionService, inboxItemService);

        when(telegramApiClient.getFile("voice_1")).thenReturn(new TelegramFile("voice_1", "u", 4096, "voice/v.ogg"));
        when(telegramApiClient.downloadFile("voice/v.ogg")).thenReturn(new byte[]{1, 2, 3});
    }

    @Test
    void savesTranscriptAsRawText() {
        when(aiTranscriptionService.transcribe(any(), eq("voice.ogg")))
                .thenReturn(Optional.of("купить молоко завтра"));

        service.ingest(42L, "voice_1", 7L);

        CreateInboxItemRequest request = captureCreatedRequest();
        Assertions.assertThat(request.rawText()).isEqualTo("купить молоко завтра");
        Assertions.assertThat(request.tags()).containsExactly("voice");
        Assertions.assertThat(request.telegramChatId()).isEqualTo(42L);
        Assertions.assertThat(request.telegramMessageId()).isEqualTo(7L);
    }

    @Test
    void fallsBackToPlaceholderWhenTranscriptionUnavailable() {
        when(aiTranscriptionService.transcribe(any(), any())).thenReturn(Optional.empty());

        service.ingest(42L, "voice_1", 7L);

        Assertions.assertThat(captureCreatedRequest().rawText()).isEqualTo("Голосовое сообщение");
    }

    @Test
    void skipsTranscriptionWhenFileUnavailableButStillCaptures() {
        when(telegramApiClient.getFile("voice_1")).thenReturn(null);

        service.ingest(42L, "voice_1", 7L);

        verify(aiTranscriptionService, never()).transcribe(any(), any());
        Assertions.assertThat(captureCreatedRequest().rawText()).isEqualTo("Голосовое сообщение");
    }

    private CreateInboxItemRequest captureCreatedRequest() {
        ArgumentCaptor<CreateInboxItemRequest> captor = ArgumentCaptor.forClass(CreateInboxItemRequest.class);
        verify(inboxItemService).create(captor.capture(), eq("voice_1"), eq("audio/ogg"));
        return captor.getValue();
    }
}
