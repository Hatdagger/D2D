# D2D Disc2Android handover

**Repo:** https://github.com/Hatdagger/D2D (branch `main`, v0.3). Every push builds an APK
(Actions tab, artifact **D2D-Disc2Android-apk**). Package id is still `com.hatdagger.discdroid`
so it upgrades the old DiscDroid install in place.

**What it is:** Android app (Kotlin, no root, no AndroidX) that rips, plays and burns
CDs/DVDs through a USB optical drive by sending SCSI/MMC commands over USB Bulk-Only Transport.

**User's hardware:** Lenovo Legion Tab Y700 Gen 2 + ASHDOL "8 in 1" USB 3.0/Type-C external
DVD burner with built-in hub and SD/TF reader. Nothing tested on real hardware yet.

**User:** not a programmer; wants plain-language updates. Allowed to push to `main`.

## Layout
- `core/` — pure Kotlin, unit-tested (52 tests): `Drive.kt` (MMC reads, SET CD SPEED),
  `DiscInfo.kt`, `Rippers.kt`, `Flac.kt`, `Metadata.kt` (MusicBrainz), `Burner.kt` (ISO, TAO
  audio, **gapless SAO audio via `GaplessLayout` + cue sheet**, erase), `Convert.kt`
  (**`PcmConverter`/`Resampler`**: any rate → 44.1 kHz windowed-sinc, surround downmix, bit-exact
  pass-through), `Playlist.kt` (**`Song`, `SongSort`, `Playlists`, `CdPlayback`** streaming CD
  audio), `BulkOnly.kt`, `Sense.kt`.
- `android/` — `MainActivity.kt` (DS split: RIP top/blue; bottom tabs Player green / Burn red;
  progress "hinge"), `Ui.kt` (Palette + styled view factory), `Player.kt` (MediaPlayer for files,
  AudioTrack + drive reads for disc), `AndroidIo.kt` (HTTP, tag reading, MediaCodec decode →
  PcmConverter, works for MP4/WebM/MKV), `OutputFolder.kt` (SAF), `UsbBulkTransport.kt`.
- Burn list is saved in prefs as `uri\tfilename` lines (persistable read permission taken).
- Tests: `app/src/test/...` (FakeDrive reader, FakeRecorder writer incl. SAO/cue sheet).
- No Android SDK in the Claude workspace: core tests run locally with Gradle's bundled Kotlin
  compiler + JUnit from `/opt/gradle-8.14.3/lib`; Android code is checked by the GitHub build
  (public API: `api.github.com/repos/Hatdagger/D2D/actions/runs`).

## v0.4 (BIN/CUE + Copy log)
- `core/DiscImage.kt`: `DiscImage` (sector mode from raw header, `needsBinCue`, cue sheet with
  INDEX 00 = 150-sector pregap for audio after data), `BinRipper` (READ CD raw 0xBE type any
  flags 0xF8 for data tracks, falls back to CD-DA read; audio tracks via readAudio; one .bin).
  `Drive.readRaw`. "Copy disc" button auto-picks: CD with data first + (Mode 2 or >1 track) →
  BIN/CUE folder (.bin, .cue, copy-log.txt); else ISO. MusicBrainz skipped when track 1 is data.
- Copy log button in the hinge copies RIP + BURN logs to clipboard.
- Audio burn: `Burner.checkAudioDisc` (CD present, blank CD-R/RW, estimated length fits) now runs BEFORE decoding tracks (user hit a non-blank error only after long preparation).
- Hardware results so far: audio TAO burn OK, disc playback OK. PS1 (Japanese) ISO copy failed
  before v0.4 (no log). Second burn (gapless, MP4s) produced a disc with an unrelated album:
  suspected non-blank/reused disc (burner refuses non-blank) — awaiting user's retest + log.
- Not possible on stock drives: GameCube/Wii, Xbox, Dreamcast GD-ROM.

## Next steps
1. When the drive arrives: Connect, Read disc, play a CD in Player → Disc, rip a CD; Test burn
   (normal and "No gaps"); real burn on a CD-RW. Collect log text for any failure.
2. Unverified on hardware: cue-sheet track numbers are BCD; SAO writes start at LBA -150.
3. Ideas not done: AccurateRip, background playback service/notification, drag-to-reorder,
   export videos' sound as FLAC files, Blu-ray.
