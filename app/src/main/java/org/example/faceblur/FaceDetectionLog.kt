package org.example.faceblur

import android.util.Log

/**
 * Unique point d'entrée des logs de l'application.
 * Tous les tags sont préfixés par `FaceDetection.` : `adb logcat -d | rg "(FaceDetection)"`.
 */
object FaceDetectionLog {
    private const val PREFIX = "FaceDetection."

    /** Active les logs DEBUG ; désactivé en release depuis MainActivity. */
    @Volatile
    var verbose: Boolean = true

    fun d(tag: String, msg: () -> String) {
        if (verbose) Log.d(PREFIX + tag, msg())
    }

    fun i(tag: String, msg: () -> String) {
        Log.i(PREFIX + tag, msg())
    }

    fun w(tag: String, throwable: Throwable? = null, msg: () -> String) {
        Log.w(PREFIX + tag, msg(), throwable)
    }

    fun e(tag: String, throwable: Throwable? = null, msg: () -> String) {
        Log.e(PREFIX + tag, msg(), throwable)
    }

    /** Exécute [block] et logue sa durée, avec un résumé optionnel du résultat. */
    inline fun <T> timed(
        tag: String,
        step: String,
        noinline describe: ((T) -> String)? = null,
        block: () -> T,
    ): T {
        val start = System.nanoTime()
        val result = try {
            block()
        } catch (t: Throwable) {
            logTimed(tag, "$step failed", start, null)
            throw t
        }
        logTimed(tag, step, start, describe?.invoke(result))
        return result
    }

    @PublishedApi
    internal fun logTimed(tag: String, step: String, startNanos: Long, description: String?) {
        val ms = (System.nanoTime() - startNanos) / 1_000_000.0
        d(tag) { "$step: ${"%.1f".format(ms)} ms" + description?.let { " — $it" }.orEmpty() }
    }

    /** Logue l'occupation du tas Java (utile autour des gros bitmaps). */
    fun heap(tag: String, label: String) {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        d(tag) { "$label: heap $usedMb / ${rt.maxMemory() / MB} MB" }
    }

    private const val MB = 1024 * 1024
}
