# 07-http-reject

HTTP: requests to one host fail before they are sent; a request to another host (a loopback server the demo starts) is untouched.

```bash
java -javaagent:<agent.jar>=configFile=plan.json Demo.java
```

Expected output:

```text
payments=payments down (chaos)
other=200
```
