package ru.ravil.petproject.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

public final class VectorEmbeddingFormatter {

    private VectorEmbeddingFormatter() {
    }

    public static String toPgVector(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            throw new IllegalArgumentException("embedding must not be empty");
        }

        return embedding.stream()
                .map(VectorEmbeddingFormatter::format)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String format(Double value) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException("embedding contains non-finite value");
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
