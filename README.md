# Loksteuerung (Android-App)

Kleine Android-App zur Steuerung der Modelllok **80 012** (Gerätename „ISE") über den
WebSocket-Endpunkt `ws://10.195.24.142:8075/locomotive`.

## Funktionen

- Verbinden per WebSocket (URL im Feld anpassbar, Standard: 80 012)
- Zustand lesen (wird beim Verbinden automatisch vom Server gesendet)
- Read-Modify-Write: es wird immer der komplette, zuletzt empfangene Zustand
  zurückgesendet – nur geänderte Felder werden vorher angepasst
- Geschwindigkeit (0–255, Slider), Richtung (vorwärts/rückwärts)
- Schalter für Fahrlicht, Kabinenlicht, Hupe, Fahrgeräusch, Rauch
  (Felder mit `null` = Hardware nicht vorhanden → ausgegraut)
- NOT-STOP-Button (setzt Geschwindigkeit sofort auf 0)
- Sicherheits-Freigabe: Fahrbefehle nur, wenn „Fahren freigeben" aktiv ist
- Protokollfenster mit allen gesendeten/empfangenen JSON-Nachrichten

## Protokoll-Hinweise (aus der Doku des Server-Repos)

- JSON-Felder: `id`, `speed`, `direction` (0=vor, 1=rück), `number`, `name`,
  `headLight`, `cabineLighting`, `hornSound`, `drivingSound`, `smoking`, `position`
- `id` wird bei Serverstart neu erzeugt → immer live lesen, nie fest verdrahten
- Nachricht ohne passende `id` wird ignoriert; unbekannte Felder = Fehler (strikt)
- Server-Debounce 300 ms, max. 100 Nachrichten/60 s pro Client-IP → App drosselt selbst
- Änderungen werden per Broadcast an alle Clients gesendet

## Bauen und Starten

- **Android Studio:** Ordner `~/StudioProjects/loksteuerung` öffnen, Gradle-Sync
  abwarten, dann auf Gerät/Emulator starten
- **Kommandozeile:** `./gradlew :app:assembleDebug`
  → APK unter `app/build/outputs/apk/debug/app-debug.apk` (`adb install …`)

Das Handy/der Emulator muss im selben Netz wie die Anlage sein (`10.195.24.0/24`).

## Git / GitLab

Dieses Projekt liegt als Branch **`android-app`** im Server-Repo
(`https://gitlab.dd.dhsn.de/3wi25-1/gruppe-1/server`). Der Branch hat keine
gemeinsame Historie mit `main` – der Ordner hier ist ein Git-Worktree dieses Branches.

Push aus Android Studio: **Git → Push…**, Branch `android-app` wählen, als Passwort
einen Personal Access Token (`write_repository`) verwenden – nie das Log-in-Passwort,
nie den Token in Dateien speichern. Alternativ im Terminal:

```bash
git push -u origin android-app
```

Branch woanders auschecken:

```bash
git fetch origin android-app
git worktree add ../loksteuerung android-app   # oder: git switch android-app
```

## Sicherheit

⚠️ Es hängen echte Loks auf echten Schienen. Erst gefahrlose Zustände testen
(z. B. Fahrlicht), Fahren nur kontrolliert und in Sichtweite.
