package com.serhii.ifiles

import android.content.ComponentName
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackgroundPlaybackTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private var controller:MediaController?=null
    private fun shell(command:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use{it.readBytes()}}
    @Before fun prepareScreen() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        instrumentation.waitForIdleSync()
    }
    private fun connect():MediaController {
        lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
        instrumentation.runOnMainSync {future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        return future.get(20,TimeUnit.SECONDS).also{controller=it}
    }
    private fun awaitCondition(check:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+45000
        while(SystemClock.elapsedRealtime()<end) {
            var done=false;instrumentation.runOnMainSync{done=check()}
            if(done)return
            SystemClock.sleep(100)
        }
        fail("Playback/PiP state timed out")
    }
    @After fun cleanup() {
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
        instrumentation.runOnMainSync{controller?.let{it.stop();it.clearMediaItems();it.release()}}
        context.stopService(android.content.Intent(context,PlaybackService::class.java))
    }
    @Test fun musicContinuesAfterHomeAndScreenOff() {
        val e=Entry("content://com.serhii.ifiles.test.preview/wav","Music.wav",false,mime="audio/wav")
        ActivityScenario.launch<MediaActivity>(MediaActivity.intent(context,e)).use {
            val p=connect()
            instrumentation.runOnMainSync{p.repeatMode=Player.REPEAT_MODE_ONE}
            awaitCondition{p.isPlaying}
            shell("input keyevent KEYCODE_HOME")
            SystemClock.sleep(500)
            instrumentation.runOnMainSync{assertTrue("Home must not pause music",p.isPlaying)}
            shell("input keyevent KEYCODE_SLEEP")
            SystemClock.sleep(1500)
            instrumentation.runOnMainSync{assertTrue("Screen off must not pause music",p.isPlaying);assertEquals(e.ref,p.currentMediaItem?.mediaId)}
        }
    }
    @Test fun videoEntersPictureInPictureOnHomeAndKeepsPlaying() {
        val e=Entry("content://com.serhii.ifiles.test.preview/mp4","Video.mp4",false,mime="video/mp4")
        ActivityScenario.launch<MediaActivity>(MediaActivity.intent(context,e)).use { scenario ->
            val p=connect();var activity:MediaActivity?=null
            scenario.onActivity{activity=it}
            instrumentation.runOnMainSync{p.repeatMode=Player.REPEAT_MODE_ONE}
            awaitCondition{p.isPlaying}
            shell("input keyevent KEYCODE_HOME")
            awaitCondition{activity?.isInPictureInPictureMode==true}
            instrumentation.runOnMainSync{assertTrue("PiP must not pause video",p.isPlaying)}
        }
    }
}
