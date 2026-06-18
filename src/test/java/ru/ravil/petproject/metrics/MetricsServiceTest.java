package ru.ravil.petproject.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetricsServiceTest {

    @Test
    void countersAccumulate() {
        MetricsService metrics = new MetricsService();
        metrics.increment("search.requested");
        metrics.increment("search.requested");
        metrics.increment("answer.produced");

        MetricsSnapshot snapshot = metrics.snapshot();
        assertThat(snapshot.counters()).containsEntry("search.requested", 2L);
        assertThat(snapshot.counters()).containsEntry("answer.produced", 1L);
    }

    @Test
    void timersTrackCountTotalAndAverage() {
        MetricsService metrics = new MetricsService();
        metrics.recordMillis("ai.classify.ms", 100);
        metrics.recordMillis("ai.classify.ms", 300);

        MetricsSnapshot.TimerView timer = metrics.snapshot().timers().get("ai.classify.ms");
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalMillis()).isEqualTo(400);
        assertThat(timer.avgMillis()).isEqualTo(200.0);
    }

    @Test
    void tokensAccumulateAndIgnoreNonPositive() {
        MetricsService metrics = new MetricsService();
        metrics.addTokens("ai.classify", 120);
        metrics.addTokens("ai.classify", 80);
        metrics.addTokens("ai.classify", 0);
        metrics.addTokens("ai.classify", -5);

        assertThat(metrics.snapshot().tokens()).containsEntry("ai.classify", 200L);
    }

    @Test
    void timeRecordsDurationCountAndReturnsValue() {
        MetricsService metrics = new MetricsService();
        String result = metrics.time("op", () -> "value");

        assertThat(result).isEqualTo("value");
        assertThat(metrics.snapshot().timers()).containsKey("op");
        assertThat(metrics.snapshot().counters()).containsEntry("op.count", 1L);
    }
}
