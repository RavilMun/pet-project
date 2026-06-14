package ru.ravil.petproject.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ru.ravil.petproject.domain.MemorySlotRole;
import ru.ravil.petproject.domain.MemorySlotValueType;
import ru.ravil.petproject.domain.MemoryUnitType;

class AiMemoryUnitExtractionServiceTest {

    private final ObjectProvider<OpenAiClient> openAiClientProvider = org.mockito.Mockito.mock(ObjectProvider.class);
    private final OpenAiClient openAiClient = org.mockito.Mockito.mock(OpenAiClient.class);
    private final AiMemoryUnitExtractionService service = new AiMemoryUnitExtractionService(
            openAiClientProvider,
            new ObjectMapper()
    );

    @Test
    void extractParsesMemoryUnitsWithSlots() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "units": [
                    {
                      "type": "EVENT",
                      "title": "Покупка кабеля в DNS",
                      "summary": "Пользователь купил USB-C кабель в магазине DNS.",
                      "tags": ["покупка", "dns"],
                      "actionable": false,
                      "confidence": 0.92,
                      "sourceQuote": "Купил USB-C кабель в магазине DNS",
                      "slots": [
                        {
                          "role": "OBJECT",
                          "value": "USB-C кабель",
                          "normalizedValue": "usb-c кабель",
                          "valueType": "TEXT",
                          "confidence": 0.95
                        },
                        {
                          "role": "PLACE",
                          "value": "магазин DNS",
                          "normalizedValue": "dns",
                          "valueType": "TEXT",
                          "confidence": 0.93
                        }
                      ],
                      "metadata": {"source": "test"}
                    }
                  ]
                }
                """);

        Optional<AiMemoryUnitExtractionResult> result = service.extract("Купил USB-C кабель в магазине DNS");

        assertThat(result).isPresent();
        AiMemoryUnitResult unit = result.get().units().getFirst();
        assertThat(unit.type()).isEqualTo(MemoryUnitType.EVENT);
        assertThat(unit.title()).isEqualTo("Покупка кабеля в DNS");
        assertThat(unit.slots()).hasSize(2);
        assertThat(unit.slots().getFirst().role()).isEqualTo(MemorySlotRole.OBJECT);
        assertThat(unit.slots().getFirst().normalizedValue()).isEqualTo("usb-c кабель");
        assertThat(unit.slots().getFirst().valueType()).isEqualTo(MemorySlotValueType.TEXT);
    }

    @Test
    void extractKeepsBackwardCompatibilityForUnitsWithoutSlots() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "units": [
                    {
                      "type": "NOTE",
                      "title": "Заметка про проект",
                      "summary": "Пользователь записал заметку про проект.",
                      "tags": ["проект"],
                      "actionable": false,
                      "confidence": 0.8,
                      "sourceQuote": "заметка про проект",
                      "metadata": {}
                    }
                  ]
                }
                """);

        Optional<AiMemoryUnitExtractionResult> result = service.extract("заметка про проект");

        assertThat(result).isPresent();
        assertThat(result.get().units()).hasSize(1);
        assertThat(result.get().units().getFirst().slots()).isEmpty();
    }

    @Test
    void extractNormalizesTagsAndLimitsSlots() {
        when(openAiClientProvider.getIfAvailable()).thenReturn(openAiClient);
        when(openAiClient.classify(anyString(), anyString())).thenReturn("""
                {
                  "units": [
                    {
                      "type": "LEARNING",
                      "title": "Embeddings лучше сочетать с full-text search",
                      "summary": "Пользователь сделал вывод, что embeddings лучше использовать вместе с full-text search.",
                      "tags": [" PGVector ", "Embeddings", "Full-Text   Search", "PostgreSQL", "Поиск", "Ёмбеддинги", "ai", "database", "extra"],
                      "actionable": false,
                      "confidence": 0.9,
                      "sourceQuote": "embeddings лучше использовать вместе с full-text search",
                      "slots": [
                        {"role":"TOPIC","value":"pgvector","normalizedValue":"pgvector","valueType":"TEXT","confidence":1},
                        {"role":"OBJECT","value":"embeddings","normalizedValue":"embeddings","valueType":"TEXT","confidence":1},
                        {"role":"OBJECT","value":"full-text search","normalizedValue":"full-text search","valueType":"TEXT","confidence":1},
                        {"role":"TIME","value":"вчера вечером","normalizedValue":"вчера вечером","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"1","normalizedValue":"1","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"2","normalizedValue":"2","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"3","normalizedValue":"3","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"4","normalizedValue":"4","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"5","normalizedValue":"5","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"6","normalizedValue":"6","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"7","normalizedValue":"7","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"8","normalizedValue":"8","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"9","normalizedValue":"9","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"10","normalizedValue":"10","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"11","normalizedValue":"11","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"12","normalizedValue":"12","valueType":"TEXT","confidence":1},
                        {"role":"ATTRIBUTE","value":"13","normalizedValue":"13","valueType":"TEXT","confidence":1}
                      ],
                      "metadata": {}
                    }
                  ]
                }
                """);

        Optional<AiMemoryUnitExtractionResult> result = service.extract("pgvector note");

        assertThat(result).isPresent();
        AiMemoryUnitResult unit = result.get().units().getFirst();
        assertThat(unit.tags()).containsExactly(
                "pgvector",
                "embeddings",
                "full-text search",
                "postgresql",
                "поиск",
                "ембеддинги",
                "ai",
                "database"
        );
        assertThat(unit.slots()).hasSize(16);
    }
}
