package com.voicedm.app.domain.model

/**
 * What kind of thing arrived in the chat. Instagram encodes media (photos, voice notes,
 * reels, story mentions) either as a picture extra or as a short placeholder sentence, so
 * we classify the notification body and announce media instead of reading the placeholder.
 */
enum class MessageKind {
    /** Regular text from the other person. */
    TEXT,

    /** Instagram voice note. */
    VOICE_NOTE,

    /** Photo or image. */
    PHOTO,

    /** Plain video. */
    VIDEO,

    /** Instagram reel. */
    REEL,

    /** "mentioned you in their story". */
    STORY_MENTION,

    /** Something we could not classify; announced generically or read as-is. */
    UNKNOWN,
}
