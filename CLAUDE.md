# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## What this is

An Eclipse plug-in that adds TypeScript support (syntax highlighting, completion, compile-on-save,
refactoring) to the Eclipse IDE. It is an OSGi bundle built with Maven Tycho.

The plug-in does not implement TypeScript itself. It runs the real TypeScript compiler services in a
**node child process** and talks to it over stdin/stdout. Most work in this repository is either Java
(the Eclipse side) or TypeScript (the node side).

## Layout

| Path | What it is |
| --- | --- |
| `com.palantir.typescript/` | The plug-in bundle. Java source in `src/`, node-side source in `bridge/src/`. |
| `com.palantir.typescript/bridge/` | The TypeScript sources compiled into `bin/bridge.js`. |
| `com.palantir.typescript.tests/` | JUnit test fragment (`eclipse-test-plugin`). |
| `com.palantir.typescript.feature/` | The Eclipse feature that packages the plug-in. |
| `com.palantir.typescript.p2updatesite/` | The p2 update site build. |
| `scripts/updateTypeScript.sh` | Refreshes the vendored TypeScript compiler. |

## The bridge protocol

`com.palantir.typescript.services.Bridge` owns the node process. The protocol is line-oriented over
stdin/stdout, one line per message:

- Java writes a JSON request to node's **stdin**.
- Node replies on **stdout** with `RESULT: <json>`, or `ERROR: <stack trace>`, or a bare line, which
  is treated as a log statement.
- Anything node writes to **stderr** is not part of the protocol. It is drained on a background
  thread and kept for crash reports.

Two endpoints share this transport, and they differ in a way that matters:

- `classifier` (`Classifier`) is **stateless** — a request carries everything needed to answer it, so
  a request may be replayed against a fresh process.
- `language` (`LanguageEndpoint`) is **stateful** — the node process holds the lib contents and the
  initialized projects. A request must never be replayed silently, because a fresh process would
  answer it from empty state. It registers a restart listener to re-send the lib contents, and
  callers reinitialize projects via `isProjectInitialized`.

When changing `Bridge`, keep three invariants:

1. **stderr must always be drained.** A pipe buffer is around 64 KB; a process that fills it blocks
   forever on write, and the plug-in then hangs rather than fails. See `BridgeTest`.
2. **A crash report must name the exit code and the stderr tail.** Without them a crash is
   undiagnosable, which is what issue #354 was about.
3. **Restarts must stay bounded.** `MAX_CONSECUTIVE_RESTARTS` guards against a process that dies
   immediately every time; the counter resets when a request succeeds.

## Constraints to respect

- **Java 6.** `Bundle-RequiredExecutionEnvironment: JavaSE-1.6`, and
  `.settings/org.eclipse.jdt.core.prefs` sets compliance and target to 1.6. No diamond operator, no
  try-with-resources, no multi-catch, no `addSuppressed`, no `Process.waitFor(long, TimeUnit)`.
- **Checkstyle** (`com.palantir.typescript/checks.xml`) is part of the Eclipse build:
  - Every `.java` and `.ts` file starts with the exact Apache 2.0 header followed by one blank line.
  - No tabs, no trailing whitespace, LF endings, newline at end of file.
  - Javadoc uses third person (`Returns`, not `Return`), and `@return` text has no trailing period.
  - Prefer Guava factories (`Lists.newArrayList()`) over `new ArrayList<...>()`.
- **Dependencies are vendored** as jars in `com.palantir.typescript/lib/` (Guava 19, Jackson 2.7.2)
  and listed in both `META-INF/MANIFEST.MF` (`Bundle-ClassPath`) and `build.properties`
  (`bin.includes`). Adding a jar means editing all three places plus `.classpath`.
- Code style: explicit `this.` on field and method access, 4-space indent, no wildcard imports.

## Building

```sh
npm install                 # grunt toolchain for the node side
grunt                       # compiles bridge/src/*.ts into com.palantir.typescript/bin/bridge.js
mvn package                 # builds the bundle, feature and update site via Tycho
mvn integration-test        # runs the test fragment under tycho-surefire
```

`grunt` must run before `mvn`: the Java bundle ships `bin/bridge.js`, which is a generated file and is
gitignored.

The Tycho build targets Eclipse Kepler (4.3) and resolves against
`http://download.eclipse.org/releases/kepler`. This is a 2013-era toolchain and may not resolve on a
modern machine.

### Running the Java tests without Tycho

`BridgeTest` deliberately has no Eclipse dependencies: it drives `Bridge` through the
`NodeProcessLauncher` seam and uses `FakeNodeProcess` (a JVM subprocess) in place of node, so it can
be compiled and run with plain `javac`/`java` against `lib/*.jar` plus JUnit 4. Do this when the Tycho
toolchain is unavailable — it is much faster than a full build and covers the crash handling.

## Gotchas

- `bin/bridge.js` is generated (`typescriptServices.js` + the compiled bridge, concatenated). Never
  edit it; edit `bridge/src/*.ts` and re-run `grunt`.
- The node path and node arguments preferences are only read when the process starts, so changing
  them requires an Eclipse restart. The preference page says so.
- `Bridge.call` is `synchronized`; the restart listener runs on the same thread and re-enters `call`.
  Anything added there must tolerate reentrancy.
- Adding a preference means touching `IPreferenceConstants`, the default in `TypeScriptPlugin`, the
  preference page, and `resources.properties`. A missing properties key is a runtime
  `MissingResourceException`, not a compile error.
