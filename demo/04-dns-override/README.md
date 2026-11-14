# 04-dns-override

DNS: a name resolves to the addresses the plan says, whatever the resolver answers.

```bash
java -javaagent:<agent.jar>=configFile=plan.json Demo.java
```

Expected output:

```text
localhost=[10.9.8.7]
```
