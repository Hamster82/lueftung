// =====================================================================
//  Lueftungs-Bruecke  –  ESP32 + RS-485 am ONE-Touch-Bus
//  Leipfinger-Bader LB-Lueftungssystem WE (erste Generation)
//
//  Funktionen:
//   * Bus mitschneiden (Telegramm-Erkennung per UART-RX-Timeout)
//   * Baudraten-Erkennung ueber kuerzeste Pulsbreite
//   * Lernmodus: Taste am Bedienfeld druecken -> neue Telegramme merken
//   * Gelernte Telegramme senden (Stufen, Feuchte, Modus, eigene)
//   * Zustandserkennung, wenn gelernte Telegramme auf dem Bus auftauchen
//   * Zeitplaene (NTP, Europe/Berlin), Stosslueften mit Rueckfall
//   * Betriebsstundenzaehler mit Filterwarnung
//   * WLAN-Einrichtung per Captive Portal, OTA-Update im Browser
//   * REST-API fuer die Android-App
//   * Klima (klima.h): Aussenwetter Open-Meteo, Innensensor SHT3x/SHT4x,
//     Regeln nach Temperatur/Feuchte, 24-h-Verlauf
// =====================================================================
#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <ESPmDNS.h>
#include <Update.h>
#include <Preferences.h>
#include <LittleFS.h>
#include <ArduinoJson.h>
#include <map>
#include <set>
#include <vector>
#include <time.h>

#define FW_VERSION "1.1.0"

// Pins je Hardware-Variante (platformio.ini setzt sie per build_flags).
// Standard: ESP32-DevKit + RS-485-Modul mit DE/RE.  ATOM + Atomic RS485 Base: RX 22, TX 19, Richtung automatisch.
#ifndef BUS_RX
#define BUS_RX 16
#endif
#ifndef BUS_TX
#define BUS_TX 17
#endif
#ifndef BUS_DE
#define BUS_DE 4      // -1 = Modul schaltet Richtung selbst
#endif
#ifndef STATUS_LED
#define STATUS_LED 2  // -1 = keine einfache LED
#endif
static const int PIN_RX = BUS_RX;
static const int PIN_TX = BUS_TX;
static const int PIN_DE = BUS_DE;
static const int PIN_LED = STATUS_LED;

static inline void deWrite(int v) { if (PIN_DE >= 0) digitalWrite(PIN_DE, v); }
static inline void ledWrite(int v) { if (PIN_LED >= 0) digitalWrite(PIN_LED, v); }

static const char *AP_SSID = "Lueftung-Setup";
static const char *AP_PASS = "lueftung123";
static const char *TZ_BERLIN = "CET-1CEST,M3.5.0,M10.5.0/3";

// ---------------------------------------------------------------- Konfiguration
struct Config {
  String ssid, pass, hostname = "lueftung";
  uint32_t baud = 9600;
  String format = "8N1";
  uint8_t gapChars = 3;          // Pause in Zeichenzeiten, die ein Telegramm beendet
  uint8_t repeat = 1;            // wie oft ein Befehl gesendet wird
  uint16_t sendGapMs = 30;       // Pause zwischen gesendeten Telegrammen
  uint32_t filterIntervalH = 2160;
} cfg;

struct Command {
  String label;
  std::vector<String> frames;    // normalisierte Hex-Strings, frames[0] = Erkennungstelegramm
};
std::map<String, Command> commands;

struct Schedule {
  uint8_t days;   // Bit0 = Mo ... Bit6 = So
  uint8_t h, m;
  String cmd;
  bool en;
};
std::vector<Schedule> schedules;

// ---------------------------------------------------------------- Bus-Daten
struct RawFrame {
  uint32_t t;
  uint16_t total;
  uint8_t len;
  uint8_t d[64];
};
QueueHandle_t frameQ;
volatile uint32_t busBytes = 0;
volatile uint32_t lastRxMs = 0;
volatile uint32_t droppedFrames = 0;

struct FrameRec {
  uint32_t seq = 0;
  uint32_t t = 0;
  String hex;
  bool isNew = false;
  String known;
  bool tx = false;
};
static const int RING = 200;
FrameRec ring[RING];
uint32_t frameSeq = 0;

struct Uniq { uint32_t count, firstSeen, lastSeen; };
std::map<String, Uniq> uniq;

// ---------------------------------------------------------------- Zustand
struct GroupState { String key, src; uint32_t at = 0; time_t epoch = 0; };
std::map<String, GroupState> state;   // "stufe", "feuchte", "modus", "sonst"

struct Learn {
  bool active = false;
  String key;
  uint32_t until = 0;
  std::set<String> baseline;
  std::vector<String> cand;
} learn;

struct Boost {
  bool active = false;
  uint32_t until = 0;
  String key, backKey;
} boost;

Preferences prefs;
uint32_t runtimeSec = 0;
uint32_t lastRuntimeSave = 0;
uint32_t lastSecTick = 0;
int lastSchedMinute = -1;

WebServer server(80);
DNSServer dns;
bool apMode = false;
bool restartPending = false;
uint32_t restartAt = 0;

