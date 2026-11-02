# 02-trickle-read

Network: every socket read returns at most 16 bytes; the reader must loop.

```bash
java -javaagent:<agent.jar>=configFile=plan.json Demo.java
```

Expected output:

```text
firstRead=16
```
