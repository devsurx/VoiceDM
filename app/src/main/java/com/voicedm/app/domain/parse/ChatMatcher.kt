package com.voicedm.app.domain.parse

/**
 * Matches a notification's chat name against the one contact the user picked.
 *
 * Instagram titles can differ from what the user typed only by casing or stray whitespace,
 * so comparison is normalized; it is deliberately *not* fuzzy, because "Jon" must never
 * match "Jonathan".
 */
object ChatMatcher {

    fun matches(target: String, actual: String): Boolean {
        val wanted = normalize(target)
        if (wanted.isEmpty()) return false
        return normalize(actual) == wanted
    }

    /**
     * The full pick: [targetThreadId], when set, pins the choice to that exact
     * conversation - a notification from any other thread never matches, even if
     * Instagram happens to show the same display name. Notifications without a
     * readable thread id fall back to the name comparison, and a blank target
     * never matches anything.
     */
    fun matches(
        target: String,
        targetThreadId: String,
        actualName: String,
        actualThreadId: String?,
    ): Boolean {
        if (target.isBlank()) return false
        if (targetThreadId.isBlank()) return matches(target, actualName)
        return when (actualThreadId) {
            targetThreadId -> true
            null -> matches(target, actualName)
            else -> false
        }
    }

    fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(ZERO_WIDTH, "")
        .replace(WHITESPACE, " ")
        .trim(':')
        .trim()

    private val WHITESPACE = Regex("\\s+")
    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\uFEFF]")
}
