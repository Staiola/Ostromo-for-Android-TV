# Installing Ostromo on an Android TV

> [!NOTE]
> Not finished software. A home screen I made for my own TV, shared as is. Expect to tweak it, with some
> coding know-how or a coding agent like Claude Code, and use it at your own risk.

Ostromo isn't in the Google Play Store yet; you install the app file (an APK) yourself. That's called
*sideloading*, and every Android TV allows it once you say so. Nothing on the TV is rooted or changed, and
removing it is one click.

It runs on **Android TV and Google TV**: TVs from TCL, Sony, Philips, Hisense and others, and boxes like
Chromecast with Google TV, the Google TV Streamer and Nvidia Shield. It's a beta, tested on the Google TV
emulator (Android 14) and a Pixel; real TVs are next, so please [say how it goes](https://github.com/Staiola/Ostromo-for-Android-TV/issues).

**The app file:** [`ostromo-android-tv.apk`](https://github.com/Staiola/Ostromo-for-Android-TV/releases/latest/download/ostromo-android-tv.apk)
from the [latest release](https://github.com/Staiola/Ostromo-for-Android-TV/releases/latest). This address
always gives the newest version:

```text
https://github.com/Staiola/Ostromo-for-Android-TV/releases/latest/download/ostromo-android-tv.apk
```

Pick one of the three ways below. The first needs nothing but the TV and its remote.

## 1. On the TV, with the Downloader app (easiest)

1. **Turn on developer options.** Settings → System → About, then scroll to **Android TV OS build** and press
   OK on it seven times, until it says you're a developer. (On some TVs it's under Settings → Device
   Preferences → About.)
2. **Install Downloader.** In the Google Play Store on the TV, search for **Downloader** (by AFTVnews) and
   install it. It's free.
3. **Let Downloader install apps.** Settings → Apps → Security & restrictions → **Unknown sources**, and switch
   Downloader on. (Some TVs ask this the first time Downloader tries to install something instead; say yes.)
4. **Download Ostromo.** Open Downloader, select the address field, type the address above and press Go.
   When it has downloaded, choose **Install**.
5. **Open it.** Choose Open, or find Ostromo in your apps row. The first time, it opens SETUP.

## 2. From your phone, with Send Files to TV

1. Do steps 1 and 3 above, but for the app **Send Files to TV** instead of Downloader, and install it on both
   the TV and your phone (Google Play).
2. Download `ostromo-android-tv.apk` on the phone from the address above.
3. In Send Files to TV, send it from the phone to the TV, then open the file on the TV and choose Install.

## 3. From a computer: the easy way, with Claude

If you use [Claude Code](https://claude.com/claude-code), it can install it over your Wi-Fi. On the TV, turn
on developer options (step 1 above), then in Settings → System → Developer options switch on **USB debugging**
(sometimes called *ADB debugging* or *Network debugging*). Note the TV's IP address (Settings → Network &
Internet → your Wi-Fi network).

Open Claude Code in an empty folder and paste this, with your own details filled in:

```text
Please install Ostromo on my Android TV.

- TV: <your model, e.g. TCL 55P745>
- Developer options and USB/network debugging are on.
- TV IP address: <the IP>

1. Use Google's adb, from Android's platform-tools. If it's missing, install it with my package
   manager (on a Mac: brew install --cask android-platform-tools; on Windows:
   winget install Google.PlatformTools).
2. Connect with: adb connect <IP>:5555 . The TV shows an "Allow USB debugging?" prompt: tell me to
   accept it on the TV, then wait. If adb then lists more than one device, add -s <IP>:5555 to
   every adb command.
3. Download https://github.com/Staiola/Ostromo-for-Android-TV/releases/latest/download/ostromo-android-tv.apk
4. Install it with adb install -r, and start it with:
   adb shell am start -n com.unterface.ostromo/.MainActivity
5. Tell me what you did. Don't change any other settings on the TV.
```

Or by hand: `adb connect <IP>:5555`, accept the prompt on the TV, then `adb install -r ostromo-android-tv.apk`
and start it from the apps row (or `adb shell am start -n com.unterface.ostromo/.MainActivity`).

## Using it

- **Arrows and OK** do everything. **Back** steps back a screen, and on the home screen leaves Ostromo.
- **SETUP** (top right of the home screen) picks your streaming apps, HDMI inputs, power prices, radar
  airport, teletext and sounds. It starts with the airport and prices for your time zone where it can.
- Colour buttons, if your remote has them: **blue** radar, **green** power, **yellow** picture quality.
- The CRT effects step down by themselves if the TV can't keep up.

What to expect:

- **Streaming tiles** open the app if it's installed on the TV; it's found by its name.
- **HDMI tiles** only work on a TV with HDMI inputs (not on a Chromecast or Shield), and which input is
  "HDMI 2" is a best guess, because Android doesn't say.
- **DR TV** (Danish live TV) works in Denmark only.
- Ostromo is an app in your apps row, not a replacement for the TV's own home screen.

## Updating

Install the new `ostromo-android-tv.apk` the same way, over the old one. Your setup is kept.

## Removing it

Settings → Apps → Ostromo → Uninstall. You can switch developer options and unknown sources off again
afterwards.

## Problems

[Open an issue](https://github.com/Staiola/Ostromo-for-Android-TV/issues) and say which TV or box you have.
"App not installed" usually means an older Ostromo signed differently is on the TV: uninstall it first.