// ================================================================= Helfer
String toHex(const uint8_t *d, size_t n) {
  static const char *H = "0123456789ABCDEF";
  String s;
  s.reserve(n * 3);
  for (size_t i = 0; i < n; i++) {
    if (i) s += ' ';
    s += H[d[i] >> 4];
    s += H[d[i] & 15];
  }
  return s;
}

bool fromHex(const String &in, std::vector<uint8_t> &out) {
  out.clear();
  int hi = -1;
  for (unsigned i = 0; i < in.length(); i++) {
    char c = in[i];
    int v;
    if (c >= '0' && c <= '9') v = c - '0';
    else if (c >= 'a' && c <= 'f') v = c - 'a' + 10;
    else if (c >= 'A' && c <= 'F') v = c - 'A' + 10;
    else if (c == ' ' || c == ',' || c == ':' || c == '-' || c == '\t' || c == '\n' || c == '\r') {
      if (hi >= 0) return false;
      continue;
    } else return false;
    if (hi < 0) hi = v;
    else { out.push_back((uint8_t)(hi * 16 + v)); hi = -1; }
  }
  return hi < 0 && !out.empty();
}

String normHex(const String &in) {
  std::vector<uint8_t> b;
  if (!fromHex(in, b)) return "";
  return toHex(b.data(), b.size());
}

String groupOf(const String &key) {
  if (key == "aus" || key.startsWith("stufe")) return "stufe";
  if (key.startsWith("feuchte")) return "feuchte";
  if (key.startsWith("modus")) return "modus";
  return "sonst";
}

bool timeValid() {
  time_t now = time(nullptr);
  return now > 1700000000;
}

String clockStr(time_t t) {
  if (t < 1700000000) return "";
  struct tm tmv;
  localtime_r(&t, &tmv);
  char b[8];
  snprintf(b, sizeof b, "%02d:%02d", tmv.tm_hour, tmv.tm_min);
  return String(b);
}

uint32_t serialCfg(const String &f) {
  if (f == "8E1") return SERIAL_8E1;
  if (f == "8O1") return SERIAL_8O1;
  if (f == "8N2") return SERIAL_8N2;
  return SERIAL_8N1;
}

// ================================================================= Dateien
bool readJson(const char *path, JsonDocument &doc) {
  File f = LittleFS.open(path, "r");
  if (!f) return false;
  DeserializationError e = deserializeJson(doc, f);
  f.close();
  return !e;
}

bool writeJson(const char *path, JsonDocument &doc) {
  File f = LittleFS.open(path, "w");
  if (!f) return false;
  serializeJson(doc, f);
  f.close();
  return true;
}

void loadConfig() {
  JsonDocument d;
  if (!readJson("/config.json", d)) return;
  cfg.ssid = d["ssid"] | "";
  cfg.pass = d["pass"] | "";
  cfg.hostname = d["hostname"] | "lueftung";
  cfg.baud = d["baud"] | 9600;
  cfg.format = d["format"] | "8N1";
  cfg.gapChars = d["gapChars"] | 3;
  cfg.repeat = d["repeat"] | 1;
  cfg.sendGapMs = d["sendGapMs"] | 30;
  cfg.filterIntervalH = d["filterIntervalH"] | 2160;
  if (cfg.gapChars < 1) cfg.gapChars = 1;
  if (cfg.gapChars > 90) cfg.gapChars = 90;
  if (cfg.repeat < 1) cfg.repeat = 1;
  if (cfg.repeat > 5) cfg.repeat = 5;
}

void saveConfig() {
  JsonDocument d;
  d["ssid"] = cfg.ssid;
  d["pass"] = cfg.pass;
  d["hostname"] = cfg.hostname;
  d["baud"] = cfg.baud;
  d["format"] = cfg.format;
  d["gapChars"] = cfg.gapChars;
  d["repeat"] = cfg.repeat;
  d["sendGapMs"] = cfg.sendGapMs;
  d["filterIntervalH"] = cfg.filterIntervalH;
  writeJson("/config.json", d);
}

void addDefaultCommands() {
  const char *keys[] = {"aus", "stufe1", "stufe2", "stufe3", "stufe4", "stufe5",
                        "feuchte1", "feuchte2", "feuchte3", "feuchte4", "feuchte5",
                        "modus1", "modus2", "modus3"};
  const char *labels[] = {"Aus", "Stufe I", "Stufe II", "Stufe III", "Stufe IV", "Stufe V",
                          "Feuchte 1", "Feuchte 2", "Feuchte 3", "Feuchte 4", "Feuchte 5",
                          "Abluft", "Zuluft", "Wärmerückgewinnung"};
  for (int i = 0; i < 14; i++) {
    // alte Standardnamen "Modus N" durch die Betriebsarten ersetzen
    auto it = commands.find(keys[i]);
    if (it != commands.end() && String(keys[i]).startsWith("modus") && it->second.label == String("Modus ") + String(keys[i]).substring(5))
      it->second.label = labels[i];
    if (!commands.count(keys[i])) commands[keys[i]] = Command{labels[i], {}};
  }
}

