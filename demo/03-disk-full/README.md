# 03-disk-full

File: writes under one directory fail with ENOSPC; elsewhere they succeed.

```bash
java -javaagent:<agent.jar>=configFile=plan.json Demo.java
```

Expected output:

```text
data=No space left on device
logs=ok
```
