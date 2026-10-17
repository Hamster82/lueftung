# Lüftungs-Brücke – App-Steuerung für das LB-Lüftungssystem WE

Steuerung der Leipfinger-Bader-Lüftung (WE, erste Generation, ONE-Touch-Bedienelement) per Android-App.
Ein ESP32 mit RS-485-Modul hängt parallel zum Bedienelement am Bus, hört mit und sendet auf Befehl.

```
[ONE Touch] ──Bus A/B/GND── [Master-Lüfter] ── Slaves
      │
   [ESP32 + RS-485] ── WLAN ── [Android-App]
```

> **Wichtig:** Das Bus-Protokoll ist nicht veröffentlicht. Die Brücke arbeitet deshalb mit **Anlernen**:
> Du wählst am Bedienfeld z. B. Stufe III, die Brücke zeichnet die dabei neu auftauchenden Telegramme auf
> und kann sie später selbst senden. Ob das bei dieser Anlage reicht, zeigt erst der Test.
> Falls nicht, liefert der eingebaute Bus-Mitschnitt die Daten, um das Protokoll gezielt auszuwerten.

---

## 1. Einkaufsliste (ca. 20 €)

| Teil | Hinweis |
|---|---|
| ESP32-DevKit (ESP32-WROOM-32, USB) | z. B. „ESP32 DevKitC V4“ / „NodeMCU-32S“ |
| RS-485-Modul für **3,3 V** mit DE/RE-Pins | z. B. „MAX3485 TTL zu RS485“. Das blaue MAX485-Modul ist für 5 V gedacht, läuft an 3,3 V oft, aber nicht garantiert |
| USB-Netzteil 5 V / 1 A + Kabel | Versorgung des ESP32 |
| 3-adrige Leitung, Wago-Klemmen, kleines Gehäuse | z. B. Rest J-Y(St)Y 2×2×0,6 |

## 2. Anschluss

**Anschlusspunkt:** die Klemmstelle zwischen Bedienelement und Lüfter (im Stromlaufplan der Anleitung **X100**,
Leitung W3 4×0,35 mm²), also hinter dem ONE-Touch-Bedienelement. Dort liegen nur **Bus A, Bus B, GND und +5 V** an –
keine 230 V. Trotzdem vor dem Klemmen die Sicherung der Lüfter ausschalten, damit die 5 V nicht kurzgeschlossen werden.

Laut Stromlaufplan (X3 am Master): 1 = Bus B, 2 = Bus A, 3 = GND, 4 = +5 V.
Die Aderfarben in Abb. 4.3 sind grün / gelb-orange / schwarz / rot – **vor dem Anschließen mit dem Messgerät prüfen**:
zwischen GND und +5 V müssen ca. 5 V anliegen.

| RS-485-Modul | Anschluss |
|---|---|
| A | Bus A |
| B | Bus B |
| GND | Bus GND **und** ESP32 GND |
| VCC | ESP32 **3V3** |
| RO | ESP32 **GPIO16** |
| DI | ESP32 **GPIO17** |
| DE + RE (gebrückt) | ESP32 **GPIO4** |

- Die **+5 V vom Bus nicht benutzen** – das Netzteil im Lüfter ist dafür nicht ausgelegt (der ESP32 zieht im WLAN bis 0,3 A).
- Hat das Modul einen 120-Ω-Abschlusswiderstand (oft R7, Aufdruck „121“) und die Telegramme sind fehlerhaft: auslöten.
- Solange nichts gesendet wird, hört die Brücke nur zu (DE = low) und beeinflusst den Bus nicht.
  Ziehst du den ESP32 ab, läuft alles wie vorher.
- Laut Herstelleranleitung sind Arbeiten an der elektrischen Installation der Lüfter einer Elektrofachkraft vorbehalten.

## 3. Firmware aufspielen

**Erstinstallation (ohne Software-Installation):**
1. ESP32 per USB an den PC, **Chrome oder Edge** öffnen: <https://espressif.github.io/esptool-js/>
2. „Connect“ → seriellen Port wählen.
3. Bei Flash Address **0x0** die Datei **`lueftung-erstinstallation.bin`** wählen → „Program“.
4. Wird der ESP32 nicht erkannt, fehlt der USB-Treiber (CP210x oder CH340). Ohne Adminrechte lässt der sich nicht installieren – dann einen privaten PC nehmen.

**Spätere Updates:** in der App → Reiter *Bus* → *Firmware-Update* → Datei **`lueftung-update.bin`** hochladen (über WLAN).

## 4. WLAN einrichten

1. Mit dem WLAN **„Lueftung-Setup“** verbinden (Passwort **`lueftung123`**). Die Einrichtungsseite öffnet sich, sonst <http://192.168.4.1>.
2. Heim-WLAN eintragen → Speichern. Die Brücke startet neu.
3. In der App *Im WLAN suchen* – oder die IP aus dem Router eintragen. Tipp: im Router eine feste IP vergeben.

