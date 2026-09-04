# TapFractal — music attribution

All four bundled tracks are Creative Commons Attribution works (two CC BY 4.0
from incompetech, two CC BY 3.0 from ccMixter). They are redistributed inside
this app unchanged except where a bitrate re-encode is stated below. These
credits are shown verbatim in the app's About screen (via `credits.json`),
in the README and here. The verbatim blocks follow the exact wording each
source generates for attribution.

Licences: CC BY 4.0 — https://creativecommons.org/licenses/by/4.0/ ·
CC BY 3.0 — https://creativecommons.org/licenses/by/3.0/

---

## Scene 1 — The Tidepool · `scene1.mp3`

incompetech attribution code (Kevin MacLeod's own generator, verbatim):

```text
"Dream Catcher" Kevin MacLeod (incompetech.com)
Licensed under Creative Commons: By Attribution 4.0 License
http://creativecommons.org/licenses/by/4.0/
```

- Source: https://incompetech.com/music/royalty-free/index.html?isrc=USUAN1800019 (ISRC USUAN1800019)
- Changes: re-encoded from the 320 kbps original MP3 to 160 kbps MP3 (ffmpeg libmp3lame, CBR) to fit the asset size budget; no other changes.

---

## Scene 2 — The Nave · `scene2.mp3`

incompetech attribution code (verbatim):

```text
"Ethernight Club" Kevin MacLeod (incompetech.com)
Licensed under Creative Commons: By Attribution 4.0 License
http://creativecommons.org/licenses/by/4.0/
```

- Source: https://incompetech.com/music/royalty-free/index.html?isrc=USUAN2100002 (ISRC USUAN2100002)
- Changes: none (the 160 kbps original file is bundled as-is).

---

## Scene 3 — The Throat · `scene3.mp3`

ccMixter attribution (the site's CC BY 3.0 credit line with the ccmixter.org file URL, including the "Featuring" credit shown on the track page):

```text
"Warriors Fall (Original RumbleStep Mix)" by cdk
(c) copyright 2015 Licensed under a Creative Commons Attribution (3.0) license.
http://ccmixter.org/files/cdk/50691
Ft: Darryl J
```

- Source: https://ccmixter.org/files/cdk/50691 (artist: cdk, featuring Darryl J; the track page states "This song is free to download and share"; no samples used)
- Changes: re-encoded from the 320 kbps original MP3 to 256 kbps MP3 (ffmpeg libmp3lame, CBR) to fit the asset size budget; no other changes.

---

## Scene 4 — The Hive · `scene4.mp3`

ccMixter attribution (CC BY 3.0 credit line with the ccmixter.org file URL, the "Featuring" credit and the sample source the track page lists under "Uses samples from"):

```text
"The Beach" by Platinum Butterfly (F_Fact)
(c) copyright 2012 Licensed under a Creative Commons Attribution (3.0) license.
http://ccmixter.org/files/F_Fact/37699
Ft: debbizo
Uses samples from: "Beach (Moana)" by debbizo (c) copyright 2010
Licensed under a Creative Commons Attribution (3.0) license.
http://ccmixter.org/files/debbizo/26843
```

- Source: https://ccmixter.org/files/F_Fact/37699 (artist: Platinum Butterfly, ccMixter user F_Fact)
- Sample source: https://ccmixter.org/files/debbizo/26843 — the spoken-word poem "Beach (Moana)" by debbizo, also CC BY 3.0 (the track page spells the credit "Debizzo"; the ccMixter account is `debbizo`).
- Changes: none (the 128 kbps original file — the only master ccMixter hosts — is bundled as-is).

---

## One-line form (README / About)

```text
"Dream Catcher" Kevin MacLeod (incompetech.com) Licensed under Creative Commons: By Attribution 4.0 License http://creativecommons.org/licenses/by/4.0/
"Ethernight Club" Kevin MacLeod (incompetech.com) Licensed under Creative Commons: By Attribution 4.0 License http://creativecommons.org/licenses/by/4.0/
"Warriors Fall (Original RumbleStep Mix)" by cdk (c) copyright 2015 Licensed under a Creative Commons Attribution (3.0) license. http://ccmixter.org/files/cdk/50691 Ft: Darryl J
"The Beach" by Platinum Butterfly (F_Fact) (c) copyright 2012 Licensed under a Creative Commons Attribution (3.0) license. http://ccmixter.org/files/F_Fact/37699 Ft: debbizo — uses samples from "Beach (Moana)" by debbizo, CC BY 3.0, http://ccmixter.org/files/debbizo/26843
```

Licence verification evidence (live page fetches, ccMixter API responses, ID3
copyright tags, catalogue entries) is summarised in `docs/MUSIC.md`.
