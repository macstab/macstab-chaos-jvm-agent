# Demos

One fault per directory, each a single-file Java program (`Demo.java`), the plan that injects the fault (`plan.json`) and the output it must print (`expected.txt`). No build, no framework:

```bash
./gradlew :chaos-agent-bootstrap:shadowJar
demo/run-all.sh                       # runs every demo, prints [PASS]/[FAIL], exits non-zero on a failure
cd demo/03-disk-full && java -javaagent:../../chaos-agent-bootstrap/build/libs/chaos-agent-bootstrap-<version>.jar=configFile=plan.json Demo.java
```

| Demo                  | Fault                                                                 |
|-----------------------|-----------------------------------------------------------------------|
| `01-connect-refused`  | connects to loopback fail with `ECONNREFUSED` (`errno`)               |
| `02-trickle-read`     | socket reads return at most 16 bytes (`trickle`)                      |
| `03-disk-full`        | writes under `data/` fail with `ENOSPC`, `logs/` is fine (`pathPattern`) |
| `04-dns-override`     | `localhost` resolves to `10.9.8.7` (`dnsAnswer` override)             |
| `05-clock-skew`       | `java.time` runs one hour ahead (`clockSkew`)                         |
| `06-process-limit`    | starting a process fails with `EAGAIN`, in the JDK's own words        |
| `07-http-reject`      | requests to one URL prefix fail before they are sent (`reject`)      |
| `08-slow-executor`    | every executor submission starts 200 ms late (`delay`)                |

CI runs `demo/run-all.sh` on every push. The plans are the same JSON the agent reads from a file, an agent argument or an environment variable ([startup-config.md](../docs/startup-config.md)).
