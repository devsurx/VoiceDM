package com.voicedm.app.service

/** Signals the engine reacts to while a turn is in progress. */
enum class EngineCommand {
    /** Stop speaking / stop listening and move on. */
    SKIP,

    /** Say the current message again. */
    REPEAT,

    /** Send the pending transcript. */
    SEND,

    /** Throw the pending transcript away. */
    DISCARD,

    /** Start a reply manually (headset button, notification action, UI). */
    LISTEN,

    /** Audio focus came back after a call or another app. */
    AUDIO_GAINED,

    /** Audio focus was taken away. */
    AUDIO_LOST,
}
