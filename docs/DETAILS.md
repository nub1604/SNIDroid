# SNIDroid – Technical details

**English** | [Deutsch](DETAILS.de.md) · [Back to README](../README.md)

## Device

- FXPak Pro, USB VID `0x1209`, PID `0x5A22`
- usb2snes protocol over USB CDC-ACM (serial)
- Firmware version is read via `INFO`

## Build

Requirements: Android Studio (Ladybug or newer), JDK 17.

```
gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

After every `assembleDebug`/`assembleRelease` (also when running from Android Studio) the APK is additionally copied as `SNIDroid-<version>-<variant>.apk` to the folder set in `snidroid.apkExportDir`. Put it in your user-level `~/.gradle/gradle.properties` (not the repo), e.g. `snidroid.apkExportDir=D:/apks`. If it is unset or the folder does not exist, the step is skipped.

## Project layout

| File | Purpose |
|---|---|
| `usb/CdcAcmPort.kt` | CDC-ACM serial via Android `UsbManager` (set DTR, bulk transfers) |
| `fxpak/Usb2Snes.kt` | Protocol constants, 512-byte command packets |
| `fxpak/FxPakClient.kt` | `info`, `list`, `mkdir(s)`, `rm`/`rmTree`, `mv`, `putFile`, `boot` |
| `ui/MainViewModel.kt` | Device detection, permission, file operations, upload |
| `ui/MainScreen.kt` | Compose UI (file browser) |

Translations: `res/values/strings.xml` (English, default) and `res/values-de/strings.xml` (German). On Android 13+ the language can also be chosen per app in the system settings.

## Protocol (summary)

| Field | Offset / value |
|---|---|
| Command size | 512 bytes |
| Magic | `"USBA"` at 0 |
| Opcode, space, flags | bytes 4, 5, 6 |
| Size | big endian at 252 |
| Path | from 256 |
| Target path (`MV`) | from 8 |
| Response | 512 bytes, opcode `RESPONSE (15)`, error code at byte 5 |

`PUT`: command → response → data in 512-byte blocks (last block padded with zeros).
