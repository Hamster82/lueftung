// =====================================================================
//  Klima-Modul: Aussenwetter (Open-Meteo), Innensensor (SHT3x/SHT4x),
//  Regeln mit Hysterese und Rueckfall, 24-h-Verlauf.
//  Wird von main.cpp eingebunden (nach den HTTP-Helfern).
// =====================================================================
#pragma once
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <Wire.h>
#include <math.h>

#ifndef SENS_SDA
#define SENS_SDA 21
#endif
#ifndef SENS_SCL
#define SENS_SCL 22
#endif

// ---------------------------------------------------------------- Daten
struct Reading {
  bool valid = false;
  float t = NAN, rh = NAN;
  uint32_t at = 0;     // millis der Messung
  String src;          // "Open-Meteo", "SHT40", "SHT30", "extern"
};
Reading outdoor, indoor;

struct KlimaCfg {
  float lat = NAN, lon = NAN;
  String place;
  bool autoEn = true;
  uint16_t holdMin = 10;    // Mindestzeit zwischen zwei automatischen Wechseln
} kcfg;

struct Rule {
  String sensor;   // aussenT, aussenRH, innenT, innenRH, absDiff
  String op;       // ">" oder "<"
  float value = 0;
  float hyst = 1;
  String cmd;
  bool en = true;
  bool active = false;   // Laufzeit
};
std::vector<Rule> rules;

static const char *KGROUPS[] = {"stufe", "feuchte", "modus"};
std::map<String, String> baseKey;        // Gruppe -> letzte Einstellung, die nicht von der Automatik kam
std::map<String, int> activeRule;        // Gruppe -> Index der wirkenden Regel, -1 = keine
std::map<String, bool> suppressed;       // Gruppe -> von Hand uebersteuert, bis die Regel endet
std::map<String, uint32_t> lastAutoSwitch;

int shtType = 0;                // 0 = kein Sensor, 3 = SHT3x, 4 = SHT4x
uint32_t lastIndoorRead = 0;
uint32_t lastWeatherTry = 0;
bool weatherDue = true;
String weatherErr;
uint32_t lastRuleEval = 0;

int masterVNow();   // proto.h: aktueller V-Wert des Masters oder -1

// ---------------------------------------------------------------- Verlauf
struct HistPoint {
  uint32_t epoch;
  int16_t oT, oRH, iT, iRH;    // x10, INT16_MIN = fehlt
  int8_t stufe, feuchte, modus; // -1 = unbekannt
  int16_t mv;                   // V-Wert aus dem Master-Telegramm, INT16_MIN = keiner
};
static const int HIST_N = 288;   // 24 h in 5-min-Schritten
static const uint32_t HIST_STEP = 300;
HistPoint hist[HIST_N];
int histCount = 0, histHead = 0;  // histHead = naechster Schreibplatz
uint32_t lastHistSlot = 0;
uint32_t lastHistSave = 0;

static inline int16_t enc10(bool ok, float v) {
  if (!ok || isnan(v)) return INT16_MIN;
  return (int16_t)lroundf(v * 10.0f);
}

int8_t keyIndex(const String &key) {
  if (!key.length()) return -1;
  if (key == "aus" || key == "feuchte0") return 0;
  int n = -1;
  if (key.startsWith("stufe")) n = key.substring(5).toInt();
  else if (key.startsWith("feuchte")) n = key.substring(7).toInt();
  else if (key.startsWith("modus")) n = key.substring(5).toInt();
  return n > 0 ? n : -1;
}

void histSave() {
  File f = LittleFS.open("/hist2.bin", "w");
  if (!f) return;
  for (int i = 0; i < histCount; i++) {
    int idx = (histHead - histCount + i + HIST_N) % HIST_N;
    f.write((const uint8_t *)&hist[idx], sizeof(HistPoint));
  }
  f.close();
}

void histLoad() {
  File f = LittleFS.open("/hist2.bin", "r");
  if (!f) return;
  histCount = 0;
  histHead = 0;
  HistPoint p;
  while (f.read((uint8_t *)&p, sizeof p) == sizeof p) {
    hist[histHead] = p;
    histHead = (histHead + 1) % HIST_N;
    if (histCount < HIST_N) histCount++;
  }
  f.close();
}

bool readingFresh(const Reading &r, uint32_t maxAgeMs) {
  return r.valid && (millis() - r.at) < maxAgeMs;
}

