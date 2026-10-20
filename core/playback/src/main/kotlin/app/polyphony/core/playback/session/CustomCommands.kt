package app.polyphony.core.playback.session

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Session-level commands beyond the standard Player API. */
object CustomCommands {
    const val TOGGLE_SHUFFLE = "app.polyphony.TOGGLE_SHUFFLE"
    const val CYCLE_REPEAT = "app.polyphony.CYCLE_REPEAT"
    const val TOGGLE_FAVORITE = "app.polyphony.TOGGLE_FAVORITE"
    const val RESTORE_QUALITY = "app.polyphony.RESTORE_QUALITY"
    const val REFRESH_DECISION = "app.polyphony.REFRESH_DECISION"
    const val SET_SLEEP_TIMER = "app.polyphony.SET_SLEEP_TIMER"
    const val SET_QUEUE_TITLE = "app.polyphony.SET_QUEUE_TITLE"
    const val SET_PLAY_CONTEXT = "app.polyphony.SET_PLAY_CONTEXT"
    const val GET_AUDIO_SESSION_ID = "app.polyphony.GET_AUDIO_SESSION_ID"

    const val ARG_ENDS_AT = "endsAt"
    const val ARG_FINISH_TRACK = "finishTrack"
    const val ARG_TITLE = "title"
    /** JSON [app.polyphony.core.domain.model.PlayContext], or absent to clear it. */
    const val ARG_CONTEXT = "context"
    const val ARG_AUDIO_SESSION_ID = "audioSessionId"

    val ALL: List<SessionCommand> = listOf(
        TOGGLE_SHUFFLE, CYCLE_REPEAT, TOGGLE_FAVORITE, RESTORE_QUALITY, REFRESH_DECISION,
        SET_SLEEP_TIMER, SET_QUEUE_TITLE, SET_PLAY_CONTEXT, GET_AUDIO_SESSION_ID,
    ).map { SessionCommand(it, Bundle.EMPTY) }

    fun of(action: String): SessionCommand = SessionCommand(action, Bundle.EMPTY)
}

/** Keys of the session extras bundle the service publishes for controllers. */
object SessionExtras {
    const val QUEUE_TITLE = "app.polyphony.extras.QUEUE_TITLE"
    const val PLAY_CONTEXT = "app.polyphony.extras.PLAY_CONTEXT"
    const val SLEEP_TIMER_ENDS_AT = "app.polyphony.extras.SLEEP_TIMER_ENDS_AT"
    const val AUDIO_SESSION_ID = "app.polyphony.extras.AUDIO_SESSION_ID"
    const val LATENCY_DEGRADED = "app.polyphony.extras.LATENCY_DEGRADED"
    const val DECISION_PROFILE_ID = "app.polyphony.extras.DECISION_PROFILE_ID"
    const val DECISION_PROFILE_NAME = "app.polyphony.extras.DECISION_PROFILE_NAME"
    const val DECISION_REASON = "app.polyphony.extras.DECISION_REASON"
    const val DECISION_CAPPED = "app.polyphony.extras.DECISION_CAPPED"
}
