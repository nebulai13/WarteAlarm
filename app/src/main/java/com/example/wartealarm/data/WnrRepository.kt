package com.example.wartealarm.data

import com.example.wartealarm.domain.RoomSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Connection lifecycle of the live socket, surfaced to the UI for status display. */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Live feed of a wartenummer.at room.
 *
 * An interface (not a concrete class) so the socket implementation, the service that consumes it, and
 * any future fake for testing all depend only on this contract. Implementations connect to the open
 * `/room` socket.io namespace and publish each parsed [RoomSnapshot].
 */
interface WnrRepository {

    /** The latest snapshot, or `null` before the first event arrives. */
    val snapshots: StateFlow<RoomSnapshot?>

    /** Current connection state. */
    val connectionState: StateFlow<ConnectionState>

    /** Connect and `join` the given room. Safe to call once per repository instance. */
    fun connect(room: String)

    /** Disconnect and release the socket. */
    fun disconnect()
}
