# StellarSky

StellarSky renders an astronomical sky in Minecraft 1.12.2. This fork targets
Cleanroom and adds per-dimension astronomical time control plus an optional
extended catalogue renderer.

## Required client renderer

Actinium is a **hard client prerequisite** for this development line, in both
standard and catalogue-only builds. There is no no-Actinium/vanilla curvature
fallback. Cleanroom's native `required-after-client:actinium` dependency rejects
a client missing the mod and orders Actinium before StellarSky. This does not
require loading the client-only Actinium renderer on a dedicated server.

The Gradle build resolves the official Actinium `alpha-0.0.8` release; a sibling
Actinium source checkout is not required. Curvature integration is still in
progress; the dependency declaration does not mean the full ring is already
rendered.

## Build variants

Use the root Gradle Wrapper and Java 25. Both builds require the sibling
`../StellarAPI` project and retain the legacy bright-star catalogue, extended
Hipparcos/Gaia star catalogues, solar system and ringworld features.

- `gradlew.bat :remapJar`: standard build, including deep-sky objects/images and
  Milky Way layers.
- `gradlew.bat -PskyFlavor=catalogue :remapJar`: smaller catalogue-only build,
  excluding deep-sky objects/images and Milky Way image layers. The output version
  has a `-catalogue` suffix. Star queries and sky overlays remain available.

These are alternative artifacts with the same mod id; do not install both at
once. Server and client must use the same build variant and version. Deploy the
remapped, non-`-dev` JAR from `build/libs/`, not IDE output. `skyFlavor=image` is
retired and is no longer a supported build option. Switching flavors requires
rebuilding/replacing the artifact, not changing a client configuration switch.

## Sky renderer modes

The original renderer remains the default. Set `Extended_Renderer=true` in
`config/stellarsky/CelestialSettings.cfg` to enable the extended renderer.

The standard build's extended renderer provides:

- 579,984 Hipparcos/Gaia DR3 stars at the default magnitude limit of 10.5
- a batched full-sky Milky Way layer
- galaxies, nebulae, and star clusters from Stellarium's deep-sky catalogue
- the original textured Messier objects as a separately controlled layer

The catalogue-only build retains the star and solar-system paths but does not
load the omitted deep-sky/Milky Way resources, even when an older configuration
still enables those layers.

The client configuration exposes independent switches for stars, the solar
system, the Milky Way, catalogue deep-sky objects, Messier images, atmosphere,
post-processing, overlays, and landscape rendering. `Low_Power_Renderer=true`
keeps only the solar system and batched star layer.

## Time controls

Use `/stellartime info` for the current dimension and observer state. The
command supports per-dimension multipliers, pause/resume, civil clock values,
latitude/longitude, and periodic system-clock synchronization. Run
`/stellartime` in game for the active state and use command completion for the
available operations.

## API

Observer sky contexts are resolved independently for each player. Integrations
for other planets or star systems can register an observer resolver without
replacing the renderer's astronomical parameters.

## License

This fork is licensed under GPL-2.0-or-later because it bundles Stellarium
catalogue data and adapted rendering techniques. See [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
