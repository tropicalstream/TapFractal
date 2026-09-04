# TapFractal — Music

Four distributable tracks, one per room, bundled as `app/src/main/assets/music/scene<N>.mp3`
with a build-time envelope `scene<N>.env.json` (energy / bass / treble per 20 ms hop, onsets, BPM)
produced by `tools/analyze_music.py`. Credits live in `assets/music/ATTRIBUTION.md` (verbatim
blocks) and `assets/music/credits.json` (the About screen's data).

| Slot | Room | Track | Artist | Tempo | Length | Licence | File |
|---|---|---|---|---|---|---|---|
| 1 | The Tidepool (Julia) | Dream Catcher | Kevin MacLeod (incompetech.com) | 70 felt / 140 pulse | 6:17 | CC BY 4.0 | 160 kbps, 7.55 MB |
| 2 | The Nave (Mandelbulb) | Ethernight Club | Kevin MacLeod (incompetech.com) | 117 | 5:06 | CC BY 4.0 | 160 kbps, 6.12 MB |
| 3 | The Throat (Menger) | Warriors Fall (Original RumbleStep Mix) | cdk ft. Darryl J | 174 two-step / 87 half-time | 4:19 | CC BY 3.0 | 256 kbps, 8.30 MB |
| 4 | The Hive (Apollonian) | The Beach | Platinum Butterfly (F_Fact) ft. debbizo | 135 | 6:51 | CC BY 3.0 | 128 kbps, 6.58 MB |

Total audio in the APK: 28.5 MB (AGP does not recompress `.mp3` assets, so `MediaPlayer` can open
them straight from the `AssetFileDescriptor`). The scout's 9 MB-per-track cap is respected; slots 1 and
3 were re-encoded from 320 kbps masters (160 and 256 kbps CBR), slots 2 and 4 are the originals
byte-for-byte (md5 verified at copy time).

---

## 1. Why each track fits its room

**Scene 1 — The Tidepool · "Dream Catcher".** Brief: dreamy ambient / downtempo psychedelic,
70–100 BPM. The catalogue lists it at 70 BPM (Electronica; "Mysterious, Relaxed, Grooving") — a
half-time lo-fi trap piece with soft pads and vocal textures over a slow kick. The 2D Julia room is
the only scene without flight speed streaks or a lattice to snap; it wants a slow breath (bass
share 63 % of the spectrum, centroid ~460 Hz) so `uBass` can drive the Phoenix coupling and the
scale breath, and a gentle pulse for the rings. At 6:17 a session rarely hears the loop point.
Caveat: it is a loud, compressed master (−11.5 LUFS, LRA 3.3 LU), so the energy array sits high
(mean 168/255) — dreamy and grooving rather than airy. Beatless alternate if a floatier bed is ever
wanted: "Opium" (Kevin MacLeod, CC BY 4.0, USUAN1100634).

**Scene 2 — The Nave · "Ethernight Club".** Brief: deep hypnotic psybient / progressive trance,
100–128 BPM. 117 BPM, "Grooving, Driving, Mysterious"; Kevin's own blurb is a 2 am room with a
hypnotic pulse. Marimba/bell figures over a clear kick (kick periodicity 0.64) with real builds and
breakdowns (16.9 dB dynamic range, LRA 4.8 LU) — exactly what the Mandelbulb needs: the power kick on
`uBeat` has a clean kick to lock to, and the breakdowns let the cathedral go quiet and dark between
sections instead of flexing continuously. 5:06.

**Scene 3 — The Throat · "Warriors Fall (Original RumbleStep Mix)".** Brief: high-energy drum &
bass / breakbeat / electro, 150–175 BPM. An original (no samples) drumstep/dnb hybrid from cdk's
"RumbleStep" series, tagged bpm_170_175, page BPM 174. The strongest beat regularity of every
candidate (0.82), bright (centroid ~2.75 kHz, treble share 33 %) and relentless (energy 0.86–1.0
for 80 % of its 4:19 after a short intro at 0.31). The Menger tunnel is the rush room (`speedScale`
1.6): lime scan-rings on every kick, treble-driven edge flicker and prism aberration, so a bright,
metronomic track is the point. The analyser locks to the 87 BPM snare backbeat (see §3) — the
two-step feel — which is what the 340 ms onset refractory turns into a ~2.4 pulses/s hammer.

**Scene 4 — The Hive · "The Beach".** Brief: euphoric psytrance / acid / uplifting electronica,
128–145 BPM. Actual progressive psytrance (tags psytrance / psychedelic_techno / trance; a ccMixter
editorial pick), artist-stated 135 BPM (measured 134.9). Rolling bass (bass share 52 %), crystal
bell and "dolphin" synth layers, and a proper arc: intro 0.23 → build 0.65 → plateau 0.93–1.0 →
outro 0.37 over 6:51 (LRA 8.9 LU). The Hive is pitch dark and lit only by the sluurp; bass extends
the lamp's reach (`R += 1.4·uBass`), so a track whose low end swells across the intro makes the cave
reveal itself over the first minute. A female spoken-word poem ("Beach (Moana)" by debbizo, itself
CC BY 3.0) floats over the middle section — dreamy, not lyrical.

---

## 2. Licence chain (verified live on 2026-09-02 by the scout and a second, sceptical verifier)

All four tracks are CC BY; CC BY 3.0 and 4.0 both permit copying and redistribution inside a free
app provided the title, author, source and licence are credited and modifications are stated
(both facts are in `ATTRIBUTION.md`: the 160/256 kbps re-encodes of slots 1 and 3 are declared).

| Slot | Evidence |
|---|---|
| 1, 2 | incompetech piece pages (`?isrc=USUAN1800019`, `?isrc=USUAN2100002`) render the site's attribution code — `"<title>" Kevin MacLeod (incompetech.com) / Licensed under Creative Commons: By Attribution 4.0 License / http://creativecommons.org/licenses/by/4.0/`; `pieces.json` lists both ISRCs (bpm 70 / 117, lengths 6:17 / 5:06); the Licenses page and FAQ state the CC licence covers all of his music and give the credit format; HEAD of the download URLs matches the original files' sizes; the ID3 comments carry the ISRCs. Caveat: the piece page template is site-wide, so the per-piece licence is established by the FAQ statement plus the catalogue entry rather than per-piece data. |
| 3 | ccMixter track page `files/cdk/50691`: `"Warriors Fall (Original RumbleStep Mix)" by cdk 2015 - Licensed under Creative Commons Attribution (3.0)` linking `by/3.0/`, "Featuring Darryl J", "This song is free to download and share", no "Uses samples from" section; API `ids=50691` → license `Attribution (3.0)`, sources null; ID3 copyright tag in the file: `2015 cdk Licensed to the public under http://creativecommons.org/licenses/by/3.0/ Verify at http://ccmixter.org/files/cdk/50691`. (The by-nc link in ccMixter's footer is the site's own text/image licence, not the track's.) |
| 4 | ccMixter track page `files/F_Fact/37699`: `"The Beach" by Platinum Butterfly 2012 - Licensed under Creative Commons Attribution (3.0)`, "Featuring Debizzo", "Uses samples from: Beach (Moana) by debbizo"; sample page `files/debbizo/26843`: `"Beach (Moana)" by debbizo 2010 - Licensed under Creative Commons Attribution (3.0)`, no further sources; API `ids=37699,26843` → both `Attribution (3.0)`; ID3 copyright tag: `2012 Platinum Butterfly Licensed to the public under http://creativecommons.org/licenses/by/3.0/ Verify at http://ccmixter.org/files/F_Fact/37699`. |

Attribution formats used: incompetech's generator text for slots 1–2; ccMixter's credit line with
the ccmixter.org file URL, the `Ft:` credit and (slot 4) the sample source for slots 3–4. The About
screen prints `‹title› · ‹artist› · ‹licence›` from `credits.json`; the README should carry the
one-line block at the bottom of `ATTRIBUTION.md`.

---

## 3. Envelopes (`tools/analyze_music.py`, run 2026-09-02 with ffmpeg 8.1)

Format (fixed, producer and consumer): `{"hopMs":20,"bpm":<float>,"energy":[…],"bass":[…],"treble":[…],"onsets":[…]}`
— energy = 20 ms block RMS then a 60 ms EMA; bass = RMS of a 20–200 Hz band, treble = RMS of a
4–12 kHz band (each band = two cascaded 2-pole Butterworth sections per edge in ffmpeg, 24 dB/oct;
treble decoded at 44.1 kHz so 12 kHz is below Nyquist); every array scaled so its 99th percentile is
255; onsets = SuperFlux spectral flux (1024-pt Hann, 6.67 ms hop) with an adaptive threshold,
strongest-first under a 340 ms refractory (≤ 3 pulses/s at any tempo — the photosensitivity gate);
bpm = autocorrelation of the onset-strength curve in 60–180 BPM with a mild 120 BPM log-normal
prior, then sharpened from the autocorrelation peak at 2–32-beat multiples (resolves DAW-quantised
tempos to ~0.01 BPM: 140.003 / 116.998 / 87.001 / 135.002 before rounding). Full method in the
script's docstring. Missing/malformed file → the app falls back to
energy 0.5, bass/treble 0.3 and a synthetic 120 BPM beat.

| Slot | bpm (in file) | autocorr. | Octave twin | onsets | onsets/s | median gap | grid fit | mean energy/bass/treble (/255) | file |
|---|---|---|---|---|---|---|---|---|---|
| 1 | **140.00** | 0.507 | 70.0 at 0.96× — the felt half-time tempo (catalogue 70); 140 is the hat/kick pulse grid | 710 | 1.88 | 433 ms | 78 / 80 % | 168 / 145 / 49 | 196 KiB |
| 2 | **117.00** | 0.534 | none (155.9 at 0.94× is a 3:4 alias, not an octave) | 620 | 2.03 | 513 ms | 38 / 55 % | 116 / 60 / 31 | 142 KiB |
| 3 | **87.00** | 0.748 | 174.0 at 0.68× — the two-step dnb tempo (page 174); 87 = snare backbeat, wins outright | 609 | 2.35 | 347 ms | 55 / 56 % (99 % on the 174 grid) | 109 / 47 / 96 | 125 KiB |
| 4 | **135.00** | 0.754 | 67.5 at 1.04× — the two-beat bar pattern; the prior keeps 135 (artist 135) | 654 | 1.59 | 447 ms | 56 / 63 % | 150 / 96 / 52 | 205 KiB |

Grid fit = share of onsets within ±40 ms of the file's bpm grid with one phase for the whole track /
one phase per 16 s. Slot 2's lower figure is syncopation, not drift (strongest-first picking keeps
off-beat marimba and bell accents; the 513 ms median gap is exactly one 117 BPM beat) — for a
hypnotic room that is a feature. Slot 3's 99 % on the 174 grid says the picker is catching kicks
*and* snares, minus the ones that fall inside the refractory jitter.

Sanity (script output): every slot PASSES — bpm within 4 % of the manifest value or its octave
(70 / 117 / 174-or-87 / 135) and onset density inside 0.5–3/s. Half/double-time notes: the file
carries the autocorrelation winner, not the catalogue number; a consumer that wants the "felt" tempo
for slot 1 may halve 140, and one that wants the dnb convention for slot 3 may double 87 — the onset
list is the same either way, and the `uBeat` refractory makes 174 BPM eighth-notes impossible anyway.
Regenerate after changing a track:

```
cd ~/Projects/TapFractal
tools/.venv/bin/python tools/analyze_music.py --all          # scene1..4.mp3 -> scene<N>.env.json
tools/.venv/bin/python tools/analyze_music.py --check app/src/main/assets/music/scene*.env.json
```

Alignment notes for the consumer: hop `i` covers `[20i, 20i+20)` ms of the decoded stream; onset
times are STFT frame centres (≤ ~12 ms late for sharp transients); the mp3s carry LAME gapless
headers, which Android's decoder honours, so `MediaPlayer.currentPosition` indexes the arrays
directly. Loop points: none of the four is a seamless loop — the 1 s music crossfade on scene change
is the only masking; a restart at 0 after `onCompletion` is audible on slots 2 and 4 (proper
outros) and near-inaudible on 1 and 3 (fade / flat energy).

---

## 4. Alternates the scout listed

CC0 (none beat the chosen tracks on fit):

- slot 2 — "Liquid Flame" by Of Far Different Nature (CC0, 125 BPM, 3:41, "dreamy, deep, electro trance") — https://opengameart.org/content/liquid-flame
- slot 2 — "Hypnotic Chill (Extended 4 minute mix)" by cynicmusic (CC0, ~125/167 BPM, 4:10, builds 0.35 → 1.0) — https://opengameart.org/content/hypnotic-chill-extended-4-minute-mix
- slot 4 — "Drama" (130 BPM WAV, 4:04) from tricksntraps "Free Rhythm Game Music Pack 2" (CC0) — https://opengameart.org/content/free-rhythm-game-music-pack-2 — weak kick clarity, amateur master
- slot 1 — tricksntraps "Free Surreal/Dream Music Pack" (CC0 OGGs) — thin, no low end; rejected

CC BY alternates:

- slot 1 — "Opium" by Kevin MacLeod (CC BY 4.0, 6:09, beatless santoor/strings drone); "Hypnothis" (4:43, 104 BPM chill electronic, dissonant run at 3:00)
- slot 3 — "Blown Away" by Kevin MacLeod (CC BY 4.0, 172 BPM, 3:46, punk-electronic, flat 0.9–1.0 energy) — https://incompetech.com/music/royalty-free/index.html?isrc=USUAN1200100 ; "Another Step Back" by Of Far Different Nature (CC BY 4.0, 170 BPM dreamy dnb, 3:03) — https://opengameart.org/content/another-step-back
- slot 4 — "Raving Energy (faster)" by Kevin MacLeod (CC BY 4.0, 134 BPM, 4:04, beat clarity 0.91, instrumental) — https://incompetech.com/music/royalty-free/index.html?isrc=USUAN1900012 ; F_Fact "End of Days" (CC BY 3.0 psytrance/goa, 134 BPM, 6:52, uses a Robbero spoken-word sample, CC BY 3.0)

Rejected during the search: Aquatone "Mental Images" (conflicting CC BY / CC BY-SA statements on
archive.org); Overdream "Soundprints" and Prokaion "Dream Filament" (every track > 7:00); Jordan
Margera "This Is Psytrance" (third-party aggregator upload, weak provenance); Fature "The Gathering
EP" (~188 BPM, microtonal) and Chump Change "Footage" (weak beat regularity); OpenGameArt loops
under 2:45 (MintoDog "Trance Adventure/City/Battle", "Awake! Megawall-10", "Hyper Ultra-Racing");
Free Music Archive was not queried (JS-rendered search, dead API).

Swapping a track: drop the new file in as `scene<N>.mp3`, run the analyser with `--expect <BPM>`,
update `ATTRIBUTION.md` and `credits.json`, and keep the total under ~30 MB.
