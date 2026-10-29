# Wildforge — Engine Review and Refactor, 2026-10-29

**Scope:** Complete source-tree / resource / test inventory of the 2026-10-29 upload; focused code changes and offline validation. This is not a claim that every subsystem has been rewritten or that the game was played end-to-end. The original ZIP and the user's save games were not modified.

## Executive summary

Wildforge has a real technical foundation: deterministic world generation, content manifests, 16×16 columns, 16-high render sections, background generation and meshing with snapshot/epoch checks, palette compression, sparse persistent edits, distinct simulation/render distances, a separate translucent fluid pass, inventory and game modes, and numerous smoke tests. These should be retained.

The largest problem is **responsibility concentration**: `Main` (input, menus, UI state, saving, world lifecycle), `TerrainMesh` (block storage, streaming, generator, fluids, geometry, mesh scheduling, saves), `Player` (movement, physics, input, inventory, item interaction), and `Renderer` (shaders, GPU resources, world, items, UI) each own several systems. The correct long-term goal is a modular voxel engine, not a one-file rewrite.

### What was changed in this delivery

| Area | Change | Compatibility |
| --- | --- | --- |
| Storage | Extracted `world.ChunkColumn` from `TerrainMesh`. | Same chunk bytes, palette and mesh revisions. |
| Coordinates | Added `world.ChunkCoordinates` for signed packed positions and the legacy column-major index. | No saved index changes. |
| Meshing | Extracted `world.mesh.FloatArrayBuilder`. | Same vertex data structure. |
| Fluids | Extracted pure rules into `world.fluid.FluidRules`: timing, water-source eligibility, seam activation and surface height. | Same timing, level and rules. |
| World spawning | `TerrainMesh.newWorld` uses the existing `SpawnFinder`; the initial `Player` uses that spawn. | Existing loaded player saves still control saved player coordinates. |
| Save integrity | New `core.save.SaveTransaction` stages terrain, player and dropped items, retains backup copies and uses a recovery journal for interrupted replacement. | Reads old `.wfs` / `.wfp` files; introduces `.wfi` and `.journal` only. |
| Entities | New `core.save.DroppedItemStorage` persists type, stack count, position, velocity and age; restores without randomized launch velocity. | Missing `.wfi` means a legacy save with no persisted drops. |
| Physics | Fast-falling items now sweep their vertical path, rather than checking only final overlap. | No input or save layout changes. |
| Tests | Four new test classes for transactions, indexing/buffers, pure fluids and dropped-item vertical collision; included in Gradle `check`. | Existing smoke tasks preserved. |
| Tooling | Added a POSIX `gradlew` launcher; kept the Windows wrapper and Java 21 configuration. | No dependency version change. |

**Validation in this environment:** `javac` successfully compiled the modified non-UI classes and the four new smoke-test sources against the compiled project classes shipped in the upload. `SaveTransactionSmokeTest`, `ChunkCoordinatesSmokeTest`, and `FluidRulesSmokeTest` executed successfully. `DroppedItemPhysicsSmokeTest` is compiled but not executed here because constructing a real world requires the unavailable runtime dependencies. A complete Gradle `check`, content generation, GLFW/OpenGL startup and visual inspection were **not** completed: the environment lacks a Gradle distribution/dependency cache, and DNS access to the Gradle distribution host failed. Run the Windows commands listed below to finish verification.

## Minecraft comparison by system

**How to read:** *Comparable* means a principle to use, not a recommendation to clone Minecraft internals, content or proprietary source. Minecraft Java implementation details vary by version.

