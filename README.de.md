# SNIDroid

[English](README.md) | **Deutsch**

Android-App zum Übertragen von SNES-ROMs vom Smartphone (lokaler Speicher oder Google Drive) direkt auf ein **FXPak Pro** per USB-OTG – ohne PC.

Die USB-Kommunikation ist eine Kotlin-Portierung des `fxpakpro`-Treibers aus [SNI](https://github.com/alttpo/sni).

## Funktionen

- FXPak Pro wird automatisch erkannt, die App startet beim Anstecken
- SD-Karte durchsuchen; Dateien und Ordner anlegen, umbenennen, verschieben, löschen (Mehrfachauswahl per langem Tippen)
- Eine oder mehrere ROMs über den Android-Dateiauswahldialog hochladen (inkl. Google Drive)
- ROM starten, optional direkt nach dem Upload
- `/sd2snes` (Firmware) ist gegen Löschen und Verschieben geschützt
- Oberfläche auf Deutsch und Englisch, je nach Systemsprache

## Voraussetzungen

- Android 9 oder neuer mit USB-Host/OTG
- USB-OTG-Kabel oder -Adapter (USB-C → Mini-USB)

## Installation

APK unter [Releases](https://github.com/nub1604/SNIDroid/releases) herunterladen und installieren.

## Build

```
gradlew assembleDebug
```

Build-Details, Projektaufbau und Protokoll: [docs/DETAILS.de.md](docs/DETAILS.de.md)
