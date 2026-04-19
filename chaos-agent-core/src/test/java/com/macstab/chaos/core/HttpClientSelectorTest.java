package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.NamePattern;
import com.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HttpClientSelector")
class HttpClientSelectorTest {

  private final FeatureSet featureSet = new FeatureSet();

  @Nested
  @DisplayName("URL pattern matching")
  class UrlPatternMatching {

    @Test
    @DisplayName("glob URL pattern matches concrete URL")
    void globUrlPatternMatchesUrl() {
      ChaosSelector selector =
          ChaosSelector.httpClient(
              Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.glob("https://*.example.com/*"));
      InvocationContext context =
          new InvocationContext(
              OperationType.HTTP_CLIENT_SEND,
              "http.client",
              null,
              "https://api.example.com/users",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("glob URL pattern rejects non-matching URL")
    void globUrlPatternRejectsUrl() {
      ChaosSelector selector =
          ChaosSelector.httpClient(
              Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.glob("https://*.example.com/*"));
      InvocationContext context =
          new InvocationContext(
              OperationType.HTTP_CLIENT_SEND,
              "http.client",
              null,
              "https://api.other.com/users",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }

    @Test
    @DisplayName("null urlPattern factory defaults to any()")
    void nullUrlPatternMatchesAny() {
      ChaosSelector.HttpClientSelector selector =
          new ChaosSelector.HttpClientSelector(Set.of(OperationType.HTTP_CLIENT_SEND), null);
      assertThat(selector.urlPattern().mode()).isEqualTo(NamePattern.MatchMode.ANY);
    }

    @Test
    @DisplayName("any() URL pattern matches any URL")
    void anyUrlPatternMatchesAnyUrl() {
      ChaosSelector selector = ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND));
      InvocationContext context =
          new InvocationContext(
              OperationType.HTTP_CLIENT_SEND,
              "http.client",
              null,
              "https://anywhere.internal/path",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("any() URL pattern matches when url is null")
    void anyUrlPatternMatchesNullUrl() {
      ChaosSelector selector = ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND));
      InvocationContext context =
          new InvocationContext(
              OperationType.HTTP_CLIENT_SEND, "http.client", null, null, false, null, null, null);
      assertThat(SelectorMatcher.matches(selector, context)).isTrue();
    }

    @Test
    @DisplayName("wrong operation type does not match")
    void wrongOperationDoesNotMatch() {
      ChaosSelector selector = ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND));
      InvocationContext context =
          new InvocationContext(
              OperationType.HTTP_CLIENT_SEND_ASYNC,
              "http.client",
              null,
              "https://api.example.com/",
              false,
              null,
              null,
              null);
      assertThat(SelectorMatcher.matches(selector, context)).isFalse();
    }
  }

  @Nested
  @DisplayName("CompatibilityValidator")
  class Validator {

    @Test
    @DisplayName("HTTP_CLIENT_SEND with HttpClientSelector is valid")
    void httpClientSendIsValid() {
      ChaosScenario scenario =
          ChaosScenario.builder("http-ok")
              .selector(ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)))
              .effect(ChaosEffect.delay(Duration.ofMillis(10)))
              .activationPolicy(ActivationPolicy.always())
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("HTTP_CLIENT_SEND_ASYNC with HttpClientSelector is valid")
    void httpClientSendAsyncIsValid() {
      ChaosScenario scenario =
          ChaosScenario.builder("http-async-ok")
              .selector(ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC)))
              .effect(ChaosEffect.delay(Duration.ofMillis(10)))
              .activationPolicy(ActivationPolicy.always())
              .build();
      assertThatCode(() -> CompatibilityValidator.validate(scenario, featureSet))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("non-HTTP operation in HttpClientSelector is rejected by the selector constructor")
    void nonHttpOperationThrows() {
      // The HttpClientSelector canonical constructor enforces its operation set — the invalid op
      // cannot reach CompatibilityValidator.
      assertThatThrownBy(
              () ->
                  new ChaosSelector.HttpClientSelector(
                      Set.of(OperationType.EXECUTOR_SUBMIT), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("HttpClientSelector")
          .hasMessageContaining("EXECUTOR_SUBMIT");
    }

    @Test
    @DisplayName("HTTP_CLIENT_SEND with non-HttpClient selector is rejected at construction")
    void httpOpWithWrongSelectorThrows() {
      // Same rationale as above, but checked from the other direction: NetworkSelector does not
      // accept HTTP_CLIENT_SEND at construction time.
      assertThatThrownBy(
              () ->
                  new ChaosSelector.NetworkSelector(
                      Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.any()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("NetworkSelector")
          .hasMessageContaining("HTTP_CLIENT_SEND");
    }
  }

  @Nested
  @DisplayName("Factory")
  class Factory {

    @Test
    @DisplayName("empty operations throws IllegalArgumentException")
    void emptyOperationsThrows() {
      assertThatThrownBy(() -> ChaosSelector.httpClient(Set.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
