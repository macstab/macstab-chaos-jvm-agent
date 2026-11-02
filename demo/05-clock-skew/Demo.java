import java.time.*;

public class Demo {
  public static void main(String[] args) {
    long skew = Duration.between(Instant.ofEpochMilli(System.currentTimeMillis()), Instant.now()).toMinutes();
    System.out.println("skewMinutes=" + skew);
  }
}
