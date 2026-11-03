# 06-process-limit

Process: starting a child process fails the way the JDK reports a missing program (ENOENT has the same number on Linux and macOS).

```bash
java -javaagent:<agent.jar>=configFile=plan.json Demo.java
```

Expected output:

```text
start=Cannot run program "<java>": error=2, No such file or directory
```
