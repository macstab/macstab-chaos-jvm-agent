import java.net.*;
import java.net.http.*;

public class Demo {
  public static void main(String[] args) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      client.send(HttpRequest.newBuilder(URI.create("http://payments.invalid/charge")).build(),
          HttpResponse.BodyHandlers.discarding());
      System.out.println("payments=ok");
    } catch (java.io.IOException rejected) {
      System.out.println("payments=" + rejected.getMessage());
    }
  }
}
