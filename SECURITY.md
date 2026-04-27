# Security Policy

## Reporting a vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Email **info@macstab.com** with subject prefix `[SECURITY]` and include:

- Affected version(s)
- Reproduction steps or proof-of-concept
- Your contact details (for credit, optional)

You will receive an acknowledgement within **72 hours** and a status update
within **7 days**. Confirmed issues are addressed under coordinated disclosure
— a fix is prepared, a CVE is requested where appropriate, and the advisory
is published alongside the patched release.

## Supported versions

Security fixes are backported to:

- The current major version (latest minor + latest patch)
- The previous major version, for **6 months** after a new major releases

Older versions receive no security backports.

## Scope

In scope:

- Vulnerabilities in the agent's runtime behaviour (privilege escalation,
  arbitrary code execution, unauthorised data exposure, denial of service
  triggered by config or network input)
- Vulnerabilities in startup-config parsing (JSON parser injection,
  path traversal, deserialization gadgets)
- Vulnerabilities in published artifacts (compromised dependencies, signing
  failures, tampered Maven Central uploads)

Out of scope:

- Bugs that only manifest when chaos scenarios are intentionally configured
  to inject failures — that is the agent doing its job
- Issues in third-party libraries not vendored by this project; report
  upstream
- Issues that require an attacker to already control the JVM-launching
  process — the agent is not a trust boundary against the launcher

## Public disclosure

Once a fix has been released and adopters have had reasonable time to upgrade
(typically **30 days**), the advisory is published as a GitHub Security
Advisory and added to the changelog with the assigned CVE / GHSA identifier.
