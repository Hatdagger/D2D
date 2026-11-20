# DiscDroid

Rip and (soon) burn CDs and DVDs on Android using a USB external disc drive. **No root needed.**

DiscDroid talks to the drive directly over USB with the same low-level commands a PC uses
(SCSI/MMC over USB Mass Storage Bulk-Only Transport), so it works even though Android has no
built-in support for optical drives.

## What works now (v0.2)

The screen is split like a Nintendo DS: **RIP** on top (blue), **BURN** on the bottom (red),
with the progress bar and Cancel button in the "hinge" between them.

**Ripping**
- Detects the disc drive (skips card readers/hubs in combo drives)
- Reads the track list, disc type, and album/track names (MusicBrainz online, or CD-TEXT)
- Rips audio CDs to **FLAC** (lossless, tagged) or WAV, with retries, cover art and a rip log
- Copies data CDs/DVDs to an ISO image

**Burning**
- Burns ISO images to CD-R/RW, DVD-R/RW, DVD+R and DVD+RW, then verifies the disc
- Burns audio CDs from WAV, FLAC, MP3, M4A and other 44.1 kHz files
- **Test burn** mode runs the whole burn with the laser off (CD-R/RW and DVD-R/RW)
- Erases CD-RW / DVD-RW and formats DVD+RW

**Not yet:** AccurateRip checks, gapless (disc-at-once) audio burning, resampling of
non-44.1 kHz files, Blu-ray.

Copy-protected discs (e.g. encrypted DVD-Video) are detected and refused. DiscDroid does not
remove copy protection.

## Getting the APK

Every push builds the app automatically:

1. Open the repo's **Actions** tab on GitHub.
2. Open the latest **Build APK** run with a green check.
3. Download **DiscDroid-apk** under *Artifacts*, unzip it, and install `DiscDroid.apk` on the
   tablet (allow "install unknown apps" for your browser or file manager when asked).

## Using it

1. Plug in the drive. If Android asks which app to open, choose DiscDroid.
2. Tap **Output folder** once and pick where rips should go (e.g. *Music* or *Download*).
3. Tap **Connect drive** and allow USB access. Combo drives with a built-in card reader may
   ask twice; the app skips the card reader by itself.
4. Insert a disc and tap **Read disc**, then **Rip audio CD** or **Copy data disc**.
5. To burn, put a blank disc in and use the bottom half. Try **Test burn** first with a new
   drive; it goes through every step without actually writing.

Keep the app open during a rip; the screen stays on automatically.

## Power

Slim USB drives can draw more power than a phone or tablet supplies, especially while
spinning up. If the drive disconnects or won't spin, unplug anything else from the drive's
hub ports, or use a powered USB-C hub.

## Testing notes

The disc logic is unit-tested against a simulated reader and a simulated writer that
enforces real drive rules, and the FLAC encoder is checked bit-for-bit with ffmpeg
(`app/src/test`, run on every build). Real
drives vary, so if something fails, copy the log text from the app into an issue.
