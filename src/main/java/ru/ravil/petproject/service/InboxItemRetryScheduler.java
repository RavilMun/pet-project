package ru.ravil.petproject.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically retries inbox items whose AI processing failed ({@code FAILED_AI}) and whose
 * backoff window has elapsed. Off-by-default-safe for tests: failed items carry a future
 * {@code next_attempt_at}, so a sweep during a short test run finds nothing; can also be
 * disabled entirely via {@code inbox.processing.retry.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "inbox.processing.retry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InboxItemRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(InboxItemRetryScheduler.class);
    private static final int BATCH_LIMIT = 50;

    private final InboxItemService inboxItemService;

    public InboxItemRetryScheduler(InboxItemService inboxItemService) {
        this.inboxItemService = inboxItemService;
    }

    @Scheduled(
            initialDelayString = "${inbox.processing.retry.initial-delay-ms:60000}",
            fixedDelayString = "${inbox.processing.retry.interval-ms:60000}"
    )
    void retryDueItems() {
        try {
            int processed = inboxItemService.reprocessDue(BATCH_LIMIT);
            if (processed > 0) {
                log.info("Retry sweep reprocessed {} inbox item(s)", processed);
            }
        } catch (RuntimeException exception) {
            log.warn("Inbox retry sweep failed: {}", exception.getMessage());
        }
    }
}
