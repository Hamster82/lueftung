// =====================================================================
//  Protokoll des ONE-Touch-Busses (aus Mitschnitten ermittelt)
//
//  Bedienfeld -> Master, ASCII, sendet immer den kompletten Zustand:
//      "Bedienfeld:V3H40M3E0N1"
//        V = Luefterstufe 0..5 (0 = aus)
//        H = Feuchte-Sollwert (z. B. 40/45/50/...; 99 = Feuchtesteuerung aus)
//        M = Betriebsart 1 = Abluft, 2 = Zuluft, 3 = Waermerueckgewinnung
//        E, N = Bedeutung unbekannt, werden unveraendert uebernommen
//
//  Master -> Bus, etwa alle 70 s und nach jedem Bedienfeld-Telegramm:
//      "Master:" + 6 Byte (bisher immer FE) + "V66"
//        Bedeutung der 6 Byte und des V-Werts noch offen -> wird mitgeschrieben
//
//  V im Master-Telegramm = 50 + 8 * Stufe * Richtung (aus Mitschnitt: 58..90 fuer
//  Stufe I..V, 50 = aus, im WRG-Betrieb wechselnd 66/34 alle 70 s).
//
//  Die Bruecke sendet nur Bedienfeld-Telegramme, niemals Master-Telegramme.
//
//  Abgleich App <-> Bedienteil: Das Bedienteil kennt Aenderungen aus der App nicht
//  und schickt beim naechsten Tippen seinen alten Gesamtzustand. Die Bruecke vergleicht
//  deshalb mit dem vorigen Telegramm des Bedienteils, uebernimmt nur das Feld, das am
//  Bedienteil wirklich geaendert wurde, und schickt den zusammengefuehrten Zustand
//  sofort hinterher.
// =====================================================================
#pragma once

struct PanelState {
  int v = 3, h = 99, m = 3, e = 0, n = 1;
  uint32_t at = 0;      // millis des letzten Bedienfeld-Telegramms (0 = noch keins seit Start)
  bool known = false;   // Zustand aus Telegramm oder Speicher bekannt
} panel,                // wirksamer Zustand (was der Master zuletzt bekommen hat bzw. bekommt)
  panelSeen;            // was das Bedienteil selbst zuletzt gesendet hat (= seine Anzeige)

String lastSentText;
uint32_t lastSentAt = 0;
bool correctionPending = false;
uint32_t correctionAt = 0;
uint32_t mergeCount = 0;

struct MasterInfo {
  String text;          // Teil nach den 6 Byte, z. B. "V66"
  int v = -1;           // Zahl nach 'V', -1 = keine
  uint8_t slots[6] = {0};
  uint32_t at = 0;
  uint32_t prevAt = 0;
  uint32_t count = 0;
} master;

struct ProtoCfg {
  bool enabled = true;                  // Telegramme selbst erzeugen
  bool sync = true;                     // Abgleich mit dem Bedienteil
  int hLevels[5] = {40, 45, 50, 55, 60};
  int hOff = 99;
} pcfg;

struct MasterLog { uint32_t t; int v; uint8_t slots[6]; };
static const int MLOG_N = 60;
MasterLog mlog[MLOG_N];
int mlogCount = 0, mlogHead = 0;

int masterVNow() {
  // nur frische Werte (letzte 5 min)
  return (master.at && millis() - master.at < 300000UL) ? master.v : -1;
}

// ---------------------------------------------------------------- Zuordnung
String panelKey(const char *group) {
  String g(group);
  if (g == "stufe") return panel.v == 0 ? String("aus") : "stufe" + String(panel.v);
  if (g == "feuchte") {
    if (panel.h == pcfg.hOff) return "feuchte0";
    for (int i = 0; i < 5; i++)
      if (pcfg.hLevels[i] == panel.h) return "feuchte" + String(i + 1);
    return "";   // unbekannter Sollwert
  }
  if (g == "modus") return "modus" + String(panel.m);
  return "";
}

bool protoHandles(const String &key) {
  if (!pcfg.enabled) return false;
  if (key == "aus" || key == "feuchte0") return true;
  if (key.startsWith("stufe")) { int n = key.substring(5).toInt(); return n >= 1 && n <= 5; }
  if (key.startsWith("feuchte")) { int n = key.substring(7).toInt(); return n >= 1 && n <= 5; }
  if (key.startsWith("modus")) { int n = key.substring(5).toInt(); return n >= 1 && n <= 3; }
  return false;
}

String panelText(const PanelState &p) {
  char b[48];
  snprintf(b, sizeof b, "Bedienfeld:V%dH%02dM%dE%dN%d", p.v, p.h, p.m, p.e, p.n);
  return String(b);
}

