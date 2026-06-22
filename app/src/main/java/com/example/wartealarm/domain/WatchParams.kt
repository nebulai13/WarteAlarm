package com.example.wartealarm.domain

/**
 * The three things the watcher needs, all derived from the email link + number (`API-research.md` §10):
 * the [room] to join on the socket, the [queue] to match within, and the user's [myNumber].
 */
data class WatchParams(
    val room: String,
    val queue: String,
    val myNumber: Int,
)
