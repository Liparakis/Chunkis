# AGENTS.md

Guidance for AI coding agents, and a quick orientation for human contributors. The contribution rules in [README.md](README.md#contributing) apply to agent-written changes too: a human reviews every change and must be able to explain it.

## Project

Chunkis is a Fabric mod (Minecraft 1.21.11, Java 21) that replaces vanilla chunk persistence with its own compact format, CIS. Keep the project focused on safe, predictable chunk storage: saving, loading, encoding, migration and the data handling around them. Don't expand it into unrelated tooling, UI, backups or general world editing unless asked.

## Priorities

When goals conflict, prefer them in this order:

1. Data safety
2. Correctness
3. Simplicity
4. Maintainability
5. Performance
6. Convenience

Chunk data loss, silent corruption and unclear migration behavior are the worst possible failures.

## Layout

| Module      | Responsibility                                                                                    |
|-------------|---------------------------------------------------------------------------------------------------|
| `common`    | Format model and codec: `ChunkDelta`, `CisEncoder` / `CisDecoder`, compression, `CisConstants`    |
| `storage`   | Region files, the region cache, block-id mapping, `CisStorage`                                    |
| `migration` | Upgrading stored CIS data between format versions                                                 |
| `fabric`    | Mod entrypoint, mixins, save and load pipeline, restoration, entities, commands, public API      |

- Mixins and entrypoints stay thin. Logic belongs in plain classes that can be tested without a running game.
- `fabric/.../api/**` is the public API for other mods. It is intentionally unused inside Chunkis; never remove it as dead code.
- `tools/stress/` holds the stress benchmark and its regression check (see its README).

## Rules

- Understand the existing structure before changing it. Prefer small, focused changes; don't mix refactoring, features and cleanup in one change.
- Don't invent APIs, mappings, method names or Minecraft internals. Check them in the mapped sources.
- Don't delete code unless the reason is clear.
- Don't swallow exceptions or hide failures behind vague logs.

### Stored data

- Preserve chunk data as completely as practical; don't drop unknown or auxiliary metadata casually. Unsupported data must be handled explicitly, and lossy behavior must be obvious.
- The format is versioned (`CisConstants.VERSION`). After a release, any change to the stored layout needs a version bump and a migration, and data written by released versions must stay readable. Document format changes and cover them with tests.
- Never rely on accidental ordering or unstable object layout in stored data.

### Errors and logging

- Errors should say what failed and where: chunk coordinates, file path, format version, operation, metadata key.
- Log conversion failures, corrupted input, skipped or unsupported data, failed I/O and recovery paths. Keep hot paths quiet.

### Tests and docs

- Add or update tests for behavior changes, especially storage read/write, compression, migration, metadata preservation, corrupted input and format compatibility.
- Update docs when behavior, format or usage changes. Javadocs should explain purpose, constraints and failure modes, not repeat the method name.

## Build and validation

Use the Gradle wrapper (`./gradlew` on Linux/macOS, `gradlew.bat` on Windows):

```bash
./gradlew build    # compiles, runs unit tests, checkstyle and the Fabric game tests
```

Delete `fabric/build/run/gameTest/world` before running game tests again, or tests that create chunks fail on the leftover world.

For changes to saving, loading, the codec or storage, also run the regression check (`tools/stress/bench.sh`). It takes about 20 minutes and needs an otherwise idle machine.

## Workflow

1. Read the relevant docs and code.
2. Find the smallest safe change.
3. Make it, then build and run the relevant tests.
4. Update tests and docs.
5. State assumptions and tradeoffs. When an architectural choice is risky, ask instead of guessing.

A change is done when it builds, its tests pass, data safety is not weakened, errors are understandable, docs are current and nothing unrelated is bundled in.