void histTick() {
  if (!timeValid()) return;
  uint32_t now = (uint32_t)time(nullptr);
  uint32_t slot = now / HIST_STEP;
  if (slot == lastHistSlot) return;
  lastHistSlot = slot;
  bool oOk = readingFresh(outdoor, 3600000UL);
  bool iOk = readingFresh(indoor, 900000UL);
  HistPoint p;
  p.epoch = slot * HIST_STEP;
  p.oT = enc10(oOk, outdoor.t);
  p.oRH = enc10(oOk, outdoor.rh);
  p.iT = enc10(iOk, indoor.t);
  p.iRH = enc10(iOk, indoor.rh);
  p.stufe = keyIndex(state["stufe"].key);
  p.feuchte = keyIndex(state["feuchte"].key);
  p.modus = keyIndex(state["modus"].key);
  p.mv = (masterVNow() >= 0) ? (int16_t)masterVNow() : INT16_MIN;
  hist[histHead] = p;
  histHead = (histHead + 1) % HIST_N;
  if (histCount < HIST_N) histCount++;
  if (millis() - lastHistSave > 1800000UL || lastHistSave == 0) {
    histSave();
    lastHistSave = millis();
  }
}

// ---------------------------------------------------------------- Physik
// absolute Feuchte in g/m3 (Magnus-Formel)
float absHum(float t, float rh) {
  if (isnan(t) || isnan(rh)) return NAN;
  float es = 6.112f * expf(17.67f * t / (t + 243.5f));
  return es * rh * 2.1674f / (273.15f + t);
}

// ---------------------------------------------------------------- Innensensor
uint8_t crc8(const uint8_t *d, int n) {
  uint8_t c = 0xFF;
  for (int i = 0; i < n; i++) {
    c ^= d[i];
    for (int b = 0; b < 8; b++) c = (c & 0x80) ? (uint8_t)((c << 1) ^ 0x31) : (uint8_t)(c << 1);
  }
  return c;
}

bool shtRead(float &t, float &rh) {
  Wire.beginTransmission((uint8_t)0x44);
  if (shtType == 4) {
    Wire.write((uint8_t)0xFD);
  } else {
    Wire.write((uint8_t)0x24);
    Wire.write((uint8_t)0x00);
  }
  if (Wire.endTransmission() != 0) return false;
  delay(shtType == 4 ? 10 : 16);
  if (Wire.requestFrom((uint8_t)0x44, (uint8_t)6) != 6) return false;
  uint8_t b[6];
  for (int i = 0; i < 6; i++) b[i] = (uint8_t)Wire.read();
  if (crc8(b, 2) != b[2] || crc8(b + 3, 2) != b[5]) return false;
  uint16_t rt = ((uint16_t)b[0] << 8) | b[1];
  uint16_t rr = ((uint16_t)b[3] << 8) | b[4];
  t = -45.0f + 175.0f * rt / 65535.0f;
  rh = shtType == 4 ? -6.0f + 125.0f * rr / 65535.0f : 100.0f * rr / 65535.0f;
  if (rh < 0) rh = 0;
  if (rh > 100) rh = 100;
  return true;
}

void shtDetect() {
  Wire.begin(SENS_SDA, SENS_SCL, 100000UL);
  float t, rh;
  shtType = 4;
  if (shtRead(t, rh)) return;
  shtType = 3;
  if (shtRead(t, rh)) return;
  shtType = 0;
}

void indoorTick() {
  if (!shtType) return;
  if (lastIndoorRead && millis() - lastIndoorRead < 30000UL) return;
  lastIndoorRead = millis();
  // externer Wert hat Vorrang, solange er frisch ist
  if (indoor.src == "extern" && readingFresh(indoor, 900000UL)) return;
  float t, rh;
  if (shtRead(t, rh)) {
    indoor.valid = true;
    indoor.t = t;
    indoor.rh = rh;
    indoor.at = millis();
    indoor.src = shtType == 4 ? "SHT40" : "SHT30";
  }
}

