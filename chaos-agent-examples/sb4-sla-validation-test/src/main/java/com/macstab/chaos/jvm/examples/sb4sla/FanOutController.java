package com.macstab.chaos.jvm.examples.sb4sla;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FanOutController {

  private final FanOutService fanOutService;

  public FanOutController(final FanOutService fanOutService) {
    this.fanOutService = fanOutService;
  }

  @GetMapping("/fanout")
  public ResponseEntity<FanOutResult> fanout() {
    return ResponseEntity.ok(fanOutService.call());
  }
}
