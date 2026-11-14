import java.util.concurrent.*;

public class Demo {
  public static void main(String[] args) throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    // submit() alone: the delay holds the submitting thread, not the task.
    long start = System.nanoTime();
    Future<?> task = executor.submit(() -> {});
    long millis = (System.nanoTime() - start) / 1_000_000;
    task.get();
    executor.shutdown();
    System.out.println("submitBlocked=" + (millis >= 200 && millis < 2000));
  }
}
