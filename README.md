# Fullscreen Windowed (Borderless) – 1.7.10 / GregTech: New Horizons

Fork of [hancin/Fullscreen-Windowed-Minecraft](https://github.com/hancin/Fullscreen-Windowed-Minecraft)
(branch `mc-1.7.10-backport`), updated for Minecraft **1.7.10** and **GregTech: New Horizons**.

Replaces the exclusive fullscreen (F11) with a borderless window covering the whole monitor.

## Changes compared to the original
- Correct monitor size with Windows display scaling (e.g. 4K @ 150 %) – works at every resolution up to 4K and above
- Proper switch out of exclusive fullscreen at startup (the framebuffer no longer stays at the old resolution)
- The chosen mode is saved and restored on the next start
- Multi-monitor: picks the monitor with the largest overlap when the window is partly off-screen
- GTNH on Java 17+ (lwjgl3ify): real borderless window built through SDL3 (instead of SDL fullscreen, which Windows treats like exclusive fullscreen)
- Build system updated (anatawa12 ForgeGradle 1.2 fork, Gradle 7.4.2) + GitHub Actions

## Build
Requires **JDK 8**.
```
gradlew build
```
The jar will be in `build/libs/`.

Or let GitHub build it: every push starts a build under *Actions*; a tag like `v1.3.1` automatically creates a release with the jar attached.

## License
BSD 2-Clause, see [LICENSE](LICENSE) – original © 2015 David Larochelle-Pratte (Hancin).
