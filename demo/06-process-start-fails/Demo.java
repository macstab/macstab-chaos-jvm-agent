public class Demo {
  public static void main(String[] args) throws Exception {
    String java = System.getProperty("java.home") + "/bin/java";
    try {
      new ProcessBuilder(java, "-version").start().waitFor();
      System.out.println("start=ok");
    } catch (java.io.IOException failed) {
      System.out.println("start=" + failed.getMessage().replace(java, "<java>"));
    }
  }
}