void loadCommands() {
  commands.clear();
  JsonDocument d;
  if (readJson("/commands.json", d)) {
    for (JsonPair p : d.as<JsonObject>()) {
      Command c;
      c.label = p.value()["label"] | p.key().c_str();
      for (JsonVariant v : p.value()["frames"].as<JsonArray>()) {
        String h = normHex(v.as<String>());
        if (h.length()) c.frames.push_back(h);
      }
      commands[String(p.key().c_str())] = c;
    }
  }
  addDefaultCommands();
}

void saveCommands() {
  JsonDocument d;
  for (auto &kv : commands) {
    JsonObject o = d[kv.first].to<JsonObject>();
    o["label"] = kv.second.label;
    JsonArray a = o["frames"].to<JsonArray>();
    for (auto &f : kv.second.frames) a.add(f);
  }
  writeJson("/commands.json", d);
}

void schedulesToJson(JsonArray a) {
  for (auto &s : schedules) {
    JsonObject o = a.add<JsonObject>();
    o["days"] = s.days;
    o["h"] = s.h;
    o["m"] = s.m;
    o["cmd"] = s.cmd;
    o["en"] = s.en;
  }
}

void schedulesFromJson(JsonArray a) {
  schedules.clear();
  for (JsonObject o : a) {
    Schedule s;
    s.days = o["days"] | 127;
    s.h = o["h"] | 0;
    s.m = o["m"] | 0;
    s.cmd = o["cmd"] | "";
    s.en = o["en"] | true;
    if (s.h < 24 && s.m < 60 && s.cmd.length()) schedules.push_back(s);
    if (schedules.size() >= 40) break;
  }
}

void loadSchedules() {
  JsonDocument d;
  if (readJson("/schedules.json", d)) schedulesFromJson(d.as<JsonArray>());
}

void saveSchedules() {
  JsonDocument d;
  JsonArray a = d.to<JsonArray>();
  schedulesToJson(a);
  writeJson("/schedules.json", d);
}

// ================================================================= Bus
void onUartRx() {
  // laeuft im UART-Event-Task: wird aufgerufen, wenn der Bus gapChars Zeichen lang ruhig war
  RawFrame f;
  f.t = millis();
  f.len = 0;
  f.total = 0;
  while (Serial2.available()) {
    int c = Serial2.read();
    if (c < 0) break;
    if (f.len < sizeof f.d) f.d[f.len++] = (uint8_t)c;
    f.total++;
  }
  if (!f.total) return;
  busBytes += f.total;
  lastRxMs = f.t;
  if (xQueueSend(frameQ, &f, 0) != pdTRUE) droppedFrames++;
}

void startBus() {
  Serial2.setRxBufferSize(2048);
  Serial2.begin(cfg.baud, serialCfg(cfg.format), PIN_RX, PIN_TX);
  Serial2.setRxTimeout(cfg.gapChars);
  Serial2.onReceive(onUartRx, true);
}

void stopBus() {
  Serial2.onReceive(nullptr);
  Serial2.end();
}

uint32_t detectBaud(uint32_t &minPulse, uint32_t &samples) {
  stopBus();
  pinMode(PIN_RX, INPUT_PULLUP);
  minPulse = 0xFFFFFFFF;
  samples = 0;
  uint32_t t0 = millis();
  while (millis() - t0 < 4000 && samples < 3000) {
    unsigned long p = pulseIn(PIN_RX, LOW, 50000);
    if (p > 0) {
      samples++;
      if (p < minPulse) minPulse = p;
    }
    yield();
  }
  startBus();
  if (samples < 5 || minPulse == 0xFFFFFFFF) return 0;
  const uint32_t rates[] = {1200, 2400, 4800, 9600, 14400, 19200, 28800, 38400, 57600, 76800, 115200, 230400, 250000};
  float measured = 1000000.0f / (float)minPulse;
  uint32_t best = rates[0];
  float bestErr = 1e9;
  for (uint32_t r : rates) {
    float err = fabsf(measured - r) / r;
    if (err < bestErr) { bestErr = err; best = r; }
  }
  return best;
}

void pushRing(const String &hex, uint32_t t, bool isNew, const String &known, bool tx) {
  frameSeq++;
  FrameRec &r = ring[frameSeq % RING];
  r.seq = frameSeq;
  r.t = t;
  r.hex = hex;
  r.isNew = isNew;
  r.known = known;
  r.tx = tx;
}

String matchCommand(const String &hex) {
  for (auto &kv : commands)
    if (!kv.second.frames.empty() && kv.second.frames[0] == hex) return kv.first;
  return "";
}

void klimaOnState(const String &key, const String &src);   // klima.h

void setState(const String &key, const String &src) {
  klimaOnState(key, src);
  String g = groupOf(key);
  GroupState &s = state[g];
  s.key = key;
  s.src = src;
  s.at = millis();
  s.epoch = time(nullptr);
  if (g == "stufe" && boost.active && src != "Stosslueften") boost.active = false;
}

