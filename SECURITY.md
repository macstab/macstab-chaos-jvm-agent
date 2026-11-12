# Security Policy

Thank you for taking the time to look. Coordinated disclosure protects users;
every report is taken seriously.

## Reporting a vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Email **info@macstab.com** with subject prefix `[SECURITY]`.

Include whatever you have:

- Affected version(s)
- Reproduction steps, proof of concept, or a failing test case
- Impact assessment — what an attacker can do with this
- Your name or alias for credit, if you want one

A short clear report beats a polished one that took three weeks. Send what
you have; we'll come back with questions.

## What you can expect

| When | What happens |
|---|---|
| Within **72 hours**       | Acknowledgement that the report was received |
| Within **7 days**         | Initial triage — confirmed, needs more info, or out of scope |
| During investigation      | Updates as the picture clarifies, usually weekly |
| At fix-ready              | Coordinated release date agreed with you; CVE / GHSA requested where applicable |
| **30 days** post-release  | Public security advisory; reporter credited (unless they asked otherwise) |

## Supported versions

Security fixes are backported to:

- The current major version — latest minor + latest patch
- The previous major version, for **6 months** after a new major releases

Older versions receive no security backports. The current supported list is
visible on the [releases page](https://github.com/macstab/macstab-chaos-jvm-agent/releases).

## In scope

- Vulnerabilities in the agent's runtime behaviour — privilege escalation,
  arbitrary code execution, unauthorised data exposure, denial of service
  triggered by config or network input
- Vulnerabilities in startup-config parsing — JSON parser injection, path
  traversal, deserialization gadgets
- Vulnerabilities in published artifacts — compromised dependencies,
  signing failures, tampered Maven Central uploads

## Not in scope

- **Bugs that only manifest when chaos scenarios are intentionally configured
  to inject failures.** That's the agent doing its job. If you can break
  something *without* deliberately configuring chaos to break it — that's
  a security issue and we want to hear about it.
- Issues in third-party libraries not vendored by this project — please
  report those upstream
- Issues that require an attacker to already control the JVM-launching
  process — the agent is not a trust boundary against the launcher itself

## Where enabling switches may come from

Plan authors are trusted: whoever writes a chaos plan may inject faults. A
switch that enables fault injection or a management endpoint
(`macstab.chaos.enabled`, `macstab.chaos.actuator.enabled`,
`macstab.chaos.management.enabled`, `macstab.chaos.management.allow-anonymous`,
`macstab.chaos.config-file`) is honoured only when it comes from a
launch-time local source: system properties, environment variables, the
command line, the application's own config files and test properties. From
any other source (Spring Cloud Config, Consul, Vault, a Kubernetes config
client) it is ignored with a log line naming the source, unless
`macstab.chaos.allow-remote-enable=true` itself comes from a launch-time local
source. A source the agent does not know, including one a future framework
release renames, is ignored the same way. The management endpoints are
authenticated. Booleans accept `true|false|1|0|yes|no|on|off`; any other value
leaves the switch off with a warning. The sources each framework counts as
local are listed in
[configuration-reference §10.1](docs/configuration-reference.md#101-configuration-trust).

## Credit

Researchers are credited in the published advisory and in the release notes.
If you would rather stay anonymous, say so in the report — that wish is
honoured.

## Thanks

Vulnerability reporting is unpaid, frequently thankless work that makes
everyone safer. It is appreciated.
