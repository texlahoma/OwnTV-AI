package tv.own.owntv.features.shell

/**
 * A request the setup wizard leaves for the shell it hands over to: [addEpg] = open
 * Settings › EPG sources › Add as soon as the shell is up ("Add a TV guide"). Read once and cleared.
 */
object PendingShellRequest {
    var addEpg: Boolean = false
}
