package app.polyphony.ui.settings.servers

import app.polyphony.core.domain.model.ContinuoDevice

/** Pure rules for the continuo account screen (#119), kept apart so they're unit-tested. */
object ContinuoDeviceLogic {

    /** This phone first, then the others by when they were last seen, most recent first. */
    fun ordered(devices: List<ContinuoDevice>): List<ContinuoDevice> =
        devices.sortedWith(
            compareByDescending<ContinuoDevice> { it.current }
                .thenByDescending { it.lastSeenAtMillis ?: it.createdAtMillis ?: Long.MIN_VALUE },
        )

    /**
     * Whether [device] looks like an earlier install of this phone: another device with the same
     * name, signed in before this one. That's what a reinstall or a restored backup leaves behind
     * (each sign-in is a new device), so the screen points it out.
     */
    fun isEarlierInstall(device: ContinuoDevice, devices: List<ContinuoDevice>): Boolean {
        if (device.current) return false
        val current = devices.firstOrNull { it.current } ?: return false
        if (!device.name.trim().equals(current.name.trim(), ignoreCase = true)) return false
        val mine = device.createdAtMillis
        val theirs = current.createdAtMillis
        return mine == null || theirs == null || mine <= theirs
    }

    /** The list after "sign out everywhere else": only this phone is left. */
    fun afterRevokingOthers(devices: List<ContinuoDevice>): List<ContinuoDevice> = devices.filter { it.current }
}
