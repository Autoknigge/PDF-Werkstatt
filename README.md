# PDF-Werkstatt

Android-App zum Bearbeiten von PDFs: zusammenfügen, Seiten sortieren/drehen/löschen, aufteilen, verkleinern, in Bilder umwandeln, Passwort setzen oder entfernen, Metadaten löschen. Alles lokal auf dem Gerät. Gratis mit AdMob-Werbung (Banner + App-Start-Anzeige). Umfang: siehe [SCOPE.md](SCOPE.md).

Paketname: `de.autoknigge.pdfwerkstatt` – nach der Veröffentlichung im Play Store nicht mehr änderbar.

## Auf GitHub bauen

1. Unter **Actions → Build** läuft der Build bei jedem Push (jeder Branch). Ergebnis: Artefakt **PdfWerkstatt** (`PdfWerkstatt.apk`) – installierbar zum Testen.
2. Release-Link zum Weitergeben: Tag `1.0` setzen → APK hängt automatisch am GitHub-Release.

## Werbung (AdMob)

Die echten IDs von PDF-Werkstatt stehen in `app/build.gradle.kts` (öffentliche Kennungen, keine Geheimnisse):

| Zweck | ID |
|---|---|
| App-ID | `ca-app-pub-7178658520690671~7731039871` |
| Banner | `ca-app-pub-7178658520690671/9559126864` |
| App-Start | `ca-app-pub-7178658520690671/8695095193` |

Überschreibbar per GitHub-Secrets `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `ADMOB_OPEN_ID` (z. B. mit den Google-Test-IDs aus dem Kommentar in `build.gradle.kts`).
**Mit echten IDs nicht selbst auf die eigenen Anzeigen tippen** (Kontosperre-Risiko). Neue Anzeigenblöcke brauchen bis zu einer Stunde, bis Anzeigen kommen.

`app-ads.txt` liegt bereits auf autoknigge.de (gleiche Publisher-ID wie die anderen Apps). In der Play Console muss als Website `https://autoknigge.de` eingetragen sein.

## Signiert für den Play Store

Secrets: `KEYSTORE_BASE64` (Keystore-Datei als Base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Dann entstehen `PdfWerkstatt-signiert.apk` und `PdfWerkstatt.aab` (Play Store braucht die AAB).
Keystore sicher aufbewahren – ohne ihn gibt es keine Updates.

## Vor dem Play-Store-Start

- Datenschutzerklärung: `https://autoknigge.de/pdfwerkstatt/datenschutz.html` (in der App hinterlegt, `URL_PRIVACY` in `MainActivity.kt`).
- Play Console → Datensicherheit: Werbe-ID und Geräte-/App-Daten werden von Google AdMob für Werbung erhoben und geteilt; Dateien der Nutzer werden **nicht** erhoben (Verarbeitung nur auf dem Gerät).
- `targetSdk` (aktuell 36) vor dem Upload gegen die aktuelle Play-Vorgabe prüfen.

## Technik

- PDF-Bearbeitung: [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache-2.0), Vorschau und Bilder: Android-`PdfRenderer`.
- Gespeichert wird über den MediaStore in `Downloads/PDF-Werkstatt` bzw. `Bilder/PDF-Werkstatt` – keine Speicher-Berechtigung nötig.
