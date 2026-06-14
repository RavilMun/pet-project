package ru.ravil.petproject.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ravil.petproject.ai.AiClassificationService;
import ru.ravil.petproject.ai.AiEmbeddingService;
import ru.ravil.petproject.ai.OpenAiClient;
import ru.ravil.petproject.service.NaturalLanguageSearchQueryParser;
import ru.ravil.petproject.telegram.AiTelegramIntentDetector;
import ru.ravil.petproject.telegram.TelegramIntent;
import ru.ravil.petproject.telegram.TelegramIntentType;

@Tag("live-openai")
class LiveOpenAiSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static OpenAiClient openAiClient;

    @BeforeAll
    static void setUp() {
        assumeTrue(LiveOpenAiTestSupport.isEnabled(), "RUN_LIVE_GPT_TESTS is not enabled");
        openAiClient = LiveOpenAiTestSupport.createClient();
    }

    @Test
    void classifyReminderPromptProducesReminderJson() throws Exception {
        AiClassificationService service = new AiClassificationService(provider(), OBJECT_MAPPER);

        var result = service.classify("напомни оплатить интернет 30 июня в 8 утра");

        assertThat(result).isPresent();
        assertThat(result.get().type().name()).isEqualTo("REMINDER");
        assertThat(result.get().actionable()).isTrue();
        assertThat(result.get().title()).isNotBlank();
    }

    @Test
    void embedSemanticQueryProducesVectorOfExpectedSize() {
        AiEmbeddingService service = new AiEmbeddingService(provider());

        var result = service.embed("что нравится Маше?");

        assertThat(result).isPresent();
        List<Double> embedding = parseVector(result.get().pgVector());
        assertThat(embedding).hasSize(1536);
        assertThat(embedding).allMatch(Double::isFinite);
    }

    @Test
    void aiTelegramDetectorRecognizesSearchIntent() {
        AiTelegramIntentDetector detector = new AiTelegramIntentDetector(provider(), OBJECT_MAPPER);

        TelegramIntent intent = detector.detect("а что там было про кухню?");

        assertThat(intent.type()).isEqualTo(TelegramIntentType.SEARCH);
        assertThat(intent.query()).isNotBlank();
    }

    private static org.springframework.beans.factory.ObjectProvider<OpenAiClient> provider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public OpenAiClient getObject(Object... args) {
                return openAiClient;
            }

            @Override
            public OpenAiClient getIfAvailable() {
                return openAiClient;
            }

            @Override
            public OpenAiClient getIfUnique() {
                return openAiClient;
            }

            @Override
            public OpenAiClient getObject() {
                return openAiClient;
            }

            @Override
            public java.util.Iterator<OpenAiClient> iterator() {
                return List.of(openAiClient).iterator();
            }
        };
    }

    private static List<Double> parseVector(String pgVector) {
        String trimmed = pgVector.trim();
        String body = trimmed.substring(1, trimmed.length() - 1);
        if (body.isBlank()) {
            return List.of();
        }

        return java.util.Arrays.stream(body.split(","))
                .map(String::trim)
                .map(Double::valueOf)
                .toList();
    }
}