void processFrame(const RawFrame &f) {
  String hex = toHex(f.d, f.len);
  if (f.total > f.len) hex += " ...(" + String(f.total) + " Byte)";
  bool isNew = false;
  auto it = uniq.find(hex);
  if (it == uniq.end()) {
    if (uniq.size() >= 300) {
      auto oldest = uniq.begin();
      for (auto u = uniq.begin(); u != uniq.end(); ++u)
        if (u->second.lastSeen < oldest->second.lastSeen) oldest = u;
      uniq.erase(oldest);
    }
    uniq[hex] = Uniq{1, f.t, f.t};
    isNew = true;
  } else {
    it->second.count++;
    it->second.lastSeen = f.t;
  }
  String known = matchCommand(hex);
  if (known.length()) {
    GroupState &gs = state[groupOf(known)];
    // nur Zustandswechsel werten (eigenes Echo / zyklische Wiederholung ignorieren)
    if (gs.key != known) setState(known, "Bedienfeld");
  }
  if (learn.active && !learn.baseline.count(hex) && learn.cand.size() < 12) {
    bool dup = false;
    for (auto &c : learn.cand) if (c == hex) dup = true;
    if (!dup) learn.cand.push_back(hex);
  }
  pushRing(hex, f.t, isNew, known, false);
}

bool sendFrames(const std::vector<String> &frames) {
  if (frames.empty()) return false;
  for (uint8_t r = 0; r < cfg.repeat; r++) {
    for (auto &h : frames) {
      std::vector<uint8_t> b;
      if (!fromHex(h, b)) continue;
      // auf Busruhe warten (max. 300 ms)
      uint32_t w0 = millis();
      while (millis() - lastRxMs < 15 && millis() - w0 < 300) delay(1);
      deWrite(HIGH);
      delayMicroseconds(80);
      Serial2.write(b.data(), b.size());
      Serial2.flush();
      delayMicroseconds(2000000UL / cfg.baud + 20);  // Stoppbit sicher raus
      deWrite(LOW);
      pushRing(toHex(b.data(), b.size()), millis(), false, matchCommand(toHex(b.data(), b.size())), true);
      delay(cfg.sendGapMs);
    }
  }
  return true;
}

bool executeCommand(const String &key, const String &src) {
  auto it = commands.find(key);
  if (it == commands.end() || it->second.frames.empty()) return false;
  setState(key, src);
  ledWrite(HIGH);
  bool ok = sendFrames(it->second.frames);
  ledWrite(LOW);
  return ok;
}

// ================================================================= HTTP-Helfer
void cors() { server.sendHeader("Access-Control-Allow-Origin", "*"); }

void sendJson(JsonDocument &d, int code = 200) {
  String s;
  serializeJson(d, s);
  cors();
  server.send(code, "application/json", s);
}

void sendErr(int code, const String &msg) {
  JsonDocument d;
  d["ok"] = false;
  d["error"] = msg;
  sendJson(d, code);
}

void sendOk() {
  JsonDocument d;
  d["ok"] = true;
  sendJson(d);
}

bool body(JsonDocument &d) {
  if (!server.hasArg("plain")) return false;
  return !deserializeJson(d, server.arg("plain"));
}

void stateToJson(JsonObject o, const char *g) {
  auto it = state.find(g);
  if (it == state.end() || !it->second.key.length()) { o["key"] = (const char *)nullptr; return; }
  o["key"] = it->second.key;
  auto c = commands.find(it->second.key);
  o["label"] = c != commands.end() ? c->second.label : it->second.key;
  o["src"] = it->second.src;
  o["agoS"] = (millis() - it->second.at) / 1000;
  o["time"] = clockStr(it->second.epoch);
}

// ================================================================= API
void apiStatus() {
  JsonDocument d;
  d["fw"] = FW_VERSION;
  d["uptimeS"] = millis() / 1000;
  d["ap"] = apMode;
  d["ip"] = apMode ? WiFi.softAPIP().toString() : WiFi.localIP().toString();
  d["rssi"] = apMode ? 0 : WiFi.RSSI();
  d["hostname"] = cfg.hostname;
  d["timeValid"] = timeValid();
  d["time"] = clockStr(time(nullptr));
  JsonObject bus = d["bus"].to<JsonObject>();
  bus["baud"] = cfg.baud;
  bus["format"] = cfg.format;
  bus["bytes"] = busBytes;
  bus["frames"] = frameSeq;
  bus["unique"] = uniq.size();
  bus["dropped"] = droppedFrames;
  bus["lastRxAgoMs"] = lastRxMs ? (int32_t)(millis() - lastRxMs) : (int32_t)-1;
  JsonObject st = d["state"].to<JsonObject>();
  stateToJson(st["stufe"].to<JsonObject>(), "stufe");
  stateToJson(st["feuchte"].to<JsonObject>(), "feuchte");
  stateToJson(st["modus"].to<JsonObject>(), "modus");
  JsonObject fl = d["filter"].to<JsonObject>();
  fl["hours"] = runtimeSec / 3600.0;
  fl["intervalH"] = cfg.filterIntervalH;
  fl["due"] = runtimeSec / 3600 >= cfg.filterIntervalH;
  JsonObject b = d["boost"].to<JsonObject>();
  b["active"] = boost.active;
  b["remainingS"] = boost.active ? (int32_t)(boost.until - millis()) / 1000 : 0;
  b["key"] = boost.key;
  b["backKey"] = boost.backKey;
  JsonObject l = d["learn"].to<JsonObject>();
  l["active"] = learn.active;
  l["key"] = learn.key;
  l["remainingS"] = learn.active ? (int32_t)(learn.until - millis()) / 1000 : 0;
  sendJson(d);
}

