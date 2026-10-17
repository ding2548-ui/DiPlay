package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmbientAlbumUiScreenshotsTest {
    private var previousFontScale = 1f

    @Before
    fun rememberConfiguration() {
        previousFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
    }

    @After
    fun restoreConfiguration() {
        RuntimeEnvironment.setQualifiers("en")
        val app = RuntimeEnvironment.getApplication()
        app.resources.updateConfiguration(
            Configuration(app.resources.configuration).apply { fontScale = previousFontScale },
            app.resources.displayMetrics,
        )
    }

    @Test
    fun albumToggleAndDialogRemainReachableAtCompactAndFullWidths() {
        for ((widthDp, fontScale) in listOf(600 to 1.3f, 1280 to 1.5f)) {
            val opened = openDialog(widthDp, fontScale)
            try {
                val dialog = opened.second
                val decor = dialog.window!!.decorView
                layout(decor, RuntimeEnvironment.getApplication().resources.displayMetrics.density, widthDp)

                val scroll = albumSettingsScroll(decor, opened.first.getString(R.string.settings_ambient_album))
                val album = descendants(scroll).filterIsInstance<Switch>().single {
                    it.contentDescription == opened.first.getString(R.string.settings_ambient_album)
                }
                assertTrue("Album artwork toggle must have a visible label", descendants(scroll)
                    .filterIsInstance<TextView>().any { it.text == opened.first.getString(R.string.settings_ambient_album) })
                val albumRow = album.parent as View
                assertTrue("Album artwork row must be keyboard/accessibility focusable", albumRow.isFocusable)
                assertTrue("Album artwork row must have a nonempty hit target", albumRow.width > 0 && albumRow.height > 0)
                val content = scroll.getChildAt(0)
                val viewportHeight = scroll.height - scroll.paddingTop - scroll.paddingBottom
                val maxScroll = maxOf(0, content.height - viewportHeight)

                val areaTitle = opened.first.getString(R.string.settings_ambient_area)
                val lastChoice = descendants(scroll).filterIsInstance<TextView>().single {
                    it.isClickable && it.text.toString().startsWith("$areaTitle ·")
                }
                scroll.scrollTo(0, maxScroll)
                shadowOf(Looper.getMainLooper()).idle()
                val visible = Rect()
                assertTrue("The last setting must be visible at ${widthDp}dp", lastChoice.getLocalVisibleRect(visible))
                assertEquals("The last setting must not be clipped at ${widthDp}dp", lastChoice.height, visible.height())
                assertDialogActionsVisible(decor)
            } finally {
                opened.first.finish()
            }
        }
    }

    @Test
    fun writesRealDialogScreenshotsForCompactAndFullLayouts() {
        for ((widthDp, fontScale, name) in listOf(
            Triple(600, 1.3f, "compact-600dp-font-1.3"),
            Triple(1280, 1.5f, "full-1280dp-font-1.5"),
        )) {
            for (atBottom in listOf(false, true)) {
                val opened = openDialog(widthDp, fontScale)
                try {
                    val decor = opened.second.window!!.decorView
                    layout(decor, RuntimeEnvironment.getApplication().resources.displayMetrics.density, widthDp)
                    val scroll = albumSettingsScroll(decor, opened.first.getString(R.string.settings_ambient_album))
                    val content = scroll.getChildAt(0)
                    val viewportHeight = scroll.height - scroll.paddingTop - scroll.paddingBottom
                    if (atBottom) scroll.scrollTo(0, maxOf(0, content.height - viewportHeight))
                    val position = if (atBottom) "bottom" else "top"
                    writeScreenshot(decor, reportDirectory().resolve("$name-$position.png"))
                } finally {
                    opened.second.dismiss()
                    opened.first.finish()
                    shadowOf(Looper.getMainLooper()).idle()
                }
            }
        }
    }

    private fun openDialog(widthDp: Int, fontScale: Float): Pair<DiPlayActivity, AlertDialog> {
        RuntimeEnvironment.setQualifiers("en-w${widthDp}dp-h960dp-mdpi")
        val app = RuntimeEnvironment.getApplication()
        app.resources.updateConfiguration(
            Configuration(app.resources.configuration).apply {
                this.fontScale = fontScale
                screenWidthDp = widthDp
                screenHeightDp = 960
            },
            app.resources.displayMetrics,
        )
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.ambientSupportCheck = { CompletableFuture.completedFuture(false) }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showAmbientConfiguration")
        shadowOf(Looper.getMainLooper()).idle()
        return activity to ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun layout(decor: View, density: Float, widthDp: Int) {
        val width = (widthDp * density).roundToInt()
        val height = (960 * density).roundToInt()
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        assertTrue("Dialog decor must be laid out before capture", decor.width > 0 && decor.height > 0)
    }

    private fun albumSettingsScroll(decor: View, albumLabel: String): ScrollView =
        descendants(decor).filterIsInstance<ScrollView>().single { scroll ->
            descendants(scroll).filterIsInstance<Switch>().any { it.contentDescription == albumLabel }
        }

    private fun reportDirectory(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        val module = if (workingDirectory.fileName.toString() == "common") workingDirectory else workingDirectory.resolve("common")
        return module.resolve("build/reports/album-artwork-ui")
    }

    private fun writeScreenshot(decor: View, output: Path) {
        shadowOf(Looper.getMainLooper()).idle()
        assertDialogActionsVisible(decor)
        descendants(decor).forEach(View::invalidate)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            decor.draw(Canvas(bitmap))
            Files.createDirectories(output.parent)
            Files.newOutputStream(output).use { stream ->
                assertTrue("PNG encoder must write the rendered dialog", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertDialogActionsVisible(decor: View) {
        for (id in listOf(android.R.id.button1, android.R.id.button2)) {
            val action = decor.findViewById<View>(id)
            val visible = Rect()
            assertTrue("Dialog action must be visible after scrolling", action.getLocalVisibleRect(visible))
            assertEquals("Dialog action must not be clipped", action.height, visible.height())
            assertEquals("Dialog action must not be clipped horizontally", action.width, visible.width())
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}
