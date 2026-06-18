package ru.ravil.petproject.metrics;

import java.util.Map;

/**
 * Point-in-time view of {@link MetricsService}: named counters, timers (count/total/avg millis) and
 * per-operation token totals. Returned by {@code GET /api/metrics}.
 */
public record MetricsSnapshot(
        Map<String, Long> counters,
        Map<String, TimerView> timers,
        Map<String, Long> tokens
) {

    public record TimerView(long count, long totalMillis, double avgMillis) {
    }
}
