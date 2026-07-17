# Task 09 — Build plumbing: folia-api dep, detection, fail-closed bootstrap skeleton (W0.9)

- **Wave:** 0
- **Depends on:** none
- **Status:** todo
- **Executor:** sonnet

## Objective
The minimal compilable skeleton of the Folia backend seam: pinned folia-api dependency,
`FoliaSupport` detection with fail-closed matrix check, `plugin.yml` flag. No behavior
change on any non-Folia platform.

## Out of scope
- NO scheduler/queue/adapter logic (wave 1+). No changes to `TaskManager`, `QueueHandler`,
  adapters, or any existing class except the two bootstrap touch-points listed below.
- Do not create FoliaTaskManager/FoliaQueueHandler/FoliaRegionDispatcher (wave 1 owns them).

## Binding references
- spec §2 (detection precedes Paper; never classloaded elsewhere), §2b (fail-closed rules,
  pinned version, certified matrix = Folia 26.1.1/26.1.2 on adapter-26.1).
- architecture.md §2 (package `com.fastasyncworldedit.bukkit.folia`, class `FoliaSupport`),
  §4 (conventions, `// Folia port:` markers).

## Files to touch
- `worldedit-bukkit/build.gradle.kts` — UPDATE: add
  `compileOnly("dev.folia:folia-api:26.1.2.build.8-stable")`. Repo:
  `https://repo.papermc.io/repository/maven-public/` — check `gradle/libs.versions.toml`
  first: FAWE manages deps there (paperApi etc.); follow the existing catalog pattern
  (add a `foliaApi` entry) rather than a raw string if the catalog is the repo convention.
  Verify no conflict with the existing paper-api dependency (compileOnly coexistence).
- `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaSupport.java` —
  CREATE: static detection (`Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`,
  no folia-api types in signatures so the class loads anywhere); `isFolia()`;
  `checkCertifiedOrFail()` comparing `Bukkit.getMinecraftVersion()`/server build against
  the certified matrix (26.1.1, 26.1.2) — on mismatch: log the operator message and throw
  a dedicated `UnsupportedFoliaVersionException` (same package) to abort enable BEFORE
  listeners/executors/world access (spec §2b).
- `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/WorldEditPlugin.java` — UPDATE
  (touch-point, marked): call the detection/fail-closed check at the very top of onLoad or
  onEnable (whichever runs first in this codebase — read it), BEFORE platform init. On
  non-Folia: zero behavior change.
- `worldedit-bukkit/src/main/resources/plugin.yml` — UPDATE: `folia-supported: true`.

## Context & decisions
- Detection result cached in a static final. No Folia API calls in this task — reflection
  only, so the jar stays loadable on Spigot/Paper/old JVMs.
- Fail-closed must not break Paper boot: the check only aborts when Folia is DETECTED and
  outside matrix.

## Escape hatch
If `WorldEditPlugin` has an existing early-abort pattern (incompatible-version guard),
report it and mirror it instead of inventing a new one. If the version string format on
Folia can't be determined statically, implement the comparison against
`Bukkit.getMinecraftVersion()` + server brand and flag the exact runtime verification
needed (harness will confirm).

## Acceptance criteria
- `./gradlew :worldedit-bukkit:compileJava` green (orchestrator runs it).
- On Paper: no code path reaches FoliaSupport beyond the boolean check (grep-able).
- Fail-closed path covered by the harness later (note it in your record).

## Test / verification strategy
Compile via orchestrator. Runtime verification lands with harness (task 01) — record as
pending.

---
## Dev record (worker fills this in on completion)

**Status:** DONE_WITH_CONCERNS

