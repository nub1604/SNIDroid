# SNIDroid

Android-App zum Übertragen von SNES-ROMs vom Smartphone (lokaler Speicher oder Google Drive) direkt auf ein **FXPak Pro** per USB-OTG – ohne PC.

Die USB-Kommunikation ist eine Kotlin-Portierung des `fxpakpro`-Treibers aus [SNI](https://github.com/alttpo/sni) (usb2snes-Protokoll über USB CDC-ACM).

## Funktionen (v0.2)

- FXPak Pro automatisch erkennen (VID `0x1209`, PID `0x5A22`), App startet beim Anstecken
- Firmware-Version anzeigen (`INFO`)
- SD-Karte durchsuchen (Ordner öffnen, Pfadleiste, Zurück-Taste = Ordner hoch)
- Ordner anlegen, umbenennen, verschieben, löschen (Ordner rekursiv); Mehrfachauswahl per langem Tippen
- `/sd2snes` (Firmware) ist gegen Löschen/Verschieben geschützt
- ROMs (auch mehrere) über den Android-Dateiauswahldialog in den aktuellen Ordner hochladen – Google Drive erscheint dort als Quelle
- ROM starten (`BOOT`), optional direkt nach dem Upload

## Voraussetzungen

- Android 9 (API 28) oder neuer mit USB-Host/OTG
- USB-OTG-Adapter/-Kabel (USB-C → Mini-USB des FXPak Pro)
- Android Studio (Ladybug oder neuer), JDK 17

## Build

```
gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Nach jedem `assembleDebug`/`assembleRelease` (auch beim Ausführen aus Android Studio) wird die APK zusätzlich als `SNIDroid-<version>-<variante>.apk` nach `G:\\Meine Ablage\\TA` kopiert. Ordner änderbar über `snidroid.apkExportDir` in `gradle.properties`; existiert er nicht, wird der Schritt übersprungen.

## Aufbau

| Datei | Zweck |
|---|---|
| `usb/CdcAcmPort.kt` | CDC-ACM-Seriell über Android `UsbManager` (DTR setzen, Bulk-Transfers) |
| `fxpak/Usb2Snes.kt` | Protokollkonstanten, 512-Byte-Befehlspakete |
| `fxpak/FxPakClient.kt` | `info`, `list`, `mkdir(s)`, `rm`/`rmTree`, `mv`, `putFile`, `boot` |
| `ui/MainViewModel.kt` | Geräteerkennung, Berechtigung, Dateioperationen, Upload |
| `ui/MainScreen.kt` | Compose-Oberfläche (Dateibrowser) |

## Protokoll (Kurzfassung)

Befehl = 512 Byte: `"USBA"`, Opcode, Space, Flags, Größe (Big Endian, Offset 252), Pfad (Offset 256).
Antwort = 512 Byte, Opcode `RESPONSE (15)`, Fehlercode an Byte 5.
`MV`: Quellpfad ab Offset 256, Zielpfad ab Offset 8.
`PUT`: Befehl → Antwort → Daten in 512-Byte-Blöcken (letzter Block mit Nullen aufgefüllt).