void savePanel() {
  prefs.putInt("pv", panel.v);
  prefs.putInt("ph", panel.h);
  prefs.putInt("pm", panel.m);
  prefs.putInt("pe", panel.e);
  prefs.putInt("pn", panel.n);
  if (panelSeen.known) {
    prefs.putInt("sv", panelSeen.v);
    prefs.putInt("sh", panelSeen.h);
    prefs.putInt("sm", panelSeen.m);
  }
}

void loadPanel() {
  if (!prefs.isKey("pv")) return;
  panel.v = prefs.getInt("pv", 3);
  panel.h = prefs.getInt("ph", 99);
  panel.m = prefs.getInt("pm", 3);
  panel.e = prefs.getInt("pe", 0);
  panel.n = prefs.getInt("pn", 1);
  panel.known = true;
  if (prefs.isKey("sv")) {
    panelSeen = panel;
    panelSeen.v = prefs.getInt("sv", panel.v);
    panelSeen.h = prefs.getInt("sh", panel.h);
    panelSeen.m = prefs.getInt("sm", panel.m);
    panelSeen.at = 0;
  }
}

bool sameVHM(const PanelState &a, const PanelState &b) { return a.v == b.v && a.h == b.h && a.m == b.m; }

// Zustand aus dem Panel in die Gruppen uebernehmen (nur Aenderungen melden)
void panelToState(const String &src) {
  const char *groups[] = {"stufe", "feuchte", "modus"};
  for (const char *g : groups) {
    String k = panelKey(g);
    if (k.length() && state[g].key != k) setState(k, src);
  }
}

// ---------------------------------------------------------------- Empfang
// liefert true, wenn das Telegramm erkannt wurde
bool protoOnFrame(const uint8_t *d, size_t n) {
  if (n >= 11 && memcmp(d, "Bedienfeld:", 11) == 0) {
    char buf[65];
    size_t l = n < 64 ? n : 64;
    memcpy(buf, d, l);
    buf[l] = 0;
    int v, h, m, e, nn;
    if (sscanf(buf, "Bedienfeld:V%dH%dM%dE%dN%d", &v, &h, &m, &e, &nn) == 5) {
      // eigenes Echo ignorieren
      if (lastSentText == String(buf) && millis() - lastSentAt < 500) return true;
      PanelState in;
      in.v = v; in.h = h; in.m = m; in.e = e; in.n = nn;
      in.at = millis();
      in.known = true;
      PanelState merged = in;
      if (pcfg.sync && pcfg.enabled && panelSeen.known && panel.known) {
        // nur die Felder uebernehmen, die am Bedienteil geaendert wurden
        merged = panel;
        if (in.v != panelSeen.v) merged.v = in.v;
        if (in.h != panelSeen.h) merged.h = in.h;
        if (in.m != panelSeen.m) merged.m = in.m;
        merged.e = in.e;
        merged.n = in.n;
        // sendet das Bedienteil unveraendert (Wiederholung), bleibt der wirksame Zustand
      }
      panelSeen = in;
      bool changed = !panel.known || !sameVHM(merged, panel);
      panel = merged;
      panel.at = in.at;
      panel.known = true;
      (void)changed;
      savePanel();
      panelToState("Bedienfeld");
      if (!sameVHM(merged, in)) {
        correctionPending = true;
        correctionAt = millis() + 150;   // Master erst antworten lassen
        mergeCount++;
      }
      return true;
    }
    return false;
  }
  if (n >= 13 && memcmp(d, "Master:", 7) == 0) {
    master.prevAt = master.at;
    master.at = millis();
    master.count++;
    memcpy(master.slots, d + 7, 6);
    master.text = "";
    for (size_t i = 13; i < n; i++) master.text += (char)d[i];
    master.v = -1;
    if (n > 14 && d[13] == 'V') {
      int val = 0, digits = 0;
      for (size_t i = 14; i < n && isdigit(d[i]); i++, digits++) val = val * 10 + (d[i] - '0');
      if (digits) master.v = val;
    }
    MasterLog &L = mlog[mlogHead];
    L.t = timeValid() ? (uint32_t)time(nullptr) : 0;
    L.v = master.v;
    memcpy(L.slots, master.slots, 6);
    mlogHead = (mlogHead + 1) % MLOG_N;
    if (mlogCount < MLOG_N) mlogCount++;
    return true;
  }
  return false;
}

// ---------------------------------------------------------------- Senden
bool protoSend(const String &key) {
  PanelState p = panel;
  if (key == "aus") p.v = 0;
  else if (key.startsWith("stufe")) p.v = key.substring(5).toInt();
  else if (key == "feuchte0") p.h = pcfg.hOff;
  else if (key.startsWith("feuchte")) p.h = pcfg.hLevels[key.substring(7).toInt() - 1];
  else if (key.startsWith("modus")) p.m = key.substring(5).toInt();
  else return false;
  String t = panelText(p);
  std::vector<String> fr;
  fr.push_back(toHex((const uint8_t *)t.c_str(), t.length()));
  lastSentText = t;
  lastSentAt = millis();
  bool ok = sendFrames(fr);
  lastSentAt = millis();
  if (ok) {
    panel.v = p.v; panel.h = p.h; panel.m = p.m;
    panel.known = true;
    savePanel();
  }
  return ok;
}

