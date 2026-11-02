# 07-http-reject

HTTP: requests to one host fail before they are sent; other hosts are untouched.

```bash
java -javaagent:<agent.jar>=configFile=plan.json Demo.java
```

Expected output:

```text
payments=payments down (chaos)
```
