# 02-trickle-read

Network: every socket read returns at most 16 bytes; the reader loops until it has all 1000 bytes.

```bash
java -javaagent:<agent.jar>=configFile=plan.json Demo.java
```

Expected output:

```text
firstRead=16 maxRead=16 total=1000
```
