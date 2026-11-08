# Evaluation Semantics

> The rules the agent follows when an intercepted call meets the active scenarios, stated as laws. Every law here is enforced by code and covered by tests; where a law names a class, that class is where it is enforced.

---

## 1. Identity

**L1 — No scenario, no change.** With no active scenario and no open session the agent weaves no JDK class (hooks are woven per hook group on the first activation or `openSession` that needs them, `JdkInstrumentationInstaller.HookGroup`) and alters no call. A session without scenarios alters no call either: a task submitted to a pool from it reaches the pool unchanged, and is wrapped to carry the session only once the session has a registered scenario. With `instrumentation=eager` the hooks are woven at start, and each intercepted call returns its unmodified result after one index lookup.

**L2 — An empty plan is no plan.** A plan whose scenarios all fail to match behaves exactly like no plan for every call they do not match. Woven hook groups stay woven until the agent is uninstalled, but a woven hook with no matching scenario returns the call unmodified. Method hooks (`METHODS`) are the exception: removing the last method scenario restores the original bytecode.

**L3 — The agent never evaluates itself.** Calls made by the agent's own code are never matched: the reentrancy depth is raised while they run (`BootstrapDispatcher`). That covers evaluation, reflective URL extraction, expiry threads, and stressors with their set-up: a stressor is built with the depth raised, and its threads are started, run and stopped with it raised, so a user `THREAD_START` reject or `THREAD_SLEEP` delay never reaches them. The thread-local-leak stressor's planting tasks on the common pool are skipped by `FORK_JOIN_TASK_RUN` and plant with the depth raised (the planted ThreadLocals themselves are plain ones: they are the leak). The agent's own ThreadLocals (the session stack, the corruption seed) are skipped by `THREAD_LOCAL_GET` / `THREAD_LOCAL_SET`, so a ThreadLocal scenario never hides the current session. One exception lowers the depth inside a dispatch: an exceptional-completion effect completes the future with the depth lowered, after its application is committed, so the dependent stages that run inside that completion (`whenComplete`, `handle`, `exceptionally`, …) are evaluated like any other call; only the nested completion of that same future passes unevaluated.

**L4 — One logical call, one evaluation.** When a hooked method reaches another hooked method for the same operation (`getByName` → `getAllByName`, a pool proxy → the driver statement, an overload → its delegate, `Files.newInputStream` → the provider's `newByteChannel` → `newFileChannel` on JDK 24+, `writeObject` of a list → the `writeObject` of each element, `FileInputStream.readAllBytes` / `readNBytes(int)` and `RandomAccessFile.readLine` → the stream's own reads), only the outermost call is evaluated (call-depth scopes, `BootstrapDispatcher.enterScope`). Delays do not add up across the nesting, the probability is drawn once and `maxApplications` is spent once. Bridge methods are never hooked: a call through a bridge is the one call of the method it bridges to. Still evaluated once per inner read (documented, not merged): `FileInputStream.transferTo` to a stream that is not a file, `RandomAccessFile.readUTF`, and `readNBytes(byte[], int, int)` inherited from `InputStream`.

## 2. Matching one scenario

A scenario matches a call when its selector matches the call's context. Every field of a selector is a conjunct:

**L5 — Filters are conjunctive.** The operation must be in `operations`, and every non-null filter must hold (`SelectorMatcher`).

**L6 — Unknown never matches.** A filter on a value the call does not carry is not satisfied: an unresolved peer never lies in a `remoteCidr`, a stream without a path never matches a `pathPattern`, an operation without a byte count never satisfies `minBytes`/`maxBytes`. Selectors that combine such a filter with an operation that can never carry the value are rejected at construction or activation (a size filter with `SOCKET_CONNECT`, a peer filter with `SOCKET_ACCEPT`).

## 3. The policy of one scenario

For a matching call, the scenario's `ActivationPolicy` is checked in this order; the first failing step ends the evaluation (`ScenarioController.evaluate`):

