package ru.ravil.petproject.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ravil.petproject.metrics.MetricsService;
import ru.ravil.petproject.metrics.MetricsSnapshot;

/**
 * Read-only observability snapshot (Phase 5.2): counters (search/answer), AI-step timers and token
 * totals. Useful even solo to see whether search answers and how much it costs.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping
    public MetricsSnapshot snapshot() {
        return metricsService.snapshot();
    }
}