**File List:**
- `gradle/libs.versions.toml` — UPDATE: added `foliaApi` version (`26.1.2.build.8-stable`) and
  `foliaApi` library entry (`dev.folia:folia-api`), following the existing catalog pattern next
  to `paperApi`. Not one of the four named files, but explicitly directed by the
  `worldedit-bukkit/build.gradle.kts` bullet ("follow the existing catalog pattern... add a
  `foliaApi` entry"), so treated as in-scope for that bullet.
- `worldedit-bukkit/build.gradle.kts` — UPDATE: added
  `compileOnly(libs.foliaApi) { isTransitive = false }` next to the existing
  `localImplementation(libs.paperApi)` block. `isTransitive = false` mirrors the pattern already
  used for other compileOnly deps in this file (vault, griefprevention, etc.) and avoids
  folia-api's own transitive paper-api pulling a different version onto the compileOnly graph
  than the pinned `paperApi` (1.21.1-R0.1-SNAPSHOT) already resolved via `localImplementation`.
  No new repository needed — the `PaperMC` maven repo (repo.papermc.io/repository/maven-public/)
  is already declared in this same file.
- `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaSupport.java` —
  CREATE: reflection-only detection (`Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`),
  cached in a `static final boolean`; `isFolia()`; `checkCertifiedOrFail()` comparing
  `Bukkit.getMinecraftVersion()` against the certified matrix (`26.1.1`, `26.1.2`), logging and
  throwing `UnsupportedFoliaVersionException` on mismatch. No folia-api types anywhere in the
  class.
- `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/folia/UnsupportedFoliaVersionException.java`
  — CREATE: dedicated checked exception, same package as `FoliaSupport`, per the task's
  "throw a dedicated `UnsupportedFoliaVersionException` (same package)" instruction. Not
  separately named as a file to touch but required by the `FoliaSupport.java` bullet.
- `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/WorldEditPlugin.java` — UPDATE
  (touch-point, marked `// FAWE start - Folia port: ...`): added the detection/fail-closed
  check as the very first statement of `onLoad()` (confirmed `onLoad()` runs before `onEnable()`
  and platform init happens later inside `onLoad()` itself, at the `BukkitServerInterface`
  registration). `UnsupportedFoliaVersionException` is wrapped into `IllegalStateException` to
  abort load, mirroring the existing early-abort pattern already present a few lines below
  (the Mojang/Spigot jar-type mismatch guard also throws `IllegalStateException` from
  `onLoad()`) — `onLoad()`/`Plugin#onLoad()` declares no `throws` clause, so the checked
  exception can't propagate directly.
- `worldedit-bukkit/src/main/resources/plugin.yml` — UPDATE: added `folia-supported: true`
  next to `api-version`.

**Completion Notes:**
- `isFolia()` result is a cached `static final`, computed once via `Class.forName` at class-init
  time — matches "Detection result cached in a static final. No Folia API calls in this task."
- On non-Folia (Paper/Spigot), `FoliaSupport.isFolia()` returns `false` immediately (one
  `ClassNotFoundException`-caught reflection probe) and no other `FoliaSupport` method is ever
  reached — grep-able: `FoliaSupport` is referenced only from `WorldEditPlugin.onLoad()`.
- Fail-closed only triggers when Folia IS detected and outside the matrix; Paper boot path is
  unaffected (zero behavior change), as required.
- Not run: `./gradlew :worldedit-bukkit:compileJava` (orchestrator owns builds per instructions).
  Verified manually instead: import ordering, package conventions (no HEADER.txt banner on
  `com.fastasyncworldedit.*` files — confirmed by checking `FaweBukkit.java`,
  `BukkitTaskManager.java`, `Fawe.java`, all header-less), and that `Bukkit.getMinecraftVersion()`
  / `Server#getMinecraftVersion()` exist on the pinned paper-api 1.21.1 jar (inspected via
  `javap` against the cached artifact) so the call at least compiles against Paper.

**Deviations:**
- None from the frozen contract. The `gradle/libs.versions.toml` edit and the
  `UnsupportedFoliaVersionException.java` file are both explicitly called for by the task's own
  prose inside the four listed bullets, not independent scope creep.

**Attack points (things the orchestrator/reviewer should double-check):**
1. **Unverified runtime version string** (flagged by the task's own escape hatch): I could not
   start a live Folia 26.1.1/26.1.2 server to confirm what `Bukkit.getMinecraftVersion()`
   actually returns on Folia (e.g. whether it's exactly `"26.1.1"`/`"26.1.2"`, includes a
   suffix, or whether Folia even overrides this the same way Paper does vs. reporting the
   underlying vanilla Minecraft version string). This is pure guesswork against the matrix
   values given in the task file. Needs runtime confirmation once the harness (task 01) is up —
   flagged in-code with a `// Folia port:` comment at the call site in `FoliaSupport.java`.
2. **compileOnly + isTransitive=false risk**: I added `isTransitive = false` on the folia-api
   compileOnly dependency as a defensive measure against a paper-api version clash, following
   the file's existing convention for other compileOnly libs — but I did not (and could not,
   per instructions) run a dependency resolution to confirm folia-api 26.1.2.build.8-stable
   actually resolves cleanly from repo.papermc.io with this coordinate. Orchestrator's
   `compileJava` run is the real check.
3. Status is DONE_WITH_CONCERNS rather than DONE solely because of point 1 (unverified runtime
   string) — everything else is static, reflection-only, and should not affect Paper/Spigot
   behavior at all.

---

**Superseding note (certification finding 7 — folia-api dependency relocated).**

The File List above records the pinned folia-api dependency being added to
`worldedit-bukkit/build.gradle.kts`. That is superseded. The dependency was moved into a
dedicated Gradle module, `worldedit-bukkit:folia`, because folia-api's compiled class metadata
forces a JVM 25 toolchain, while `worldedit-core` and `worldedit-bukkit` keep Java-21 output.

Current layout (verified on disk 2026-07-17):
- `settings.gradle.kts:62-63` includes the module: `include("worldedit-bukkit:folia")`
  (`// Folia port: dedicated Java-25 backend module`).
- `worldedit-bukkit/folia/build.gradle.kts` — Java 25 toolchain (`JavaLanguageVersion.of(25)`,
  `options.release.set(25)`, `disableAutoTargetJvm()`); dependencies are
  `compileOnly(project(":worldedit-core"))` and `compileOnly(libs.foliaApi)` (:27). folia-api
  (`libs.foliaApi`) is declared **only** in this module — it is no longer present in
  `worldedit-bukkit/build.gradle.kts`. `gradle/libs.versions.toml` still holds the
  `foliaApi` version + library entries (:5, :76).
- `worldedit-bukkit/build.gradle.kts` defines a `foliaBackend` configuration (:81-88,
  "Folia backend to include only in the Mojang/Paper JAR") that consumes
  `project(":worldedit-bukkit:folia")` (:130).

Packaging/shading proof — **DONE (dynamic, 2026-07-17, certification corrective F2)**:
- `./gradlew :worldedit-bukkit:shadowJar :worldedit-bukkit:reobfShadowJar` both green
  (BUILD SUCCESSFUL; see build-environment note below).
- `FastAsyncWorldEdit-Paper-2.15.4-SNAPSHOT.jar`: 18 `com/fastasyncworldedit/bukkit/folia/*`
  class files = 16 backend-module class files (FoliaCommitBroker + DiagnosticSnapshot,
  FoliaRegionDispatcher, FoliaBackpressure + nested records, FoliaTaskManager,
  FoliaQueueHandler, FoliaSnapshotCache, RegionKey) + the detector pair; 44
  `com/fastasyncworldedit/core/util/task/*` SPI class files shaded (plus one
  package-directory entry — counts per certification r2).
- `FastAsyncWorldEdit-Bukkit-2.15.4-SNAPSHOT.jar` (reobf/Spigot): exactly 2 folia classes —
  `FoliaSupport` (bootstrap detector, required on every platform) and
  `UnsupportedFoliaVersionException`; ZERO backend-module classes (the `foliaBackend`
  configuration is resolved by `shadowJar` only, per `worldedit-bukkit/build.gradle.kts:211-212`).
- Exactly one `FoliaSupport.class` per artifact (duplicate removed by corrective C-F1F2).

Build-environment note (local, reproducibility): the full bukkit build on this machine needs
`-I <init-script>` pinning the OLD 1.21.x adapters (`adapter-1_21` … `adapter-1_21_11`) to a
Java 21 toolchain via `afterEvaluate` — their pinned dev-bundle codebook (1.0.14) cannot parse
Java 25 class files (`Unsupported class file major version 69`) while the repo-wide toolchain
is 25 (`build-logic/.../buildlogic.common.gradle.kts:16`). The 26.1/26.2 adapters and all port
modules stay on 25. Pre-existing upstream/environment issue, NOT introduced by the port; no
repo files were modified for it. Init script archived at the orchestration session scratchpad
(`adapter-jdk21.init.gradle.kts`); consider committing a copy under `contrib/` if local full
builds become routine.