void apiFrames() {
  uint32_t since = server.hasArg("since") ? server.arg("since").toInt() : 0;
  bool onlyNew = server.hasArg("new") && server.arg("new") == "1";
  JsonDocument d;
  d["seq"] = frameSeq;
  JsonArray a = d["frames"].to<JsonArray>();
  uint32_t start = frameSeq > RING ? frameSeq - RING + 1 : 1;
  if (since + 1 > start) start = since + 1;
  int n = 0;
  for (uint32_t s = start; s <= frameSeq && n < 120; s++) {
    FrameRec &r = ring[s % RING];
    if (r.seq != s) continue;
    if (onlyNew && !r.isNew && !r.tx) continue;
    JsonObject o = a.add<JsonObject>();
    o["seq"] = r.seq;
    o["t"] = r.t;
    o["hex"] = r.hex;
    o["new"] = r.isNew;
    o["tx"] = r.tx;
    if (r.known.length()) o["known"] = r.known;
    n++;
  }
  sendJson(d);
}

void apiUnique() {
  JsonDocument d;
  JsonArray a = d.to<JsonArray>();
  uint32_t now = millis();
  for (auto &kv : uniq) {
    JsonObject o = a.add<JsonObject>();
    o["hex"] = kv.first;
    o["count"] = kv.second.count;
    o["lastAgoS"] = (now - kv.second.lastSeen) / 1000;
    String k = matchCommand(kv.first);
    if (k.length()) o["known"] = k;
  }
  sendJson(d);
}

void apiUniqueClear() {
  uniq.clear();
  sendOk();
}

void apiLogTxt() {
  cors();
  server.setContentLength(CONTENT_LENGTH_UNKNOWN);
  server.send(200, "text/plain; charset=utf-8", "");
  String h = "Lueftungs-Bruecke " FW_VERSION "  Baud " + String(cfg.baud) + " " + cfg.format +
             "  Pause " + String(cfg.gapChars) + " Zeichen\n\n# Letzte Telegramme (seq; ms; R/T; hex; befehl)\n";
  server.sendContent(h);
  uint32_t start = frameSeq > RING ? frameSeq - RING + 1 : 1;
  for (uint32_t s = start; s <= frameSeq; s++) {
    FrameRec &r = ring[s % RING];
    if (r.seq != s) continue;
    String line = String(r.seq) + "; " + String(r.t) + "; " + (r.tx ? "T" : "R") + "; " + r.hex;
    if (r.known.length()) line += "; " + r.known;
    if (r.isNew) line += "; NEU";
    server.sendContent(line + "\n");
  }
  server.sendContent("\n# Verschiedene Telegramme (anzahl; vor s; hex)\n");
  uint32_t now = millis();
  for (auto &kv : uniq)
    server.sendContent(String(kv.second.count) + "; " + String((now - kv.second.lastSeen) / 1000) + "; " + kv.first + "\n");
  server.sendContent("\n# Gelernte Befehle\n");
  for (auto &kv : commands) {
    String line = kv.first + " (" + kv.second.label + "):";
    for (auto &f : kv.second.frames) line += " [" + f + "]";
    server.sendContent(line + "\n");
  }
  server.sendContent("");
}

void apiCmd() {
  JsonDocument d;
  if (!body(d)) return sendErr(400, "JSON fehlt");
  String key = d["key"] | "";
  int minutes = d["minutes"] | 0;
  if (!commands.count(key)) return sendErr(404, "Unbekannter Befehl");
  if (commands[key].frames.empty()) return sendErr(409, "Befehl noch nicht angelernt");
  String prev = state["stufe"].key;
  if (minutes > 0 && groupOf(key) == "stufe") {
    executeCommand(key, "Stosslueften");
    boost.active = true;
    boost.until = millis() + (uint32_t)minutes * 60000UL;
    boost.key = key;
    boost.backKey = (prev.length() && prev != key) ? prev : "";
  } else {
    executeCommand(key, "App");
  }
  sendOk();
}

void apiBoostCancel() {
  if (boost.active && boost.backKey.length()) executeCommand(boost.backKey, "App");
  boost.active = false;
  sendOk();
}

void apiSend() {
  JsonDocument d;
  if (!body(d)) return sendErr(400, "JSON fehlt");
  std::vector<String> frames;
  for (JsonVariant v : d["frames"].as<JsonArray>()) {
    String h = normHex(v.as<String>());
    if (!h.length()) return sendErr(400, "Ungueltiger Hex-Wert");
    frames.push_back(h);
  }
  if (frames.empty()) {
    String h = normHex(d["hex"] | "");
    if (!h.length()) return sendErr(400, "Ungueltiger Hex-Wert");
    frames.push_back(h);
  }
  sendFrames(frames);
  sendOk();
}

