# Fassadenvariante der Loksteuerung

Diese Datei beschreibt die Implementierung im Branch `case/nachher`. Die direkte
Vergleichsversion liegt in `case/vorher` bei Commit `984d4b0`. Beide beruhen auf
dem bestehenden NFC-App-Stand `54c1ec8`; der Produktionscode der Vorher-Version
entspricht diesem Stand. Die gemeinsame Testsuite wurde ergänzt.

## Rollen

- `MainActivity`: NFC-Scan, Adresszuordnung, Verbindungseinrichtung,
  Bedienereignisse, Fahrfreigabe und Oberfläche.
- `LokSteuerung`: Fassade mit `stop()`, `setSpeed()`, `setDirection()` und
  `setFeature()`; koordiniert Zustandsänderung und zeitlich geplanten Versand.
- `LokZustand`: Subsystemkomponente für vollständiges JSON, Änderungen und
  einen ausgewerteten `LokAnzeige`-Zustand für die Oberfläche.
- `LokKommunikation`: Subsystemkomponente für OkHttp und WebSocket; enthält
  keine Referenz auf `MainActivity` oder `LokSteuerung`.

## Beispiel Stopp

Der Button ruft `steuerung.stop()` auf. Die Fassade setzt mit `LokZustand` die
Geschwindigkeit auf null und plant den Versand. `LokKommunikation` versendet
den vollständigen zuletzt geladenen Zustand einschließlich unveränderter
Felder. Wie vorher wird der Slider lokal sofort auf null gesetzt.

Eingehende WebSocket-Nachrichten werden vom Listener an den UI-Thread
übergeben. Eine bei der Einrichtung registrierte Funktion der Fassade lässt
`LokZustand` die Nachricht verarbeiten und meldet einen `LokAnzeige`-Zustand
an `MainActivity.onServerState`. Die MainActivity aktualisiert die Anzeige.
Dieser Weg gilt auch für die erste Nachricht und für spätere Zustandsmeldungen,
die nicht durch einen Bedienaufruf dieser App ausgelöst wurden.

Der Rückweg ist nicht die synchrone Rückgabe von `stop()`. Eine Servernachricht
beschreibt den serverseitigen Zustand und keine gemessene physische Bewegung.

## Gemeinsame Funktion

- Dieselbe Oberfläche und dieselben Adressen für Gisela, 80 012/ISE und Blauwal.
- Dieselbe NDEF-Auswertung und Behandlung leerer, unbekannter oder mehrdeutiger Tags.
- Geschwindigkeit, Richtung, Stopp, Licht, Hupe, Fahrgeräusch und Rauch.
- Fahrfreigabe und nicht verfügbare Funktionen bleiben berücksichtigt.
- Vollständiger JSON-Zustand statt Aufbau einer neuen, verkürzten Nachricht.
- Gleiche Sendefristen: 700 ms Verzögerung bei Slideränderung und mindestens
  400 ms Abstand zwischen geplanten Sendungen; Abschluss einer Sliderbedienung
  und Stopp fordern Versand ohne zusätzliche Verzögerung an.
- Beim Lokwechsel ausstehende Sendungen abbrechen, die alte Lok gegebenenfalls
  stoppen und Fahrfreigabe zurücksetzen. Alte Verbindungen liefern keine neuen
  Zustände an die aktuelle Steuerung.

Die MainActivity verwendet die Kommunikationskomponente weiterhin direkt
zur Einrichtung. Die Fassade vereinfacht den Steuerungszugang; sie beseitigt
nicht jede Abhängigkeit zum Subsystem. Beim Zerstören der Activity werden
Rückmeldefunktionen, geplante Aufrufe und Verbindung freigegeben. Wie im
Ausgangsstand wird eine neue Activity nicht automatisch wieder verbunden.

## Prüfen und vergleichen

```sh
git switch case/vorher
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
git switch case/nachher
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
git diff case/vorher..case/nachher -- app/src/main/java
```

Am 24.11.2026 wurden beide Branches gebaut und mit denselben sieben Unit-Tests
und sieben Android-Tests auf dem vorhandenen Emulator geprüft. Lint war in
beiden erfolgreich; die Nachher-Version enthält vier Warnungen und keine Fehler.
Der lokale WebSocket-Testserver prüft Nachrichten und Darstellung. Physische
NFC-Tags, reale Lokbewegungen und vollständige Gleichheit in jeder denkbaren
Fehlersituation wurden dabei nicht geprüft.
