package com.novelgrabber.app

import android.os.Process
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Importing runs on a small POOL of worker threads, separate from the UI thread and from the thread
 * that builds the library list.
 *
 *  - Workers only exist while there is import work: they are created when jobs are queued and exit a
 *    few seconds after the queue runs dry (nothing idles in the background).
 *  - Several books are parsed at the same time (one per worker). Parsing an epub is CPU + disk bound,
 *    so a pool of 2-4 workers makes a folder import several times faster than one thread did.
 *  - INTERACTIVE jobs (one book the user just picked) are queued ahead of BULK jobs, so a single
 *    import never waits behind hundreds of books — at most for the files currently being processed.
 *  - Bulk workers run at a slightly lower priority than the UI thread, but NOT in Android's
 *    "background" scheduling group (which throttles a thread hard while the app is on screen — that
 *    was the main reason importing crawled).
 */
object ImportManager {

    enum class Lane { INTERACTIVE, BULK }

    /** Worker count: one per core, but never more than 4 (every worker holds a whole epub in memory) and never fewer than 2. */
    val WORKERS: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private val seq = AtomicLong()
    private val threadNo = AtomicInteger()

    private class Job(val lane: Lane, val block: () -> Unit) : Runnable, Comparable<Job> {
        private val order = seq.incrementAndGet()
        override fun run() {
            try {
                Process.setThreadPriority(
                    if (lane == Lane.INTERACTIVE) Process.THREAD_PRIORITY_DEFAULT
                    else BULK_PRIORITY)
            } catch (e: Exception) {}
            try { block() } catch (e: Throwable) { /* a job reports its own failures; never kill the worker */ }
        }
        override fun compareTo(other: Job): Int =
            if (lane != other.lane) lane.ordinal - other.lane.ordinal      // interactive first
            else order.compareTo(other.order)                              // then first come, first served
    }

    // nice +4: a little behind the UI thread, but well clear of THREAD_PRIORITY_BACKGROUND (10), which is throttled
    private const val BULK_PRIORITY = 4

    private val pool: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            WORKERS, WORKERS, 4, TimeUnit.SECONDS, PriorityBlockingQueue<Runnable>(),
            ThreadFactory { r -> Thread(r, "ng-import-" + threadNo.incrementAndGet()).apply { isDaemon = true } }
        ).apply { allowCoreThreadTimeOut(true) }       // threads are created on demand and die when idle
    }

    fun submit(lane: Lane, block: () -> Unit) { pool.execute(Job(lane, block)) }
}
