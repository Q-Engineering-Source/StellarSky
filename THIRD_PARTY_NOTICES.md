# Third-Party Notices

Both build variants retain the star catalogues, star-name data and shared solar
resources described below. The standard build additionally includes the deep-sky
catalogues/images and Milky Way layers. The catalogue-only build omits those
deep-sky and Milky Way resources; notices for retained shared code still apply.
The former image-sky build has been retired. Its NASA SVS background image is no
longer included in either variant; historical provenance remains in the C22
research and release records, not as a claim that current artifacts contain it.

Stellar Sky includes data and rendering techniques adapted from Stellarium.

## Stellarium

Copyright (C) 2000-2026 the Stellarium contributors.

The star catalogue extension was authored by Johannes Gajdosik and later
contributors. The bundled `stars_*.cat` files are the Stellarium 26.2
Hipparcos/Gaia DR3 catalogues, format version 27. The bundled
`dso_catalog.txt` is the Stellarium 26.2 deep-sky catalogue, standard edition
version 3.23. The extended renderer's `extended_milkyway.png` is Stellarium
26.2's official full-sky Milky Way texture. The bundled `dso/*.png` collection
and `dso/textures.json` are Stellarium 26.2's default deep-sky image set. The
manifest retains the per-image author and source credits, exact J2000 sky
coordinates, texture coordinates, brightness metadata, and multi-tile layout.

Source: https://github.com/Stellarium/stellarium

Milky Way source:
https://github.com/Stellarium/stellarium/blob/v26.2/textures/milkyway.png

License: GNU General Public License version 2 or, at your option, any later
version. See `LICENSE`.

The catalogue data contains material compiled from astronomical catalogues
including Hipparcos, Gaia DR3, NGC, IC, Messier, Caldwell, PGC and UGC.
Individual catalogue references are retained in the comments at the end of
`dso_catalog.txt`. Individual image attributions are retained verbatim in
`dso/textures.json`.

The object-information overlay also includes names from Stellarium sky-culture
data: `common_star_names.fab` is the common English star-name list,
`modern_iau.json` supplies modern English deep-sky names, while
`star_names.zh_CN.fab` and `dso_names.zh_CN.fab` are from the Chinese sky
culture. The Chinese sky-culture name data is licensed CC BY-SA 4.0; the
English list follows the Stellarium project license. These files are sourced
from the Stellarium repository above and are used only for stable bilingual
object identification.
