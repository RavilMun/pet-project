package ru.ravil.petproject.service;

import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Fetches and extracts readable text from a web page (Feature 8.3 — link ingestion). Uses Jsoup to
 * download and strip HTML. Best-effort: any failure (timeout, non-HTML, paywall) yields
 * {@link Optional#empty()} so the capture still succeeds with just the link tag.
 */
@Service
public class LinkContentService {

    private static final Logger log = LoggerFactory.getLogger(LinkContentService.class);
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_TEXT_LENGTH = 8_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; pet-project-secondbrain/1.0; +https://github.com/RavilMun/pet-project)";

    public Optional<ArticleContent> fetch(String url) {
        if (!StringUtils.hasText(url)) {
            return Optional.empty();
        }
        try {
            Document document = Jsoup.connect(url)
                    .timeout(TIMEOUT_MS)
                    .userAgent(USER_AGENT)
                    .followRedirects(true)
                    .get();
            String title = document.title();
            String text = document.body() == null ? "" : document.body().text();
            if (!StringUtils.hasText(text)) {
                return Optional.empty();
            }
            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH);
            }
            return Optional.of(new ArticleContent(title == null ? "" : title.trim(), text.trim()));
        } catch (Exception exception) {
            log.warn("Link fetch failed for {}: {}", url, exception.getMessage());
            return Optional.empty();
        }
    }

    public record ArticleContent(String title, String text) {
    }
}
