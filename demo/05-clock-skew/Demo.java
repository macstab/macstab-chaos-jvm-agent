import java.time.*;

public class Demo {
  public static void main(String[] args) {
    // System.currentTimeMillis() is a JIT intrinsic the agent cannot skew: the real time.
    Instant real = Instant.ofEpochMilli(System.currentTimeMillis());
    print("instantNow", real, Instant.now());
    print("clockRead", real, Clock.systemUTC().instant());
    print("localDateTimeNow", real, LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant());
    print("zonedDateTimeNow", real, ZonedDateTime.now().toInstant());
  }

  private static void print(String api, Instant real, Instant read) {
    System.out.println(api + "=" + Duration.between(real, read).toMinutes());
  }
}
