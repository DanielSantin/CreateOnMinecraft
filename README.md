# CreateOnMinecraft Plugin

A Paper plugin that recreates the [Create mod](https://modrinth.com/mod/create) mechanics as a server-side plugin for Minecraft 1.21.4 (Paper 26.1.2).

## Features

- **Cogwheel** — transmits rotation to adjacent cogwheels; meshing gears spin in opposite directions
- **Large Cogwheel** — 2× speed ratio when meshing with a small cogwheel; supports bevel (90°) connections
- **Axle** — extends rotation along an axis without changing speed
- **Motor** — configurable RPM source that drives a gear network
- **Water Wheel** — generates rotation from flowing water; speed and direction depend on water flow

## Gear Networks

Gears automatically form networks when placed adjacent to each other. Speed ratios propagate through the network based on gear sizes. Conflicting motors (opposing directions) and gear locks (impossible constraints) are detected and the offending block is removed.

## Tech Stack

| | |
|---|---|
| Server | Paper 26.1.2 (Minecraft 1.21.4) |
| Language | Kotlin 2.4.10 |
| Build | Gradle 8.14 + Shadow JAR |
| Java | 21 |
| Items | [Nexo](https://docs.nexomc.com) 1.26.0 (hard dependency, see `plugin.yml`) |

## Building

```bash
./gradlew shadowJar
```

Output: `build/libs/CreateOnMinecraft-1.0-SNAPSHOT.jar`

The `deploy` task also kills and restarts a local server (path configured in `build.gradle.kts`).

## Commands

| Command | Description |
|---|---|
| `/ssggive <item>` | Give a Create item (gear, biggear, eixo, motor, water_wheel) |
| `/cmotor [rpm]` | Give a motor item with specified RPM (default 10) |
| `/ctest spawn [rpm]` | Spawn a motor at your cursor position |
| `/cspeed <rpm>` | Set global network speed |

## Placement

Hold the item in hand and **right-click a block face** with a stick to place. Left-click with a stick (or hit the barrier block) to remove. The placed direction determines the rotation axis.

## Resource Pack

Requires the companion resource pack `SSGGearMachine` for item models.

## Nexo

All player-facing items (gear, biggear, eixo, motor, water_wheel, millstone, esteira,
funel) and internal display-only variants are registered as plain Nexo items — see
`nexo/items/createonmc.yml` (copy it to `plugins/Nexo/items/` on the server). They carry
no Nexo Block/Furniture mechanic: all placement/removal stays in this plugin's own code
(`GearManager`, `BeltManager`, `FunelManager`), Nexo is only used as the item id/model
registry (`dev.createonmc.nexo.NexoIds`, `NexoCompat`). The Nexo item models still point
at the existing `ssggearmachine:` resource pack entries, so no texture/model rework was
needed for the migration.
