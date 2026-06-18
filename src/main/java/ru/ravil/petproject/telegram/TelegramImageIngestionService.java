package ru.ravil.petproject.telegram;

import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.AiVisionService;
import ru.ravil.petproject.domain.InboxItemSource;
import ru.ravil.petproject.dto.CreateInboxItemRequest;
import ru.ravil.petproject.service.InboxItemService;

/**
 * Telegram image MVP (no external binary storage): downloads the photo by {@code file_id}, turns it
 * into a vision description + OCR text via {@link AiVisionService}, and saves it through the normal
 * ingestion pipeline ({@link InboxItemService#create}) so it becomes searchable/answerable as text.
 * The original {@code file_id} is stored so the photo itself can be re-sent on retrieval.
 *
 * <p>Runs asynchronously: the bot acks instantly and the (slow) download + vision call happen in the
 * background, mirroring the degraded-save capture flow for text.
 */
@Service
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
public class TelegramImageIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TelegramImageIngestionService.class);
    // Telegram delivers photos as JPEG regardless of the original upload format.
    private static final String IMAGE_MIME_TYPE = "image/jpeg";
    private static final String IMAGE_TAG = "image";

    private final TelegramApiClient telegramApiClient;
    private final AiVisionService aiVisionService;
    private final InboxItemService inboxItemService;

    public TelegramImageIngestionService(
            TelegramApiClient telegramApiClient,
            AiVisionService aiVisionService,
            InboxItemService inboxItemService
    ) {
        this.telegramApiClient = telegramApiClient;
        this.aiVisionService = aiVisionService;
        this.inboxItemService = inboxItemService;
    }

    @Async
    public void ingest(long chatId, String fileId, String caption, Long messageId) {
        try {
            String description = describeImage(fileId);
            String rawText = buildRawText(caption, description);
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
                            Set.of(IMAGE_TAG)
                    ),
                    fileId,
                    IMAGE_MIME_TYPE
            );
        } catch (RuntimeException exception) {
            log.warn("Telegram image ingestion failed for chat {} file {}: {}", chatId, fileId, exception.getMessage());
        }
    }

    private String describeImage(String fileId) {
        TelegramFile file = telegramApiClient.getFile(fileId);
        if (file == null || !StringUtils.hasText(file.filePath())) {
            return null;
        }
        byte[] bytes = telegramApiClient.downloadFile(file.filePath());
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);
        return aiVisionService.describe(base64, IMAGE_MIME_TYPE).orElse(null);
    }

    /**
     * raw_text is NOT NULL: prefer caption + description, fall back to either, and finally to a
     * placeholder so a caption-less photo with vision disabled is still captured (and re-sendable).
     */
    private String buildRawText(String caption, String description) {
        boolean hasCaption = StringUtils.hasText(caption);
        boolean hasDescription = StringUtils.hasText(description);
        if (hasCaption && hasDescription) {
            return caption.trim() + "\n\n" + description.trim();
        }
        if (hasCaption) {
            return caption.trim();
        }
        if (hasDescription) {
            return description.trim();
        }
        return "Изображение";
    }
}
