package com.macstab.chaos.jvm.quarkus.management;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A minimal HTTP/1.1 client over one socket, so a test controls what is sent (headers without the
 * body they declare, a chunked body, several requests on one connection) and when.
 */
final class RawHttp implements AutoCloseable {

  /** A response's status and body. */
  record Response(int status, String body) {}

  private final Socket socket;
  private final InputStream in;
  private final OutputStream out;

  RawHttp(final int port) throws IOException {
    socket = new Socket("localhost", port);
    socket.setSoTimeout(10_000);
    in = socket.getInputStream();
    out = socket.getOutputStream();
  }

  /**
   * The head of a request to {@code target}: its request line, {@code Host} and {@code headers},
   * each line ended by CRLF, and the empty line.
   */
  static String head(final String method, final String target, final String... headers) {
    final StringBuilder head = new StringBuilder(method).append(' ').append(target);
    head.append(" HTTP/1.1\r\nHost: localhost\r\n");
    for (final String header : headers) {
      head.append(header).append("\r\n");
    }
    return head.append("\r\n").toString();
  }

  /** Sends {@code text} as it is (CRLF line ends are the caller's). */
  void send(final String text) throws IOException {
    send(text.getBytes(StandardCharsets.UTF_8));
  }

  void send(final byte[] bytes) throws IOException {
    out.write(bytes);
    out.flush();
  }

  /** Reads one response; its body is read by its {@code Content-Length}. */
  Response read() throws IOException {
    final String statusLine = line();
    final int status = Integer.parseInt(statusLine.split(" ", 3)[1]);
    int length = 0;
    for (String header = line(); !header.isEmpty(); header = line()) {
      final int colon = header.indexOf(':');
      if (header.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("content-length")) {
        length = Integer.parseInt(header.substring(colon + 1).trim());
      }
    }
    return new Response(status, new String(in.readNBytes(length), StandardCharsets.UTF_8));
  }

  private String line() throws IOException {
    final ByteArrayOutputStream line = new ByteArrayOutputStream();
    for (int b = in.read(); b != '\n'; b = in.read()) {
      if (b < 0) {
        throw new IOException("connection closed");
      }
      if (b != '\r') {
        line.write(b);
      }
    }
    return line.toString(StandardCharsets.UTF_8);
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