// ---------------------------------------------------------------- Aussenwetter
bool fetchWeather() {
  if (apMode || WiFi.status() != WL_CONNECTED) { weatherErr = "kein WLAN"; return false; }
  if (isnan(kcfg.lat) || isnan(kcfg.lon)) { weatherErr = "kein Ort eingestellt"; return false; }
  WiFiClientSecure cli;
  cli.setInsecure();   // reine Wetterdaten, kein Zertifikatsabgleich noetig
  HTTPClient http;
  http.setTimeout(8000);
  http.setConnectTimeout(6000);
  String url = "https://api.open-meteo.com/v1/forecast?latitude=" + String(kcfg.lat, 4) +
               "&longitude=" + String(kcfg.lon, 4) + "&current=temperature_2m,relative_humidity_2m&timezone=auto";
  if (!http.begin(cli, url)) { weatherErr = "Verbindung fehlgeschlagen"; return false; }
  int code = http.GET();
  if (code != 200) {
    weatherErr = "Open-Meteo antwortet " + String(code);
    http.end();
    return false;
  }
  JsonDocument d;
  DeserializationError e = deserializeJson(d, http.getString());
  http.end();
  if (e) { weatherErr = "Antwort nicht lesbar"; return false; }
  JsonVariant c = d["current"];
  if (!c["temperature_2m"].is<float>() || !c["relative_humidity_2m"].is<float>()) { weatherErr = "Werte fehlen"; return false; }
  // externer Aussenwert hat Vorrang, solange er frisch ist
  if (outdoor.src == "extern" && readingFresh(outdoor, 3600000UL)) { weatherErr = ""; return true; }
  outdoor.valid = true;
  outdoor.t = c["temperature_2m"].as<float>();
  outdoor.rh = c["relative_humidity_2m"].as<float>();
  outdoor.at = millis();
  outdoor.src = "Open-Meteo";
  weatherErr = "";
  return true;
}

void weatherTick() {
  uint32_t now = millis();
  uint32_t interval = weatherErr.length() ? 300000UL : 900000UL;   // bei Fehler alle 5 min
  if (!weatherDue && now - lastWeatherTry < interval) return;
  if (now < 15000UL) return;   // WLAN erst stabil werden lassen
  weatherDue = false;
  lastWeatherTry = now;
  fetchWeather();
}

// ---------------------------------------------------------------- Regeln
bool sensorValue(const String &s, float &v) {
  bool oOk = readingFresh(outdoor, 3600000UL);
  bool iOk = readingFresh(indoor, 900000UL);
  if (s == "aussenT") { v = outdoor.t; return oOk; }
  if (s == "aussenRH") { v = outdoor.rh; return oOk; }
  if (s == "innenT") { v = indoor.t; return iOk; }
  if (s == "innenRH") { v = indoor.rh; return iOk; }
  if (s == "absDiff") {   // innen minus aussen in g/m3: positiv = Aussenluft trockener
    if (!oOk || !iOk) return false;
    v = absHum(indoor.t, indoor.rh) - absHum(outdoor.t, outdoor.rh);
    return !isnan(v);
  }
  return false;
}

// wird von setState() aufgerufen
void klimaOnState(const String &key, const String &src) {
  if (src.startsWith("Automatik")) return;
  String g = groupOf(key);
  if (g == "sonst") return;
  baseKey[g] = key;
  auto it = activeRule.find(g);
  if (it != activeRule.end() && it->second >= 0) suppressed[g] = true;   // Hand schlaegt Regel
}

void evalRules() {
  // Aktivitaet jeder Regel mit Hysterese bestimmen
  for (auto &r : rules) {
    float v;
    if (!r.en || !sensorValue(r.sensor, v)) { r.active = false; continue; }
    float h = fabsf(r.hyst);
    if (r.op == ">") r.active = r.active ? (v > r.value - h) : (v > r.value);
    else r.active = r.active ? (v < r.value + h) : (v < r.value);
  }
  for (const char *gc : KGROUPS) {
    String g(gc);
    int want = -1;
    if (kcfg.autoEn) {
      for (size_t i = 0; i < rules.size(); i++)
        if (rules[i].active && groupOf(rules[i].cmd) == g) { want = (int)i; break; }
    }
    int prev = activeRule.count(g) ? activeRule[g] : -1;
    if (want < 0) {
      activeRule[g] = -1;
      bool wasSuppressed = suppressed[g];
      suppressed[g] = false;
      if (prev >= 0 && !wasSuppressed) {
        String back = baseKey[g];
        if (back.length() && back != state[g].key) executeCommand(back, "Automatik Ende");
      }
      continue;
    }
    if (want != prev) suppressed[g] = false;   // neue Regel greift wieder
    activeRule[g] = want;
    if (suppressed[g]) continue;
    if (g == "stufe" && boost.active) continue;
    const String &target = rules[want].cmd;
    if (state[g].key == target) continue;
    uint32_t last = lastAutoSwitch[g];
    if (last && millis() - last < (uint32_t)kcfg.holdMin * 60000UL) continue;
    if (executeCommand(target, "Automatik")) lastAutoSwitch[g] = millis();
  }
}

