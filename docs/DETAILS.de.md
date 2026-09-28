# SNIDroid – Technische Details

[English](DETAILS.md) | **Deutsch** · [Zurück zur README](../README.de.md)

## Gerät

- FXPak Pro, USB-VID `0x1209`, PID `0x5A22`
- usb2snes-Protokoll über USB CDC-ACM (seriell)
- Firmware-Version wird über `INFO` gelesen

## Build

Voraussetzungen: Android Studio (Ladybug oder neuer), JDK 17.

```
gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Nach jedem `assembleDebug`/`assembleRelease` (auch beim Ausführen aus Android Studio) wird die APK zusätzlich als `SNIDroid-<version>-<variante>.apk` in den Ordner aus `snidroid.apkExportDir` kopiert. Die Einstellung gehört in die benutzerweite `~/.gradle/gradle.properties` (nicht ins Repo), z. B. `snidroid.apkExportDir=D:/apks`. Ist sie nicht gesetzt oder existiert der Ordner nicht, wird der Schritt übersprungen.

## Aufbau

| Datei | Zweck |
|---|---|
| `usb/CdcAcmPort.kt` | CDC-ACM-Seriell über Android `UsbManager` (DTR setzen, Bulk-Transfers) |
| `fxpak/Usb2Snes.kt` | Protokollkonstanten, 512-Byte-Befehlspakete |
| `fxpak/FxPakClient.kt` | `info`, `list`, `mkdir(s)`, `rm`/`rmTree`, `mv`, `putFile`, `boot` |
| `ui/MainViewModel.kt` | Geräteerkennung, Berechtigung, Dateioperationen, Upload |
| `ui/MainScreen.kt` | Compose-Oberfläche (Dateibrowser) |

Übersetzungen: `res/values/strings.xml` (Englisch, Standard) und `res/values-de/strings.xml` (Deutsch). Ab Android 13 lässt sich die Sprache zusätzlich pro App in den Systemeinstellungen wählen.

## Protokoll (Kurzfassung)

| Feld | Offset / Wert |
|---|---|
| Befehlsgröße | 512 Byte |
| Kennung | `"USBA"` ab 0 |
| Opcode, Space, Flags | Byte 4, 5, 6 |
| Größe | Big Endian ab 252 |
| Pfad | ab 256 |
| Zielpfad (`MV`) | ab 8 |
| Antwort | 512 Byte, Opcode `RESPONSE (15)`, Fehlercode an Byte 5 |

`PUT`: Befehl → Antwort → Daten in 512-Byte-Blöcken (letzter Block mit Nullen aufgefüllt).
