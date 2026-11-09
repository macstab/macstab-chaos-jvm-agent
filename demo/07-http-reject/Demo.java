import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;

public class Demo {
  public static void main(String[] args) throws Exception {
    HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    other.createContext("/", exchange -> {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    other.start();
    try (HttpClient client = HttpClient.newHttpClient()) {
      try {
        client.send(HttpRequest.newBuilder(URI.create("http://payments.invalid/charge")).build(),
            HttpResponse.BodyHandlers.discarding());
        System.out.println("payments=ok");
      } catch (java.io.IOException rejected) {
        System.out.println("payments=" + rejected.getMessage());
      }
      // Another host: the scenario's urlPattern does not match it, so the request is sent.
      URI otherUri = URI.create("http://127.0.0.1:" + other.getAddress().getPort() + "/orders");
      HttpResponse<Void> response =
          client.send(HttpRequest.newBuilder(otherUri).build(), HttpResponse.BodyHandlers.discarding());
      System.out.println("other=" + response.statusCode());
    } finally {
      other.stop(0);
    }
  }
}
