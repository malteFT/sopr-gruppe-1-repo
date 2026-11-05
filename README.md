# Loksteuerung mit NFC

Dieser Branch erweitert die vollständige Loksteuerung aus **android-app** im **ise-server**: Die NDEF-Texte **Gisela**, **80 012** und **Blauwal** öffnen automatisch die WebSocket-Verbindung der jeweiligen Lok. Die unbekannte Tag-Seriennummer/UID wird nicht benötigt; eine Tag-ID-Zuordnung ist nicht erforderlich.

## Auf dem anderen Laptop installieren

1. In Android Studio **Get from Version Control / Clone Repository**: `https://gitlab.dd.dhsn.de/3wi25-1/gruppe-1/server.git` klonen und **feature/android-nfc-gisela** auschecken. Mit dem eigenen GitLab-Zugang anmelden; Zugangsdaten nicht in die URL schreiben.
2. Projektordner mit `settings.gradle.kts` öffnen und Gradle synchronisieren. Die App liegt direkt im Stamm dieses Branches. SDK 34 und Build Tools 34.0.0 bei Bedarf installieren; als Gradle JDK 21 oder 17 wählen. Gradle Wrapper 8.11.1, Android Gradle Plugin 8.7.3 und Kotlin 2.1.10 sind eingerichtet.
3. Am Pixel 9a in **Über das Telefon** siebenmal auf **Build-Nummer** tippen und in den Entwickleroptionen **USB-Debugging** einschalten. Mit einem USB-Datenkabel verbinden und den Dialog am Handy bestätigen. Unter Windows gegebenenfalls den Google-USB-Treiber über den SDK Manager installieren.
4. In Android Studio das Modul **app** und das Pixel wählen, dann **Run ▶** drücken. Die App heißt **Loksteuerung NFC**.

**Vorhandenes Projekt aktualisieren:** Im Branch **feature/android-nfc-gisela** in Android Studio **Git → Pull**, anschließend **Sync Project with Gradle Files** und **Run ▶**.

Alternativ den Branch in GitLab auswählen, **Code → Download source code → ZIP**, entpacken und in Android Studio öffnen. Unterstützt Android 8.0/API 26 und höher. `local.properties` wird auf jedem Laptop lokal erzeugt.

Bauen: `./gradlew :app:assembleDebug` (macOS/Linux) bzw. `.\gradlew.bat :app:assembleDebug` (Windows/PowerShell). APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Drei Loks ohne URL-Eingabe verbinden

1. Handy mit dem Netz der Gartenbahn verbinden, NFC einschalten und die App im Vordergrund geöffnet lassen.
2. Die Rückseite des entsperrten Pixel an den **ISO14443-3A / NFC-A**-Tag halten.
3. Die App liest den Lok-Text und verbindet automatisch mit der zugehörigen Adresse aus der Tabelle.
4. Den vom Server geladenen Loknamen prüfen. Zum Fahren wie bisher **Fahren freigeben** einschalten. Ein Scan selbst sendet keinen Fahrbefehl an die neu ausgewählte Lok.

In NFC Tools muss ein **Text-Datensatz** mit **Gisela**, **80 012** (Leerzeichen zwischen 80 und 012) oder **Blauwal** auf dem jeweiligen Lok-Tag stehen. Groß-/Kleinschreibung und äußere Leerzeichen werden ignoriert. Mehrere Leerzeichen, Tabulatoren und geschützte Leerzeichen zwischen `80` und `012` werden ebenfalls erkannt. Die App liest UTF-8- und UTF-16-Text-Datensätze mit ihrer Sprachkennung. Sie beschreibt den Tag nicht.

| Text auf dem Tag | Lok / Registry-Name | WebSocket-Adresse |
|---|---|---|
| Gisela | Gisela | ws://10.195.24.105:8076/locomotive |
| 80 012 (auch ISE) | 80 012; Gerätename ISE | ws://10.195.24.142:8075/locomotive |
| Blauwal (auch Blauwaal) | Blauwaal | ws://10.195.24.112:8075/locomotive |

