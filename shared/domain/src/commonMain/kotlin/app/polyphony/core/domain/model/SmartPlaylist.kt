package app.polyphony.core.domain.model

import kotlinx.serialization.Serializable

/**
 * A playlist made of rules over the library on the phone (#33): the songs matching all (or any)
 * of [rules], in [sort] order, at most [limit] of them (0: all). Evaluated live against the
 * library cache of the active server, so it follows syncs, plays, favourites and ratings with
 * nothing to refresh.
 */
@Serializable
data class SmartPlaylist(
    val id: String,
    val name: String,
    val rules: List<SmartRule> = emptyList(),
    /** All rules must hold (true) or any one (false). */
    val matchAll: Boolean = true,
    val sort: TrackSort = TrackSort.RECENTLY_ADDED,
    /** The sort's natural direction (newest, most played, A to Z first), as on the Songs tab. */
    val ascending: Boolean = true,
    val limit: Int = 0,
) {
    /** The rules that say something; an unfinished one (no value yet) narrows nothing. */
    val activeRules: List<SmartRule> get() = rules.filter { it.isValid }
}

/** One condition on a song: [field] [op] [value] ("Genre contains jazz", "Added within 30 days"). */
@Serializable
data class SmartRule(val field: SmartField, val op: SmartOp, val value: String = "") {
    val isValid: Boolean
        // `this.field`: in a getter a bare `field` is the property's own backing field.
        get() = op in this.field.ops && when (this.field.kind) {
            SmartField.Kind.TEXT -> value.isNotBlank()
            SmartField.Kind.NUMBER, SmartField.Kind.DAYS -> value.trim().toIntOrNull()?.let { it >= 0 } == true
            SmartField.Kind.FLAG -> true
        }

    /** [value] as a whole number, for number and day rules. */
    val number: Int get() = value.trim().toIntOrNull() ?: 0
}

@Serializable
enum class SmartField(val kind: Kind) {
    TITLE(Kind.TEXT),
    /** The song's artist or its album's artist. */
    ARTIST(Kind.TEXT),
    ALBUM(Kind.TEXT),
    GENRE(Kind.TEXT),
    /** The file's format as the server names it ("flac", "mp3"). */
    FORMAT(Kind.TEXT),
    YEAR(Kind.NUMBER),
    /** Stars, 0 to 5. */
    RATING(Kind.NUMBER),
    PLAY_COUNT(Kind.NUMBER),
    /** Whole minutes. */
    DURATION_MINUTES(Kind.NUMBER),
    BITRATE_KBPS(Kind.NUMBER),
    /** Added to the library within (or not within) the last N days. */
    ADDED(Kind.DAYS),
    /** Played within the last N days; "not within" includes songs never played. */
    LAST_PLAYED(Kind.DAYS),
    FAVOURITE(Kind.FLAG),
    ;

    enum class Kind { TEXT, NUMBER, DAYS, FLAG }

    /** The operators that make sense for this field, the first being the usual one. */
    val ops: List<SmartOp>
        get() = when (kind) {
            Kind.TEXT -> listOf(SmartOp.CONTAINS, SmartOp.IS, SmartOp.IS_NOT, SmartOp.NOT_CONTAINS)
            Kind.NUMBER -> listOf(SmartOp.AT_LEAST, SmartOp.AT_MOST, SmartOp.IS)
            Kind.DAYS -> listOf(SmartOp.WITHIN_DAYS, SmartOp.NOT_WITHIN_DAYS)
            Kind.FLAG -> listOf(SmartOp.IS_TRUE, SmartOp.IS_FALSE)
        }
}

@Serializable
enum class SmartOp { IS, IS_NOT, CONTAINS, NOT_CONTAINS, AT_LEAST, AT_MOST, WITHIN_DAYS, NOT_WITHIN_DAYS, IS_TRUE, IS_FALSE }
