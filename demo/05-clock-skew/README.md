# 05-clock-skew

Time: every `java.time` read runs one hour ahead: `Instant.now()` (`INSTANT_NOW`), a read of the system `Clock` (`CLOCK_READ`), `LocalDateTime.now()` (`LOCAL_DATE_TIME_NOW`) and `ZonedDateTime.now()` (`ZONED_DATE_TIME_NOW`). `System.currentTimeMillis()` is a JIT intrinsic the agent does not skew, so the demo measures against it.

```bash
java -javaagent:<agent.jar>=configFile=plan.json Demo.java
```

Expected output:

```text
instantNow=60
clockRead=60
localDateTimeNow=60
zonedDateTimeNow=60
```