| System | Current Wildforge | Minecraft-style target | Priority |
| --- | --- | --- | --- |
| World / chunk storage | 16×16 columns × 512 Y, palettes, 32 vertical mesh sections, sparse edits. Streaming, fluids and persistence are still co-located. | `World` owns chunk access; `ChunkSource` loads/unloads; `Chunk`/`ChunkSection` own data; `BlockView` exposes read-only queries. Separate render section caches. | P1 architecture |
| Game loop | Frame delta and multiple simulation loops mixed with UI controller. Fluid updates use 20 Hz scheduling but entities are driven by clamped frame delta. | Consistent **fixed-step game simulation** with interpolation for render; deterministic system order and pause policy. | P1 |
| Liquid physics | 1–7 strength, sources, water renewal, water/lava interaction, bounded queue, simulation-radius parking. Shader movement already gives visual flow. | Scheduled fluid ticks, downhill search, equilibrium/recession after source removal, falling flow state, fluid/block coexistence, explicit fluid state independent of block ID. | P1 |
| Terrain generation | Deterministic noise, regional hydrology, 35 surface biomes, cave types, 11 trees and multiple ocean families. Generation is in/adjacent to `TerrainMesh`. | Staged generation pipeline (climate → density → surface rules → carvers → features → fluids → lighting), immutable seed/config and generation-version migrations. | P1 |
| Spawn selection | Dedicated `SpawnFinder` previously unused in live creation; now connected for new worlds. | Durable world-spawn coordinate, validated full player bounding box, safe breathing room, deterministic fallback and spawn-area loading. | P1 |
| Blocks/states | Save-stable `BlockType` enum with separate model metadata and a newer registry; some content still lives in hardcoded switches. | Namespaced block registry, immutable block-state properties, tags and collision/occlusion/model behavior per state. Data-driven model and loot rules. | P2 |
| Render / meshes | Separate section meshes, worker snapshots, distant-water LOD, translucent fluid pass, GPU update budget. | Separate world renderer from render scheduling, GPU buffers, material/texture system, frustum/occlusion pipeline; progressive distant geometry. | P2 |
| Texture animation | Water and lava have procedural shader animation; the atlas loader truncates 32×64 sprites to their first 32×32 slice. | Asset metadata distinguishing **tall 2-cell plant textures** from **animated sprite frames**; distinct cutout/material animation paths. | P1 visuals |
| Item entities | Drops, stacking, pickup, gravity, water buoyancy; previously vanished on world reload, now stored. | Entity registry, serialization codec, per-chunk lifecycle, swept collision and lifetime/despawn rules. | P2 |
| Player | Movement, jumping, swimming, creative flight and spectator; player class also owns inventory/input interactions. | Player entity + physics/collision component + input intent + inventory/interaction components. | P2 |
| Inventory | 36 slots, cursor operations, hotbar, creative catalog, save format. | Slot abstraction, container transactions, block entities and recipe matching. | P2 |
| UI | Central menu/input state in `Main`, multiple dedicated renderers. | Screen stack and event routing separated from game session/state; UI rendering only consumes a view model. | P2 |
| Saving | v1–v5 terrain migrations and player formats; now journal-backed 3-file save generation. | Explicit world version, per-chunk serialized state, durable checkpoints and data-fixer migration layer; eventual region files. | P1 |
| Testing | Many JavaExec smoke suites, mostly without a renderer; generated assets checked. | JUnit (or equivalent) unit/property/fixture tests, Gradle `test`, golden seeds, save corruption and stress tests, render validation. | P2 |

### Highest-risk unresolved findings

1. **[P1] Mismatched world-coordinate ranges.** `TerrainMesh.WORLD_HEIGHT` is 512 and valid world Y is 0..511. However `ContentDataGenerator.generateWorldGenConfig` still writes `min_y=-64`, `max_y=191`, `sea_level=64`, and `WorldGenSmokeTest` checks results against that range. Negative generated heights cannot be represented without explicit offsets; high Y from 192..511 is available to game storage but not the intended configured terrain profile. Decide whether to support signed world Y (recommended) or change the data profile with a conscious worldgen version/migration. **Do not silently shift existing world saves.**
2. **[P1] Fluids do not fully recompute when a source is removed.** The flow update path spreads/strengthens water and lava but does not implement a complete withdrawal/decay algorithm. `FLOWING_*` is stored as a block ID and flow strength in a separate sparse map. Need a fluid state API, source-loss propagation and dedicated simulation tests for removal, downward spreading, chunk seams, world reload and distant wake-up.
3. **[P1] Tall sprites are truncated.** There are 12 32×64 PNGs including tall grass, cattails and hanging roots, and `BlockTextures.loadTexture` uploads only the first 32×32 pixels. The model may draw a taller cross but cannot display the upper sprite half. Add explicit sprite slicing/paired atlas layers and upper/lower quad generation; do not treat every 32×64 plant as an animation.
4. **[P1] Generated foliage/editability remains incomplete.** Some leaves and marine overlays are reconstructed as deterministic meshes instead of fully mutable block states. Tree cutting does not have a complete foliage-support/decay system. Distant proxy trees already include a trunk-support guard, but the full system needs consistency.
5. **[P1] Safe-spawn validation still needs full collision checks.** New-world creation now uses `SpawnFinder`, but the current heuristic samples land height and local slopes, not the entire player's body or overhead hazards. Add a deterministic dry-spawn fallback and validate the actual generated chunk collision volume.
6. **[P1] Save commit is recoverable, but not yet a full database.** The journal protects multi-file replacement across *process interruptions* and IO failures. It does not fsync directory/file metadata for sudden power loss. World name and cheats/rules are separate writes, and only dropped items—not future block entities/mobs—have their own codec. Add durable directory synchronization, format checksums and staged multi-file snapshots as the project matures.
7. **[P2] Terrain generation/mesh jobs can create CPU and memory pressure.** A column stores 131,072 raw block bytes before compaction; at render distance 8 the square contains up to 289 columns. Section vertices, snapshots and multiple in-flight jobs add to that. Profile chunk-generation time, bytes/column, VBO uploads/frame and 99th percentile frame time before increasing view distance. Favor immutable section-level snapshots and no accidental entire-column clone per frame.
8. **[P2] `Main`, `TerrainMesh`, `Player`, and `Renderer` are still oversized.** This refactor removed some embedded helpers, but it would be unsafe to decompose every responsibility without broader integration tests. Continue in small compilable passes; keep the runtime/API compatible at each pass.
9. **[P2] Content is only partially data-driven.** IDs/texture selection and several feature species are still hard-coded. Registries should become the source of truth while legacy numeric IDs remain a migration concern, not gameplay logic.
10. **[P2] Gameplay parity is incomplete by design.** The upload has no general-purpose mob spawn manager, recipe execution pipeline, time/weather system, true light propagation or advanced block entities. Do not pretend these are bugs in implemented components: they are future systems that need explicit design before being added.

