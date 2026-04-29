package com.macstab.chaos.jvm.examples.sb3retry;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller that proxies requests to the downstream service. */
@RestController
public class DownstreamController {

  private final DownstreamClient downstreamClient;

  /**
   * Creates a new DownstreamController.
   *
   * @param downstreamClient the downstream HTTP client
   */
  public DownstreamController(final DownstreamClient downstreamClient) {
    this.downstreamClient = downstreamClient;
  }

  /**
   * Fetches a response from the downstream service.
   *
   * @return HTTP 200 with the downstream response body
   */
  @GetMapping("/fetch")
  public ResponseEntity<String> fetch() {
    return ResponseEntity.ok(downstreamClient.fetch());
  }
}
