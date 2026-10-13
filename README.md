# Utah Radio

An Android app that plays **FM100.3 (Salt Lake City)** and **Q92 (Logan, 92.9)** over the internet,
with **Android Auto** support (the app's icon appears on the car screen, and you pick a station there).

## What it does
- Two big station buttons. Tap one to play, tap again to stop. Big play/stop button at the bottom.
- Keeps playing with the screen off; shows a "now playing" notification with controls.
- Pauses automatically for phone calls and navigation directions, and when headphones are unplugged.
- **Android Auto:** open the Android Auto app launcher in your car, tap **Utah Radio**, pick a station.
  The steering-wheel / on-screen *next* and *previous* buttons switch between the two stations.
  Voice works too: "Hey Google, play Q92 on Utah Radio".
- Works on Wi-Fi or mobile data (it streams the stations' internet feeds, so in the car it uses your phone's data —
  roughly 20–60 MB per hour).

## Finding the stream
Stations change their stream addresses now and then, so the app looks it up itself: the station's own website
player first, then the public Radio Browser directory, then TuneIn. The last working address is remembered.
If a station ever stops working, open **Settings (gear icon)** and paste the station's stream address.

## Build the app (pick one)

**A. Android Studio (free, Windows/Mac/Linux)**
1. Install Android Studio from developer.android.com/studio.
2. *File → Open* → pick this `RadioApp` folder. Wait for "Gradle sync" to finish.
3. Plug in your phone with USB debugging on (Settings → About phone → tap *Build number* 7 times →
   Developer options → *USB debugging*). Press the green ▶ Run button.
   Or: *Build → Build App Bundle(s)/APK(s) → Build APK(s)* and copy the APK to your phone.

**B. GitHub (no install)**
Upload this folder to a new GitHub repository. The included workflow (`.github/workflows/build-apk.yml`)
builds the APK automatically — download it from the repo's *Actions* tab → latest run → *UtahRadio-apk*.

## Install on the phone
Open the APK on your phone and allow "Install unknown apps" for your file manager / browser when asked.

## Turn on Android Auto for an app you installed yourself
Android Auto only shows Play Store apps unless you allow others (one-time setup):
1. On the phone open **Settings → Apps → Android Auto → Additional settings in the app**
   (or open Android Auto settings directly).
2. Scroll to the bottom and tap **Version** about 10 times → OK to enable developer settings.
3. Top-right ⋮ menu → **Developer settings** → check **Unknown sources**.
4. In Android Auto settings → **Customize launcher** and make sure **Utah Radio** is checked.
5. Reconnect to the car — the Utah Radio icon appears on the car screen.

## Adding another station
Edit `app/src/main/java/com/utahradio/app/Stations.kt` and add another `Station(...)` entry
(plus a 512×512 picture in `app/src/main/res/drawable-nodpi/`).
