# 1.0.0 (2026-04-29)


### Bug Fixes

* resolve ThreadLocal reentrancy - re-enable ThreadLocal/park/AQS instrumentation ([738d4ed](https://github.com/macstab/macstab-chaos-jvm-agent/commit/738d4ed3613f0f67483bc4323febdc77201a57b3))


### Features

* final changes required for inital version  ([b62fb6d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b62fb6d77650ddaee5d3d63bbd8457c2213f8323)), closes [#1](https://github.com/macstab/macstab-chaos-jvm-agent/issues/1) [#2](https://github.com/macstab/macstab-chaos-jvm-agent/issues/2) [#3](https://github.com/macstab/macstab-chaos-jvm-agent/issues/3) [JdkInstrumentationInstaller#install](https://github.com/JdkInstrumentationInstaller/issues/install)
* implement missing stressors, ClockSkew, ExceptionInjection, ReturnValueCorruption, fix state machine, registry cleanup, maxApplications race, logging levels ([250413a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/250413ad85539b5e66357c6bd2623823ca4cad73))
* initial chaos testing Java agent implementation ([68eff23](https://github.com/macstab/macstab-chaos-jvm-agent/commit/68eff238fc09d6d5bc6a22127fc07414ce56037a))
* phase 2 - full JVM chaos coverage across 30+ new interception points ([6132125](https://github.com/macstab/macstab-chaos-jvm-agent/commit/6132125b367a24518b78f2ed96ea76dd0494b9ed))