void apiCommandsGet() {
  JsonDocument d;
  for (auto &kv : commands) {
    JsonObject o = d[kv.first].to<JsonObject>();
    o["label"] = kv.second.label;
    o["group"] = groupOf(kv.first);
    JsonArray a = o["frames"].to<JsonArray>();
    for (auto &f : kv.second.frames) a.add(f);
  }
  sendJson(d);
}

void apiCommandsSet() {
  JsonDocument d;
  if (!body(d)) return sendErr(400, "JSON fehlt");
  String key = d["key"] | "";
  key.trim();
  key.toLowerCase();
  if (!key.length() || key.length() > 24) return sendErr(400, "Schluessel fehlt");
  Command &c = commands[key];
  if (d["label"].is<const char *>()) c.label = d["label"].as<String>();
  if (!c.label.length()) c.label = key;
  if (d["frames"].is<JsonArray>()) {
    c.frames.clear();
    for (JsonVariant v : d["frames"].as<JsonArray>()) {
      String h = normHex(v.as<String>());
      if (h.length()) c.frames.push_back(h);
      if (c.frames.size() >= 8) break;
    }
  }
  saveCommands();
  sendOk();
}

void apiCommandsDelete() {
  JsonDocument d;
  if (!body(d)) return sendErr(400, "JSON fehlt");
  String key = d["key"] | "";
  commands.erase(key);
  addDefaultCommands();   // Standardplaetze bleiben erhalten (leer)
  saveCommands();
  sendOk();
}

void apiLearnStart() {
  JsonDocument d;
  if (!body(d)) return sendErr(400, "JSON fehlt");
  int secs = d["seconds"] | 10;
  if (secs < 3) secs = 3;
  if (secs > 60) secs = 60;
  learn.key = d["key"] | "";
  learn.cand.clear();
  learn.baseline.clear();
  uint32_t now = millis();
  for (auto &kv : uniq)
    if (now - kv.second.lastSeen < 30000) learn.baseline.insert(kv.first);
  learn.until = now + secs * 1000UL;
  learn.active = true;
  sendOk();
}

void apiLearnGet() {
  JsonDocument d;
  d["active"] = learn.active;
  d["key"] = learn.key;
  d["remainingS"] = learn.active ? (int32_t)(learn.until - millis()) / 1000 : 0;
  d["baseline"] = learn.baseline.size();
  JsonArray a = d["candidates"].to<JsonArray>();
  for (auto &c : learn.cand) {
    JsonObject o = a.add<JsonObject>();
    o["hex"] = c;
    auto u = uniq.find(c);
    o["count"] = u != uniq.end() ? u->second.count : 0;
    String other = matchCommand(c);
    if (other.length() && other != learn.key) o["duplicateOf"] = other;
  }
  sendJson(d);
}

void apiLearnStop() {
  learn.active = false;
  sendOk();
}

void apiSchedulesGet() {
  JsonDocument d;
  schedulesToJson(d.to<JsonArray>());
  sendJson(d);
}

void apiSchedulesSet() {
  JsonDocument d;
  if (!body(d) || !d.is<JsonArray>()) return sendErr(400, "Liste erwartet");
  schedulesFromJson(d.as<JsonArray>());
  saveSchedules();
  sendOk();
}

void apiConfigGet() {
  JsonDocument d;
  d["ssid"] = cfg.ssid;
  d["hostname"] = cfg.hostname;
  d["baud"] = cfg.baud;
  d["format"] = cfg.format;
  d["gapChars"] = cfg.gapChars;
  d["repeat"] = cfg.repeat;
  d["sendGapMs"] = cfg.sendGapMs;
  d["filterIntervalH"] = cfg.filterIntervalH;
  sendJson(d);
}

void apiConfigSet() {
  JsonDocument d;
  if (!body(d)) return sendErr(400, "JSON fehlt");
  bool busChanged = false, wifiChanged = false;
  if (d["baud"].is<uint32_t>()) { cfg.baud = d["baud"]; busChanged = true; }
  if (d["format"].is<const char *>()) { cfg.format = d["format"].as<String>(); busChanged = true; }
  if (d["gapChars"].is<int>()) { cfg.gapChars = constrain((int)d["gapChars"], 1, 90); busChanged = true; }
  if (d["repeat"].is<int>()) cfg.repeat = constrain((int)d["repeat"], 1, 5);
  if (d["sendGapMs"].is<int>()) cfg.sendGapMs = constrain((int)d["sendGapMs"], 0, 1000);
  if (d["filterIntervalH"].is<uint32_t>()) cfg.filterIntervalH = d["filterIntervalH"];
  if (d["hostname"].is<const char *>()) { cfg.hostname = d["hostname"].as<String>(); wifiChanged = true; }
  if (d["ssid"].is<const char *>()) { cfg.ssid = d["ssid"].as<String>(); wifiChanged = true; }
  if (d["pass"].is<const char *>()) { cfg.pass = d["pass"].as<String>(); wifiChanged = true; }
  if (cfg.baud < 300 || cfg.baud > 1000000) cfg.baud = 9600;
  saveConfig();
  if (busChanged) { stopBus(); startBus(); uniq.clear(); }
  if (wifiChanged) { restartPending = true; restartAt = millis() + 1500; }
  JsonDocument r;
  r["ok"] = true;
  r["restart"] = wifiChanged;
  sendJson(r);
}