| Step | Check                                                     | On failure                                           | Counted as a match? |
|------|-----------------------------------------------------------|------------------------------------------------------|---------------------|
| 1    | The scenario is started (`ACTIVE`)                        | skip                                                 | no                  |
| 2    | The call's session equals the scenario's session, if any  | skip                                                 | no                  |
| 3    | The selector matches (§2)                                 | skip                                                 | no                  |
| 4    | Now is before `activeUntil` and within `activeFor`        | the scenario becomes `INACTIVE` (`"expired"`)       | no                  |
| 5    | Now is at or after `activeFrom`                           | skip                                                 | no                  |
| 6    | More than `activateAfterMatches` matches have been seen   | skip                                                 | yes                 |
| 7    | The probability draw succeeds                             | skip                                                 | yes                 |
| 8    | A rate-limit permit is available                          | skip                                                 | yes                 |
| 9    | An application fits under `maxApplications` (reserved)    | skip                                                 | yes                 |

**L7 — Time bounds use the real clock.** `activeFrom`, `activeUntil` and `activeFor` are read from the system clock inside the dispatch, where the agent's own clock hooks return the real value: a clock skew the agent injects cannot move them.

**L8 — The probability is in (0, 1].** `0.0` is rejected; a scenario that must never fire is not activated. `1.0` always passes step 7.

**L9 — Draws are reproducible.** With a fixed `randomSeed`, the probability draw and the sampled delay of the *n*-th match are fixed: both derive from `seed ^ n ^ id.hashCode()`, the delay from an independent split stream, so they are not correlated. So are the effects' own choices of that match: the length of a torn write, the bit a corrupted read flips and the order of a shuffled DNS answer each derive from the same seed, salted per kind of choice.

**L9a — A replay log replaces the draw.** With `replayMatches`, step 7 passes exactly on the listed match numbers (counted from 1 over every match since the scenario started, warm-up included, as step 6 counts them). `ChaosDecisionRecorder` records the numbers from the `match` attribute of `APPLIED` events, so a run — seeded or not — replays decision by decision as long as the calls arrive in the same order.

**L10 — A rate-limit window starts at activation.** Windows are fixed, anchored at the scenario's start (not at an epoch boundary); a clock that steps back re-anchors the window.

## 4. Composing several scenarios

All scenarios that pass §3 for a call contribute. They are ordered by descending `precedence`, then ascending scenario id, then scope key (`ScenarioRegistry`): the same id in the JVM scope (`"jvm"`) comes before its sessions (`"session:<id>"`), and sessions by id, so an equal-precedence tie between scopes is decided the same way on every thread. The same order answers `ChaosDiagnostics.scenario(id)` for an id registered in several scopes: the JVM scope's report, else the session with the smallest id.

**L11 — Delays add up.** The delays of every contribution are summed and applied first. A single delay is at most 720 hours, so the sum stays far from overflow (it saturates at `Long.MAX_VALUE` ms regardless).

**L12 — One gate.** Of the gate contributions, the one with the highest precedence (the smallest scenario id on a tie) holds the caller, after the delay; a sole gate holds it whatever its precedence, `Integer.MIN_VALUE` included. Expiry (`activeFor`, `activeUntil`) releases every caller held at the gate; a spent `maxApplications` does not, so its last admitted caller is held like the others until `release()` or `stop()`. A `release()` of an `INACTIVE` scenario opens the gate once without closing it again.

**L13 — One terminal.** Every effect other than a delay or a gate is a terminal: reject, suppress, an errno, exception injection, exceptional completion, return-value corruption, timeout, connection reset, redirect, a DNS answer or rewrite, a spurious wakeup, and the data-shaping effects trickle, torn write and corrupted read. They are exclusive and do not compose: of the terminal contributions, the one with the highest precedence (the smallest scenario id on a tie) decides the outcome, after the delay and the gate, so a trickle and a corrupted read on the same read never both apply. A sole terminal decides whatever its precedence, `Integer.MIN_VALUE` included.