## Target directory structure (incremental, not all changes applied)

```text
src/main/java/com/wildforge/
  Main.java                    # eventual tiny GLFW/DI bootstrap
  app/GameApplication.java    # proposed: lifecycle, window ownership
  app/GameSession.java        # proposed: active world, player, rules
  client/input/               # proposed: map GLFW to actions
  client/gui/                 # proposed: screens, widgets, controllers
  client/render/              # proposed: renderer and GPU resources
  content/                    # existing registries, IDs and tags
  core/                       # existing settings, timing, controls
    save/                     # NEW SaveTransaction, DroppedItemStorage
  entity/                     # existing Entity, dropped items, manager
  inventory/                  # existing Inventory
  player/                     # existing Player, Camera, interactions
  world/
    ChunkColumn.java          # NEW
    ChunkCoordinates.java     # NEW
    TerrainMesh.java          # transitional world facade
    fluid/FluidRules.java     # NEW
    mesh/FloatArrayBuilder.java # NEW
    chunk/                    # proposed provider/cache, sections
    physics/                  # proposed block collisions/sweep
    storage/                  # proposed saved chunk data
  worldgen/                   # existing terrain/climate/caves/biomes
```

**Migration order:** (A) fixed-step server simulation; (B) signed-Y coordinate specification and world version policy; (C) `ChunkSource` + pure `ChunkSection` storage; (D) full fluid engine; (E) sprite assets and block-model registry; (F) `GameSession` and screen navigation; (G) typed entity systems, block entities, recipes, lighting. Do not merge the player rendering/UI code into the simulation as a shortcut.

## Verification commands — Windows (from project root)

```powershell
.\gradlew.bat clean check
.\gradlew.bat worldgenCheck undergroundCheck riverModelCheck vegetationCheck oceanTreeCheck
.\gradlew.bat architectureCheck chunkCoordinateCheck fluidRulesCheck droppedItemPhysicsCheck
.\gradlew.bat run
```

Check an **existing world only after copying the entire `saves` directory first**. Make a new seed and inspect: land spawn, ocean surface, flowing-water edges, tree tops, inventory pickup, save/quit/reload with dropped items, chunk-border streaming, and falling dropped items. Confirm logs have no new exceptions and that FPS/VBO uploads remain stable at varying render distances.

### Test result details (this execution)

- **Passed:** targeted `javac` compilation of changed headless production classes and new regression test sources.
- **Passed:** `SaveTransactionSmokeTest` (writer failure preserves earlier save, 3-file commit, simulated interrupted commit recovers previous matching pair).
- **Passed:** `ChunkCoordinatesSmokeTest` (signed X/Z pack/unpack, 512-high indexing, growable mesh buffer).
- **Passed:** `FluidRulesSmokeTest` (water/lava delays, surface heights, source rules, seam behavior).
- **Not run here:** `DroppedItemPhysicsSmokeTest` (requires world generation runtime assets), normal `gradlew check`, GLFW client startup, rendering, long-duration performance tests.

**Minecraft references for the architecture comparison:** minecraft.wiki `/w/Chunk`, `/w/Chunk_format`, `/w/Fluid`, `/w/Tick`, and Microsoft Learn `simulationrenderdistanceguide` (Bedrock guidance, useful for concept separation only). Wildforge does not share Minecraft's engine or exact worldgen tick policies.
