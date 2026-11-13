package tv.own.owntv.core.download

/**
 * When to open one more connection: start on one, and add another only while each one added has
 * made the download clearly faster. Once one does not, the line (or the provider) is full and
 * adding more only costs the provider's patience.
 */
internal class ConnectionRamp(private val max: Int = MAX_CONNECTIONS) {
    private var lastSpeed: Long? = null
    private var stopped = false

    /** True when it is worth adding a connection, given the speed over the last interval. */
    fun shouldAdd(connections: Int, cap: Int, bytesPerSecond: Long?): Boolean {
        if (stopped || connections >= minOf(cap, max) || bytesPerSecond == null) return false
        val previous = lastSpeed
        lastSpeed = bytesPerSecond
        if (previous != null && bytesPerSecond < previous + previous / GAIN_DIVISOR) {
            stopped = true
            return false
        }
        return true
    }

    /** The provider refused a connection: never ask for more on this attempt. */
    fun stop() {
        stopped = true
    }

    companion object {
        const val MAX_CONNECTIONS = 8

        /** A new connection has to add at least a tenth to the speed to earn the next one. */
        private const val GAIN_DIVISOR = 10
    }
}