**L14 — Only what takes effect counts.** `APPLIED` is published, `appliedCount` grows and `maxApplications` is spent only for contributions that take effect: every delay, the winning gate and the winning terminal. A scenario that matched but lost on precedence keeps its budget (`ScenarioContribution.commit`/`release`). An effect that cannot change the call in hand does not take effect either, and counts nothing: a timeout on a call without one, a torn write of fewer than two bytes or to a file stream, a trickle on a read no larger than its cap, a corrupted read of no bytes or of a kernel transfer, a trickle or corrupted read of a channel read into a read-only `ByteBuffer` (the JDK refuses such a read with `IllegalArgumentException`, so it is evaluated as a read of no bytes: byte count 0; a delay still applies), a DNS rewrite of the local-host lookup, a reject or suppress of `QUEUE_OFFER` on a queue without a capacity limit (`remainingCapacity()` is `Integer.MAX_VALUE`, or no less than `Integer.MAX_VALUE - size()`: an unbounded `LinkedBlockingQueue`, a `PriorityBlockingQueue`, a scheduled pool's delay queue), which is never full. Delays and gates on such an offer still apply. Every effect the validator accepts on an operation changes that operation's call when it counts (`EffectReachesTheCallTest`); a DNS shuffle that happens to keep the order is one of its draws and counts. A corrupted read is decided before the read runs, so one that ends up at end of stream (no bytes returned) counts although no bit is flipped.

**L15 — An interrupted gate restores the interrupt.** An interrupt ends the gate wait; the interrupt flag is restored and the call proceeds, so the intercepted method observes the interrupt as it would without chaos.

## 5. Clock skew and stressors

**L16 — A clock skew applies to every clock read while its time bounds hold.** It honours `activeFor`, `activeFrom` and `activeUntil` and is session-scoped when its scenario is. It is not a per-call application, so `maxApplications`, `rateLimit`, `activateAfterMatches` and `probability < 1.0` are rejected for it. `FREEZE` freezes at the moment the scenario becomes active (`activeFrom` when it is later than the activation), the wall clock and `nanoTime` together. Wall-clock reads are skewed in whole milliseconds, rounded at each read, so millisecond and `Instant` reads agree; `nanoTime` to the nanosecond. Without a clock-skew scenario a `java.time` read is returned before any conversion.

**L17 — A stressor runs from activation until it ends.** It ends on `stop()`/`close()`, when `activeFor` elapses or at `activeUntil`. `activeFrom`, `maxApplications`, `rateLimit`, `activateAfterMatches`, `probability < 1.0` and `replayMatches` are rejected for it. Its parameters are never reduced silently: heap pressure above the free heap, more interned strings than their length makes distinct or than the caps, and an interval below 1 ms are refused at activation; a duration too large for nanoseconds lasts until the stressor ends.

**L18 — Unrecoverable states need consent.** Deadlocks, leaked non-daemon threads and holding every virtual-thread carrier (which stops every virtual thread in the JVM) require `allowDestructiveEffects`. Without it, a carrier-pinning stressor leaves one carrier free.

## 6. Lifecycle

**L19 — Stop is final for a handle.** A stopped or expired scenario never fires again; `start()` on it is a no-op.

**L20 — A finished scenario can be replaced.** Activating an id whose scenario is `STOPPED` or `INACTIVE` replaces it atomically; activating an id whose scenario is still `REGISTERED` or `ACTIVE` throws `ChaosActivationException` and changes nothing.

**L21 — A failed activation leaves no trace.** If validation or weaving fails, the scenario is not registered, the only event published for its id is `FAILED`, and the failure is recorded in diagnostics and on that event with its category (`INVALID_CONFIGURATION`, `UNSUPPORTED_RUNTIME`, `ACTIVATION_CONFLICT`, `INSTRUMENTATION_FAILURE`).
