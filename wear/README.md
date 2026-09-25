# Metrolist for Wear OS

A standalone Wear OS app: it searches, browses and streams YouTube Music on the watch itself.
No phone app is involved.

## Build

```sh
STORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=... gradle :wear:assembleRelease
adb install wear/build/outputs/apk/release/wear-release.apk
```

## Signing in

Wear OS has no WebView, so the watch can't show the Google login page. Instead it takes the
cookies of a signed-in `music.youtube.com` browser session (they must include `SAPISID`).

**On the watch** (Home or Settings → Sign in): the watch joins Wi-Fi and shows a QR code and an
address like `192.168.1.20:8080/482913`. Open it in any browser on the same network, then paste
the cookies or pick an exported file. Accepted: a `Cookie` header, a Netscape `cookies.txt`, or a
JSON export from a cookie extension; cookies for other sites are ignored. The page only exists
while that screen is open, and its 6-digit code changes after 20 wrong guesses. It is plain HTTP on
your local network, so use a network you trust.

**Over adb:**

```sh
adb shell am broadcast -n com.metrolist.music/com.metrolist.wear.AccountImportReceiver \
    --es cookie "'SAPISID=...; __Secure-3PSID=...; ...'"
```

Optional extras: `--es data_sync_id "'...'"` for brand accounts, `--es visitor_data "'...'"`.
Sign out from Settings, or with `--ez sign_out true`. Debug builds use the package
`com.metrolist.music.debug`. The receiver requires the `DUMP` permission, which only the adb
shell and the system hold.

## Offline

- **Downloads** (Home): long-press any song to download it (long-press again to remove it), use
  *Download all* at the top of a playlist, album or Liked songs, or the download button on the
  player. Downloads are never evicted.
- **Cached songs** (Home): songs played to the end stay in the player cache until it needs room for
  newer ones. Long-press one to download it for good.

Both play without a connection. Covers are saved next to the audio so lists look the same offline.

## Settings

| Setting | What it does |
|---|---|
| Repeat | Off, whole queue, or current song. Also on the player screen, next to shuffle |
| Shuffle | Also on the player screen |
| Autoplay | When a list or album ends and repeat is off, keep going with a radio of similar songs |
| Sleep timer | 15/30/45/60 min, or pause at the end of the current song |
| Skip silence | Skips silent parts of songs |
| High quality audio | Higher bitrate on unmetered networks |
| Allow watch speaker | Otherwise playback waits for Bluetooth headphones (applies on the next start) |
| Crown seeks | On the player, the crown skips 5 s instead of changing volume |
| Max cache size | 64 MB – 2 GB for the player cache; shrinking it evicts right away. Downloads don't count |
| Auto-download liked songs | Downloads the liked list when the app starts and every song liked on the watch |
