import java.net.*;
import java.util.*;

public class Demo {
  public static void main(String[] args) throws Exception {
    InetAddress[] answer = InetAddress.getAllByName("localhost");
    System.out.println("localhost=" + Arrays.stream(answer).map(InetAddress::getHostAddress).sorted().toList());
  }
}
