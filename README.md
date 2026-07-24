# StellarSky

StellarSky renders an astronomical sky in Minecraft 1.12.2. This fork targets
Cleanroom and adds per-dimension astronomical time control plus an optional
extended catalogue renderer.

## Sky renderer modes

The original renderer remains the default. Set `Extended_Renderer=true` in
`config/stellarsky/CelestialSettings.cfg` to enable the extended renderer.

The extended renderer provides:

- 579,984 Hipparcos/Gaia DR3 stars at the default magnitude limit of 10.5
- a batched full-sky Milky Way layer
- galaxies, nebulae, and star clusters from Stellarium's deep-sky catalogue
- the original textured Messier objects as a separately controlled layer

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
