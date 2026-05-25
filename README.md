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
| Language | Kotlin 2.2.0 |
| Build | Gradle 8.14 + Shadow JAR |
| Java | 21 |

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
