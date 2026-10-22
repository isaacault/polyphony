package app.polyphony.core.domain.repository

import app.polyphony.core.domain.model.Feedback
import kotlinx.coroutines.flow.StateFlow

/** The [Feedback] system in effect: the Library setting, resolved against what the active server can store. */
interface FeedbackRepository {
    val feedback: StateFlow<Feedback>
}
