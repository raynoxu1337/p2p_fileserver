package com.raynoxu.p2pfileserver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { STOPPED, STARTING, RUNNING, STOPPING }

data class Status(
    val phase: Phase = Phase.STOPPED,
    val peerId: String = "",
    /** At least one relay reservation or public browser-dialable address. */
    val online: Boolean = false,
    val relayAddrs: Int = 0,
    val peers: Int = 0,
    val sessions: Int = 0,
    val backendUp: Boolean = false,
    val error: String? = null,
    val runningConfig: String = "",
)

/** Process-wide state shared by the service and the UI. */
object ServerState {
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private const val MAX_LOG = 400
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun update(f: (Status) -> Status) = _status.update(f)

    fun log(source: String, line: String) {
        val l = "${fmt.format(Date())} [$source] ${line.take(500)}"
        _log.update { (it + l).takeLast(MAX_LOG) }
    }
}
