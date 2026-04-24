package com.macstab.chaos.jvm.examples.sb3retry;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DownstreamController {

  private final DownstreamClient downstreamClient;

  public DownstreamController(final DownstreamClient downstreamClient) {
    this.downstreamClient = downstreamClient;
  }

  @GetMapping("/fetch")
  public ResponseEntity<String> fetch() {
    return ResponseEntity.ok(downstreamClient.fetch());
  }
}
