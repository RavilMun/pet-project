package ru.ravil.petproject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ru.ravil.petproject.ai.OpenAiClient;
import ru.ravil.petproject.domain.MemorySlotRole;
import ru.ravil.petproject.domain.MemorySlotValueType;
import ru.ravil.petproject.domain.MemoryUnitType;
import ru.ravil.petproject.dto.MemorySlotResponse;
import ru.ravil.petproject.dto.MemoryUnitResponse;

class MemoryAnswerServiceTest {

    private final ObjectProvider<OpenAiClient> openAiClientProvider = org.mockito.Mockito.mock(ObjectProvider.class);
    private final OpenAiClient openAiClient = org.mockito.Mockito.mock(OpenAiClient.class);
    private MemoryAnswerService service;

    @BeforeEach
    void setUp() {
        service = new MemoryAnswerService(openAiClientProvider, new ObjectMapper());
    }

    @Test
    void answerUsesOpenAiToGenerateGroundedAnswerFromMemoryUnitsAndSlots() {
        MemoryUnitResponse cable = response(
                "Купил USB-C кабель и зарядку в магазине DNS",
                "Куплен USB-C кабель и зарядка в магазине DNS",
                Set.of(
                        slot(MemorySlotRole.OBJECT, "USB-C кабель", "usb-c кабель"),
                        slot(MemorySlotRole.PLACE, "магазин DNS", "dns")
                )
        );
        MemoryUnitResponse noise = response("Купить лампочки E27", "Купить лампочки E27", Set.of());
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), contains("USB-C кабель"))).thenReturn("""
                {
                  "answer": "USB-C кабель куплен в магазине DNS, по записи от 2026-06-14.",
                  "confidence": 0.86,
                  "sourceIndexes": [1]
                }
                """);

        Optional<MemoryAnswer> answer = service.answer("где я купил кабель?", List.of(cable, noise));

        assertThat(answer).isPresent();
        assertThat(answer.get().text()).isEqualTo("USB-C кабель куплен в магазине DNS, по записи от 2026-06-14.");
        assertThat(answer.get().confidence()).isEqualTo(0.86);
        assertThat(answer.get().sources()).containsExactly(cable);
    }

    @Test
    void answerDoesNotCallOpenAiForPlainSearchCommand() {
        Optional<MemoryAnswer> answer = service.answer("найди кабель", List.of(
                response("Купил USB-C кабель", "Куплен USB-C кабель", Set.of())
        ));

        assertThat(answer).isEmpty();
        verify(openAiClientProvider, never()).getIfAvailable();
    }

    @Test
    void answerReturnsEmptyWhenOpenAiIsUnavailable() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(null);

        Optional<MemoryAnswer> answer = service.answer("где я купил кабель?", List.of(
                response("Купил USB-C кабель", "Куплен USB-C кабель", Set.of())
        ));

        assertThat(answer).isEmpty();
    }

    @Test
    void answerReturnsEmptyForLowConfidenceResponse() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "answer": "Возможно, в DNS.",
                  "confidence": 0.3,
                  "sourceIndexes": [1]
                }
                """);

        Optional<MemoryAnswer> answer = service.answer("где я купил кабель?", List.of(
                response("Купил USB-C кабель", "Куплен USB-C кабель", Set.of())
        ));

        assertThat(answer).isEmpty();
    }

    private static MemoryUnitResponse response(String rawText, String title, Set<MemorySlotResponse> slots) {
        OffsetDateTime dateTime = OffsetDateTime.parse("2026-06-14T12:00:00Z");
        return new MemoryUnitResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                rawText,
                title,
                rawText,
                MemoryUnitType.NOTE,
                Set.of(),
                slots,
                false,
                1.0,
                rawText,
                null,
                null,
                dateTime,
                dateTime,
                dateTime
        );
    }

    private static MemorySlotResponse slot(MemorySlotRole role, String value, String normalizedValue) {
        return new MemorySlotResponse(role, value, normalizedValue, MemorySlotValueType.TEXT, 1.0);
    }
}
