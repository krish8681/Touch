package com.niftyengine.app

import android.content.Context
import android.os.Build
import com.niftyengine.engine.core.Session
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Writes crashes to `files/crash-last.txt` so the next launch can show and share them. Fatal crashes are still
 * passed on to Android (the process must die); non-fatal ones are only recorded.
 */
object CrashLog {
    private const val FILE = "crash-last.txt"

    fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(ctx, t, e, fatal = true) }
            previous?.uncaughtException(t, e)
        }
    }

    fun write(ctx: Context, thread: Thread, e: Throwable, fatal: Boolean) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        val now = System.currentTimeMillis()
        val text = buildString {
            appendLine("NIFTY Direction Engine ${version ?: "?"} — ${if (fatal) "CRASH" else "error (app kept running)"}")
            appendLine("Time: ${Session.zdt(now).toLocalDate()} ${Session.hhmm(now)} IST")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine("Memory: max ${Runtime.getRuntime().maxMemory() / 1_048_576} MB, used ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576} MB")
            appendLine()
            append(sw.toString().take(20_000))
        }
        // A fatal crash always wins; a non-fatal one never overwrites an unread fatal report.
        val f = file(ctx)
        if (!fatal && f.exists() && f.readText().contains("— CRASH")) return
        f.writeText(text)
    }

    /** The unread report, if any. */
    fun pending(ctx: Context): File? = file(ctx).takeIf { it.exists() && it.length() > 0 }

    fun dismiss(ctx: Context) { file(ctx).delete() }
}
