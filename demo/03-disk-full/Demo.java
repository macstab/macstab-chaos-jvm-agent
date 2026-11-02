import java.nio.file.*;

public class Demo {
  public static void main(String[] args) throws Exception {
    // Relative paths: the plan selects "data", whatever directory the demo runs in.
    for (String dir : new String[] {"data", "logs"}) {
      Path file = Files.createDirectories(Path.of(dir)).resolve("f.bin");
      try (var out = Files.newOutputStream(file)) {
        out.write(new byte[64]);
        System.out.println(dir + "=ok");
      } catch (FileSystemException full) {
        System.out.println(dir + "=" + full.getReason());
      }
    }
  }
}
