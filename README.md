# DiscDroid

Rip and (soon) burn CDs and DVDs on Android using a USB external disc drive. **No root needed.**

DiscDroid talks to the drive directly over USB with the same low-level commands a PC uses
(SCSI/MMC over USB Mass Storage Bulk-Only Transport), so it works even though Android has no
built-in support for optical drives.

## What works now (v0.1)

| Feature | Status |
|---|---|
| Detect the disc drive (skips card readers/hubs in combo drives) | ✅ |
| Read track list, disc type and CD-TEXT (album/track names) | ✅ |
| Rip audio CDs to WAV, one file per track, with retries and a rip log | ✅ |
| Copy data CDs/DVDs to an ISO image | ✅ |
| Eject | ✅ |
| FLAC output, online track names (MusicBrainz), AccurateRip check | Planned |
| Burn ISO to CD-R/RW, DVD±R/RW | Planned |
| Burn audio CDs from WAV/FLAC | Planned |

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

Keep the app open during a rip; the screen stays on automatically.

## Power

Slim USB drives can draw more power than a phone or tablet supplies, especially while
spinning up. If the drive disconnects or won't spin, unplug anything else from the drive's
hub ports, or use a powered USB-C hub.

## Testing notes

The disc-reading logic is unit-tested against a simulated drive (`app/src/test`). Real
drives vary, so if something fails, copy the log text from the app into an issue.
