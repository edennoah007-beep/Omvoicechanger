# Chaos Voice — 2011X & Fleetway Super Sonic voice changer (Android)

## Get the APK
1. Create a new GitHub repo (for example `ChaosVoice`) and upload **everything in this folder**,
   including the hidden `.github` folder.
2. GitHub Actions builds it automatically (about 3–5 minutes, **Actions** tab).
3. Open the repo's **Releases** → *Chaos Voice (latest build)* → download `ChaosVoice.apk` on your phone and install it.
   (Allow "Install unknown apps" for your browser when Android asks.)

## Filters
| | 2011X | Fleetway Super Sonic |
|---|---|---|
| Pitch layers | −7 st (85%) + −12 st (55%) + 12% real voice | +3 st (90%) + detuned double +4.5 st (38%) |
| Grit | heavy saturation (drive 3.2) | raspy saturation (drive 2.3) |
| Growl | 34 Hz ring-mod, 22% | — |
| EQ | high-pass 55 Hz, +5 dB lows @160 Hz, low-pass 5.2 kHz, −4 dB air | high-pass 180 Hz, −2 dB @300 Hz, +5 dB @3 kHz, +2.5 dB air, low-pass 12 kHz |
| Echo | 190 ms, feedback 28%, mix 22% | 95 ms slap, feedback 18%, mix 16% |
| Reverb | big dark hall, 28% | small bright room, 13% |

Sliders in the app: pitch tweak (±6 semitones), effect strength (0–150%), mic boost.

## How it works with Discord / Roblox / WhatsApp / Signal
* **Voice messages – works:** record a clip with a filter, then tap WhatsApp / Signal / Discord to send it.
  Clips are also saved to `Music/ChaosVoice` (Android 10+).
* **Live calls – limited by Android:** normal apps can't replace the microphone other apps hear, so the filter
  can't go *into* a Discord/Roblox/WhatsApp/Signal call on the same phone. Live mode plays your changed voice
  to headphones/speaker; to use it in a call, run it on this phone (Speaker mode or an aux/USB cable) into the
  mic of the device that's in the call.

## Notes
* `app/chaosvoice.jks` is a signing key made just for this app (password `chaosvoice`) so updates install over
  old versions. Make a new one if you ever publish the app.
* Use headphones for live mode, or turn on Speaker mode to reduce squealing.
