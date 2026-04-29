package com.macstab.chaos.jvm.examples.sb4sla;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller that triggers a fan-out request across multiple downstream services. */
@RestController
public class FanOutController {

  private final FanOutService fanOutService;

  /**
   * Creates a new FanOutController.
   *
   * @param fanOutService the fan-out service
   */
  public FanOutController(final FanOutService fanOutService) {
    this.fanOutService = fanOutService;
  }

  /**
   * Executes a fan-out call to all downstream services.
   *
   * @return HTTP 200 with the aggregated fan-out result
   */
  @GetMapping("/fanout")
  public ResponseEntity<FanOutResult> fanout() {
    return ResponseEntity.ok(fanOutService.call());
  }
}
