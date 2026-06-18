package ru.ravil.petproject.metrics;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Lightweight in-memory metrics (Phase 5.2) — dependency-free, thread-safe. Records named counters,
 * timers (count + total millis → average) and token totals per AI operation, exposed as a snapshot
 * via {@code GET /api/metrics}. Intentionally simple (no Micrometer/Actuator): personal-scale
 * observability for "is search answering?", AI-step latency and token spend.
 */
@Service
public class MetricsService {

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, TimerStat> timers = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> tokens = new ConcurrentHashMap<>();

    public void increment(String name) {
        counters.computeIfAbsent(name, key -> new LongAdder()).increment();
    }

    public void recordMillis(String name, long millis) {
        timers.computeIfAbsent(name, key -> new TimerStat()).record(millis);
    }

    public void addTokens(String operation, long count) {
        if (count <= 0) {
            return;
        }
        tokens.computeIfAbsent(operation, key -> new LongAdder()).add(count);
    }

    /** Times {@code operation}, recording both its duration (millis) and an invocation count. */
    public <T> T time(String name, Supplier<T> operation) {
        long startNanos = System.nanoTime();
        try {
            return operation.get();
        } finally {
            recordMillis(name, (System.nanoTime() - startNanos) / 1_000_000L);
            increment(name + ".count");
        }
    }

    public MetricsSnapshot snapshot() {
        Map<String, Long> counterView = new TreeMap<>();
        counters.forEach((name, adder) -> counterView.put(name, adder.sum()));

        Map<String, MetricsSnapshot.TimerView> timerView = new TreeMap<>();
        timers.forEach((name, stat) -> timerView.put(name, stat.view()));

        Map<String, Long> tokenView = new TreeMap<>();
        tokens.forEach((name, adder) -> tokenView.put(name, adder.sum()));

        return new MetricsSnapshot(counterView, timerView, tokenView);
    }

    private static final class TimerStat {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalMillis = new LongAdder();

        void record(long millis) {
            count.increment();
            totalMillis.add(Math.max(0, millis));
        }

        MetricsSnapshot.TimerView view() {
            long c = count.sum();
            long total = totalMillis.sum();
            double avg = c == 0 ? 0.0 : (double) total / c;
            return new MetricsSnapshot.TimerView(c, total, avg);
        }
    }
}