void apiBaudDetect() {
  uint32_t minPulse, samples;
  uint32_t baud = detectBaud(minPulse, samples);
  JsonDocument d;
  d["baud"] = baud;
  d["minPulseUs"] = samples ? minPulse : 0;
  d["samples"] = samples;
  if (!baud) d["hint"] = "Keine Flanken gemessen - Verdrahtung A/B pruefen und am Bedienfeld tippen";
  sendJson(d);
}

void apiFilterReset() {
  runtimeSec = 0;
  prefs.putULong("runtime", 0);
  sendOk();
}

void apiRestart() {
  sendOk();
  restartPending = true;
  restartAt = millis() + 500;
}

// ================================================================= Klima
#include "klima.h"

// ================================================================= Webseiten
const char PAGE_ROOT[] PROGMEM = R"HTML(<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Lueftung</title>
<style>body{font-family:sans-serif;margin:16px;max-width:520px}input,select,button{font-size:16px;padding:8px;margin:4px 0;width:100%;box-sizing:border-box}
button{background:#2e7d32;color:#fff;border:0;border-radius:6px}.c{border:1px solid #ccc;border-radius:8px;padding:12px;margin:12px 0}a{display:block;margin:6px 0}</style></head>
<body><h2>Lueftungs-Bruecke</h2><div class="c" id="st">Lade Status ...</div>
<div class="c"><b>WLAN einrichten</b><form id="wf"><input name="ssid" placeholder="WLAN-Name (SSID)"><input name="pass" type="password" placeholder="Passwort">
<input name="hostname" placeholder="Geraetename" value="lueftung"><button>Speichern &amp; neu starten</button></form></div>
<div class="c"><a href="/api/log.txt">Bus-Protokoll als Text</a><a href="/update">Firmware-Update</a></div>
<script>
fetch('/api/status').then(r=>r.json()).then(s=>{document.getElementById('st').innerHTML=
'Firmware '+s.fw+'<br>IP '+s.ip+(s.ap?' (Einrichtungsmodus)':'')+'<br>Bus: '+s.bus.baud+' '+s.bus.format+', '+s.bus.frames+' Telegramme<br>Stufe: '+(s.state.stufe.label||'unbekannt')});
document.getElementById('wf').onsubmit=e=>{e.preventDefault();const f=new FormData(e.target);
fetch('/api/config',{method:'POST',body:JSON.stringify({ssid:f.get('ssid'),pass:f.get('pass'),hostname:f.get('hostname')||'lueftung'})})
.then(()=>{document.body.innerHTML='<h3>Gespeichert. Das Geraet startet neu und verbindet sich mit dem WLAN.</h3>'})};
</script></body></html>)HTML";

const char PAGE_UPDATE[] PROGMEM = R"HTML(<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Update</title></head>
<body style="font-family:sans-serif;margin:16px"><h2>Firmware-Update</h2>
<p>Datei <b>lueftung-update.bin</b> (ATOM: <b>lueftung-atom-update.bin</b>) waehlen, nicht die Erstinstallations-Datei.</p>
<form method="POST" action="/update" enctype="multipart/form-data"><input type="file" name="fw" accept=".bin"><br><br>
<button>Hochladen</button></form></body></html>)HTML";

void setupRoutes() {
  server.on("/", HTTP_GET, [] { server.send_P(200, "text/html", PAGE_ROOT); });
  server.on("/api/status", HTTP_GET, apiStatus);
  server.on("/api/frames", HTTP_GET, apiFrames);
  server.on("/api/unique", HTTP_GET, apiUnique);
  server.on("/api/unique/clear", HTTP_POST, apiUniqueClear);
  server.on("/api/log.txt", HTTP_GET, apiLogTxt);
  server.on("/api/cmd", HTTP_POST, apiCmd);
  server.on("/api/boost/cancel", HTTP_POST, apiBoostCancel);
  server.on("/api/send", HTTP_POST, apiSend);
  server.on("/api/commands", HTTP_GET, apiCommandsGet);
  server.on("/api/commands", HTTP_POST, apiCommandsSet);
  server.on("/api/commands/delete", HTTP_POST, apiCommandsDelete);
  server.on("/api/learn", HTTP_GET, apiLearnGet);
  server.on("/api/learn/start", HTTP_POST, apiLearnStart);
  server.on("/api/learn/stop", HTTP_POST, apiLearnStop);
  server.on("/api/schedules", HTTP_GET, apiSchedulesGet);
  server.on("/api/schedules", HTTP_POST, apiSchedulesSet);
  server.on("/api/config", HTTP_GET, apiConfigGet);
  server.on("/api/config", HTTP_POST, apiConfigSet);
  server.on("/api/baud/detect", HTTP_GET, apiBaudDetect);
  server.on("/api/filter/reset", HTTP_POST, apiFilterReset);
  server.on("/api/restart", HTTP_POST, apiRestart);
  klimaRoutes();

  server.on("/update", HTTP_GET, [] { server.send_P(200, "text/html", PAGE_UPDATE); });
  server.on(
      "/update", HTTP_POST,
      [] {
        bool ok = !Update.hasError();
        server.send(200, "text/html", ok ? "<h3>Update OK - Neustart ...</h3>" : "<h3>Update fehlgeschlagen</h3>");
        if (ok) { restartPending = true; restartAt = millis() + 1000; }
      },
      [] {
        HTTPUpload &u = server.upload();
        if (u.status == UPLOAD_FILE_START) {
          stopBus();
          Update.begin(UPDATE_SIZE_UNKNOWN);
        } else if (u.status == UPLOAD_FILE_WRITE) {
          Update.write(u.buf, u.currentSize);
        } else if (u.status == UPLOAD_FILE_END) {
          Update.end(true);
        }
      });

  server.onNotFound([] {
    if (apMode) {
      server.sendHeader("Location", "http://192.168.4.1/");
      server.send(302, "text/plain", "");
    } else {
      sendErr(404, "Nicht gefunden");
    }
  });
}

