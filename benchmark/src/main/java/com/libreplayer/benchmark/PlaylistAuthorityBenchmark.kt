package com.libreplayer.benchmark

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistAuthorityBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val uri = Uri.parse("content://com.libreplayer.playlist-authority")

    @Test fun playlistAndLocalM3uAuthority() {
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        check(device.executeShellCommand("getprop ro.build.version.sdk").trim() == "36")
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "LibrePlayer_Benchmark_API_36")
        try {
            val setup = call("setup")
            device.executeShellCommand("am force-stop com.libreplayer")
            val intent = instrumentation.context.packageManager.getLaunchIntentForPackage("com.libreplayer")!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            instrumentation.context.startActivity(intent)
            check(device.wait(Until.hasObject(By.text("Playlists")), 10_000))
            val reopened = call("inspect")
            check(setup.getStringArray("ids")!!.contentEquals(reopened.getStringArray("ids")!!))
            click("Playlists")
            click("Q36 Ordered")
            val titles = reopened.getStringArray("titles")!!
            titles.forEach { check(device.wait(Until.hasObject(By.text(it)), 5_000)) }
            click(titles.first())
            SystemClock.sleep(1_000)
            call("playback", "0")
            click(titles[2])
            SystemClock.sleep(1_000)
            call("playback", "2")

            // Actual persisted UI edits, checked through Room rather than only menu completion.
            device.findObjects(By.desc("Playlist song actions"))[2].click()
            click("Move up")
            SystemClock.sleep(500)
            call("ui-moved")
            device.findObjects(By.desc("Playlist song actions"))[2].click()
            click("Remove from playlist")
            SystemClock.sleep(500)
            call("ui-removed")
            repeat(3) {
                device.swipe(540, 1550, 540, 500, 20)
                device.swipe(540, 500, 540, 1550, 20)
            }
            click("Back")
            click("Import M3U")
            SystemClock.sleep(1_000)
            check(device.currentPackageName.endsWith("documentsui"))
            device.pressBack() // One-time picker cancellation must not create a playlist or retain a grant.
            check(device.wait(Until.hasObject(By.text("Export M3U")), 5_000))
            click("Export M3U")
            click("Q36 Ordered")
            SystemClock.sleep(1_000)
            check(device.currentPackageName.endsWith("documentsui"))
            device.pressBack()

            val path = "/storage/emulated/0/Music/LibrePlayerBenchmark/Q36_PLAYLIST_AUTHORITY/One.mp3"
            device.executeShellCommand("content delete --uri content://media/external/audio/media --where \"_data = '$path'\"")
            device.executeShellCommand("rm -f $path")
            call("removed")
            val pid = device.executeShellCommand("pidof com.libreplayer").trim()
            check(pid.matches(Regex("[0-9]+")))
            val logs = device.executeShellCommand("logcat -d --pid $pid")
            check(!logs.contains("FATAL EXCEPTION") && !logs.contains("was already used")) { logs }
            Log.i("Q36Authority", "PASS: Room, local M3U/M3U8, round trip, restart, UI edits, selected queue index, missing-source behavior")
        } finally {
            call("cleanup")
        }
    }

    private fun call(method: String, arg: String? = null): Bundle {
        val result = checkNotNull(instrumentation.context.contentResolver.call(uri, method, arg, null))
        Log.i("Q36Authority", "$method=$result")
        check(result.getString("status")?.startsWith("PASS") == true)
        return result
    }

    private fun click(text: String) {
        check(device.wait(Until.hasObject(By.text(text)), 5_000)) { "Missing UI text: $text" }
        repeat(3) { attempt ->
            try {
                val candidates = device.findObjects(By.text(text))
                val target = candidates.firstOrNull { it.visibleBounds.centerY() < device.displayHeight * 0.8 } ?: candidates.first()
                target.click()
                device.waitForIdle()
                return
            } catch (stale: StaleObjectException) {
                if (attempt == 2) throw stale
            }
        }
    }
}
