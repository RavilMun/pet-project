package ru.ravil.petproject.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenAiClientTest {

    @Test
    void retriesOnRateLimitAndServerErrors() {
        assertThat(OpenAiClient.isRetryableStatus(429)).isTrue();
        assertThat(OpenAiClient.isRetryableStatus(500)).isTrue();
        assertThat(OpenAiClient.isRetryableStatus(503)).isTrue();
    }

    @Test
    void doesNotRetryOnClientErrorsOrSuccess() {
        assertThat(OpenAiClient.isRetryableStatus(200)).isFalse();
        assertThat(OpenAiClient.isRetryableStatus(400)).isFalse();
        assertThat(OpenAiClient.isRetryableStatus(401)).isFalse();
        assertThat(OpenAiClient.isRetryableStatus(404)).isFalse();
    }

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        long first = OpenAiClient.backoffMillis(1);
        long second = OpenAiClient.backoffMillis(2);
        long large = OpenAiClient.backoffMillis(10);

        assertThat(first).isBetween(500L, 750L);
        assertThat(second).isBetween(1_000L, 1_250L);
        // Capped at MAX_BACKOFF_MS (8s) plus jitter (<250ms).
        assertThat(large).isBetween(8_000L, 8_250L);
    }
}
