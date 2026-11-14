import java.net.*;

public class Demo {
  public static void main(String[] args) throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), 2000);
      System.out.println("connect=ok");
    } catch (ConnectException refused) {
      System.out.println("connect=" + refused.getMessage());
    }
  }
}
