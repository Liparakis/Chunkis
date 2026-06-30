<div align="center">
  <img src="core/src/main/resources/assets/logo.png" width="150" alt="Chunkis Logo" style="border-radius: 50%;">

  ![Loader](https://img.shields.io/badge/Loader-Fabric-brightgreen)
  ![Status](https://img.shields.io/badge/Status-Beta-orange)
  ![Version](https://img.shields.io/badge/Version-3.0.0-blue)

  Chunkis replaces Minecraft's Anvil (`.mca`) chunk storage with a custom CIS format built around modified-chunk persistence, migration control, and safer save/load behavior.

  *This is a disk I/O and persistence-layer optimization. It has no direct effect on FPS, TPS, or gameplay.*
</div>

---

`3.0.0` focuses on persistence safety, migration robustness, and overall runtime stability. It significantly reduces corruption-prone edge cases compared to older baseless-delta behavior. The tradeoff is that storage usage can increase in worlds where many modified chunks now persist safer restoration data.

---

## At a Glance

|                        |                                                              |
|:-----------------------|:-------------------------------------------------------------|
| **Primary goal**       | Safer custom chunk persistence and migration                 |
| **File size**          | Often smaller than vanilla, but depends on world history     |
| **Performance impact** | Lower save/load overhead with stability-focused safeguards   |
| **Compatibility**      | Fabric only                                                  |
| **Reversibility**      | ⚠️ **Not reversible** — always back up before installing     |
| **Maturity**           | Beta; tested on single-player and multiplayer servers        |

---

## ✅ Is This For You?

**Good fit if:**
- ✅ You want Chunkis-specific persistence behavior instead of vanilla chunk storage
- ✅ You care about migration control and safer chunk restore behavior
- ✅ You run a modpack server where world storage still matters and you are willing to test
- ✅ Your modpack does not include mods that read `.mca` region files directly
- ✅ You are able to test on a backup world before committing

**Not a good fit if:**
- ❌ You are looking only for FPS or TPS improvements
- ❌ You need guaranteed smaller storage than vanilla in every world
- ❌ Your modpack includes mods listed under Known Incompatibilities
- ❌ You do not have a backup strategy in place

---

## How It Works

Vanilla Minecraft writes full chunk data to disk. Chunkis intercepts the save/load pipeline and persists chunk state through CIS, a custom format that can store modified chunks more compactly while preserving Chunkis-specific metadata and restore behavior.

Older versions leaned heavily on sparse delta replay. In `3.0.0`, the format is more safety-oriented: when needed, Chunkis persists enough restoration data to avoid terrain-regeneration and data-loss problems that could happen with baseless sparse deltas.

Depending on the chunk, CIS data may include:

- palette-compressed block delta data
- block entity and entity NBT
- structure and Chunkis-owned metadata
- persisted restoration/base chunk data when required for safe reloads

That means Chunkis is no longer purely a "write only changed blocks" system in every case. The exact storage win now depends on how many chunks need the safer restoration path.

**On-disk layout (overworld example):**
```text
world/
└── chunkis/
    ├── global_ids.json
    └── regions/
        ├── r.0.0.cis
        ├── r.0.1.cis
        └── ...
```

For non-overworld dimensions, Chunkis stores data under `world/dimensions/<namespace>/<path>/chunkis/`, including a dimension-local `global_ids.json` alongside that dimension's `regions/` directory.

---

## 3.0.0 Notes

`3.0.0` is mainly a stability release.

- Improves persistence safety and save/load correctness
- Hardens migration behavior and avoids destructive upgrade paths
- Improves async save handling and base-capture safety
- Fixes several portal and restore edge cases
- May increase storage usage in worlds where many modified chunks now store safer restoration data

> **Known regression for `1.21.11`:** Versions `3.0.0+` currently have a known storage regression. Stability and correctness improved, but storage efficiency can be worse than older Chunkis releases in some worlds. Storage efficiency work is still planned, but the exact compression or baseline redesign path is not final yet.
>
> **Known unresolved restore bug for `1.21.11`:** Some cross-chunk natural structures, especially tree borders, can still restore with cut seams under unlucky load/generation timing. Current suspicion is missing neighboring chunk context during regeneration rather than plain leaf-tick replay. This is not fixed yet and is deferred while performance and storage work continue.

---

## Known Incompatibilities

The following are incompatible with Chunkis:

- **WorldEdit** — reads and writes `.mca` region files directly
- Any mod that directly manipulates region files
- Mods that apply chunk post-processing after world generation
- Custom world managers with their own chunk serialization

> If you are unsure about a mod in your list, test on a backup world. Look for mods that mention "region files", "world editing", or "chunk serialization".

---

## FAQ

**Will this improve my FPS?**  
No. Chunkis only affects what gets written to disk. Rendering, ticking, and gameplay are unaffected.

**Does this work on servers?**  
Yes. Chunkis is tested on both single-player and multiplayer.

**Why has my world not converted to CIS yet?**  
Chunks convert when they save. Unvisited or unmodified chunks stay in Anvil format until a player loads and modifies them.

**What about `1.21.11`?**  
The `1.21.11` version line will continue once `1.21.11` stabilises.

**Is Chunkis always smaller than vanilla?**  
No. In many worlds it is still smaller, sometimes substantially smaller, but Chunkis does not guarantee a storage win in every world.

**Can I move a CIS world to a different server?**  
Yes, as long as both servers have Chunkis installed. The `global_ids.json` file ensures portability. Without Chunkis, the world cannot be read.

**What happens if Chunkis development stops?**  
Your world remains in CIS format. You will need to keep Chunkis installed, or restore from a pre-Chunkis backup.

**Can I use this with a specific mod?**  
Check Known Incompatibilities above. If the mod reads `.mca` files or modifies chunks post-generation, it is likely incompatible. When unsure, test on a backup.

**Will older Minecraft versions be supported?**  
Development focuses on the latest release. Older versions will not receive regular updates unless a critical bug is reported. If anyone wants to backport changes, they are welcome to open a pull request.

---

## Reporting Issues

Before opening an issue:
- Test on a backup world, not your main save
- Confirm the issue is not caused by a mod in the Known Incompatibilities list
- Make sure you are on the latest Chunkis version
- State whether the problem is about correctness, performance, or storage growth

When opening an issue, include:
- Minecraft version, Fabric Loader version, and full modlist
- `latest.log` or crash report
- Steps to reproduce

> Issues missing this information may be closed without response.

If you prefer direct contact first, Discord is `Liparakis`.

---

## Architecture Docs

The permanent architecture documentation lives under [docs/Architecture](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/README.md).

Recommended reading order:

1. [System Overview](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/System-Overview.md)
2. [Delta And Ownership Model](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Delta-And-Ownership-Model.md)
3. [Save Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Save-Pipeline.md)
4. [Load And Restore Pipeline](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Load-And-Restore-Pipeline.md)
5. [Tracking, Guards, And Durability](C:/Users/Liparakis/Desktop/Chunkis/docs/Architecture/Tracking-Guards-And-Durability.md)

---

## Contributing

Contributions are welcome. The most valuable contributions are:

- Bug fixes
- Compatibility fixes or testing against popular modpacks
- Performance improvements
- Storage-format and compression improvements
- Backports to older Minecraft versions
- Documentation improvements

**Process:**
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Commit changes with clear messages
4. Open a pull request with a description of what changed and why

---

## Building

```bash
git clone <repository>
cd chunkis
./gradlew build
# Output: fabric/build/libs/chunkis-<version>.jar
```

---

## Contact

If you want to contact me directly about Chunkis, you can reach me on Discord: `Liparakis`
