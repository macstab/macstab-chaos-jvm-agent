# 01-connect-refused

Network: a connect fails with ECONNREFUSED, as the kernel would report it.

```bash
java -javaagent:<agent.jar>=configFile=plan.json Demo.java
```

Expected output:

```text
connect=Connection refused
```
