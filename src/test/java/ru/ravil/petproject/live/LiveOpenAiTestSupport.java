package ru.ravil.petproject.live;

import org.springframework.util.StringUtils;
import ru.ravil.petproject.ai.OpenAiClient;

final class LiveOpenAiTestSupport {

    private LiveOpenAiTestSupport() {
    }

    static boolean isEnabled() {
        return "true".equalsIgnoreCase(System.getenv("RUN_LIVE_GPT_TESTS"));
    }

    static OpenAiClient createClient() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String model = valueOrDefault(System.getenv("OPENAI_MODEL"), "gpt-4.1-mini");
        String embeddingModel = valueOrDefault(System.getenv("OPENAI_EMBEDDING_MODEL"), "text-embedding-3-small");

        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("OPENAI_API_KEY must be set when RUN_LIVE_GPT_TESTS=true");
        }

        return new OpenAiClient(apiKey, model, embeddingModel);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
