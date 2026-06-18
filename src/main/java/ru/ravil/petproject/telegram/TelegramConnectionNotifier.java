package ru.ravil.petproject.telegram;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;
import ru.ravil.petproject.dto.MemoryUnitResponse;
import ru.ravil.petproject.service.InboxItemProcessedEvent;
import ru.ravil.petproject.service.MemoryConnectionService;

/**
 * "Connections on capture" (Feature): after a Telegram capture is processed and embedded, surfaces a
 * short list of semantically related past memories ("you noted something similar before"). Runs only
 * after the processing transaction commits (so the new unit's embedding is visible) and only when the
 * bot is enabled. Failures are swallowed — this is a non-critical enhancement.
 */
@Component
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true")
public class TelegramConnectionNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramConnectionNotifier.class);
    private static final int MAX_RELATED = 3;
    // Telegram allows only a fixed reaction set — these stand in for ✅/❌.
    private static final String DONE_REACTION = "👍";
    private static final String FAILED_REACTION = "👎";

    private final MemoryConnectionService connectionService;
    private final TelegramApiClient telegramApiClient;

    public TelegramConnectionNotifier(MemoryConnectionService connectionService, TelegramApiClient telegramApiClient) {
        this.connectionService = connectionService;
        this.telegramApiClient = telegramApiClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProcessed(InboxItemProcessedEvent event) {
        if (event.telegramChatId() == null) {
            return;
        }
        // Capture-feedback reaction on the user's original message (replaces the 👀 set at capture time).
        if (event.telegramMessageId() != null) {
            try {
                telegramApiClient.setMessageReaction(event.telegramChatId(), event.telegramMessageId(),
                        event.success() ? DONE_REACTION : FAILED_REACTION);
            } catch (RuntimeException exception) {
                log.warn("Reaction update failed for item {}: {}", event.itemId(), exception.getMessage());
            }
        }
        if (!event.success()) {
            return;
        }
        try {
            List<MemoryUnitResponse> related = connectionService.findRelatedToItem(event.itemId(), MAX_RELATED);
            if (!related.isEmpty()) {
                telegramApiClient.sendMessage(event.telegramChatId(), format(related));
            }
        } catch (RuntimeException exception) {
            log.warn("Connections notification failed for item {}: {}", event.itemId(), exception.getMessage());
        }
    }

    private String format(List<MemoryUnitResponse> related) {
        StringBuilder builder = new StringBuilder("🔗 Похоже на прошлое:");
        for (int i = 0; i < related.size(); i++) {
            builder.append("\n").append(i + 1).append(". ").append(title(related.get(i)));
        }
        return builder.toString();
    }

    private String title(MemoryUnitResponse unit) {
        String text = StringUtils.hasText(unit.title()) ? unit.title() : unit.sourceRawText();
        if (!StringUtils.hasText(text)) {
            return "(без названия)";
        }
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }
}
