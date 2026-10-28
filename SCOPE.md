# PDF-Werkstatt – Umfang Version 1.0

Muster wie Fotos zu PDF: GitHub-Build (Actions), Gratis-Version mit AdMob, gleiches Aussehen (Tannengrün/Gold, Kacheln, Fußzeile mit Datenschutz · Impressum · Werbe-Einstellungen).

## Enthalten in 1.0

**Zusammenfügen & bearbeiten (Hauptkachel)**
- Eine oder mehrere PDFs wählen (Systemdialog, keine Speicher-Berechtigung), auch nachträglich weitere hinzufügen
- Alle Seiten als Liste mit Vorschau: nach oben/unten (Pfeile oder lange drücken und ziehen), um 90° drehen, entfernen
- Eigener Dateiname, Speichern als neue PDF in `Downloads/PDF-Werkstatt`; danach Teilen oder Öffnen
- Bis 1000 Seiten

**PDF aufteilen**
- Jede Seite einzeln oder nach Bereichen (`1-3, 4, 5-10`, auch `8-` = bis zum Ende); jeder Bereich wird eine eigene PDF
- Ein einzelner Bereich = Seiten herausziehen

**PDF verkleinern**
- Stufen Leicht / Mittel / Stark
- Standard: nur die Bilder in der PDF werden verkleinert und neu komprimiert, Text bleibt Text
- Option „Seiten komplett als Bild“: kleinste Datei, Text danach nicht mehr markierbar
- Anzeige vorher/nachher; wird die Datei nicht kleiner, wird nichts gespeichert

**PDF zu Bildern**
- JPG oder PNG, Auflösung Klein (~100 dpi) / Normal (~150 dpi) / Hoch (~300 dpi)
- Alle Seiten oder Auswahl (`1-3, 5`); Speichern in `Bilder/PDF-Werkstatt` (Galerie)

**Passwort & Metadaten**
- Passwort zum Öffnen setzen (AES-256)
- Passwort entfernen: geschützte PDFs fragen beim Öffnen nach dem Passwort und werden ohne Schutz gespeichert
- Metadaten entfernen (Autor, Programm, Titel, Datum, XMP)

**Allgemein**
- Passwortgeschützte PDFs funktionieren in allen Werkzeugen (Passwortabfrage beim Öffnen)
- PDFs per „Teilen“ oder „Öffnen mit“ an die App übergeben → Auswahl des Werkzeugs
- Alles lokal, kein Upload; Zwischendateien im App-Speicher werden nach einem Tag gelöscht

**Werbung / Rechtliches**
- AdMob-Banner am Fuß jedes Bildschirms, App-Start-Anzeige einmal je Start
- Google-Einwilligungsabfrage (UMP), „Werbe-Einstellungen“ im Startmenü
- Hinweis „Kostenlos dank Werbung“ beim ersten Start, vor der Einwilligungsabfrage
- Datenschutz: `https://autoknigge.de/pdfwerkstatt/datenschutz.html`

## Bewusst nicht in 1.0
- Ordner/Namen beim Speichern frei wählen (Systemdialog „Speichern unter“)
- Text/Unterschrift einfügen, Formulare ausfüllen, Texterkennung (OCR), Wasserzeichen, Seitenzahlen
- Bilder/Fotos zu PDF (gibt es in „Fotos zu PDF“)
- Pro-Version ohne Werbung, Mehrsprachigkeit
