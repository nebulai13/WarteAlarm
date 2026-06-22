package com.example.wartealarm.socket

import com.example.wartealarm.data.ConnectionState
import com.example.wartealarm.data.WnrRepository
import com.example.wartealarm.domain.RoomSnapshot
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.net.URISyntaxException

/**
 * Live [WnrRepository] over the open wartenummer.at socket.io `/room` feed (`API-research.md` §6).
 *
 * No authentication is involved: we connect to the `/room` namespace, `emit("join", room)`, and the
 * server streams the room object, ticket list, and a server clock to anyone in the room. We translate
 * those raw events into a single, continuously-updated [RoomSnapshot] published on [snapshots].
 *
 * The server runs socket.io v2 / Engine.IO 3, so this is built against `io.socket:socket.io-client:1.0.1`
 * (the v2-compatible Android client). Because the protocol is reverse-engineered and can change without
 * notice, the layer is deliberately defensive and fails loudly via [connectionState] rather than hanging.
 *
 * Threading: the socket.io client invokes listeners on its own background thread. We never touch the
 * socket from the UI thread, and all state is published through thread-safe [MutableStateFlow]s, so no
 * extra synchronisation is needed.
 */
class WnrSocketRepository(
    private val baseUrl: String = "https://wartenummer.at",
) : WnrRepository {

    private val _snapshots = MutableStateFlow<RoomSnapshot?>(null)
    override val snapshots: StateFlow<RoomSnapshot?> = _snapshots.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** The room id to (re-)join; captured on [connect] and re-emitted on every reconnect. */
    @Volatile
    private var room: String? = null

    /**
     * The live socket. Created lazily on first [connect] so a [URISyntaxException] from a bad [baseUrl]
     * surfaces there rather than at construction time.
     */
    private var socket: Socket? = null

    // --- WnrRepository --------------------------------------------------------------------------

    /**
     * Opens the `/room` namespace and joins [room]. Safe to call once per instance.
     *
     * Listeners are registered before [Socket.connect] so we never miss the initial `connect`/`data`
     * burst. The actual `join` is emitted from the [Socket.EVENT_CONNECT] handler, so reconnects
     * automatically re-join without extra bookkeeping here.
     */
    override fun connect(room: String) {
        this.room = room
        _connectionState.value = ConnectionState.CONNECTING

        val socket = socket ?: createSocket().also { socket = it }
        registerListeners(socket)
        socket.connect()
    }

    /** Detaches our listeners and closes the socket; idempotent and safe to call when never connected. */
    override fun disconnect() {
        socket?.let { socket ->
            socket.off()
            socket.disconnect()
        }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // --- Socket setup ---------------------------------------------------------------------------

    /** Builds a socket pointed at the `/room` namespace; wraps the checked URI failure as [IllegalArgumentException]. */
    private fun createSocket(): Socket {
        val options = IO.Options().apply {
            // The wait is long (a 30–90 min appointment) across flaky waiting-room Wi-Fi, so keep retrying.
            reconnection = true
            // Prefer websocket but allow the polling handshake/fallback the server supports (EIO3).
            transports = arrayOf("polling", "websocket")
        }
        return try {
            // Namespace is part of the URI for the v2 client: "https://host/room".
            IO.socket("$baseUrl/room", options)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("Invalid wartenummer.at base URL: $baseUrl", e)
        }
    }

    /** Registers all event listeners. Re-attached on every [connect]; [Socket.off] in [disconnect] clears them. */
    private fun registerListeners(socket: Socket) {
        // Clear first so a second connect() can't double-register and deliver events twice.
        socket.off()

        socket.on(Socket.EVENT_CONNECT, onConnect)
        socket.on(Socket.EVENT_CONNECT_ERROR, onConnectError)
        socket.on(Socket.EVENT_DISCONNECT, onDisconnect)

        // Both "data" (sent on join) and "room" (pushed on change) carry the full room object — same handling.
        socket.on(EVENT_DATA, onRoom)
        socket.on(EVENT_ROOM, onRoom)
        socket.on(EVENT_TICKETS, onTickets)
        socket.on(EVENT_TIME, onTime)
    }

    // --- Lifecycle listeners --------------------------------------------------------------------

    private val onConnect = Emitter.Listener {
        // Re-emit join on every connect so a reconnect rejoins the room automatically.
        room?.let { socket?.emit(EVENT_JOIN, it) }
        _connectionState.value = ConnectionState.CONNECTED
    }

    private val onConnectError = Emitter.Listener { _connectionState.value = ConnectionState.ERROR }

    private val onDisconnect = Emitter.Listener { _connectionState.value = ConnectionState.DISCONNECTED }

    // --- Data listeners -------------------------------------------------------------------------

    /**
     * A `room`/`data` event: replace the room fields and queues but PRESERVE the existing [RoomSnapshot.tickets].
     * Keeping `tickets` untouched (and `null` until a real `tickets` event arrives) is what lets the analyzer
     * pick coarse fallback vs. precise mode (see [RoomSnapshot] docs).
     */
    private val onRoom = Emitter.Listener { args ->
        val room = args.firstObject() ?: return@Listener
        val parsed = SnapshotParser.parseRoom(room)
        _snapshots.update { current ->
            parsed.copy(
                tickets = current?.tickets,
                serverTime = current?.serverTime,
            )
        }
    }

    /** A `tickets` event: a JSON array of ticket objects. Sets [RoomSnapshot.tickets], leaving room fields intact. */
    private val onTickets = Emitter.Listener { args ->
        val tickets = args.firstArray() ?: return@Listener
        val parsed = SnapshotParser.parseTickets(tickets)
        _snapshots.update { current ->
            (current ?: RoomSnapshot(roomUrl = room.orEmpty())).copy(tickets = parsed)
        }
    }

    /** A `time` event: the server clock string, updated roughly every 2 s. Updates [RoomSnapshot.serverTime] only. */
    private val onTime = Emitter.Listener { args ->
        val time = args.firstOrNull()?.toString() ?: return@Listener
        _snapshots.update { current ->
            (current ?: RoomSnapshot(roomUrl = room.orEmpty())).copy(serverTime = time)
        }
    }

    private companion object {
        // Emitted event names (per API-research.md §6).
        const val EVENT_JOIN = "join"

        // Received event names.
        const val EVENT_DATA = "data"
        const val EVENT_ROOM = "room"
        const val EVENT_TICKETS = "tickets"
        const val EVENT_TIME = "time"
    }
}

// --- Emitter argument helpers ---------------------------------------------------------------------
// socket.io hands listeners a raw Object[]; the first element is the event payload. These guard
// against a missing or wrongly-typed payload so a malformed event can never crash the watcher.

/** The first listener argument, or null if the server sent none. */
private fun Array<Any?>.firstOrNull(): Any? = getOrNull(0)

/** The first argument as a [JSONObject], or null if it's absent or a different type. */
private fun Array<Any?>.firstObject(): JSONObject? = firstOrNull() as? JSONObject

/** The first argument as a [JSONArray], or null if it's absent or a different type. */
private fun Array<Any?>.firstArray(): JSONArray? = firstOrNull() as? JSONArray
