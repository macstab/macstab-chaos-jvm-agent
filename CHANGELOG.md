# [2.0.0](https://github.com/macstab/macstab-chaos-jvm-agent/compare/1.0.0...2.0.0) (2026-11-14)


* build(bootstrap)!: relocate ByteBuddy and Jackson in the agent jar ([3cf2af3](https://github.com/macstab/macstab-chaos-jvm-agent/commit/3cf2af39fb924f086d8429f2af8f2c65837c0738))
* build(testkit)!: the testkit compiles against JUnit but no longer exports it or its BOM ([919265e](https://github.com/macstab/macstab-chaos-jvm-agent/commit/919265e3ab65fb96dca9f8596fb32bf6d74afef2))
* docs!: list the 2.0 breaking changes of commits already published without a footer ([d9b0e7d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d9b0e7d238e4584f039c6f4bc1e86642d7494a6f))
* feat(api)!: match globs by code points, '*' across line terminators ([e0c3f8d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e0c3f8dcfabd52aca11a023d9dc23bffa98d719b))
* feat(api)!: wall-clock activation window and count/window policy aliases ([380b032](https://github.com/macstab/macstab-chaos-jvm-agent/commit/380b0323914d93b63b6ba3fe3ae815031ba57a18))
* feat(instrumentation)!: weave hooks on demand ([1ffc187](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1ffc18728770969e96ba2e43863cc5e0b58879c1))
* fix(api, core)!: an injected exception is a JDK class resolved once, at activation ([befeaf1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/befeaf17359a9ddb592240947fd705458574d1ee))
* fix(api, core)!: the event log line is quoted and escaped, and attributes and operations keep one order ([fc726da](https://github.com/macstab/macstab-chaos-jvm-agent/commit/fc726da5ae9de44f57ebb9f4e4c01cefc452839a))
* fix(api, instrumentation)!: method hooks never weave JDK classes, and match-all method selectors are refused ([e266bcb](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e266bcbd802c92141f571a674353a19d9b80dccc))
* fix(api)!: a name pattern refuses what it used to reinterpret, and its caches clear when full ([fa67d36](https://github.com/macstab/macstab-chaos-jvm-agent/commit/fa67d36ba22336948763eea1863030ffa98fc5f0))
* fix(api)!: every production package is @NullMarked and nullable parameters say so ([671dc39](https://github.com/macstab/macstab-chaos-jvm-agent/commit/671dc397ccf642743ba758a0f4f07b8e5ba5df6b))
* fix(api)!: omitted JSON fields take their documented defaults ([1ed4c68](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1ed4c68908730b4f83d02d9714770387b9d64b73))
* fix(bootstrap, core)!: the JFR sink and the reports' counter build no SKIPPED events, and JFR names the message and records match and delay ([986ea13](https://github.com/macstab/macstab-chaos-jvm-agent/commit/986ea13f3c54028444ca378a72c657e76ea3367b))
* fix(bootstrap)!: the JVM-wide control plane of ChaosPlatform cannot be closed ([b294955](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b2949555ff8c7cf84587fb3700dca8c7f14b01e4))
* fix(core)!: a corruption with no value to corrupt does not count, EMPTY covers every empty type, and unused runtime overloads are removed ([21db636](https://github.com/macstab/macstab-chaos-jvm-agent/commit/21db636862cd524cafe9686e20e734155e102c19))
* fix(core)!: a Method.invoke whose caller is a JDK class is not a REFLECTION_INVOKE target ([55da997](https://github.com/macstab/macstab-chaos-jvm-agent/commit/55da9972e42b9e58d7910becbd4360753ad0fd1f))
* fix(core)!: a reject is refused where no real call fails, and a session refuses only sessionless operations ([dda6b6b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/dda6b6bac2afb6f50948a463c7ecc1f7d4d861ec))
* fix(core)!: a reject or an errno raises the exception the real call raises ([9da926d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9da926d79a26f96ffe95ebaf18eb8985a858343a))
* fix(core)!: a spuriousWakeup effect selects NIO_SELECTOR_SELECT alone ([92cf581](https://github.com/macstab/macstab-chaos-jvm-agent/commit/92cf581f780b8001da9f835f83f77fcd2f378a5a))
* fix(core)!: a stressor that cannot, or no longer does, produce load does not stay ACTIVE ([94f4fa6](https://github.com/macstab/macstab-chaos-jvm-agent/commit/94f4fa67ff599f92bce9e53808fc517febd4b456))
* fix(core)!: a stringInternPressure above the free heap is refused before it interns ([54340e0](https://github.com/macstab/macstab-chaos-jvm-agent/commit/54340e0daeb89cfca93d1602cb5c0c597e2da050))
* fix(core)!: a suppress on HTTP or JDBC is refused at activation; use reject ([31345bf](https://github.com/macstab/macstab-chaos-jvm-agent/commit/31345bfd88e4cfd5abcfcce714d5e1706a28c6d0))
* fix(core)!: a thread-local leak or reference-queue flood larger than the free heap is refused at activation ([b4a53a0](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b4a53a04b5b5f1325dbd0f02200e55e45363d769))
* fix(core)!: lifecycle events are published per transition, and a scenario that ends on its own publishes INACTIVE ([5f29640](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5f29640d905ba71c4babfdce5f893ad85a1ea5ef))
* fix(core)!: stressor parameters are refused instead of reduced, and classes are defined without Unsafe ([ff647b1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ff647b1515c078eb64fa43a9b1a8fa567082348f))
* fix(instrumentation, core)!: a gRPC reject fails the call through gRPC's FailingClientStream ([fe0000f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/fe0000f9bb7dcff0642e2a040df26afc2d4d429f))
* fix(instrumentation, core)!: the JDK's own use of a general primitive is not a target ([36fad55](https://github.com/macstab/macstab-chaos-jvm-agent/commit/36fad5541015378419561c4b5eb98fd6f37fc937))
* fix(instrumentation)!: a JDK HttpClient.sendAsync reject returns a failed future ([964d843](https://github.com/macstab/macstab-chaos-jvm-agent/commit/964d843d8ac35e73a875171a0f43f3c81a2c1234))
* fix(instrumentation)!: a Kafka send reject fails the send's callback, interceptors and future ([75db5e9](https://github.com/macstab/macstab-chaos-jvm-agent/commit/75db5e9f8cd211b941b41e27519ecc438db7ff64))
* fix(instrumentation)!: a Lettuce reject completes the command exceptionally, and a batch is evaluated ([cfa827e](https://github.com/macstab/macstab-chaos-jvm-agent/commit/cfa827eac1911406174e465232d1a949d00b08de))
* fix(instrumentation)!: a Netty write reject fails the flushed writes and closes the channel ([ca862c5](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ca862c5052869fa60884a8be96ff44505441502d))
* fix(instrumentation)!: an R2DBC reject is signalled through the publisher execute() returns ([4891bf6](https://github.com/macstab/macstab-chaos-jvm-agent/commit/4891bf67334d778f3aefe9635e59e7062167e162))
* fix(instrumentation)!: Apache HttpClient is evaluated at doExecute, with one URL rule ([45c2818](https://github.com/macstab/macstab-chaos-jvm-agent/commit/45c2818215ee29713f148f715f72f0b4d6c137a6))
* fix(instrumentation)!: OkHttp is evaluated in the call chain, so enqueue rejects reach the callback ([0f63ec1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0f63ec13d11b6b03a0c397c68a0608cc9555f4cc))
* fix(instrumentation)!: Reactor Netty is evaluated per subscription, and a reject is an error signal ([37f478a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/37f478a952eb93006af8e8bfb85ca9d32268f8a6))
* fix(instrumentation)!: the socket adaptors, SSL accept, JDBC batches and scheduled and virtual-thread executors reach their hooks ([e550bfb](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e550bfbec472b0366c0f2e8495ea727c71ba7457))
* fix(startup-config)!: a gate read from JSON needs maxBlock, and the randomSeed default is stated as applied ([11fca27](https://github.com/macstab/macstab-chaos-jvm-agent/commit/11fca2723ccb168f682f5bb6974e7e0351d4756f))
* fix(startup-config)!: plan JSON is read strictly and written in ISO-8601 ([7cbbf8a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/7cbbf8a9856982c5508c935ffd736cf73f1d826c))
* fix(startup-config)!: the plan mapper refuses what the plan schema refuses ([e555828](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e55582891f1b27613876eed3784b25b1991d5c8f))
* fix(testkit)!: count assertions under within hold for the duration, and their waiting is unobserved ([e122db0](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e122db097b65a3f4be026fefa577e303e816df09))
* fix(testkit)!: test extensions activate and bind chaos around the test method ([8b325cf](https://github.com/macstab/macstab-chaos-jvm-agent/commit/8b325cff7a3d47e100fd3128f76e67388b73a363))
* refactor!: delete the legacy com.macstab.chaos packages and the schedule-delay slot ([2b45975](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2b45975deb1a446d3422771c0c8b8f651c47e59c))
* refactor(api)!: keep the API free of Jackson ([9f5bcbd](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9f5bcbd38875c686c258c129fd5fb59f0d428621))


### Bug Fixes

* activation integrity, observability ownership and tooling defects ([795b7c2](https://github.com/macstab/macstab-chaos-jvm-agent/commit/795b7c21bde9e02f9b9abc7faed23905fa50f8cc))
* an activation conflict is a type, not a message to match ([4f3f942](https://github.com/macstab/macstab-chaos-jvm-agent/commit/4f3f942e261a94df1e480d6020e524ed717c54c4))
* **api, core, bootstrap:** the failure history keeps the 256 most recent, debugDump lists them, and currentSessionId is absent outside a session ([2a3aab4](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2a3aab4a797c3181072a8b218fbfc41afec52250))
* **api, core, micrometer, opentelemetry:** sub-millisecond delays are measured, one snapshot serves a scrape, and ChaosMetricsSink is deprecated ([45fff4a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/45fff4a25abc5aea5ed331ef0d55999eadcd54aa)), closes [#6](https://github.com/macstab/macstab-chaos-jvm-agent/issues/6)
* **api, quarkus:** a FREEZE clock skew accepts a zero amount, so the freeze shorthand stops the clock where it is ([9ecea34](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9ecea34550c82cd8bed501b2fb8d8de12a7a15e1))
* **api, startup-config:** the members only the agent uses are @Internal ([0da7644](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0da764401f45324f4dbd423945a9e47b64c00c16))
* **api:** bindSession is abstract, so an implementation cannot silently ignore sessions ([5666ffc](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5666ffc0d68fe6f455cb6edc3fa0dfbcb20a4388))
* **api:** ChaosDecisionRecorder replays a stressor or a clock skew unchanged ([1039d10](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1039d104ba2d2226e1cfc5e8bfc4970108e5ec78))
* **api:** CIDR blocks accept ASCII digits only ([01ce5ff](https://github.com/macstab/macstab-chaos-jvm-agent/commit/01ce5fffc76000e662a9765a819f9b5fc6e7c391))
* **api:** dnsOverride takes at least one address in its signature ([667c780](https://github.com/macstab/macstab-chaos-jvm-agent/commit/667c780a2a2e139d2f0b515371b952197f6264f7))
* **api:** normalise the empty path; pin the matchers' boundaries ([acdd812](https://github.com/macstab/macstab-chaos-jvm-agent/commit/acdd812d9e79960ff1e0bed48fc1fd5fae9a45a3))
* **api:** the decision recorder keeps one log per scope and activation, and bounds it ([799a3de](https://github.com/macstab/macstab-chaos-jvm-agent/commit/799a3de8c4eb6c0e13d17574972fb2014b880cb3))
* **api:** the optional Netty and preset peer filters are @Nullable ([9f9a6a7](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9f9a6a70ec36f48b98b08ceca4dd18636352e492))
* **benchmarks:** pass the write flag to beforeDatagramSend ([b57364c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b57364c2c7bc8e0e72d2d55514c10d50ea5b16e3))
* **benchmarks:** the one-shot scenarios exhaust, and the record-constructor policy is written down ([8b6b25f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/8b6b25f536a851f4db86b3ac582bf2d86875ad22))
* **bootstrap, core, instrumentation:** premain leaves java.util.logging and the LogManager choice to the application ([c69fb12](https://github.com/macstab/macstab-chaos-jvm-agent/commit/c69fb1208ab41cbb0062e56b2bf7eec10b54826e))
* **bootstrap, core, spring, micronaut, quarkus:** one log prefix, and each management outcome has its own message prefix ([9ab7753](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9ab7753ce433093fed7d4689e13c311f21675a8f))
* **bootstrap, instrumentation, startup-config:** a read-only java.io.tmpdir no longer stops the agent, and bridgeDir names the bridge jar's directory ([79acd5c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/79acd5cc071144c8cbf2ec2138ed66125cdc064e))
* **bootstrap, startup-config, core:** a reload reports a real reason once, survives an Error, refuses a taken id before it stops anything, and applies its observability before its scenarios start ([1482322](https://github.com/macstab/macstab-chaos-jvm-agent/commit/14823228c61b3f27502e699c3b4bd3f8b90eccc4))
* **bootstrap, startup-config:** reports never replace or remove another user's file, and a ConfigMap mount directory is checked ([3e00199](https://github.com/macstab/macstab-chaos-jvm-agent/commit/3e001998914a9a0524d115bc17a4fae9ef51970b))
* **bootstrap, startup-config:** settings are resolved once, and every startup setting has a system property ([dcf1baf](https://github.com/macstab/macstab-chaos-jvm-agent/commit/dcf1baf61579b0635737cd20fb3a259153260058))
* **bootstrap, startup-config:** the plan poller reads the file only when its stamp changed, and its own file I/O is agent work ([276b58c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/276b58cee6ae54dd0a01a7465bf3bb5b13c8fd75))
* **bootstrap:** a JVM that exits before JUL is initialised still prints the agent's held start lines ([4618aa2](https://github.com/macstab/macstab-chaos-jvm-agent/commit/4618aa2709f76daa16e53f4e0838039a167b4773))
* **bootstrap:** a negative configWatchInterval is reported, not taken silently as off ([c22a866](https://github.com/macstab/macstab-chaos-jvm-agent/commit/c22a8667ccbf39415352cdc09ca7463f0aca900e))
* **bootstrap:** a plan being activated answers conflict to a stop and a second activation ([982dca3](https://github.com/macstab/macstab-chaos-jvm-agent/commit/982dca3782437cd890ddf88c7d68f124e876a0c4))
* **bootstrap:** a plan file reload rolls back on an Error too, and does not retry that content ([dcec55e](https://github.com/macstab/macstab-chaos-jvm-agent/commit/dcec55e7811794cdc4b57e0142e18c84ea70e3bd))
* **bootstrap:** a plan named like a running plan is a conflict ([a3c6da3](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a3c6da3034ab95cfc242a0b710c54fb64566e185))
* **bootstrap:** a plan refused for its name never injects a fault ([9cdc8aa](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9cdc8aa9927871157893537db8c6d8037b2da97d))
* **bootstrap:** a reload overtaken by close reports nothing ([e778499](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e77849983db19319aa9010c2d1e9decb94ac6069))
* **bootstrap:** a reload whose hooks cannot be woven leaves the running plan untouched ([77fec37](https://github.com/macstab/macstab-chaos-jvm-agent/commit/77fec37de6df14ba92ac5b04115de2629064869d))
* **bootstrap:** a skipped MBean registration names its cause, and the limited-runtime test reaches it ([3ce9902](https://github.com/macstab/macstab-chaos-jvm-agent/commit/3ce990204d481892e1937a02885d056a910d7127))
* **bootstrap:** a stop racing an activation never answers not-found, and stop-all stops a plan in activation ([234c37b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/234c37b93df76b59e9289587c604e270bb87ffd6))
* **bootstrap:** apply reloaded plans atomically and report parse errors precisely ([1bf80d2](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1bf80d2083982c7ebaef5cd61cc7b427b82c67b0))
* **bootstrap:** closing the reports waits for a stuck hits writer at most 5 s, and their failed removals are traced ([0fbdeee](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0fbdeee1ec7564297e9760e1b2c79f326013a82e))
* **bootstrap:** closing the reports waits for the hits writer, so no write follows close ([b05b82c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b05b82c540c027f4c9ed17d239e1749f82ff5728))
* **bootstrap:** configWatchInterval=0 overrides the environment ([788b361](https://github.com/macstab/macstab-chaos-jvm-agent/commit/788b361cd2eac285c19943a08212135c36866939))
* **bootstrap:** find a reload's id conflict before stopping anything ([a37ff95](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a37ff95c32e9490d2e19f9f1a0664e8fdbd754f8))
* **bootstrap:** parse jmxControl, reports and dynamic attach like every switch ([ad2d83d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ad2d83d89d88d0f9dc101ac49f90db14b8533637))
* **bootstrap:** premain reads the JUL state as the agent's own work, so a startup plan's REFLECTION_INVOKE scenario no longer counts it ([7fd9558](https://github.com/macstab/macstab-chaos-jvm-agent/commit/7fd95580e458202087f1c82f121b6d0d52b6bfa6))
* **bootstrap:** premain starts its deferred logging thread as the agent's own work, so a startup plan's THREAD_START reject no longer fails it ([da44e50](https://github.com/macstab/macstab-chaos-jvm-agent/commit/da44e509c0c1128719f840e151ed5f2c4d810bf2))
* **bootstrap:** premain's logging-start thread and log-release hook are made by AgentThreads ([5d95003](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5d9500364171ee4bd63a4757f413ac974d54d210))
* **bootstrap:** report a recovered config file as ok ([09de2ad](https://github.com/macstab/macstab-chaos-jvm-agent/commit/09de2ad19a5750eef51df8051dbf468f270f4fc6))
* **bootstrap:** report staging files are created new, never written through a file or link already in a shared directory ([a45a86d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a45a86dc45d8353cf231c2a954e938ad8f3abac6))
* **bootstrap:** roll back failed initialization and refuse a second agent copy ([31bebc6](https://github.com/macstab/macstab-chaos-jvm-agent/commit/31bebc65a2225b1c239e7427a012c662fe01a443))
* **bootstrap:** status and hits files only with reports=on, and failures are reported ([4f6ac0c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/4f6ac0ca65e782dd349f0765d4f21725f4a3175a))
* **bootstrap:** the agent hands its Instrumentation to the runtime on every start, so a safepoint storm with retransformClassCount above 0 retransforms classes ([8542811](https://github.com/macstab/macstab-chaos-jvm-agent/commit/854281189b9e5cb3fdbddf7843b5e31d34b6ce89))
* **bootstrap:** the agent starts without java.management, and an eager start survives a group's LinkageError ([bac51f5](https://github.com/macstab/macstab-chaos-jvm-agent/commit/bac51f5e3389e6d650d6fa10f364aedf827156c4))
* **bootstrap:** the bridge jar is written at the real path bridgeDir was checked at, and a path another user controls is refused ([b9a6e0f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b9a6e0fe6d43163ff14d4765a0e336dd9befcdff))
* **bootstrap:** the hits file and the JFR total keep the counts of closed and reloaded scenarios ([fde9ac4](https://github.com/macstab/macstab-chaos-jvm-agent/commit/fde9ac446a0397e2abecc686963fcc160c5e7fea))
* **bootstrap:** the management endpoints forget plans that finished on their own ([12bd93f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/12bd93f97bbf86cc91eeeed79346b73b3c7b7d3f))
* **bootstrap:** the plan file poller's scheduler is the agent's own, so a SCHEDULE_TICK suppress no longer stops hot reload ([cbe2c63](https://github.com/macstab/macstab-chaos-jvm-agent/commit/cbe2c63185b064f26a12d5ed3f3434480eeb3c2b))
* **bootstrap:** the plan poller trusts an unchanged stamp only after a read that began past its provisional window, so a same-size edit right after a read in the same whole second is applied ([81caea3](https://github.com/macstab/macstab-chaos-jvm-agent/commit/81caea37e313eb182256473f5c8ec1a24300308d))
* **bootstrap:** the status of a rejected reload counts the scenarios still running ([239f102](https://github.com/macstab/macstab-chaos-jvm-agent/commit/239f1023c4815c28ac7c32f13bffb03f942c25c2))
* **build:** declare the inputs of childJvmCoverage ([29e3f95](https://github.com/macstab/macstab-chaos-jvm-agent/commit/29e3f954177b4f8f1f1952e699cc3b43c7ff6f43))
* **core, bootstrap:** a plan's observability settings belong to its scope, and a closed session clears them ([1f32eba](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1f32eba7317be7830a3acb85a418a9b73a98802d))
* **core, bootstrap:** every refusal and weaving failure is recorded once, and a plan weaves all its hooks before it starts any scenario ([cd5a672](https://github.com/macstab/macstab-chaos-jvm-agent/commit/cd5a672f3d11cc0cc509c4940db955f409bd6e42))
* **core, bootstrap:** the event delivery, hits writer, final-hits hook and poll threads keep no application loader ([cb675f7](https://github.com/macstab/macstab-chaos-jvm-agent/commit/cb675f7ead736a1c50c230d9ee99156c97256207))
* **core, instrumentation:** a process wait is matched by the program the child was started as ([495722e](https://github.com/macstab/macstab-chaos-jvm-agent/commit/495722e692d4f1b89a992e68e5f815375b510fa5))
* **core, instrumentation:** every agent log line starts with the [chaos-agent] prefix ([348c6fe](https://github.com/macstab/macstab-chaos-jvm-agent/commit/348c6fe99287bf02f9545878a055b9980c64e9c4))
* **core, instrumentation:** log messages are built only for a line that is written, and the suppressed-tick warning is agent work ([421042b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/421042bcf4779544af0044a27d2c31573443aefc))
* **core, instrumentation:** scheduledOnly and periodicOnly match ([415f928](https://github.com/macstab/macstab-chaos-jvm-agent/commit/415f928e83ead931e5d1b38f5d6fbeeb76278f4e))
* **core,instrumentation:** a file-open failure follows the hooked java.io API, and a torn transferFrom tears the bytes the source holds ([800dcf9](https://github.com/macstab/macstab-chaos-jvm-agent/commit/800dcf995651c47cb6537c74ed27779ca13fd000))
* **core,instrumentation:** a timeout() stall ends on a selector wakeup or close and on a close of the socket ([92062ab](https://github.com/macstab/macstab-chaos-jvm-agent/commit/92062ab5b2e0460a57f0010f1454e10516b77b44))
* **core:** a candidate that throws returns the reservations of the candidates before it ([a3950a1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a3950a19fc2f81d135082c1cfae87b9b6c44ef87))
* **core:** a concurrent activation of the same id is refused before it builds a stressor ([095247a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/095247ab4cd7d7d4fbfefd786092bfd2d3c22f4d))
* **core:** a connect of a connected, connecting or closed socket channel is not evaluated ([62974d4](https://github.com/macstab/macstab-chaos-jvm-agent/commit/62974d4a3834ebcabc8b3147759585354bc7db88))
* **core:** a connect of an already connected or closed socket is not evaluated ([140dfff](https://github.com/macstab/macstab-chaos-jvm-agent/commit/140dfffe0713f54ff159872a67fd8f33e247e03e))
* **core:** a DNS rewrite does not count the local-host lookup as applied ([f5c581b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f5c581b4ed2e2b9e36d77c2aa68114fef2f3d773))
* **core:** a failed method re-weave no longer leaves the still-active method scenarios unhooked ([471a260](https://github.com/macstab/macstab-chaos-jvm-agent/commit/471a26085690a69ad4ac4c5a0e2e814a1be21a5b))
* **core:** a failing close, connect or TLS handshake leaves the endpoint closed, as the JDK does ([ae2d291](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ae2d2912a99b4dc7f5955a95092fb1ded5ff472e))
* **core:** a failing end after a failed expiry task is traced too ([2b84025](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2b84025d0edad8f7709ae192770b52fb3115314f))
* **core:** a frozen clock stops at activeFrom, and wall-clock skews are whole milliseconds ([867c865](https://github.com/macstab/macstab-chaos-jvm-agent/commit/867c8651e38fd725294af538b3e2d0b075a37033))
* **core:** a gate with maxApplications holds its last admitted caller ([181acf1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/181acf12ab27b2561df48fa02ea4ed6ab21f453b))
* **core:** a lone lowest-precedence effect applies, and a read into a read-only buffer counts no corruption ([b3a696f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b3a696ff79209984c10891cb80820f66458ede34))
* **core:** a Method.invoke from a class appended to the boot class path stays a REFLECTION_INVOKE target ([6ee7c92](https://github.com/macstab/macstab-chaos-jvm-agent/commit/6ee7c921ad1eb97ee24013454c1bb21be7592419))
* **core:** a plan handle throws the first delegate failure itself ([68614d3](https://github.com/macstab/macstab-chaos-jvm-agent/commit/68614d312972308f26b3f243e7e43a1ddc585b8f))
* **core:** a plan whose scenario throws an Error rolls back the ones registered before it ([6644e98](https://github.com/macstab/macstab-chaos-jvm-agent/commit/6644e988256ede1edfbedc7667ac7c1dca0e06c7))
* **core:** a plan whose scenarios have not started yet reports REGISTERED, not INACTIVE ([c1430ef](https://github.com/macstab/macstab-chaos-jvm-agent/commit/c1430efedf6680cfd31afca9b2bc4c16c370d7fa))
* **core:** a redirect leaves Unix-domain connects alone ([ad8fd77](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ad8fd7753cd538d99323814e4e368b38bf2f6af2))
* **core:** a refused offer no longer drops the tasks of a scheduled pool ([d7431e5](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d7431e52901ead7a434868b5010fd57b01678a0e))
* **core:** a registration whose re-weave throws leaves no controller behind ([3dad6c6](https://github.com/macstab/macstab-chaos-jvm-agent/commit/3dad6c6c040ec8ed2a50ce150da729d0085e97f7))
* **core:** a reject on METHOD_EXIT fails the call instead of being ignored ([57496aa](https://github.com/macstab/macstab-chaos-jvm-agent/commit/57496aa353e5fdbb46b6cdc6d98d7833dfa3fa90))
* **core:** a scenario id in several scopes resolves to the JVM's, then the smallest session ([6a136ae](https://github.com/macstab/macstab-chaos-jvm-agent/commit/6a136ae51cb1e077ef93c9ac063dfc1961489db7))
* **core:** a scenario's state follows its time bounds, start() never revives an INACTIVE one, and the window is read unskewed ([ff2ecc5](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ff2ecc5fbf4221aff2aa0aaa3b51b943be8b55ec))
* **core:** a session hand-off counter resync cannot drop a capture ([273d4f0](https://github.com/macstab/macstab-chaos-jvm-agent/commit/273d4f07c653ee3e41a0a8df6ffdfdf2a0c2bcca))
* **core:** a session keeps no handle of a scenario the caller already closed ([75e5c14](https://github.com/macstab/macstab-chaos-jvm-agent/commit/75e5c14299144c127d3c0015716027c974c5ea2a))
* **core:** a session plan is activated like a control-plane plan ([9b5b69d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9b5b69d346f4234fe8bcf11273e68803d89136e5))
* **core:** a session without scenarios no longer replaces the tasks submitted to a pool ([a881582](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a881582558249b44a2021ae39530be4a1e852cc6))
* **core:** a shutdown hook registered twice keeps its one wrapper, and a null hook reaches the JDK ([41a071f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/41a071f1646ef7b2f84821d2f594f88ab3030648))
* **core:** a spent maxApplications ends the scenario from the thread that spent it ([f07c26b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f07c26b8196daab737db6460192fb2dcca56e0c1))
* **core:** a stop that races a stressor's start no longer leaves its expiry thread parked for activeFor ([48e2153](https://github.com/macstab/macstab-chaos-jvm-agent/commit/48e21531a48ec0a086b5e71614c43176817ee27b))
* **core:** a stressor that cannot start publishes FAILED alone, and an interrupted stressor ends its scenario ([9e6ca69](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9e6ca695438a6d788657b13395afa9d7bb31fd57))
* **core:** a suppressed submit cancels its Future; consistent scenario counters ([09fe581](https://github.com/macstab/macstab-chaos-jvm-agent/commit/09fe581bbb85614c0651526d998696193f51b686))
* **core:** a task submitted from a session stays a Future ([da323bd](https://github.com/macstab/macstab-chaos-jvm-agent/commit/da323bd39f448ca8adcea720fa8b8aee56b7580b))
* **core:** a thread-leak thread ended while parked is logged as ended ([0bdca7c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0bdca7c95da1c343b93731285da4fa1360891d05))
* **core:** a thread-local leak whose values cannot all be allocated plants none ([d352965](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d352965b073e907628011d3cff0f1ef28606a317))
* **core:** a threadLocalLeak plants its entries on the common pool's workers only ([79289d9](https://github.com/macstab/macstab-chaos-jvm-agent/commit/79289d926e05ab6e6679c68200a5b4b0f2c6ee3e))
* **core:** a timeout on connect waits out the connect timeout and throws ([ab5dbdd](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ab5dbddb371592cf1436ea3f7ffc59591594c8d0))
* **core:** a weaver Error resets the woven method selectors too ([65e8385](https://github.com/macstab/macstab-chaos-jvm-agent/commit/65e8385122fbb511763dbe4e2a5c4e84cd2a01bd))
* **core:** a weaving failure is a ChaosInstrumentationException ([eb140d6](https://github.com/macstab/macstab-chaos-jvm-agent/commit/eb140d62e89a26907f80394af0f4d931531b6b62))
* **core:** an activation overtaken by close() after registering is refused ([0e2af9f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0e2af9fe959c2bec5ba6d7d091300363419b4026))
* **core:** an activation refused because the plane closed is not recorded as its failure ([74c4924](https://github.com/macstab/macstab-chaos-jvm-agent/commit/74c49247f1e7ae45fa5335943c6061aef8630c9a))
* **core:** an activation that fails releases its claim before FAILED is published ([32a3d6c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/32a3d6c4d823427466ef16da614b89d485b94240))
* **core:** an effect that cannot change the call does not count ([67a1531](https://github.com/macstab/macstab-chaos-jvm-agent/commit/67a1531fa3d869b3296d91edf10087742668f272))
* **core:** an empty DNS family filter fails through FailureFactory ([a9fc27a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a9fc27a951aa14ef30fb8897b2f0f76e67ff204d))
* **core:** an Error or exhausted resources while a scenario starts are rolled back and recorded ([4663ca4](https://github.com/macstab/macstab-chaos-jvm-agent/commit/4663ca48e62b00496790640e7cdeadb3d786a7ac))
* **core:** an expiry thread started before the agent's install raises its depth once installed ([b86d75c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b86d75ce0dfc90fed288954902b45471ff5e361a))
* **core:** anchor the rate-limit window at start, failure causes, accurate docs ([4a0fc93](https://github.com/macstab/macstab-chaos-jvm-agent/commit/4a0fc93b2ccb7e96613b0bb5c7e536bba87a8b99))
* **core:** apply delay, then gate, then terminal action, without dropping the delay ([6c7d406](https://github.com/macstab/macstab-chaos-jvm-agent/commit/6c7d406a86779d75dacd20b5434cb9a183452350))
* **core:** carrier pinning never holds every carrier without consent ([5e31197](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5e31197aff405b26e15e4f73c55e986d25a901fa))
* **core:** carrier pinning on a single-carrier scheduler is refused at activation ([1c8547b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1c8547b00656e1f47392e85cf266658307052f66))
* **core:** codeCachePressure runs on a runtime without java.management ([ff36740](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ff36740b4bbaefcb169870a0996e59888d04b556))
* **core:** draw and sleep delays to the nanosecond ([6ad4e83](https://github.com/macstab/macstab-chaos-jvm-agent/commit/6ad4e83583ed96fbc756ca947c77b3a4c64d104f))
* **core:** EMPTY return-value corruption returns a value of the declared type ([451d199](https://github.com/macstab/macstab-chaos-jvm-agent/commit/451d1996eaa5125b8bca0406d0bfa7e9f09ebf62))
* **core:** ETIMEDOUT on a read is a dead connection, not a read timeout ([7056d81](https://github.com/macstab/macstab-chaos-jvm-agent/commit/7056d811faf395f4d6d54a966f7b27d4bc08b32a))
* **core:** event and snapshot timestamps are the real time under a clock-skew scenario ([516413d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/516413d7f6536bd9c97c8dab7df39a1397ba6de6))
* **core:** failures on the expiry thread are traced, and close() stops every scenario although one stop fails ([03d7c1f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/03d7c1f75ed90468d44212750854c06a8da962b3))
* **core:** FORK_JOIN_TASK_RUN is not evaluated when a virtual thread mounts on its carrier ([14a2580](https://github.com/macstab/macstab-chaos-jvm-agent/commit/14a258043856c9eaa980a0dd497e5aed8e9885a2))
* **core:** free direct-buffer stressor memory when the stressor stops ([aaf31b9](https://github.com/macstab/macstab-chaos-jvm-agent/commit/aaf31b9f5b90b9e36bba7e1b7fb16896abfbe7a5))
* **core:** gate re-arms after release and an interrupt ends the wait cleanly ([6d510e0](https://github.com/macstab/macstab-chaos-jvm-agent/commit/6d510e0f870ddf506bd42a15759b4fc527f1951e))
* **core:** getByName("") is not evaluated as a DNS lookup ([46c2e8a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/46c2e8a906c9ef7bf182453f7903cea099f2ad96))
* **core:** honour session scope for worker runs, ticks, clock skew and JVM-wide calls ([8a0659c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/8a0659cc06f4f3e33bab7d7a6aca27912ac17616))
* **core:** IP literals, Jedis set-up commands and a second Socket.close are not evaluated ([8a48f52](https://github.com/macstab/macstab-chaos-jvm-agent/commit/8a48f5219fa9e4962a5ce855b0845df831c962ea))
* **core:** method hooks follow the registry in order, and a failed registration unweaves ([f66767b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f66767b8ebdc37eea4c9afa3c4805d960febdb27))
* **core:** of two concurrent starts of a MANUAL scenario, only one builds a stressor ([bfe70b8](https://github.com/macstab/macstab-chaos-jvm-agent/commit/bfe70b826bde141bfb24c7292698a27ed0bb67e5))
* **core:** openSession and ChaosSession.wrap refuse null where it is passed ([4fe333a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/4fe333adb094bdf922ba67f0559f921a781b9272))
* **core:** point the Javadoc links at the domain dispatchers ([5296f4b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5296f4b9a7f757768d98e2ba737601cbb4574110))
* **core:** re-activating a stopped id reports the old scenario's end before the new one is registered ([d7296b3](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d7296b38e7598df48dbe251ed25518a69fa5f0ac))
* **core:** re-activating an expired id ends the expired scenario outside the control plane's lock ([e82e83f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e82e83f393fbb9b6497fc4e4a0b3c1a7677d6cb8))
* **core:** record APPLIED only for contributions that take effect ([b8d7113](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b8d71134d6c221e8ef2693d819f7b5cf7b04b759))
* **core:** refuse a reject on QUEUE_TAKE and QUEUE_POLL ([43db587](https://github.com/macstab/macstab-chaos-jvm-agent/commit/43db58768718324e6347f9fa2e29e5c15d9c2cc6))
* **core:** refuse a reject on worker and fork-join runs ([ec3a70a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ec3a70a53e30b762eaa522f7ed0b6dc2b6cd2bec))
* **core:** refuse an activation that close() overtakes ([0f9467b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0f9467bfcd5b48b241a2dc9700f420e814bbc47a))
* **core:** register a shutdown hook as itself ([0a12cc1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0a12cc1517397b260332544b1d7221facfc79e9d))
* **core:** reject effects an operation cannot carry out ([471a42b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/471a42b298bad20626f45ffb5eeb132374f8b9e2))
* **core:** release a thread-local leak's values on close, whatever the pool does ([aa43af2](https://github.com/macstab/macstab-chaos-jvm-agent/commit/aa43af2825dd516e4f07fddcf83d0f37e41feac8))
* **core:** removing an asynchronous listener lets its event finish and counts what it drops; a failing listener warns once ([9a3c23d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9a3c23dba175aac9d9d883b5e3acdd37247e0a21))
* **core:** return the rate-limit permit of a contribution that does not apply ([2c587d6](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2c587d6af1b4102728eff5cbdd5beee4bc30c559))
* **core:** saturate the GC stressor's batch instead of wrapping it ([e4345b3](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e4345b3e29d48c420105cbd07111110d8c778ace))
* **core:** skew Instant and java.time reads to the nanosecond ([3c2af7c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/3c2af7c0f515e280fe7628e8b9f1ec91c3ba958e))
* **core:** stopping a finalizer backlog ends the finalizer sleeping in it ([4bccd92](https://github.com/macstab/macstab-chaos-jvm-agent/commit/4bccd921ddada187bdd60eb520cc611634fe803b))
* **core:** stressor durations below a millisecond are kept and huge ones last until the stressor ends ([9d31eb4](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9d31eb4fe388d5e50811d6f438cffdc44cda8fa8))
* **core:** stressor parameters take effect or are reported ([beffd9b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/beffd9b25edf29f98e1fca59cafe747b49f4bc2a))
* **core:** stressors honour activeFor, and carrier pinning really pins ([2eefb03](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2eefb033670b0468831ece8d62f727aa6b19d930))
* **core:** strip the JDK 21+ lambda class-name suffix from task class names ([515bd51](https://github.com/macstab/macstab-chaos-jvm-agent/commit/515bd51ff28ebb850ac65664135706cd07afa55e))
* **core:** the agent's event delivery and its stressor teardown are its own work ([337623b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/337623bdd7f99913c8d6548c5f595443ceff6d63))
* **core:** the agent's own threads, tasks and ThreadLocals are never evaluated ([300813e](https://github.com/macstab/macstab-chaos-jvm-agent/commit/300813e89d17019123fa95cb4964fb750dd93642))
* **core:** the agent's threads have no context class loader ([49b2fc5](https://github.com/macstab/macstab-chaos-jvm-agent/commit/49b2fc50a19f187d897cf151644a485b83919c40))
* **core:** the agent's threads keep no application loader through inherited thread-locals or caller frames ([f47bf4d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f47bf4d524b63e946646d15b4d685816eaa12125))
* **core:** the carrier-pinning spin threads end on interrupt after a failed partial start ([c07365d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/c07365d95f5bf688e35d519faf897cb7f362b5a5))
* **core:** the close a virtual thread's interrupt makes is not a close target either ([67e3a80](https://github.com/macstab/macstab-chaos-jvm-agent/commit/67e3a80672ed3a7c9f78242c5dc5bf3bb7a4ed67))
* **core:** the close an interrupt makes is not a FILE_IO_CLOSE or SOCKET_CLOSE target ([a23b200](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a23b200b3a99b4d5c33288c2d54fcca82b8f2c11))
* **core:** the dependents of a future completed by an exceptional-completion effect are evaluated ([e4d8ead](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e4d8eadd6bc76af0626af35928deb46b8df6e811))
* **core:** the finalizer backlog's finalize() sleeps as agent work ([21d645f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/21d645facddf6f1f2683a3e37bb2f66192f1788e))
* **core:** the JDK's start of the agent's shutdown hooks is not a THREAD_START ([9915a38](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9915a38a38e70bc17e744c029f7ebe7923634702))
* **core:** the reference queue flood stressor no longer keeps every reference it enqueued ([6853bb4](https://github.com/macstab/macstab-chaos-jvm-agent/commit/6853bb4816d9c01981a884a03784c080297d11b0))
* **core:** the session hand-off keys carriers by identity and takes no lock of its own ([295d515](https://github.com/macstab/macstab-chaos-jvm-agent/commit/295d5155c1363f47ca5a0c75e57b82a8117594e4))
* **core:** the thread-leak and carrier-pinning stressor threads keep no loader of the activating thread ([c0ae06e](https://github.com/macstab/macstab-chaos-jvm-agent/commit/c0ae06edc8eb49fcfdc59c447fa4cacf8f48f634))
* **core:** the thread-local leak's heap bound counts each entry, not only its value ([cf8fc41](https://github.com/macstab/macstab-chaos-jvm-agent/commit/cf8fc41ed85d790262cb907d871f441dcbc90f78))
* **core:** the thread-local leak's plant timeout starts after its values are allocated ([52f14bc](https://github.com/macstab/macstab-chaos-jvm-agent/commit/52f14bcf8c1c01f256dbf0095076de012e4fabdf))
* **core:** while premain holds the agent's log lines, only INFO and above are kept ([5e05311](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5e05311028b8b59f737c3d602d89d27d200ff48b))
* **effects:** reset torn socket writes, seed the effects' own choices ([16fe9f4](https://github.com/macstab/macstab-chaos-jvm-agent/commit/16fe9f40acf012a9622aef135e130b7d4052d299))
* every parameter that receives null is @Nullable, so NullAway checks its uses ([2c04683](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2c046830790f3e3858033a2230678e39151f07bf))
* **instrumentation, core:** advice reads no target while idle, extractor lookups are cached, and the session header asks for the session first ([5729aaf](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5729aafea687d11555b65e261aa55dbd97812c07))
* **instrumentation, core:** HttpURLConnection, file system and JDBC hooks check idle before they build their target ([e97127f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e97127fa6a52c75f818c7dbcc10b15ac6d15f209))
* **instrumentation, core:** the module opens reach the agent's own module and the bridge ([94282c6](https://github.com/macstab/macstab-chaos-jvm-agent/commit/94282c602c2ee56d76c8ba21b9a97db8b1e29c4f))
* **instrumentation, packaging:** hooks in a child-first class loader reach the agent ([30165ca](https://github.com/macstab/macstab-chaos-jvm-agent/commit/30165ca4764fcfefd795452854c7e73ec2d0bf55))
* **instrumentation:** a class whose superclass is missing is no target, not a hook failure ([46a473c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/46a473ce2644050cc40f5941113ce794f1a07d8c))
* **instrumentation:** a datagram receive counts the packet's window, and a dropped send keeps the JDK's checks ([d9a4253](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d9a42534015de341af5e43fa98893d74685998c6))
* **instrumentation:** a dropped datagram send keeps the JDK's protocol-family check ([5a830b5](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5a830b56f3d6843d990d3527648c5ca13d58a177))
* **instrumentation:** a failed bridge injection can be retried ([a0f733b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a0f733b0780505877781b54ea019b4e2d331d5c6))
* **instrumentation:** a failing socket close throws after the real close; a failed connect closes as the JDK does ([1569d3b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1569d3bba80e2e0e860cc263d5185544a74d1a3f))
* **instrumentation:** a gRPC reject goes through the channel's retry policy ([7f558f9](https://github.com/macstab/macstab-chaos-jvm-agent/commit/7f558f94588d034c1c6cd9e2bffa60259b1cce0e))
* **instrumentation:** a JDBC driver's and HikariCP's own statements are not JDBC targets ([45a73a0](https://github.com/macstab/macstab-chaos-jvm-agent/commit/45a73a00bef141b37c31ebf18b805b3b053a0ff8))
* **instrumentation:** a JDK HttpClient send outside a session no longer reads the request's headers ([85a310d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/85a310d17d57ab560108576e29aad3cd6a76c490))
* **instrumentation:** a NETTY_CHANNEL_WRITE reject no longer closes datagram channels ([da5d8a6](https://github.com/macstab/macstab-chaos-jvm-agent/commit/da5d8a6d287baa1e4aad6e74e40b0e7368c5e481))
* **instrumentation:** a refused agent copy changes nothing, and a failed install leaves no delegate ([98a40db](https://github.com/macstab/macstab-chaos-jvm-agent/commit/98a40db50b2e695c0a788d70b430face0c01adc5))
* **instrumentation:** a runtime without java.sql no longer aborts premain ([9b6c767](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9b6c76779d510b594f90e64b88551414429787b6))
* **instrumentation:** a stalled select keeps a wakeup made before it; a closed selector throws ([273639f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/273639ff01fd9ea3cc23579e4caf44799354abed))
* **instrumentation:** a suppressed tick of a scheduled Callable cancels its future ([b6fb93e](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b6fb93e6db44b4c49544785c91ff9d3b7da3da94))
* **instrumentation:** a suppressed timed poll waits its timeout ([0a41b5a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0a41b5ac10aaddf6d90e2f5ff3e56a3ee08f6a1a))
* **instrumentation:** a wakeup consumed by a real select is dropped when that select returns ([bd56988](https://github.com/macstab/macstab-chaos-jvm-agent/commit/bd56988eeb8e2f4338053fee9bac400438f36f01))
* **instrumentation:** a wakeup that ends a real select is not kept for a later timeout() stall ([d9b18af](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d9b18af4162efe8bfdb1b824a5fad1f38ec50f6c))
* **instrumentation:** a weave the JVM refuses fails the activation and is retried, and an uninstall reports what it could not undo ([577e858](https://github.com/macstab/macstab-chaos-jvm-agent/commit/577e858e43ca897d71b0ff2885fe627606c02633))
* **instrumentation:** an idle DatagramSocket.send no longer allocates its packet's address ([6c0ed3c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/6c0ed3c297db50a95a3198850fe7a0adc3de3f27))
* **instrumentation:** an OkHttp reject ends the call as an OkHttp network failure does ([6626687](https://github.com/macstab/macstab-chaos-jvm-agent/commit/66266871be0cdb5c7d2db2fd8e62c0939fb8c055))
* **instrumentation:** client-library advice links in a loader that cannot see the agent's ([d876651](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d876651a91a42730533d8257489864cfa1ce9fa0))
* **instrumentation:** do not leave a bridge jar in the temp directory per JVM start ([14aa095](https://github.com/macstab/macstab-chaos-jvm-agent/commit/14aa09566f7f45b9b33eeafe7bb5e2ebcb951751))
* **instrumentation:** escapee retransform after a partial weave, recorded escapee failures, bounded hook failures, idle-first JMX getAttributes ([eeb5251](https://github.com/macstab/macstab-chaos-jvm-agent/commit/eeb52513eb903cd8626b9ada23ee3a4055f4ef34))
* **instrumentation:** evaluate a DatagramSocket.send once ([2334568](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2334568d7e30c98883b2eb33fe80756cdf2f3102))
* **instrumentation:** evaluate nested instrumented calls once per logical call ([f458fd1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f458fd1bcd62de10649d5bacf335eac0162fc535))
* **instrumentation:** EXECUTOR_WORKER_RUN applies to pools that override beforeExecute without calling super ([d818afc](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d818afcbb40cd0f83c04b36f628d68c29eed2eef))
* **instrumentation:** getByName(null) is not evaluated as a DNS lookup ([3df8c5a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/3df8c5a9be6598110eca5a5bdc6493a5443902ec))
* **instrumentation:** gRPC attempts are rejected on every gRPC version; delay and gate stay the call's ([de0024c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/de0024ce09130b4791b08b3c68580468f22c9c0b))
* **instrumentation:** hook diagnostics report what is woven and why a group failed ([c4357c2](https://github.com/macstab/macstab-chaos-jvm-agent/commit/c4357c2c51b2d4d45954563efc72fdd093068398))
* **instrumentation:** install() checks for another copy of the agent and installs its delegate in one step ([a397562](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a397562439f2fcee36756f3405c9332954ab9ea8))
* **instrumentation:** intercept call paths that were never woven ([74e5eb1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/74e5eb131a411db52ddfb70648805683735d7e32))
* **instrumentation:** leave a scheduled callable's interrupt status as the task left it ([63843e7](https://github.com/macstab/macstab-chaos-jvm-agent/commit/63843e7813ae627e4cce6cb21fa89fe5c85c50ed))
* **instrumentation:** one provider open, one serialization and one compound java.io read are each evaluated once ([bf1407e](https://github.com/macstab/macstab-chaos-jvm-agent/commit/bf1407efd0da15228b123e73f0ea6e30a52f8c2f))
* **instrumentation:** read the Hikari pool name a JDBC selector filters on ([2c7f4ef](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2c7f4efe57fcb167ac3d3fbab2be698cf83e2911))
* **instrumentation:** skew LocalDateTime/ZonedDateTime.now(ZoneId) and keep precision ([8047349](https://github.com/macstab/macstab-chaos-jvm-agent/commit/80473490956da94dd451c8194b281f55c99ae5a5))
* **instrumentation:** stop rewriting schedule units and evaluate SCHEDULE_SUBMIT once ([d2e14fc](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d2e14fc08814d0346417e21d79d17653578129cc))
* **instrumentation:** the agent no longer makes JDK 24+ warn about sun.misc.Unsafe ([c527af1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/c527af1d32fa2b656fc3cbf762f55ffa725c5db9))
* **instrumentation:** the agent's call of OkHttp's noMoreExchanges is no Method.invoke ([0b81c4f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0b81c4fde32be23df560b7cb745f1110a466370c))
* **instrumentation:** the agent's weaving is its own work, so a REFLECTION_INVOKE reject leaves no hook missing ([198ec37](https://github.com/macstab/macstab-chaos-jvm-agent/commit/198ec374230f7d379512ad7ad0a962f51f5586ba))
* **instrumentation:** the cancel of a suppressed Callable tick runs the future's own cancel as application code ([f3b3717](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f3b3717a57e08ada89bdd8284d5420a8e74b07e0))
* **instrumentation:** the session-header hosts reach the bootstrap copy the advice reads, also under a child-first loader ([7894cbe](https://github.com/macstab/macstab-chaos-jvm-agent/commit/7894cbe0ed3f00ca0514e98523b799c1fdb70a9f))
* **instrumentation:** the torn-write reset runs its reflection and close as agent-internal calls, so no scenario applies to them ([2c0ec88](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2c0ec88c16a192d71def6feef89111163d0161b0))
* judge chaos enablement by the source Spring resolves; reject a null plan ([8908c79](https://github.com/macstab/macstab-chaos-jvm-agent/commit/8908c79e088dca7c30fe64d46159567486bcbbca))
* **management:** a plan activated while the endpoint closes is stopped; stop-all continues past a failure; runtime errors stay in the log; a malformed id is a 400 ([e9ef3ba](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e9ef3bab4f86a226f17830ac322f32b737ba07c1))
* **management:** an activation whose plan a concurrent close() already stopped is answered as rejected ([a5a1472](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a5a14723e77568f50783a545f8568de91c817175))
* **management:** Quarkus management extension, Micronaut status codes ([2c45168](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2c45168da4b8cb3770f77f2e6ccfcc6de27e76e8))
* **micrometer, opentelemetry:** the series of ended scenarios are retired, and failure counts carry no scenario id ([cbeb58c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/cbeb58cc4450633f67ef3336caff08897681fcff))
* **micrometer:** ended scenarios' series are retired when a MeterFilter denies the chaos.scenarios gauges ([54c4052](https://github.com/macstab/macstab-chaos-jvm-agent/commit/54c4052ee97eba94267f2aabb74d277744542c1d))
* **micronaut, security:** injecting the control plane attaches the agent only when chaos is enabled ([0466d37](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0466d371ea64a972bc3221829afc3e9191f0e329))
* **micronaut,quarkus:** a chaos test class's application starts outside the class's session ([fe491bf](https://github.com/macstab/macstab-chaos-jvm-agent/commit/fe491bf9cada324fcb7eb6ff9ed8166eb0f50fe9))
* **micronaut:** decode a stop-by-id selector once ([ffa56b8](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ffa56b82117ab704f84ad20f828ffeedd4d368c3))
* **micronaut:** generate bean definitions so ChaosControlPlane is injectable ([3878cb3](https://github.com/macstab/macstab-chaos-jvm-agent/commit/3878cb35c08d5b7e625506bc441fd68ad2d089b5))
* **micronaut:** install the agent from the test control-plane bean ([19d5f62](https://github.com/macstab/macstab-chaos-jvm-agent/commit/19d5f62040ad4fec2fbb3241531fa94ae75f8a0d))
* **micronaut:** JVM scenarios activated through the control plane injected under @MicronautTest end with the test class ([a53ef0f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a53ef0fa55b97c4a3fa330dbe1f15094ea53f126))
* **opentelemetry:** the chaos.events description no longer names a scenario attribute it does not record ([f9098d4](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f9098d47ec7010658c74070c532169c766f62b49))
* **packaging:** shading keeps the agent's ByteBuddy names and switch ([ea4fd65](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ea4fd65e6a1f80591300439273222b87ab1fe4a3))
* **packaging:** the agent jar carries the project licence and the merged notices ([8742b6b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/8742b6b06b67296bfbdec94087977cdb542e013a))
* **packaging:** the agent manifest no longer asks for a native method prefix ([ce352e5](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ce352e53dc0a1d3e592a3ca8822a741868ac051e))
* **quarkus, instrumentation, packaging:** one agent version per JVM, and Quarkus shares the api and the agent ([889339b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/889339bdf5bf714993b0ff2e472c05ae261ff7bc))
* **quarkus, micronaut:** a plus in a stopped plan's id stays a plus, and @QuarkusChaosTest is inherited ([0d6fe3a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0d6fe3adcf14a6413e0f87f5cf1a4c381a75afa3))
* **quarkus, security:** the control-plane producer attaches the agent only when chaos is enabled at run time ([227fdfe](https://github.com/macstab/macstab-chaos-jvm-agent/commit/227fdfe211a70d6bbd8a5ec9e9440d0833cb1307))
* **quarkus, spring, micronaut:** every @ChaosScenario shorthand pair is valid or refused up front, and a stop answers scenarioId ([64c1e49](https://github.com/macstab/macstab-chaos-jvm-agent/commit/64c1e49452a2dac7dc710de28d4addeeb10957e3))
* **quarkus:** /q/chaos admits a request before it reads the body ([b484b83](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b484b839f698d04f8c410f9c0de80e42ed73d967))
* **quarkus:** /q/chaos is served by the management interface when it is enabled ([a060b22](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a060b220757322e2427760000f46617d169b0d4c))
* **quarkus:** /q/chaos requires an authenticated user ([eb83f3e](https://github.com/macstab/macstab-chaos-jvm-agent/commit/eb83f3efdcf644fd151abdf23cd6e5309e32dbc3))
* **quarkus:** a @QuarkusChaosTest class is a bean, so its @Inject fields are injected ([42aadac](https://github.com/macstab/macstab-chaos-jvm-agent/commit/42aadac5f72547816adf843f036d3719f891d3b0))
* **quarkus:** a @QuarkusTest without the testkit gets the control-plane bean without a warning ([0561bd9](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0561bd9f2e583a5c2c39a9aab85eeb0769b7be8f))
* **quarkus:** a subclass of a @QuarkusChaosTest class is a test-class bean too ([35863ec](https://github.com/macstab/macstab-chaos-jvm-agent/commit/35863eca3cb178c30ee89c6f2a0d3290b401590f))
* **quarkus:** an injected ChaosControlPlane in a Quarkus test is tracked and released after the last chaos class ([0154d7f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/0154d7f3ff2bc4147b720f7991d616a989ea9942))
* **quarkus:** contextPlane and releaseContextPlane are @Internal ([8037753](https://github.com/macstab/macstab-chaos-jvm-agent/commit/8037753f6160f84e1cf9169953de390936fd48a6))
* **quarkus:** JVM scenarios activated through the injected control plane end with the test class ([8e98991](https://github.com/macstab/macstab-chaos-jvm-agent/commit/8e989914dcd3fa93710c9840b37e0fdf595c9907))
* **quarkus:** make the extension load and honour application.properties ([95292dc](https://github.com/macstab/macstab-chaos-jvm-agent/commit/95292dcd53c0961cfa54564079c27deaaea0f6cb))
* **quarkus:** only a missing agent answers 503; other failures are 500 ([e51c9b9](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e51c9b993c13da4004ee8cb7d5f9d2bd608d3df1))
* **quarkus:** stop a method-level session @ChaosScenario with its method ([893b6d5](https://github.com/macstab/macstab-chaos-jvm-agent/commit/893b6d5bc6b4ab00379825d952cc3773fb85f657))
* **quarkus:** the bean producer finds the testkit where the test run loaded it ([3bc9951](https://github.com/macstab/macstab-chaos-jvm-agent/commit/3bc99514a53e581ba65dafc165ec5d54fc9e3baf))
* **quarkus:** the concrete implementations of a @QuarkusChaosTest interface are test-class beans ([23ac5d0](https://github.com/macstab/macstab-chaos-jvm-agent/commit/23ac5d06448c7bec35520782253efbaf6fba8486))
* **quarkus:** the freeze shorthand of @ChaosScenario also freezes CLOCK_READ ([1ac1ff6](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1ac1ff69c7e2c76d07bd21debcbe59eccfdbcd95))
* **quarkus:** the recorder keeps its single constructor ([a0ffe22](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a0ffe22abe705ccc6b8711a9e5f497165a4f1cda))
* **release:** align all repo-name references with actual GitHub repo ([4fa43e1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/4fa43e1da91b0f7f3bdd2f781420df77c8b20a58))
* **reload:** startup baseline, retries, swap races, grace period, owners ([34acf1d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/34acf1d39b3c16667b3fc16043d19ff37ac1efe3))
* **reload:** validate a reloaded plan before changing the active set ([b5bd35d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b5bd35d2892267ec21914902f105c8b53109e1bf))
* report the fields selectors filter on for sleeps and socket I/O ([fbd808b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/fbd808b08f8ba10e17e58eb11e104ca3de234df4))
* resolve ThreadLocal reentrancy - re-enable ThreadLocal/park/AQS instrumentation ([d36b528](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d36b52858583fd1f3d23adb05b9cfb803a6f8242))
* scenario re-activation, Spring context close and trusted enablement ([f0742cc](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f0742ccbca113d53ed81bc6b35fe66044a014ed6))
* **security:** logs and stderr never carry the parser's excerpt of a malformed plan ([151e996](https://github.com/macstab/macstab-chaos-jvm-agent/commit/151e9964f961e65879b5d25d1b4dc1d075193231))
* **security:** one trust rule for the switches that enable chaos ([753afbd](https://github.com/macstab/macstab-chaos-jvm-agent/commit/753afbd0df4e758fd0f5b58ea8e7567e5e3f3d73))
* **sessions:** isolate pool workers, header-driven requests, internal hosts ([ee33675](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ee33675fdaec9ff902d5d572bdf670645f48daf3))
* **spring, micronaut, quarkus:** nested classes share the outer session ([8d29a64](https://github.com/macstab/macstab-chaos-jvm-agent/commit/8d29a6444b43cb6104e5ff0a6e55b860ae43402c))
* **spring, micronaut:** a test context's control plane is released when the context closes ([a318579](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a318579ba3470967b2870e5d9ae5dd224277ae75))
* **spring, quarkus, micronaut:** the test extensions leave the parameters the framework resolves to it ([1e62824](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1e62824de3dea1d509680291cb7e6284c81cf92f))
* **spring, quarkus:** scenario and handle ids are sanitized before they are logged ([ceabac1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ceabac1566bb6995243954a3f1aae12d0c297403))
* **spring, security:** the actuator switch and the startup plan file follow the trusted-source rule of macstab.chaos.enabled ([8eca1fd](https://github.com/macstab/macstab-chaos-jvm-agent/commit/8eca1fd18081f151f1d2de72c570a2053db5b4aa))
* **spring:** /actuator/chaos refuses a body above the plan cap with 413 before Spring MVC binds it ([e2c087a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e2c087a7893d1f6f4c640a4581675b9488a1be24))
* **spring:** a @ChaosTest class's application context starts outside the class's session ([a9d0591](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a9d05914ba52836781315dd14f53726880f3d6c0))
* **spring:** a failed self-attach no longer fails the application ([32e59c7](https://github.com/macstab/macstab-chaos-jvm-agent/commit/32e59c74844bd7cf88291c31296f252d7febf02a))
* **spring:** JVM scenarios activated through a chaos test context's control plane bean end with the test class ([ae792d1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ae792d1a6a73bb3aad1f9b32a231431ffc4bfbbd))
* **spring:** order the test control plane before the application's ([60c3aab](https://github.com/macstab/macstab-chaos-jvm-agent/commit/60c3aabd97e4a37f4d99cefc1f851c590b19c192))
* **spring:** the session filter exists in a running application only when chaos is enabled ([d092a12](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d092a1281d940ab2d4f7700eb3a362b5ad225903))
* **spring:** the startup plan belongs to the contexts that hold it ([404801d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/404801dfac1236ff64d4f192d0c5486eb89e3860))
* **startup-config:** a blank chaos switch value is off without a warning ([5ed56a5](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5ed56a59ab4c207b749e661e614de4c4b6a3bee5))
* **startup-config:** a parse failure relays an unknown property's name and the property names of its path sanitised and capped ([ba90bd0](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ba90bd0db1cf0e2e9ce4bcffd4f9de4f494e762c))
* **startup-config:** a plan file's key is @Nullable, as BasicFileAttributes.fileKey() may return null ([f42a637](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f42a63706317605bca505af76adea92b03b5c5bb))
* **startup-config:** a plan's parse failure names its kind and the property ([59900a4](https://github.com/macstab/macstab-chaos-jvm-agent/commit/59900a47eecef57683a10a8f3279f125c018c214))
* **startup-config:** keep backslashes, accept flags, strict base64, bounded reads ([02a1a49](https://github.com/macstab/macstab-chaos-jvm-agent/commit/02a1a49be75d011ba0132a8f826f72500d1d0445))
* **startup-config:** one plan reader for every source ([2a6b87e](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2a6b87ed97b65e6a564d49422373bb69113576d8))
* **startup-config:** report wrapped syntax errors and unknown properties as documented ([b5fa3e8](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b5fa3e82323cf73db67da1495445d2107895da0c))
* **startup-config:** the plan schema's constraints are probed from the mapper ([959fb42](https://github.com/macstab/macstab-chaos-jvm-agent/commit/959fb4299eb81080d7cd4ff321474c4addb8b27d))
* **startup:** a plan in a linked directory loads, and the owner check runs only on POSIX file systems ([99641e5](https://github.com/macstab/macstab-chaos-jvm-agent/commit/99641e546c8c8b48d6e4fc6fa83c41905911fd5c))
* **testkit, spring, micronaut, quarkus, core:** a test's sessions and listeners end with it, @WithChaos keeps its failures and its plan, and teardown failures are logged ([2a2fcc5](https://github.com/macstab/macstab-chaos-jvm-agent/commit/2a2fcc5acecfe0f3139da29e20e2769fffe3e586))
* **testkit:** apply the enclosing classes' @WithChaos to @Nested tests ([c832030](https://github.com/macstab/macstab-chaos-jvm-agent/commit/c832030b088a4f827d8556bf320c56cb9481b387))
* **testkit:** refuse a chaos parameter outside a test instead of injecting null ([c4ffb09](https://github.com/macstab/macstab-chaos-jvm-agent/commit/c4ffb0949833e5564edd81f6bb1e3e38202473c4))


### Build System

* **quarkus, micronaut:** the Quarkus and Micronaut modules no longer export the testkit ([7675aa0](https://github.com/macstab/macstab-chaos-jvm-agent/commit/7675aa04118a9430d67fa4fade62638ba52a68e4))


### Code Refactoring

* mark the cross-module implementation types @Internal and narrow the rest ([f1bf3f4](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f1bf3f499f9d67dcc6e008d5d00b6936e9a723d5))


### Documentation

* the actuator's HTTP statuses are a 2.0 breaking change, with its migration step ([1127ee4](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1127ee4e315056ea31652754593a0f68d6426818))


### Features

* **api:** add ActivationPolicy.builder() ([de1376e](https://github.com/macstab/macstab-chaos-jvm-agent/commit/de1376e00f510e603413f838ca988ac4fd3b5813))
* **api:** ClockSkewMode.REGRESS, a one-time backward step ([d4fb8f2](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d4fb8f204c3a90182914f15da14083f14f42e515))
* **api:** decision replay with ChaosDecisionRecorder and replayMatches ([31e75e9](https://github.com/macstab/macstab-chaos-jvm-agent/commit/31e75e9c26b547af5ddbfd7da974dc0d37d0ecd5))
* **api:** ErrnoEffect, typed operating-system errors ([01686c6](https://github.com/macstab/macstab-chaos-jvm-agent/commit/01686c668495d4010d1e5651dea1411625e0324c))
* **api:** fault presets ([61d9ff3](https://github.com/macstab/macstab-chaos-jvm-agent/commit/61d9ff3b2b98bc785f0a22d6f259f468b64fec33))
* **bootstrap:** ChaosPlatform.installed() asks whether the agent is installed without throwing ([1a106b8](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1a106b87e4b4a2c0b5821255972f9ab61f1edf4b))
* **bootstrap:** status and hits files in the native library's format ([82d9a17](https://github.com/macstab/macstab-chaos-jvm-agent/commit/82d9a17e4147f8db1258be6f0724712f1d6a40d7))
* carry chaos sessions across HTTP with X-Chaos-Session ([5b3917d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5b3917db11f7a0de66e352602278108eb259d16a))
* carry sessions into ForkJoin tasks, started threads and virtual threads ([1791e9c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1791e9c17f694b8f7d46e5051dd2cc061581049f))
* clock skew at System.currentTimeMillis/nanoTime call sites ([7d12254](https://github.com/macstab/macstab-chaos-jvm-agent/commit/7d12254c48b4769dca96878697ee5f62654060dc))
* **demo:** one-fault demos checked in CI; fix JDK HttpClient URL filters ([88ca570](https://github.com/macstab/macstab-chaos-jvm-agent/commit/88ca57053fb4e80c321f80084afef67d32ddb34b))
* DNS answer shaping and host rewrite ([953f373](https://github.com/macstab/macstab-chaos-jvm-agent/commit/953f373b4fd3324fed6df0e3d7d990529fc0b792))
* **effects:** redirect a connect to another endpoint ([077c6d9](https://github.com/macstab/macstab-chaos-jvm-agent/commit/077c6d9ab180c48188331dead5417c3da7e8f7cb))
* event model, JMX control, handle counters and Micrometer binder ([d15034f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/d15034fc379f7d18b6193f279d6784673023f68f))
* file truncate/delete/move operations and torn socket writes ([c55b892](https://github.com/macstab/macstab-chaos-jvm-agent/commit/c55b89260156797d3c63c0b2620aea136ff0b950))
* FileChannel, RandomAccessFile and fsync hooks; tornWrite ([45f5567](https://github.com/macstab/macstab-chaos-jvm-agent/commit/45f5567598476bdc728583057e0fa76c308cc817))
* final code qa and final hands on ([#2](https://github.com/macstab/macstab-chaos-jvm-agent/issues/2)) ([a20078c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a20078cb96dc8aa46abe4e7ce9ca8dae0e5080a5))
* Finish the project. Add CI workflows for build, test, deploy, release, and semantic versioning ([#1](https://github.com/macstab/macstab-chaos-jvm-agent/issues/1)) ([a0b7a9b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a0b7a9bcb4f5516cf2a756deca8388905d8cb074))
* hooks for Kafka, gRPC, Redis, R2DBC, the AWS SDK and Netty ([9228028](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9228028170102c03050e11212c3eadf4d86fe5d4))
* implement missing stressors, ClockSkew, ExceptionInjection, ReturnValueCorruption, fix state machine, registry cleanup, maxApplications race, logging levels ([ef28412](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ef28412d73c2727508cf27391e0ce455bc77eeeb))
* initial chaos testing Java agent implementation ([728e2b8](https://github.com/macstab/macstab-chaos-jvm-agent/commit/728e2b86d9f4de66b02f98b32e44b8b1e0e16c89))
* Initial release of the jvm agent ([#3](https://github.com/macstab/macstab-chaos-jvm-agent/issues/3)) ([b3fa15d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b3fa15d6ed4bfbbd927540770d6e982ff2662420)), closes [JdkInstrumentationInstaller#install](https://github.com/JdkInstrumentationInstaller/issues/install)
* **instrumentation:** closes, move targets, half-closes, select and wait timeouts ([63d6bb2](https://github.com/macstab/macstab-chaos-jvm-agent/commit/63d6bb23e58a155d2183f7e745f9b95871bdbe00))
* **instrumentation:** file opens, binds, accept and thread-start errnos ([c94a449](https://github.com/macstab/macstab-chaos-jvm-agent/commit/c94a449acd585d4d4ec70910a69d779f1689c756))
* **instrumentation:** hook coverage self-check, fix Apache HttpClient 4 ([31dd459](https://github.com/macstab/macstab-chaos-jvm-agent/commit/31dd459bc948a8b3567c7975baa5557c67fac915))
* **instrumentation:** intercept java.net.HttpURLConnection ([07d3024](https://github.com/macstab/macstab-chaos-jvm-agent/commit/07d3024ff6f88eaf7c13a88d1a6fa92636e0198a))
* **instrumentation:** memory-mapped files, transfers and Files.copy ([71a945d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/71a945d26a36eef9bb4e773e4e12dd0da253b381))
* **instrumentation:** skew every java.time read through CLOCK_READ ([5347e08](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5347e083b3ecdd23d3f3a97cf194159b054ab2cd))
* **instrumentation:** socket channels as sockets, scattering and gathering I/O ([76162eb](https://github.com/macstab/macstab-chaos-jvm-agent/commit/76162ebc32f1e40b1a73b952dd0499e199a719e8))
* **instrumentation:** UDP datagrams and reverse DNS lookups ([ff1c246](https://github.com/macstab/macstab-chaos-jvm-agent/commit/ff1c246f73ff99498dde0df1a6567e8c624e2863))
* **instrumentation:** weave MethodSelector scenarios automatically ([3677c11](https://github.com/macstab/macstab-chaos-jvm-agent/commit/3677c11ce2aa350497e019eb6400aa76c518530d))
* **micronaut,quarkus:** chaos management endpoints ([1b6a30a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1b6a30a5f61c99f78af8e76f11710fa682340d69))
* **observability:** OpenTelemetry module, delay metrics, plan observability ([1e7324c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/1e7324cd37fde7b4404a8138ba1a464ccd8884cc))
* peer, path and size filters for network and file I/O selectors ([e9f1dcd](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e9f1dcd1aa150b354d8ba975c4fab95ae27d3885))
* phase 2 - full JVM chaos coverage across 30+ new interception points ([9463027](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9463027fe51c0bb5e6c9fe859a36f5bbb4f678e3))
* process-start faults ([3fe2fa7](https://github.com/macstab/macstab-chaos-jvm-agent/commit/3fe2fa7e21938723e5436c03e437d0aa1c922560))
* **startup-config:** publish the plan JSON schema, specify the format ([39368b1](https://github.com/macstab/macstab-chaos-jvm-agent/commit/39368b1186b6546e9600a8868d3cc93de98152d5))
* **startup-config:** robust reloads and Kubernetes ConfigMap mounts ([f244cbd](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f244cbdf4d6976bf46c238ee41b2293520b41eff))
* **testkit:** @WithChaos plans and ChaosAssertions across test extensions ([a41864c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/a41864c5ec5f174d192a3f534b0c6e0953610b23))
* trickle, corruptRead, timeout and connectionReset effects ([f8e573c](https://github.com/macstab/macstab-chaos-jvm-agent/commit/f8e573c431db0d52c380945989bcad1bbf789e78))


### Performance Improvements

* benchmark the new hot-path hooks, share the no-op decision ([9370747](https://github.com/macstab/macstab-chaos-jvm-agent/commit/9370747bbc401763e390fff10a8c8c600fcb80b6))
* **core:** index scenarios by operation type ([be57b59](https://github.com/macstab/macstab-chaos-jvm-agent/commit/be57b59ff7ad5400c75b5921630f80df312fcf87))
* **core:** LocalDateTime.now() without a clock scenario returns before resolving the default zone ([83d739b](https://github.com/macstab/macstab-chaos-jvm-agent/commit/83d739bb27ecf5a2aeb594df30e5f03de9ab825a))
* **core:** skip the call context when no scenario covers the operation ([7a36bd9](https://github.com/macstab/macstab-chaos-jvm-agent/commit/7a36bd965c38f9efd1070bcaf73e4d8b0364a7dc))
* **core:** the stack walk of a Method.invoke runs only for a call a REFLECTION_INVOKE scenario selects ([3a1a4bb](https://github.com/macstab/macstab-chaos-jvm-agent/commit/3a1a4bbba1cc8c08a22788dc3824f7ade4f4297f))
* **instrumentation:** hierarchy hooks walk only the JDK's own implementations among JDK classes ([e52b40d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/e52b40db9894a462a870e2708cdd7c0141ca673b))
* **instrumentation:** the beforeExecute-override rule reads no JDK class's methods ([463a53a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/463a53af9e616527b30046af4ecd74eeea44570f))
* **instrumentation:** whether a pool class declares its own toString() is looked up once per class, not on every connection acquire ([055a21f](https://github.com/macstab/macstab-chaos-jvm-agent/commit/055a21f0f4e87013ff7e0ffdb59f76a2772194f2))
* remove per-call allocations from the dispatch path ([68645f6](https://github.com/macstab/macstab-chaos-jvm-agent/commit/68645f6ffcc56ff7d7047c636b90698a97498fd3))


### Reverts

* Revert "fix(bootstrap): the status of a rejected reload counts the scenarios still running" ([5e77884](https://github.com/macstab/macstab-chaos-jvm-agent/commit/5e7788482a072956f3741a8a735b7d8c9de9b105))


### BREAKING CHANGES

* a number or a boolean in a string field and an operation listed twice are refused with ConfigLoadException; see docs/migration-2.0.md section 25 (Plan JSON is read strictly and written in ISO-8601).
* close() on the control plane returned by ChaosPlatform.installLocally() or current() throws UnsupportedOperationException; see docs/migration-2.0.md section 45 (The control plane of ChaosPlatform cannot be closed).
* a stringInternPressure whose strings exceed the free heap is refused at activation; see docs/migration-2.0.md section 21 (Stressor parameters are refused instead of reduced).
* Socket scenarios also match socket channels (docs/migration-2.0.md §1).
* ChaosEffect.Errno.code() is the current OS's number (docs/migration-2.0.md §8).
* OperationType gained constants between existing ones (docs/migration-2.0.md §9).
* Bridge handle slots renumbered (docs/migration-2.0.md §10).
* **quarkus, micronaut:** The Quarkus and Micronaut modules no longer bring chaos-agent-testkit (docs/migration-2.0.md §43).
* Of chaos-agent-bootstrap, only ChaosPlatform is API (docs/migration-2.0.md §44).
* A thread-local leak or reference-queue flood larger than the free heap is refused at activation (docs/migration-2.0.md §21).
* A Method.invoke whose caller is a JDK class is not a REFLECTION_INVOKE target (docs/migration-2.0.md §33).
* a spuriousWakeup scenario whose NioSelector also lists a channel operation is refused at activation (migration §42).
* **testkit, spring, micronaut, quarkus, core:** A test's sessions and listeners end with it (docs/migration-2.0.md §41).
* The JFR sink and the reports' counter build no SKIPPED events, and JFR names the message and records match and delay (docs/migration-2.0.md §40).
* A gate read from JSON needs maxBlock, and the randomSeed default is stated as applied (docs/migration-2.0.md §39).
* The event log line is quoted and escaped, and attributes and operations keep one order (docs/migration-2.0.md §38).
* A stressor that cannot, or no longer does, produce load does not stay ACTIVE (docs/migration-2.0.md §37).
* An injected exception is a JDK class resolved once, at activation (docs/migration-2.0.md §36).
* The socket adaptors, SSL accept, JDBC batches and scheduled and virtual-thread executors reach their hooks (docs/migration-2.0.md §35).
* Method hooks never weave JDK classes, and match-all method selectors are refused (docs/migration-2.0.md §34).
* The JDK's own use of a general primitive is not a target (docs/migration-2.0.md §33).
* An R2DBC reject is signalled through the publisher execute() returns (docs/migration-2.0.md §32).
* Reactor Netty is evaluated per subscription, and a reject is an error signal (docs/migration-2.0.md §32).
* OkHttp is evaluated in the call chain, so enqueue rejects reach the callback (docs/migration-2.0.md §32).
* A JDK HttpClient.sendAsync reject returns a failed future (docs/migration-2.0.md §32).
* A Lettuce reject completes the command exceptionally, and a batch is evaluated (docs/migration-2.0.md §32).
* A Kafka send reject fails the send's callback, interceptors and future (docs/migration-2.0.md §32).
* A Netty write reject fails the flushed writes and closes the channel (docs/migration-2.0.md §32).
* A gRPC reject fails the call through gRPC's FailingClientStream (docs/migration-2.0.md §32).
* Apache HttpClient is evaluated at doExecute, with one URL rule (docs/migration-2.0.md §31).
* ChaosEvent.Type has the new constant INACTIVE; exhaustive switches over it must handle it, and STOPPED is published once per scenario (migration-2.0.md §30).
* chaos-agent-testkit no longer brings junit-jupiter-api; declare JUnit in the test dependencies.
* Count assertions under within hold for the duration, and their waiting is unobserved (docs/migration-2.0.md §28).
* Kotlin sees the API's types as non-null or nullable instead of platform types (docs/migration-2.0.md §27).
* a name pattern with a mode and no value, a value and no mode, or ANY with a value other than blank or "*" is refused (docs/migration-2.0.md §26).
* plans with numeric durations or instants, coerced scalars, enum ordinals or duplicate properties are refused, and JSON output carries ISO-8601 strings instead of decimal seconds (docs/migration-2.0.md §25).
* ActivationPolicy.fromJson is no longer public (docs/migration-2.0.md §24).
* set-up and tear-down methods no longer run under @WithChaos scenarios; the framework extensions implement BeforeTestExecutionCallback/AfterTestExecutionCallback instead of BeforeEachCallback/AfterEachCallback (docs/migration-2.0.md §23).
* the four short ChaosRuntime overloads are removed.
* heap pressure above the free heap, StringIntern counts or lengths beyond their limits, and stressor intervals below 1 ms fail at activation.
* plans with the refused rejects fail at activation; the destructive-effect refusal is a ChaosValidationException.
* the reject and file errno exception types changed (DNS_ANSWER, NIO select, socket reads and writes, ZIP_INFLATE, REFLECTION_INVOKE, SCHEDULE_SUBMIT, QUEUE_PUT, EXECUTOR_AWAIT_TERMINATION, THREAD_START, SHUTDOWN_HOOK_REGISTER, java.nio opens, client fallbacks, file errnos on descriptor calls); errnos are refused on calls that never report them. Migration: docs/migration-2.0.md section 19.
* a suppress on HTTP_CLIENT_* or JDBC_* fails activation with ChaosValidationException; ChaosHttpSuppressException and ChaosJdbcSuppressException are removed; the seven HTTP/JDBC bridge methods return void. Migration: docs/migration-2.0.md section 18.
* **core:** A pool task carries a session only once the session has a scenario (docs/migration-2.0.md §16).
* **core:** Reject and suppress on QUEUE_OFFER skip queues without a capacity limit (docs/migration-2.0.md §15).
* /actuator/chaos answers with the operation's HTTP status (docs/migration-2.0.md §12).
* **spring, security:** Where enabling switches may come from (docs/migration-2.0.md §14).
* a GLOB's '*' now matches any sequence, line terminators
included; in 1.x it was the regex '.*', which stops at a line break,
while '?' matched one. '?' is one code point, a surrogate pair counting
as one character. Use a REGEX where a pattern must not cross a line
break (docs/migration-2.0.md section 11).

Globs no longer compile a regex: one allocation-free two-pointer match
over code points replaces the matcher and the regex it fell back to,
with its cache and translation.
* removeEventListener, matchedCount and appliedCount are
new abstract interface methods.
* clock-skew scenarios with maxApplications, rateLimit,
activateAfterMatches or probability < 1.0 are rejected.
ActivationPolicy.fromJson takes activeFrom and activeUntil.
* chaos-agent-bootstrap has no transitive dependencies
and its ByteBuddy and Jackson packages are relocated. Build the agent
with :chaos-agent-bootstrap:shadowJar.
* chaos-agent-api no longer depends on
jackson-annotations. Serialising or deserialising plans with a mapper
other than ChaosPlanMapper loses the type discriminators; use
ChaosPlanMapper.read/write.
* the com.macstab.chaos.{api,core,bootstrap,
instrumentation,startup,testkit} packages are gone; use the
com.macstab.chaos.jvm equivalents. BootstrapDispatcher handle indices
above 4 shift down by one and HANDLE_COUNT is 57.
* the first activation of a scenario pays the weaving cost
of its hook group. Dynamic attach (agentmain) now gets the same hook set as
premain; the premainMode parameter of ChaosAgentBootstrap.initialize is gone.

# 1.0.0 (2026-04-29)


### Bug Fixes

* resolve ThreadLocal reentrancy - re-enable ThreadLocal/park/AQS instrumentation ([738d4ed](https://github.com/macstab/macstab-chaos-jvm-agent/commit/738d4ed3613f0f67483bc4323febdc77201a57b3))


### Features

* final changes required for inital version  ([b62fb6d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b62fb6d77650ddaee5d3d63bbd8457c2213f8323)), closes [#1](https://github.com/macstab/macstab-chaos-jvm-agent/issues/1) [#2](https://github.com/macstab/macstab-chaos-jvm-agent/issues/2) [#3](https://github.com/macstab/macstab-chaos-jvm-agent/issues/3) [JdkInstrumentationInstaller#install](https://github.com/JdkInstrumentationInstaller/issues/install)
* implement missing stressors, ClockSkew, ExceptionInjection, ReturnValueCorruption, fix state machine, registry cleanup, maxApplications race, logging levels ([250413a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/250413ad85539b5e66357c6bd2623823ca4cad73))
* initial chaos testing Java agent implementation ([68eff23](https://github.com/macstab/macstab-chaos-jvm-agent/commit/68eff238fc09d6d5bc6a22127fc07414ce56037a))
* phase 2 - full JVM chaos coverage across 30+ new interception points ([6132125](https://github.com/macstab/macstab-chaos-jvm-agent/commit/6132125b367a24518b78f2ed96ea76dd0494b9ed))
