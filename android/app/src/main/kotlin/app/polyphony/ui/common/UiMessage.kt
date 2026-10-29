package app.polyphony.ui.common

import androidx.annotation.StringRes

/** A one-off message for the shell's snackbar: a string resource and an optional argument. */
data class UiMessage(@StringRes val text: Int, val arg: String? = null)
