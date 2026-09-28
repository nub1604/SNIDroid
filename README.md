# SNIDroid

**English** | [Deutsch](README.de.md)

Android app that copies SNES ROMs from your phone (local storage or Google Drive) straight to an **FXPak Pro** over USB OTG – no PC needed.

The USB layer is a Kotlin port of the `fxpakpro` driver from [SNI](https://github.com/alttpo/sni).

## Features

- Detects the FXPak Pro automatically; the app opens when it is plugged in
- Browse the SD card; create, rename, move and delete files and folders (multi-select by long press)
- Upload one or more ROMs via the Android file picker (Google Drive included)
- Boot a ROM, optionally right after uploading
- `/sd2snes` (firmware) is protected against deletion and moving
- UI in English and German, following the system language

## Requirements

- Android 9 or newer with USB host/OTG support
- USB OTG cable or adapter (USB-C → Mini-USB)

## Installation

Download the APK from [Releases](https://github.com/nub1604/SNIDroid/releases) and install it.

## Build

```
gradlew assembleDebug
```

Build details, project layout and the protocol: [docs/DETAILS.md](docs/DETAILS.md)
