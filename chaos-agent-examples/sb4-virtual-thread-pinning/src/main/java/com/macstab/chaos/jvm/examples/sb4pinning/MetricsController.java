package com.macstab.chaos.jvm.examples.sb4pinning;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/metrics")
public class MetricsController {

  private final MetricsAggregator aggregator;

  public MetricsController(final MetricsAggregator aggregator) {
    this.aggregator = aggregator;
  }

  @PostMapping
  public ResponseEntity<Void> record(@RequestParam final String name) {
    aggregator.record(name);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/snapshot")
  public ResponseEntity<Map<String, Long>> snapshot() {
    return ResponseEntity.ok(aggregator.snapshot());
  }
}