// ---------------------------------------------------------------- Speichern
void loadKlima() {
  JsonDocument d;
  if (!readJson("/klima.json", d)) return;
  kcfg.lat = d["lat"].is<float>() ? d["lat"].as<float>() : NAN;
  kcfg.lon = d["lon"].is<float>() ? d["lon"].as<float>() : NAN;
  kcfg.place = d["place"] | "";
  kcfg.autoEn = d["autoEn"] | true;
  kcfg.holdMin = d["holdMin"] | 10;
  rules.clear();
  for (JsonObject o : d["rules"].as<JsonArray>()) {
    Rule r;
    r.sensor = o["sensor"] | "";
    r.op = o["op"] | ">";
    r.value = o["value"] | 0.0f;
    r.hyst = o["hyst"] | 1.0f;
    r.cmd = o["cmd"] | "";
    r.en = o["en"] | true;
    if (r.sensor.length() && r.cmd.length()) rules.push_back(r);
    if (rules.size() >= 20) break;
  }
}

void saveKlima() {
  JsonDocument d;
  if (!isnan(kcfg.lat)) d["lat"] = kcfg.lat;
  if (!isnan(kcfg.lon)) d["lon"] = kcfg.lon;
  d["place"] = kcfg.place;
  d["autoEn"] = kcfg.autoEn;
  d["holdMin"] = kcfg.holdMin;
  JsonArray a = d["rules"].to<JsonArray>();
  for (auto &r : rules) {
    JsonObject o = a.add<JsonObject>();
    o["sensor"] = r.sensor;
    o["op"] = r.op;
    o["value"] = r.value;
    o["hyst"] = r.hyst;
    o["cmd"] = r.cmd;
    o["en"] = r.en;
  }
  writeJson("/klima.json", d);
}

// ---------------------------------------------------------------- API
void readingToJson(JsonObject o, const Reading &r, uint32_t maxAge) {
  bool ok = readingFresh(r, maxAge);
  o["valid"] = ok;
  if (ok) {
    o["t"] = roundf(r.t * 10) / 10.0f;
    o["rh"] = roundf(r.rh * 10) / 10.0f;
    o["ah"] = roundf(absHum(r.t, r.rh) * 10) / 10.0f;
  }
  o["src"] = r.src;
  o["agoS"] = r.valid ? (millis() - r.at) / 1000 : -1;
}

void apiKlimaGet() {
  JsonDocument d;
  readingToJson(d["outdoor"].to<JsonObject>(), outdoor, 3600000UL);
  readingToJson(d["indoor"].to<JsonObject>(), indoor, 900000UL);
  d["sensor"] = shtType == 4 ? "SHT40" : shtType == 3 ? "SHT30" : "";
  d["weatherErr"] = weatherErr;
  JsonObject c = d["cfg"].to<JsonObject>();
  if (!isnan(kcfg.lat)) c["lat"] = kcfg.lat;
  if (!isnan(kcfg.lon)) c["lon"] = kcfg.lon;
  c["place"] = kcfg.place;
  c["autoEn"] = kcfg.autoEn;
  c["holdMin"] = kcfg.holdMin;
  JsonArray a = d["rules"].to<JsonArray>();
  for (auto &r : rules) {
    JsonObject o = a.add<JsonObject>();
    o["sensor"] = r.sensor;
    o["op"] = r.op;
    o["value"] = r.value;
    o["hyst"] = r.hyst;
    o["cmd"] = r.cmd;
    o["en"] = r.en;
    o["active"] = r.active;
  }
  JsonObject g = d["groups"].to<JsonObject>();
  for (const char *gc : KGROUPS) {
    String k(gc);
    JsonObject o = g[k].to<JsonObject>();
    o["rule"] = activeRule.count(k) ? activeRule[k] : -1;
    o["suppressed"] = suppressed[k];
    o["base"] = baseKey[k];
  }
  sendJson(d);
}

void apiKlimaConfig() {
  JsonDocument d;
  if (!body(d)) return sendErr(400, "JSON fehlt");
  if (d["lat"].is<float>() && d["lon"].is<float>()) {
    float la = d["lat"], lo = d["lon"];
    if (la < -90 || la > 90 || lo < -180 || lo > 180) return sendErr(400, "Koordinaten ungueltig");
    kcfg.lat = la;
    kcfg.lon = lo;
    weatherDue = true;
  }
  if (d["place"].is<const char *>()) kcfg.place = d["place"].as<String>();
  if (d["autoEn"].is<bool>()) kcfg.autoEn = d["autoEn"];
  if (d["holdMin"].is<int>()) kcfg.holdMin = constrain((int)d["holdMin"], 0, 240);
  saveKlima();
  sendOk();
}

