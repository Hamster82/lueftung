package de.hamster82.lueftung

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FoundDevice(val name: String, val address: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("lueftung", Context.MODE_PRIVATE)

    private val _host = MutableStateFlow(prefs.getString("host", "") ?: "")
    val host = _host.asStateFlow()

    val api = Api { _host.value }

    private val _status = MutableStateFlow<Status?>(null)
    val status = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _commands = MutableStateFlow<List<Cmd>>(emptyList())
    val commands = _commands.asStateFlow()

    private val _schedules = MutableStateFlow<List<Schedule>>(emptyList())
    val schedules = _schedules.asStateFlow()

    private val _frames = MutableStateFlow<List<Frame>>(emptyList())
    val frames = _frames.asStateFlow()
    private var frameSeq = 0L

    private val _learn = MutableStateFlow<LearnState?>(null)
    val learn = _learn.asStateFlow()

    private val _config = MutableStateFlow<DeviceConfig?>(null)
    val config = _config.asStateFlow()

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found = _found.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    val monitorActive = MutableStateFlow(false)
    val monitorOnlyNew = MutableStateFlow(false)
    val monitorPaused = MutableStateFlow(false)

    private var learnPolling = false

    fun consumeMessage() { _message.value = null }

    private fun say(msg: String) { _message.value = msg }

    /** Führt einen API-Aufruf aus und meldet Fehler als Snackbar. */
    private fun act(okMsg: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                if (okMsg != null) say(okMsg)
            } catch (e: ApiException) {
                say(e.message ?: "Fehler")
            }
        }
    }

    // ------------------------------------------------------------ Verbindung
    fun setHost(h: String) {
        val v = h.trim().removePrefix("http://").trimEnd('/')
        _host.value = v
        prefs.edit().putString("host", v).apply()
        _status.value = null
        _frames.value = emptyList()
        frameSeq = 0
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            refreshStatus()
            if (_status.value != null) {
                runCatching { _commands.value = api.commands() }
                runCatching { _schedules.value = api.schedules() }
                runCatching { _config.value = api.config() }
            }
        }
    }

    private suspend fun refreshStatus() {
        if (_host.value.isEmpty()) {
            _error.value = "Noch kein Gerät eingestellt"
            return
        }
        try {
            val wasOffline = _status.value == null
            _status.value = api.status()
            _error.value = null
            if (wasOffline && _commands.value.isEmpty()) {
                runCatching { _commands.value = api.commands() }
                runCatching { _schedules.value = api.schedules() }
                runCatching { _config.value = api.config() }
            }
        } catch (e: ApiException) {
            _status.value = null
            _error.value = e.message
        }
    }

    /** Läuft, solange die App sichtbar ist (repeatOnLifecycle in MainActivity). */
    suspend fun pollLoop() {
        while (true) {
            refreshStatus()
            if (_status.value != null && monitorActive.value && !monitorPaused.value) pollFrames()
            if (_status.value != null && klimaActive.value) pollKlima()
            val fast = (monitorActive.value && !monitorPaused.value) || learnPolling
            delay(if (fast) 1000 else 3000)
        }
    }

    private suspend fun pollFrames() {
        try {
            val (seq, list) = api.frames(frameSeq, monitorOnlyNew.value)
            if (seq < frameSeq) { // Gerät neu gestartet
                frameSeq = 0
                _frames.value = emptyList()
                return
            }
            frameSeq = seq
            if (list.isNotEmpty()) _frames.value = (_frames.value + list).takeLast(300)
        } catch (_: ApiException) {
        }
    }

    fun clearFrames() { _frames.value = emptyList() }

    // ------------------------------------------------------------ Gerätesuche (mDNS)
    fun discover() {
        if (_searching.value) return
        val ctx = getApplication<Application>()
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("lueftung").apply { setReferenceCounted(false); acquire() }
        _found.value = emptyList()
        _searching.value = true

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { _searching.value = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val addr = si.host?.hostAddress ?: return
                        val dev = FoundDevice(si.serviceName, addr)
                        if (_found.value.none { it.address == dev.address }) _found.value = _found.value + dev
                    }
                })
            }
        }
        try {
            nsd.discoverServices("_lueftung._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            _searching.value = false
            lock.release()
            return
        }
        viewModelScope.launch {
            delay(8000)
            runCatching { nsd.stopServiceDiscovery(listener) }
            lock.release()
            _searching.value = false
            val f = _found.value
            if (f.size == 1 && _host.value.isEmpty()) {
                setHost(f.first().address)
                say("Gerät gefunden: ${f.first().address}")
            } else if (f.isEmpty()) {
                say("Kein Gerät gefunden – Adresse von Hand eintragen")
            }
        }
    }

    // ------------------------------------------------------------ Steuerung
    fun sendCmd(key: String, minutes: Int = 0) = act {
        api.sendCmd(key, minutes)
        refreshStatus()
    }

    fun cancelBoost() = act {
        api.cancelBoost()
        refreshStatus()
    }

    fun resetFilter() = act("Filterzähler zurückgesetzt") {
        api.resetFilter()
        refreshStatus()
    }

    // ------------------------------------------------------------ Zeitpläne
    fun saveSchedules(list: List<Schedule>) = act("Zeitpläne gespeichert") {
        api.saveSchedules(list)
        _schedules.value = api.schedules()
    }

    // ------------------------------------------------------------ Anlernen
    fun reloadCommands() = act { _commands.value = api.commands() }

    fun startLearn(key: String, seconds: Int = 10) {
        viewModelScope.launch {
            try {
                api.learnStart(key, seconds)
                learnPolling = true
                _learn.value = LearnState(true, key, seconds, emptyList())
                while (true) {
                    delay(1000)
                    val l = api.learn()
                    _learn.value = l
                    if (!l.active) break
                }
            } catch (e: ApiException) {
                say(e.message ?: "Fehler")
                _learn.value = null
            } finally {
                learnPolling = false
            }
        }
    }

    fun stopLearn() = act { api.learnStop() }

    fun closeLearn() { _learn.value = null }

    fun saveCommand(key: String, label: String, frames: List<String>?) = act("Gespeichert") {
        api.saveCommand(key, label, frames)
        _commands.value = api.commands()
        _learn.value = null
    }

    fun deleteCommand(key: String) = act("Gelöscht") {
        api.deleteCommand(key)
        _commands.value = api.commands()
    }

    fun testFrames(frames: List<String>) = act("Gesendet") { api.sendFrames(frames) }

    // ------------------------------------------------------------ Bus / Einstellungen
    fun clearUnique() = act("Liste bekannter Telegramme geleert") { api.clearUnique() }

    fun saveConfig(c: DeviceConfig) = act("Einstellungen gespeichert") {
        api.saveConfig(c)
        _config.value = api.config()
        frameSeq = 0
        _frames.value = emptyList()
    }

    private val _baud = MutableStateFlow<BaudResult?>(null)
    val baud = _baud.asStateFlow()
    private val _baudRunning = MutableStateFlow(false)
    val baudRunning = _baudRunning.asStateFlow()

    fun detectBaud() {
        viewModelScope.launch {
            _baudRunning.value = true
            try {
                _baud.value = api.detectBaud()
            } catch (e: ApiException) {
                say(e.message ?: "Fehler")
            } finally {
                _baudRunning.value = false
            }
        }
    }

    fun restartDevice() = act("Gerät startet neu") { api.restart() }

    fun baseUrlOrNull(): String? = runCatching { api.baseUrl() }.getOrNull()

    // ------------------------------------------------------------ Klima
    val klimaActive = MutableStateFlow(false)
    private val _klima = MutableStateFlow<Klima?>(null)
    val klima = _klima.asStateFlow()
    private val _history = MutableStateFlow<History?>(null)
    val history = _history.asStateFlow()
    private val _places = MutableStateFlow<List<Place>>(emptyList())
    val places = _places.asStateFlow()
    private val _placeSearching = MutableStateFlow(false)
    val placeSearching = _placeSearching.asStateFlow()
    private var lastKlimaFetch = 0L
    private var lastHistoryFetch = 0L

    private suspend fun pollKlima(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastKlimaFetch > 10_000) {
            lastKlimaFetch = now
            runCatching { _klima.value = api.klima() }
        }
        if (force || now - lastHistoryFetch > 60_000) {
            lastHistoryFetch = now
            runCatching { _history.value = api.history() }
        }
    }

    fun refreshKlima() {
        viewModelScope.launch { pollKlima(force = true) }
    }

    fun saveRules(list: List<Rule>) = act("Regeln gespeichert") {
        api.saveRules(list)
        _klima.value = api.klima()
    }

    fun setAuto(on: Boolean) = act(if (on) "Automatik ein" else "Automatik aus") {
        api.saveKlimaConfig(null, on, null)
        _klima.value = api.klima()
    }

    fun setHold(min: Int) = act("Mindestzeit gespeichert") {
        api.saveKlimaConfig(null, null, min)
        _klima.value = api.klima()
    }

    fun searchPlace(q: String) {
        if (q.isBlank()) return
        viewModelScope.launch {
            _placeSearching.value = true
            try {
                _places.value = searchPlaces(q)
                if (_places.value.isEmpty()) say("Kein Ort gefunden")
            } catch (e: ApiException) {
                say(e.message ?: "Fehler")
            } finally {
                _placeSearching.value = false
            }
        }
    }

    fun choosePlace(p: Place) {
        _places.value = emptyList()
        viewModelScope.launch {
            try {
                api.saveKlimaConfig(p, null, null)
                val err = api.refreshWeather()
                say(if (err.isEmpty()) "Ort ${p.name} gespeichert, Wetter geladen" else "Ort gespeichert – $err")
                pollKlima(force = true)
            } catch (e: ApiException) {
                say(e.message ?: "Fehler")
            }
        }
    }

    fun refreshWeather() = act {
        val err = api.refreshWeather()
        say(if (err.isEmpty()) "Wetter aktualisiert" else err)
        pollKlima(force = true)
    }

    init {
        if (_host.value.isEmpty()) discover() else refreshAll()
    }
}
