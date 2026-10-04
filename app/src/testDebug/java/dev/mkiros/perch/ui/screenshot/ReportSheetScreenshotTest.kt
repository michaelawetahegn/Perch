package dev.mkiros.perch.ui.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import dev.mkiros.perch.model.ThemeMode
import dev.mkiros.perch.ui.article.ArticleTestTags
import dev.mkiros.perch.ui.article.RenderProblem
import dev.mkiros.perch.ui.article.RenderReportSheet
import dev.mkiros.perch.ui.theme.Dimens
import dev.mkiros.perch.ui.theme.PerchTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * J08's captures, in `build/perch-screenshots/`: the Report sheet (#86) open over an
 * article, with one problem chosen so the button reads enabled. The sheet is the real
 * `ModalBottomSheet`, so its scrim, handle and window are what the reader sees.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ReportSheetScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val theme = mutableStateOf(ThemeMode.Light)

    @Test
    fun `the report sheet with a problem chosen in both themes`() {
        compose.setContent {
            PerchTheme(mode = theme.value, dynamicColor = false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(Dimens.screenHorizontal),
                ) {
                    Text(
                        text = "Learning LLVM (Part-1) - Writing a simple LLVM pass",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                RenderReportSheet(onOpen = { _, _ -> }, onDismiss = {})
            }
        }
        compose.onNodeWithTag(ArticleTestTags.reportProblem(RenderProblem.CodeOrTables))
            .performSemanticsAction(SemanticsActions.OnClick)

        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            theme.value = mode
            compose.waitForIdle()
            Screenshots.captureAndAssert(compose, "report-sheet-${mode.name.lowercase()}")
        }
    }
}