void apiKlimaRules() {
  JsonDocument d;
  if (!body(d) || !d.is<JsonArray>()) return sendErr(400, "Liste erwartet");
  std::vector<Rule> nr;
  for (JsonObject o : d.as<JsonArray>()) {
    Rule r;
    r.sensor = o["sensor"] | "";
    r.op = o["op"] | ">";
    r.value = o["value"] | 0.0f;
    r.hyst = o["hyst"] | 1.0f;
    r.cmd = o["cmd"] | "";
    r.en = o["en"] | true;
    if (r.op != ">" && r.op != "<") return sendErr(400, "Vergleich muss > oder < sein");
    if (!r.sensor.length() || !r.cmd.length()) return sendErr(400, "Sensor und Befehl noetig");
    // Laufzeitzustand uebernehmen, damit Hysterese nicht springt
    for (auto &old : rules)
      if (old.sensor == r.sensor && old.op == r.op && old.value == r.value && old.cmd == r.cmd) r.active = old.active;
    nr.push_back(r);
    if (nr.size() >= 20) break;
  }
  rules = nr;
  activeRule.clear();
  saveKlima();
  lastRuleEval = 0;
  sendOk();
}

// externe Werte, z. B. aus ioBroker: {"outT":..,"outRH":..,"inT":..,"inRH":..}
void apiKlimaSensor() {
  JsonDocument d;
  if (!body(d)) return sendErr(400, "JSON fehlt");
  if (d["outT"].is<float>() && d["outRH"].is<float>()) {
    outdoor.valid = true;
    outdoor.t = d["outT"];
    outdoor.rh = d["outRH"];
    outdoor.at = millis();
    outdoor.src = "extern";
  }
  if (d["inT"].is<float>() && d["inRH"].is<float>()) {
    indoor.valid = true;
    indoor.t = d["inT"];
    indoor.rh = d["inRH"];
    indoor.at = millis();
    indoor.src = "extern";
  }
  sendOk();
}

void apiKlimaRefresh() {
  bool ok = fetchWeather();
  lastWeatherTry = millis();
  JsonDocument d;
  d["ok"] = ok;
  d["error"] = weatherErr;
  sendJson(d);
}

void apiHistory() {
  cors();
  server.setContentLength(CONTENT_LENGTH_UNKNOWN);
  server.send(200, "application/json", "");
  server.sendContent("{\"step\":" + String(HIST_STEP) + ",\"now\":" + String((uint32_t)time(nullptr)) + ",\"points\":[");
  uint32_t now = (uint32_t)time(nullptr);
  bool first = true;
  String chunk;
  for (int i = 0; i < histCount; i++) {
    int idx = (histHead - histCount + i + HIST_N) % HIST_N;
    const HistPoint &p = hist[idx];
    if (timeValid() && now - p.epoch > 86400UL + HIST_STEP) continue;
    auto num = [](int16_t v) { return v == INT16_MIN ? String("null") : String(v / 10.0f, 1); };
    auto idv = [](int8_t v) { return v < 0 ? String("null") : String(v); };
    if (!first) chunk += ',';
    first = false;
    chunk += "[" + String(p.epoch) + "," + num(p.oT) + "," + num(p.oRH) + "," + num(p.iT) + "," + num(p.iRH) + "," +
             idv(p.stufe) + "," + idv(p.feuchte) + "," + idv(p.modus) + "," +
             (p.mv == INT16_MIN ? String("null") : String(p.mv)) + "]";
    if (chunk.length() > 1200) {
      server.sendContent(chunk);
      chunk = "";
    }
  }
  chunk += "]}";
  server.sendContent(chunk);
  server.sendContent("");
}

void klimaRoutes() {
  server.on("/api/klima", HTTP_GET, apiKlimaGet);
  server.on("/api/klima/config", HTTP_POST, apiKlimaConfig);
  server.on("/api/klima/rules", HTTP_POST, apiKlimaRules);
  server.on("/api/klima/sensor", HTTP_POST, apiKlimaSensor);
  server.on("/api/klima/refresh", HTTP_POST, apiKlimaRefresh);
  server.on("/api/history", HTTP_GET, apiHistory);
}

void klimaSetup() {
  loadKlima();
  histLoad();
  shtDetect();
}

void klimaLoop() {
  indoorTick();
  weatherTick();
  if (millis() - lastRuleEval >= 60000UL || lastRuleEval == 0) {
    if (millis() > 20000UL) {
      lastRuleEval = millis();
      evalRules();
    }
  }
  histTick();
}

void klimaBeforeRestart() { histSave(); }
