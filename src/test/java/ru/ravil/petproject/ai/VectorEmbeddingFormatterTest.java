package ru.ravil.petproject.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class VectorEmbeddingFormatterTest {

    @Test
    void formatsEmbeddingAsPgVectorLiteral() {
        String vector = VectorEmbeddingFormatter.toPgVector(List.of(0.1, -2.5, 3.0));

        assertThat(vector).isEqualTo("[0.1,-2.5,3]");
    }

    @Test
    void rejectsInvalidEmbeddingValues() {
        assertThatThrownBy(() -> VectorEmbeddingFormatter.toPgVector(List.of(Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