Findet die Brücke das WLAN nicht (z. B. Router aus), öffnet sie wieder „Lueftung-Setup“ und versucht es alle 10 Minuten erneut.

## 5. Inbetriebnahme

1. **Reiter Bus:** Kommen Telegramme an? Wenn nicht: *Baudrate erkennen* und dabei am Bedienfeld tippen, dann *Speichern*.
2. **Format prüfen:** Gleicher Tastendruck → immer gleiches Telegramm? Wenn sich Telegramme wild unterscheiden: 8E1 probieren.
3. **„nur neue“** einschalten und am Bedienfeld tippen – so siehst du, was ein Tastendruck auslöst.
4. **Reiter Anlernen:** jeden Befehl (Aus, Stufe I–V, Feuchte, Betriebsart) einzeln anlernen. Vorher am Bedienfeld einen *anderen* Wert einstellen.
5. **Testen:** Bedienfeld auf einen anderen Wert stellen, in der App *Testen* – die Lüftung muss wechseln.
6. Erscheint beim Anlernen „identisch mit …“, sendet das Bedienfeld nur Tastendrücke (z. B. „Stufe +“). Dann im Reiter Bus *Protokoll öffnen* und den Text teilen – damit lässt sich die Firmware gezielt erweitern.

Die Beschriftungen „Modus 1–3“ (dritte Zeile am Bedienfeld) kannst du unter *Anlernen → Bearbeiten* umbenennen.

## 6. Funktionen

- **Steuerung:** Aus, Stufe I–V, Feuchte 1–3, Betriebsart 1–3, eigene Befehle
- **Stoßlüften:** 15 / 30 / 60 min auf Stufe V, danach automatisch zurück zur vorherigen Stufe
- **Zeitpläne:** laufen auf der Brücke (NTP-Zeit, Sommer-/Winterzeit), das Handy muss nicht an sein
- **Zustand:** wird erkannt, wenn ein gelernter Befehl gesendet oder am Bedienfeld gewählt wird
- **Filter:** Betriebsstundenzähler (zählt, solange nicht „Aus“) mit einstellbarem Intervall, Standard 2160 h
- **Bus-Monitor:** Live-Mitschnitt, Kennzeichnung neuer Telegramme, Testsenden, Protokoll-Export

- **Klima (ab Firmware 1.1):** Außentemperatur und -feuchte von Open-Meteo für den eingestellten Ort, optional Innensensor
  (M5Stack ENV III/IV mit SHT30/SHT40 am Grove-Anschluss, wird automatisch erkannt), Regeln wie
  „Wenn Außentemperatur unter −5 °C → Stufe I“ oder „Außenluft trockener als innen → Stufe IV“, 24-h-Verlauf

**Grenzen:** Die eigene Filtermeldung des Geräts (alle LEDs an) und die Luftfeuchte können erst gelesen werden,
wenn das Protokoll entschlüsselt ist.

## 7. Bauen (GitHub)

Repository anlegen, den kompletten Ordnerinhalt hochladen. GitHub Actions baut bei jedem Push auf `main`
die App (`Lueftung.apk`) und die Firmware und legt alles unter **Releases** ab.

- `android/` – Android-App (Kotlin, Jetpack Compose), fest signiert, damit Updates sich drüber installieren lassen
- `firmware/` – ESP32-Firmware (PlatformIO, Arduino)

## 8. REST-API der Brücke

| Methode | Pfad | Zweck |
|---|---|---|
| GET | `/api/status` | Zustand, Bus-Statistik, Filter, Stoßlüften |
| POST | `/api/cmd` `{key, minutes?}` | Befehl senden (mit `minutes` = Stoßlüften) |
| GET/POST | `/api/commands` | gelernte Befehle lesen / speichern |
| POST | `/api/learn/start` `{key, seconds}` | Lernfenster starten; `GET /api/learn` liefert Kandidaten |
| GET/POST | `/api/schedules` | Zeitpläne (Liste `{days, h, m, cmd, en}`, Tage-Bits Mo=1 … So=64) |
| GET | `/api/frames?since=n&new=1` | Telegramme seit Nummer n |
| GET | `/api/log.txt` | Mitschnitt als Text |
| POST | `/api/send` `{hex}` | Rohtelegramm senden |
| GET/POST | `/api/config` | Baudrate, Format, Pause, WLAN |
| GET | `/api/baud/detect` | Baudrate messen |
| GET | `/api/klima` | Außen-/Innenwerte, Regeln, Automatik-Zustand |
| POST | `/api/klima/config` `{lat, lon, place, autoEn, holdMin}` | Ort und Automatik |
| POST | `/api/klima/rules` `[{sensor, op, value, hyst, cmd, en}]` | Regeln (sensor: aussenT, aussenRH, innenT, innenRH, absDiff) |
| POST | `/api/klima/sensor` `{outT, outRH, inT, inRH}` | Werte von außen einspeisen, z. B. aus ioBroker |
| GET | `/api/history` | 24-h-Verlauf in 5-min-Schritten |
