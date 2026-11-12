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
      byte[] buffer = new byte[1000];
      int first = -1;
      int max = 0;
      int total = 0;
      // A trickled reader must loop: every read returns at most 16 bytes, not only the first.
      while (total < 1000) {
        int read = in.read(buffer, 0, 1000 - total);
        if (read < 0) {
          break;
        }
        if (first < 0) {
          first = read;
        }
        max = Math.max(max, read);
        total += read;
      }
      System.out.println("firstRead=" + first + " maxRead=" + max + " total=" + total);
    }
  }
}
