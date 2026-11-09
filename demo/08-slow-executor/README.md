# 08-slow-executor

Concurrency: each `submit()` holds the submitting thread for 200 ms before the task is handed to the executor.

```bash
java -javaagent:<agent.jar>=configFile=plan.json Demo.java
```

Expected output (`submit()` returns after at least 200 ms and well under 2 s):

```text
submitBlocked=true
```
