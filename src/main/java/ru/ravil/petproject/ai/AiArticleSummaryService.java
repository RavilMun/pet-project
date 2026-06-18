package ru.ravil.petproject.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Summarizes fetched article text via OpenAI (Feature 8.3) into a short title + summary, so a saved
 * link becomes searchable by its actual content. ObjectProvider-gated — degrades when OpenAI is off.
 */
@Service
public class AiArticleSummaryService {

    private static final Logger log = LoggerFactory.getLogger(AiArticleSummaryService.class);

    private static final String SYSTEM_PROMPT = """
            You summarize a web article for a personal memory assistant.
            Return only valid JSON: {"title": "...", "summary": "..."}.
            title: a short headline (<= 100 chars). summary: 1-3 sentences capturing the key point.
            Answer in the same language as the article (Russian if mixed). Do not invent facts beyond the text.
            """;

    private final ObjectProvider<OpenAiClient> openAiClientProvider;
    private final ObjectMapper objectMapper;

    public AiArticleSummaryService(ObjectProvider<OpenAiClient> openAiClientProvider, ObjectMapper objectMapper) {
        this.openAiClientProvider = openAiClientProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return openAiClientProvider.getIfAvailable() != null;
    }

    public Optional<ArticleSummary> summarize(String articleText) {
        OpenAiClient openAiClient = openAiClientProvider.getIfAvailable();
        if (openAiClient == null || !StringUtils.hasText(articleText)) {
            return Optional.empty();
        }
        try {
            String response = openAiClient.classify(SYSTEM_PROMPT, "Article:\n" + articleText);
            JsonNode root = objectMapper.readTree(response);
            String title = root.path("title").asText("").trim();
            String summary = root.path("summary").asText("").trim();
            if (!StringUtils.hasText(summary)) {
                return Optional.empty();
            }
            return Optional.of(new ArticleSummary(title, summary));
        } catch (Exception exception) {
            log.warn("Article summary failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    public record ArticleSummary(String title, String summary) {
    }
}
