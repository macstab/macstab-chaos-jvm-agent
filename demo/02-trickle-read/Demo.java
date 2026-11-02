import java.io.*;
import java.net.*;

public class Demo {
  public static void main(String[] args) throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        Socket peer = server.accept()) {
      peer.getOutputStream().write(new byte[1000]);
      peer.getOutputStream().flush();
      InputStream in = client.getInputStream();
      int first = in.read(new byte[1000]);
      System.out.println("firstRead=" + first);
    }
  }
}
