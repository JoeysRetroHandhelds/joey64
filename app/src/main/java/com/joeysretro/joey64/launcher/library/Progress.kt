package com.joeysretro.joey64.launcher.library

/**
 * How far a long job has got (copying saves and states, reading the games), for a progress bar
 * with "37 of 211 · about 20 s left". [doneBytes]/[totalBytes] are 0 when the job counts files
 * only. Apart from Android so it's unit tested (ProgressTest).
 */
data class Progress(
    val done: Int,
    val total: Int,
    val startedAt: Long,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    /** After the total: " MB" for a download ("12 of 28 MB"). */
    val unit: String = "",
) {
    /** 0..1, by bytes when they're known, else by count. */
    val fraction: Float
        get() = when {
            totalBytes > 0 -> (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            total > 0 -> (done.toFloat() / total).coerceIn(0f, 1f)
            else -> 0f
        }

    /**
     * Seconds left at [now], from the pace so far; null until there's enough to go on (2 s in and
     * 3% done), so the first guesses, which swing most, aren't shown.
     */
    fun secondsLeft(now: Long): Long? {
        val elapsed = now - startedAt
        val f = fraction
        if (elapsed < 2000 || f < 0.03f) return null
        if (f >= 1f) return 0
        return ((elapsed * (1 - f) / f) / 1000).toLong()
    }

    /** "37 of 211 · about 20 s left", "… about 2 min left", "… about 2 h left" (from 90 min), "37 of 211". */
    fun text(now: Long): String {
        val left = secondsLeft(now) ?: return "$done of $total$unit"
        val eta = when {
            left < 5 -> "a few seconds left"
            left < 60 -> "about ${(left + 4) / 5 * 5} s left"
            left < 90 * 60 -> "about ${(left + 30) / 60} min left"
            else -> "about ${(left + 1800) / 3600} h left"
        }
        return "$done of $total$unit · $eta"
    }
}
