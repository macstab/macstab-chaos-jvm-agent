import java.util.concurrent.*;

public class Demo {
  public static void main(String[] args) throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    long start = System.nanoTime();
    executor.submit(() -> {}).get();
    long millis = (System.nanoTime() - start) / 1_000_000;
    executor.shutdown();
    System.out.println("delayed=" + (millis >= 200));
  }
}