// ---------------------------------------------------------------- Speichern / API
void loadProto() {
  JsonDocument d;
  if (readJson("/proto.json", d)) {
    pcfg.enabled = d["enabled"] | true;
    pcfg.sync = d["sync"] | true;
    pcfg.hOff = d["hOff"] | 99;
    JsonArray a = d["hLevels"].as<JsonArray>();
    if (a.size() == 5)
      for (int i = 0; i < 5; i++) pcfg.hLevels[i] = a[i] | pcfg.hLevels[i];
  }
  loadPanel();
}

void saveProto() {
  JsonDocument d;
  d["enabled"] = pcfg.enabled;
  d["sync"] = pcfg.sync;
  d["hOff"] = pcfg.hOff;
  JsonArray a = d["hLevels"].to<JsonArray>();
  for (int i = 0; i < 5; i++) a.add(pcfg.hLevels[i]);
  writeJson("/proto.json", d);
}

void protoToJson(JsonObject o) {
  o["enabled"] = pcfg.enabled;
  o["sync"] = pcfg.sync;
  o["mergeCount"] = mergeCount;
  o["hOff"] = pcfg.hOff;
  JsonArray a = o["hLevels"].to<JsonArray>();
  for (int i = 0; i < 5; i++) a.add(pcfg.hLevels[i]);
  JsonObject p = o["panel"].to<JsonObject>();
  p["known"] = panel.known;
  p["v"] = panel.v;
  p["h"] = panel.h;
  p["m"] = panel.m;
  p["text"] = panelText(panel);
  p["agoS"] = panel.at ? (int32_t)((millis() - panel.at) / 1000) : -1;
  JsonObject s = o["seen"].to<JsonObject>();
  s["known"] = panelSeen.known;
  s["v"] = panelSeen.v;
  s["h"] = panelSeen.h;
  s["m"] = panelSeen.m;
  s["text"] = panelText(panelSeen);
  o["inSync"] = !panelSeen.known || sameVHM(panelSeen, panel);
  JsonObject m = o["master"].to<JsonObject>();
  m["count"] = master.count;
  m["text"] = master.text;
  if (master.v >= 0) {
    m["v"] = master.v;
    int d = master.v - 50;
    if (d % 8 == 0 && abs(d) <= 40) {
      m["runStufe"] = abs(d) / 8;
      m["runDir"] = d > 0 ? 1 : d < 0 ? 2 : 0;
    }
  }
  m["slots"] = toHex(master.slots, 6);
  m["agoS"] = master.at ? (int32_t)((millis() - master.at) / 1000) : -1;
  m["intervalS"] = (master.at && master.prevAt) ? (int32_t)((master.at - master.prevAt) / 1000) : -1;
}

void apiProtoGet() {
  JsonDocument d;
  protoToJson(d.to<JsonObject>());
  JsonArray l = d["masterLog"].to<JsonArray>();
  for (int i = 0; i < mlogCount; i++) {
    const MasterLog &L = mlog[(mlogHead - mlogCount + i + MLOG_N) % MLOG_N];
    JsonObject x = l.add<JsonObject>();
    x["t"] = L.t;
    x["v"] = L.v;
    x["slots"] = toHex(L.slots, 6);
  }
  sendJson(d);
}

void apiProtoSet() {
  JsonDocument d;
  if (!body(d)) return sendErr(400, "JSON fehlt");
  if (d["enabled"].is<bool>()) pcfg.enabled = d["enabled"];
  if (d["sync"].is<bool>()) pcfg.sync = d["sync"];
  if (d["hOff"].is<int>()) pcfg.hOff = constrain((int)d["hOff"], 0, 99);
  if (d["hLevels"].is<JsonArray>()) {
    JsonArray a = d["hLevels"].as<JsonArray>();
    if (a.size() != 5) return sendErr(400, "5 Feuchtewerte erwartet");
    for (int i = 0; i < 5; i++) pcfg.hLevels[i] = constrain((int)(a[i] | 0), 0, 99);
  }
  saveProto();
  panelToState("Bedienfeld");
  sendOk();
}

// im loop(): Korrektur nach einem Bedienteil-Tastendruck senden
void protoLoop() {
  if (!correctionPending || (int32_t)(millis() - correctionAt) < 0) return;
  correctionPending = false;
  String t = panelText(panel);
  std::vector<String> fr;
  fr.push_back(toHex((const uint8_t *)t.c_str(), t.length()));
  lastSentText = t;
  lastSentAt = millis();
  sendFrames(fr);
  lastSentAt = millis();
}

void protoRoutes() {
  server.on("/api/proto", HTTP_GET, apiProtoGet);
  server.on("/api/proto", HTTP_POST, apiProtoSet);
}