Die Adressen von Gisela und 80 012 stammen aus **doku/gartenbahn-ise**, [04-geraete.md](https://gitlab.dd.dhsn.de/3wi25-1/gruppe-1/server/-/blob/doku/gartenbahn-ise/04-geraete.md), Stand 15.10.2026. **Blauwaal** wurde am 05.11.2026 vom Nutzer aus der Live-Liste des RRS gemeldet. Der Tag-Text **Blauwal** und der Registry-Name **Blauwaal** werden beide akzeptiert. Gisela wurde vom Nutzer bereits erfolgreich per Pixel-Scan verbunden. Die Zuordnung steht in `MainActivity.nfcUrlForName`, die Adressen in `app/src/main/res/values/strings.xml` (`gisela_url`, `default_url`, `blauwal_url`). Bei geänderter IP/Port diese Ressourcen anpassen oder die bestehende manuelle Eingabe verwenden. Der Scan benötigt keinen Registry-Server.

Leere, unbekannte oder mehrdeutige Texte lösen keine neue Verbindung aus; die aktuelle Verbindung bleibt bestehen. Derselbe Lok-Tag trennt eine bereits bestehende Verbindung nicht. Beim Wechsel zu einer anderen Lok werden ausstehende Sendetimer gelöscht, gegebenenfalls ein Stopp an die bisherige Lok gesendet und die Fahrfreigabe zurückgesetzt. Verspätete Zustandsnachrichten der alten Verbindung werden ignoriert. Bei Netzwerkausfall kann der Empfang eines Stoppbefehls nicht garantiert werden.

Ohne NFC bzw. bei ausgeschaltetem NFC erscheint ein Hinweis; die manuelle URL-Eingabe bleibt möglich.

## Vorhandene Steuerung

- Zustand wird beim Verbinden automatisch vom Server gelesen.
- Read-Modify-Write: kompletter zuletzt empfangener JSON-Zustand wird zurückgesendet; nur geänderte Felder werden angepasst.
- Geschwindigkeit 0–255 per Slider, Richtung vorwärts/rückwärts.
- Schalter für Fahrlicht, Kabinenlicht, Hupe, Fahrgeräusch und Rauch; nicht vorhandene Funktionen bleiben ausgegraut.
- NOT-STOP setzt Geschwindigkeit auf 0.
- Fahrbefehle benötigen die vorhandene Freigabe **Fahren freigeben**.
- Protokollfenster zeigt gesendete und empfangene Nachrichten.
- Drosselung der Nachrichten und Richtungsänderung nur bei Stillstand bleiben erhalten.

## Protokoll

JSON-Felder: `id`, `speed`, `direction` (0=vor, 1=rück), `number`, `name`, `headLight`, `cabineLighting`, `hornSound`, `drivingSound`, `smoking`, `position`.

Die `id` wird bei jedem Serverstart neu erzeugt. Sie wird weiterhin vom verbundenen Lokserver übernommen und nicht vom NFC-Tag gelesen. Der Server ignoriert Nachrichten ohne passende `id` und akzeptiert keine unbekannten Felder. Debounce: 300 ms, maximal 100 Nachrichten/60 s pro Client-IP; die App drosselt selbst.

## Prüfen

Build/Unit-Tests: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. Mit laufendem Testemulator/Testgerät: `./gradlew :app:connectedDebugAndroidTest`.

Unit-Tests prüfen UTF-8/UTF-16-NDEF-Text, Sprachkennung, Normalisierung und ungültige Nutzdaten. Android-Tests speisen einen gelesenen Text in den Scan-Ablauf ein und prüfen gegen einen lokalen WebSocket-Testserver die tatsächliche Adresszuordnung aller drei Loks, automatische Verbindung, Freigabe erst nach Empfang des Zustands, vollständige JSON-Nachrichten sowie Licht/STOP, unbekannte/mehrdeutige Tags und wiederholte Scans. Die Tests kontaktieren keine echte Lok.

Am Pixel vor Ort jeden der drei Tags scannen und den angezeigten Loknamen bzw. die Nummer prüfen. Nach jedem Lokwechsel ist die Fahrfreigabe erneut nötig. Fahrlicht testen, dann kontrolliert niedrige Geschwindigkeit und STOP testen. Die aktuellen Geräte-IDs und verfügbaren Funktionen werden immer vom jeweiligen Lokserver geladen.

Geprüft am 05.11.2026: Debug-APK (Version 1.2-nfc) gebaut, sieben Unit-Tests und fünf Android-Tests erfolgreich. Die Android-Tests liefen auf Android 16/API 36.1. Android Lint meldet keine Fehler und sieben Warnungen. Physischer NFC-Scan und echte Fahrt wurden hier nicht durchgeführt.

## Projektquellen

- [Vorhandener Android-App-Branch](https://gitlab.dd.dhsn.de/3wi25-1/gruppe-1/server/-/tree/android-app)
- [WebSocket-Protokolldokumentation](https://gitlab.dd.dhsn.de/3wi25-1/gruppe-1/server/-/blob/doku/gartenbahn-ise/03-websocket-protokolle.md)
