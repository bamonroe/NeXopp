package com.nexopp

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.render.DrawingSurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that a mouse wheel scrolls the document vertically. Needs a real
 * `ACTION_SCROLL` event carrying an `AXIS_VSCROLL` value from a `SOURCE_MOUSE` device — neither
 * `adb input` nor a JVM unit test can produce one — so it runs via `connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MouseWheelInputTest {

    /** Wheel down scrolls further down the document (scrollbar direction, not grab-the-paper). */
    @Test
    fun wheelDownScrollsDown() = onView { view ->
        assertEquals("starts at the top", 0f, view.scrollY, 0.5f)
        assertTrue("the wheel event was consumed", wheel(view, -1f))
        assertTrue("wheel down moved further down the document", view.scrollY > 0f)
    }

    /** Wheel up from the top is clamped: nothing moves, and the event isn't swallowed. */
    @Test
    fun wheelUpAtTopIsClamped() = onView { view ->
        assertTrue(!wheel(view, 1f))
        assertEquals(0f, view.scrollY, 0.5f)
    }

    /** Wheel up after scrolling down returns towards the top. */
    @Test
    fun wheelUpScrollsBack() = onView { view ->
        wheel(view, -3f)
        val down = view.scrollY
        assertTrue(down > 0f)
        wheel(view, 1f)
        assertTrue("wheel up moved back towards the top", view.scrollY < down)
    }

    /** Dispatch one mouse-wheel notch ([notches] negative = wheel down); returns whether it was consumed. */
    private fun wheel(view: View, notches: Float): Boolean {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = 500f
                y = 900f
                setAxisValue(MotionEvent.AXIS_VSCROLL, notches)
            },
        )
        val t = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            t, t, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        return try {
            view.dispatchGenericMotionEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun onView(body: (DrawingSurfaceView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = DrawingSurfaceView(instrumentation.targetContext)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, VIEW_W, VIEW_H)
            // Two pages, so the stack is comfortably taller than the view and there is room to scroll.
            view.load(twoPageDocument())
            body(view)
        }
        instrumentation.waitForIdleSync()
    }

    private fun twoPageDocument(): Document {
        val page = Page(595.276, 841.89, Background.Solid(0xFFFFFFFF.toInt(), "graph"), listOf(Layer(emptyList())))
        return Document(pages = listOf(page, page))
    }

    private companion object {
        const val VIEW_W = 1080
        const val VIEW_H = 1920
    }
}
