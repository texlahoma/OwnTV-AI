package tv.own.owntv.player

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong

/**
 * Live network throughput (bits/sec): bytes since [bitsPerSecond] was last read, divided by time since
 * then. Assumes a single regular poller (the debug overlay) — a second reader would steal bytes meant
 * for the first. Starts disabled — call [setEnabled] to activate.
 */
class ThroughputTracker : TransferListener {
    private var pendingBytes = 0L
    private var lastReadMs = 0L
    @Volatile private var enabled = false
    @Volatile private var everTransferred = false

    /** True once any byte has been transferred while enabled — distinguishes "never measured" from
     *  "measured, currently 0". */
    val hasMeasured: Boolean
        get() = everTransferred

    val bitsPerSecond: Long
        get() = readAndReset()

    /** Every byte counted since [reset], for averages that must not be steered by when they are read
     *  ([FileBitrate]); [bitsPerSecond] reads and clears its own count. */
    @Volatile var totalBytes = 0L
        private set

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

    /** When bytes last arrived (elapsedRealtime), tracked even while disabled — the reconnect logic uses
     *  it to tell a stream that is already coming back from one that is still dead. 0 = never. */
    @Volatile var lastBytesAtMs = 0L
        private set

    /**
     * Network bytes since [resetPassive], counted whether or not tracking is enabled. Kept apart from the
     * overlay's count on purpose: [bitsPerSecond] reads-and-clears its own bytes and [reset] runs whenever
     * the overlay opens, and neither touches this one — so the film buffer sizing ([SmartBuffer]) and the
     * stream-info overlay can both read without stealing from each other.
     */
    private val passive = AtomicLong(0L)
    val passiveBytes: Long get() = passive.get()

    /** Start a new passive count (one per item). Leaves the overlay's counters alone. */
    fun resetPassive() = passive.set(0L)

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) {
            lastBytesAtMs = SystemClock.elapsedRealtime()
            passive.addAndGet(bytesTransferred.toLong())
        }
        if (!enabled) return
        everTransferred = true
        synchronized(this) { pendingBytes += bytesTransferred; totalBytes += bytesTransferred }
    }

    @Synchronized
    private fun readAndReset(): Long {
        val now = SystemClock.elapsedRealtime()
        if (lastReadMs == 0L) {
            lastReadMs = now
            return 0L
        }
        val elapsedMs = now - lastReadMs
        val bps = if (elapsedMs > 0) pendingBytes * 8_000 / elapsedMs else 0L
        pendingBytes = 0L
        lastReadMs = now
        return bps
    }

    /** Enabling starts fresh rather than counting bytes from whenever tracking was last on. */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (enabled) reset()
    }

    @Synchronized
    fun reset() {
        pendingBytes = 0L
        totalBytes = 0L
        lastReadMs = 0L
        everTransferred = false
    }
}