// ================================================================= WLAN
void startWifi() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setHostname(cfg.hostname.c_str());
  if (cfg.ssid.length()) {
    WiFi.begin(cfg.ssid.c_str(), cfg.pass.c_str());
    uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 20000) {
      delay(250);
      if (PIN_LED >= 0) digitalWrite(PIN_LED, !digitalRead(PIN_LED));
    }
  }
  ledWrite(LOW);
  if (WiFi.status() != WL_CONNECTED) {
    apMode = true;
    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASS);
    dns.start(53, "*", WiFi.softAPIP());
  } else {
    WiFi.setAutoReconnect(true);
    WiFi.setSleep(false);
    if (MDNS.begin(cfg.hostname.c_str())) {
      MDNS.addService("http", "tcp", 80);
      MDNS.addService("lueftung", "tcp", 80);
    }
    configTzTime(TZ_BERLIN, "pool.ntp.org", "time.google.com");
  }
}

// ================================================================= Ablauf
void checkSchedules() {
  if (!timeValid()) return;
  time_t now = time(nullptr);
  struct tm t;
  localtime_r(&now, &t);
  int minuteOfDay = t.tm_hour * 60 + t.tm_min;
  if (minuteOfDay == lastSchedMinute) return;
  lastSchedMinute = minuteOfDay;
  uint8_t dayBit = 1 << ((t.tm_wday + 6) % 7);
  for (auto &s : schedules) {
    if (s.en && (s.days & dayBit) && s.h == t.tm_hour && s.m == t.tm_min) {
      if (groupOf(s.cmd) == "stufe") boost.active = false;
      executeCommand(s.cmd, "Zeitplan");
    }
  }
}

void setup() {
  if (PIN_DE >= 0) pinMode(PIN_DE, OUTPUT);
  deWrite(LOW);
  if (PIN_LED >= 0) pinMode(PIN_LED, OUTPUT);
  Serial.begin(115200);
  Serial.println("\nLueftungs-Bruecke " FW_VERSION);

  LittleFS.begin(true);
  prefs.begin("lue", false);
  runtimeSec = prefs.getULong("runtime", 0);

  loadConfig();
  loadCommands();
  loadSchedules();
  klimaSetup();

  frameQ = xQueueCreate(48, sizeof(RawFrame));
  startBus();
  startWifi();
  setupRoutes();
  server.begin();
  lastSecTick = millis();
  Serial.printf("Bereit: %s\n", apMode ? WiFi.softAPIP().toString().c_str() : WiFi.localIP().toString().c_str());
}

void loop() {
  if (apMode) dns.processNextRequest();
  server.handleClient();

  RawFrame f;
  int n = 0;
  while (n < 16 && xQueueReceive(frameQ, &f, 0) == pdTRUE) {
    processFrame(f);
    n++;
  }

  uint32_t now = millis();
  if (learn.active && (int32_t)(now - learn.until) >= 0) learn.active = false;

  if (boost.active && (int32_t)(now - boost.until) >= 0) {
    boost.active = false;
    if (boost.backKey.length()) executeCommand(boost.backKey, "Stosslueften Ende");
  }

  if (now - lastSecTick >= 1000) {
    uint32_t secs = (now - lastSecTick) / 1000;
    lastSecTick += secs * 1000;
    if (state["stufe"].key != "aus") runtimeSec += secs;
    if (now - lastRuntimeSave > 600000UL) {
      prefs.putULong("runtime", runtimeSec);
      lastRuntimeSave = now;
    }
    checkSchedules();
    // im Einrichtungsmodus mit hinterlegtem WLAN alle 10 min neu versuchen (z.B. Router war aus)
    if (apMode && cfg.ssid.length() && now > 600000UL && WiFi.softAPgetStationNum() == 0) ESP.restart();
  }

  klimaLoop();

  if (restartPending && (int32_t)(now - restartAt) >= 0) {
    prefs.putULong("runtime", runtimeSec);
    klimaBeforeRestart();
    ESP.restart();
  }
  delay(1);
}
