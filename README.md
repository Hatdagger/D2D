# D2D Disc2Android

Rip, play and burn CDs and DVDs on Android with a USB external disc drive. **No root needed.**

D2D talks to the drive directly over USB with the same low-level commands a PC uses
(SCSI/MMC over USB Mass Storage Bulk-Only Transport), so it works even though Android has no
built-in support for optical drives.

## What works now (v0.3)

The screen is split like a Nintendo DS: **RIP** on top (blue), and a bottom screen with two
tabs, **Player** (green) and **Burn** (red). The progress bar and Cancel button sit in the
"hinge" between them. Everything uses a dark theme.

**Ripping**
- Detects the disc drive (skips card readers/hubs in combo drives)
- Reads the track list, disc type, and album/track names (MusicBrainz online, or CD-TEXT)
- Rips audio CDs to **FLAC** (lossless, tagged) or WAV, with retries, cover art and a rip log
- Copies data CDs/DVDs to an ISO image

**Player**
- **Burn list**: add music *or video* files, put them in order, and listen to them before burning
  - Sort by track number, file name, title or artist, or reverse the order
  - Move songs up/down (▲ ▼) or remove them (✕); the list is kept between launches
- **Disc**: play an audio CD straight from the drive, like a CD player (the drive spins at 4x
  so it stays quiet)
- Play/pause, previous/next, seek bar

**Burning**
- Burns ISO images to CD-R/RW, DVD-R/RW, DVD+R and DVD+RW, then verifies the disc
- Burns audio CDs from the burn list, in the order shown in the Player
- **No gaps between tracks** option: burns the whole CD in one pass ("disc-at-once") so live
  albums and DJ mixes flow straight from one track into the next. Leave it off for the usual
  2-second pause between songs.
- Accepts WAV, FLAC, MP3, M4A, OGG/Opus and the sound from **MP4, WebM and MKV videos**. Files
  that aren't CD quality (for example 48 kHz video sound, or 5.1 surround) are converted
  automatically: resampled to 44.1 kHz and mixed down to stereo.
- **Test burn** mode runs the whole burn with the laser off (CD-R/RW and DVD-R/RW)
- Erases CD-RW / DVD-RW and formats DVD+RW

**Not yet:** AccurateRip checks, playback that keeps going when you leave the app, Blu-ray.

Copy-protected discs (e.g. encrypted DVD-Video) are detected and refused. D2D does not
remove copy protection.

## Getting the APK

Every push builds the app automatically:

1. Open the repo's **Actions** tab on GitHub.
2. Open the latest **Build APK** run with a green check.
3. Download **D2D-Disc2Android-apk** under *Artifacts*, unzip it, and install
   `D2D-Disc2Android.apk` on the tablet (allow "install unknown apps" for your browser or file
   manager when asked). It installs over the old DiscDroid version and keeps your settings.

## Using it

1. Plug in the drive. If Android asks which app to open, choose D2D Disc2Android.
2. Tap **Folder** once and pick where rips should go (e.g. *Music* or *Download*).
3. Tap **Connect** and allow USB access. Combo drives with a built-in card reader may ask
   twice; the app skips the card reader by itself.
4. Insert a disc and tap **Read disc**, then **Rip audio CD** or **Copy disc (ISO)**. To just
   listen, open the **Player** tab, choose **Disc** and tap a track.
5. To make an audio CD: in **Player → Burn list**, tap **+ Add files**, pick songs or videos,
   sort them, and play them to check. Then open the **Burn** tab, put a blank CD-R in and tap
   **Burn audio CD**. Try **Test burn** first with a new drive; it goes through every step
   without actually writing.

Keep the app open during a rip or burn; the screen stays on automatically.

## Power

Slim USB drives can draw more power than a phone or tablet supplies, especially while
spinning up. If the drive disconnects or won't spin, unplug anything else from the drive's
hub ports, or use a powered USB-C hub.

## Testing notes

The disc logic is unit-tested against a simulated reader and a simulated writer that
enforces real drive rules (including gapless disc-at-once burning), the FLAC encoder is
checked bit-for-bit with ffmpeg, and the audio converter is checked for exact pass-through
of CD audio and clean resampling (`app/src/test`, run on every build). Real drives vary, so
if something fails, copy the log text from the app into an issue.
