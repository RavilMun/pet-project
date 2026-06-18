package ru.ravil.petproject.telegram;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.AiTranscriptionService;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.service.InboxItemService;

/**
 * Telegram voice MVP (no external binary storage): downloads the OGG/Opus voice message by
 * {@code file_id}, transcribes it via {@link AiTranscriptionService} (Whisper), and saves the
 * transcript through the normal ingestion pipeline ({@link InboxItemService#create}) so it becomes
 * searchable/answerable as text. The original {@code file_id} is stored (media reference) but the
 * audio is not re-sent on retrieval — the transcript carries the information.
 *
 * <p>Runs asynchronously: the bot acks instantly and the (slow) download + transcription happen in
 * the background, mirroring {@link TelegramImageIngestionService}.
 */
@Service
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
public class TelegramVoiceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TelegramVoiceIngestionService.class);
    private static final String VOICE_MIME_TYPE = "audio/ogg";
    private static final String VOICE_TAG = "voice";

    private final TelegramApiClient telegramApiClient;
    private final AiTranscriptionService aiTranscriptionService;
    private final InboxItemService inboxItemService;

    public TelegramVoiceIngestionService(
            TelegramApiClient telegramApiClient,
            AiTranscriptionService aiTranscriptionService,
            InboxItemService inboxItemService
    ) {
        this.telegramApiClient = telegramApiClient;
        this.aiTranscriptionService = aiTranscriptionService;
        this.inboxItemService = inboxItemService;
    }

    @Async
    public void ingest(long chatId, String fileId, Long messageId) {
        try {
            String transcript = transcribe(fileId);
            String rawText = StringUtils.hasText(transcript) ? transcript : "Голосовое сообщение";
            inboxItemService.create(
                    new CreateInboxItemRequest(
                            rawText,
                            null,
                            null,
                            null,
                            InboxItemSource.TELEGRAM,
                            null,
                            null,
                            chatId,
                            messageId,
                            Set.of(VOICE_TAG)
                    ),
                    fileId,
                    VOICE_MIME_TYPE
            );
        } catch (RuntimeException exception) {
            log.warn("Telegram voice ingestion failed for chat {} file {}: {}", chatId, fileId, exception.getMessage());
        }
    }

    private String transcribe(String fileId) {
        TelegramFile file = telegramApiClient.getFile(fileId);
        if (file == null || !StringUtils.hasText(file.filePath())) {
            return null;
        }
        byte[] bytes = telegramApiClient.downloadFile(file.filePath());
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return aiTranscriptionService.transcribe(bytes, "voice.ogg").orElse(null);
    }
}
