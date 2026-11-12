package com.macstab.chaos.jvm.spring.boot.common;

import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.PayloadTooLargeException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Caps the request body of {@code POST /actuator/chaos} on Spring MVC before it is bound: Spring
 * reads the whole body into memory, and the plan reader's 1 MiB cap ({@link
 * ChaosPlanMapper#MAX_JSON_LENGTH}) applies only after that. A body above {@link #MAX_BODY_BYTES}
 * is refused with 413, decided from {@code Content-Length} before anything is read, or while
 * reading a body without one. The body carries the plan as a JSON string, whose escaping at most
 * doubles it, so every plan the reader accepts fits. Other endpoints and controllers are left
 * alone. It is a {@link ControllerAdvice}, so the management context of a separate management port
 * applies it too.
 */
@ControllerAdvice
final class ChaosActuatorBodyLimit extends RequestBodyAdviceAdapter {

  /**
   * The largest body accepted: a plan of the reader's cap, every character escaped, and the key.
   */
  static final int MAX_BODY_BYTES = 2 * ChaosPlanMapper.MAX_JSON_LENGTH + 64;

  private static final String OPERATION_HANDLER =
      "AbstractWebMvcEndpointHandlerMapping$OperationHandler";

  private final Supplier<@Nullable String> chaosPath;

  /**
   * @param chaosPath the endpoint's write path ({@code /actuator/chaos}), or {@code null} when it
   *     is not exposed over the web
   */
  ChaosActuatorBodyLimit(final Supplier<@Nullable String> chaosPath) {
    this.chaosPath = chaosPath;
  }

  @Override
  public boolean supports(
      final MethodParameter methodParameter,
      final Type targetType,
      final Class<? extends HttpMessageConverter<?>> converterType) {
    return methodParameter.getParameterType() == Map.class
        && methodParameter.getDeclaringClass().getName().endsWith(OPERATION_HANDLER);
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      final HttpInputMessage inputMessage,
      final MethodParameter parameter,
      final Type targetType,
      final Class<? extends HttpMessageConverter<?>> converterType)
      throws IOException {
    if (!isChaosRequest()) {
      return inputMessage;
    }
    if (inputMessage.getHeaders().getContentLength() > MAX_BODY_BYTES) {
      throw tooLarge();
    }
    final byte[] body;
    try (InputStream in = inputMessage.getBody()) {
      body = in.readNBytes(MAX_BODY_BYTES + 1);
    }
    if (body.length > MAX_BODY_BYTES) {
      throw tooLarge();
    }
    final HttpHeaders headers = inputMessage.getHeaders();
    return new HttpInputMessage() {
      @Override
      public InputStream getBody() {
        return new ByteArrayInputStream(body);
      }

      @Override
      public HttpHeaders getHeaders() {
        return headers;
      }
    };
  }

  private boolean isChaosRequest() {
    final RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return false;
    }
    final Object pattern =
        attributes.getAttribute(
            HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    final String path = chaosPath.get();
    return pattern != null && path != null && path.equals(pattern.toString());
  }

  private static PayloadTooLargeException tooLarge() {
    return new PayloadTooLargeException(
        new IOException("the /actuator/chaos body is above " + MAX_BODY_BYTES + " bytes"));
  }
}
