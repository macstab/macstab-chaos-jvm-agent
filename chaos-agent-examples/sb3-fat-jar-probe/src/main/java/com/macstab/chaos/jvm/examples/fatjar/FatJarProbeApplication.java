package com.macstab.chaos.jvm.examples.fatjar;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Takes a connection from the Hikari pool and makes an OkHttp request to a loopback server, then
 * prints each outcome ({@code ok}/the status code, or the exception class) and exits.
 */
@SpringBootApplication
public class FatJarProbeApplication implements CommandLineRunner {

  private final DataSource dataSource;

  /**
   * Creates the probe.
   *
   * @param dataSource the application's pooled data source
   */
  public FatJarProbeApplication(final DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Runs the probe.
   *
   * @param args ignored
   */
  public static void main(final String[] args) {
    System.exit(SpringApplication.exit(SpringApplication.run(FatJarProbeApplication.class, args)));
  }

  @Override
  public void run(final String... args) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("select 1");
      System.out.println("jdbc=ok");
    } catch (final Exception failed) {
      System.out.println("jdbc=" + failed.getClass().getName());
    }

    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          final byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    final OkHttpClient client = new OkHttpClient();
    try (Response response =
        client
            .newCall(
                new Request.Builder()
                    .url("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                    .build())
            .execute()) {
      System.out.println("okhttp=" + response.code());
    } catch (final Exception failed) {
      System.out.println("okhttp=" + failed.getClass().getName());
    } finally {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
      server.stop(0);
    }
  }
}
