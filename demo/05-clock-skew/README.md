# 05-clock-skew

Time: java.time reads run one hour ahead.

```bash
java -javaagent:<agent.jar>=configFile=plan.json Demo.java
```

Expected output:

```text
skewMinutes=60
```
