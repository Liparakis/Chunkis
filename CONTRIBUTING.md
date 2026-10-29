# Contributing to Chunkis

Thanks for helping. Chunkis stores people's worlds, so the bar is high: a bug here doesn't crash a game, it can quietly
destroy someone's builds. This guide explains how to contribute without putting that data at risk.

- [Ground rules](#ground-rules)
- [Using AI tools](#using-ai-tools)
- [Before you start](#before-you-start)
- [Setting up](#setting-up)
- [Making a change](#making-a-change)
- [Testing](#testing)
- [Performance changes](#performance-changes)
- [Changing the storage format](#changing-the-storage-format)
- [Opening a pull request](#opening-a-pull-request)
- [Review](#review)
- [Reporting bugs](#reporting-bugs)

## Ground rules

1. **Data safety comes first.** When goals conflict, prefer data safety, then correctness, simplicity, maintainability,
   performance and convenience, in that order.
2. **You own what you submit.** Whoever opens a pull request is responsible for every line in it, however it was
   written.
3. **One concern per pull request.** Don't mix a refactor, a feature and a cleanup.
4. **Stay in scope.** Chunkis is about chunk storage: saving, loading, encoding, migration and the data handling around
   them. Ask before adding unrelated tooling, UI, backup features or world editing.

[AGENTS.md](AGENTS.md) holds the full project rules for code style, error handling, logging and architecture. They apply
to everyone.

## Using AI tools

AI-assisted contributions are welcome. Chunkis itself is developed with AI assistance. The same rules apply whether you
or a tool wrote the code:

- **A human reviews all code before it's submitted.** Read every generated line as carefully as you would a stranger's
  pull request. Run it. Check the edge cases.
- **You must be able to explain the change.** Expect questions about why something works, what happens on failure and
  why this approach beats the alternatives. "The AI wrote it" is not an answer, and a pull request whose author can't
  explain it will be closed.
- **Verify what the tool claims.** AI tools invent Minecraft method names, mappings and APIs that don't exist. Check
  every one against the mapped sources.
- **Don't submit unverified results.** If a tool says tests pass or a benchmark improved, run them yourself and include
  the real output.
- **Say how you used it.** Mention AI assistance in the pull request description. It isn't held against you; it tells
  the reviewer where to look harder.

AI agents working in this repository follow [AGENTS.md](AGENTS.md).

## Before you start

- **Bugs:** check open issues first. If there's none, open one with reproduction steps
  (see [Reporting bugs](#reporting-bugs)).
- **Small fixes** (typos, clear one-line bugs): just open a pull request.
- **Anything bigger** (new behavior, refactors, performance work, format changes): open an issue or discussion first and
  describe the plan. That saves you from building something that can't be merged.

## Setting up

You need **JDK 21** and Git. Gradle comes with the wrapper.

```bash
git clone https://github.com/Liparakis/Chunkis.git
cd Chunkis
./gradlew build          # Windows: gradlew.bat build
```

The build compiles every module, runs the unit tests, checkstyle and the Fabric game tests, and writes the mod jar to
`fabric/build/libs/`.

| Module      | What lives there                                                                   |
|-------------|------------------------------------------------------------------------------------|
| `common`    | Format model and codec: `ChunkDelta`, encoder and decoder, compression, constants  |
| `storage`   | Region files, block-id mapping, `CisStorage`                                       |
| `migration` | Upgrading stored data between format versions                                      |
| `fabric`    | The mod: entrypoint, mixins, save and load pipeline, restore, commands, public API |

The [wiki](https://github.com/Liparakis/Chunkis/wiki) explains the architecture, the storage format and the load
pipeline in depth.

## Making a change

- **Branch from the default branch** and give the branch a descriptive name.
- **Keep mixins thin.** Put logic in plain classes that can be tested without a running game.
- **Don't remove the public API** in `fabric/.../api/`. Chunkis doesn't use it internally; other mods do.
- **Errors must say what failed and where:** chunk coordinates, file path, format version, operation.
- **Never swallow an exception** or hide a failure behind a vague log line.
- **Write commit messages that say what changed and why.** One logical change per commit.

## Testing

Every behavior change needs tests. Prioritize:

- storage reads and writes;
- compression and decompression;
- migration between format versions;
- metadata preservation;
- corrupted or invalid input;
- compatibility with data written by earlier versions.

Logic that's hard to test inside the game belongs in a plain class with a unit test. Behavior that only shows up in a
running world gets a game test under `fabric/src/gametest`.

```bash
./gradlew build                        # everything
./gradlew :storage:test                # one module's unit tests
```

Before running the game tests again, delete `fabric/build/run/gameTest/world`. A leftover world makes tests that create
chunks fail.

For changes to the save or load path, also test a real world by hand: create it, edit it, save, restart, reload, and
check it from a second client.

## Performance changes

Claims about speed need numbers. The stress benchmark in [`tools/stress`](tools/stress/README.md) runs a fixed scenario
for Chunkis and vanilla alternately and checks the result against a saved baseline:

```bash
tools/stress/bench.sh --save-baseline   # once, on the commit you started from
tools/stress/bench.sh                   # on your change
```

- Run it on an otherwise idle machine; a game or a video call skews tick times by 15% or more.
- Include the before and after output in the pull request.
- A change that wins one metric but loses another must say so. Better medians at the cost of worse lag spikes are not an
  improvement.

## Changing the storage format

Stored data outlives the code that wrote it, so format changes get extra care:

- Any change to how stored bytes are written or read is a format change, even if it looks like a local optimization.
- Once a format version has been released, a change needs a new version number (`CisConstants.VERSION`), a migration
  edge in `CisVersionMap`, and tests proving old data still loads.
- Never rely on field order, object layout or other undocumented assumptions.
- Document the change on the wiki's Storage Format and Migration pages.
- Say explicitly in the pull request what happens to existing worlds.

## Opening a pull request

The description is part of the contribution. Reviewers should understand the change without reverse-engineering the
diff. Include:

1. **What changed:** the behavior before and after, from a user's or another mod's point of view.
2. **How:** the approach, and the main classes and methods involved.
3. **Why:** the problem it solves, and why this approach over the alternatives you considered.
4. **Data impact:** does it touch stored data, the format or migration? What happens to existing worlds?
5. **Testing:** which tests you added or ran, and any manual testing. Paste benchmark output for performance work.
6. **AI use:** whether and how AI tools were involved.

Before submitting, check that:

- [ ] `./gradlew build` passes;
- [ ] new behavior has tests;
- [ ] docs (README, wiki, Javadocs) match the change;
- [ ] nothing unrelated is bundled in;
- [ ] you can explain every line (not literally).

## Review

- A maintainer reviews every pull request. Expect questions, especially about data safety and edge cases.
- Answer review questions yourself, in your own words.
- Pull requests that weaken data safety, can't be explained by their author, or bundle unrelated changes won't be merged
  until that's fixed.

## Reporting bugs

Before opening an issue:

- reproduce it on a **copy** of the world;
- check the compatibility notes in the [README](README.md#compatibility);
- confirm it happens on the latest version.

Then include:

- Minecraft, Fabric Loader and Fabric API versions, and the full mod list;
- exact steps to reproduce;
- `latest.log` or the crash report;
- the output of `/chunkis debug latest` if the problem is reproducible in game.

Attach world or CIS files only when they're safe to share publicly.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
