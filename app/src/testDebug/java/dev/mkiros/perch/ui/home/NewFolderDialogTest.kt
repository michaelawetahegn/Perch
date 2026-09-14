package dev.mkiros.perch.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F14/#64: the "New folder…" gesture is one thing shared by the drawer and the add-source
 * sheet, so what it promises is pinned once, here, rather than once per host.
 */
@RunWith(RobolectricTestRunner::class)
class NewFolderDialogTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a confirmed name reaches the host once and the dialog goes away`() {
        val created = mutableListOf<String>()
        compose.setContent {
            val request = rememberNewFolderRequest()
            androidx.compose.material3.TextButton(onClick = request::open) {
                androidx.compose.material3.Text("New folder…")
            }
            NewFolderDialog(request, onCreate = { created += it })
        }
        compose.onNodeWithTag(FolderActionTestTags.NAME_FIELD).assertDoesNotExist()

        compose.onNodeWithText("New folder…").performClick()
        compose.onNodeWithTag(FolderActionTestTags.NAME_FIELD).performTextReplacement("Graphics")
        compose.onNodeWithTag(FolderActionTestTags.NAME_CONFIRM).performSemanticsAction(SemanticsActions.OnClick)

        assertThat(created).containsExactly("Graphics")
        compose.onNodeWithTag(FolderActionTestTags.NAME_FIELD).assertDoesNotExist()
    }

    @Test
    fun `cancelling creates nothing and closes the dialog`() {
        val created = mutableListOf<String>()
        compose.setContent {
            val request = rememberNewFolderRequest()
            androidx.compose.material3.TextButton(onClick = request::open) {
                androidx.compose.material3.Text("New folder…")
            }
            NewFolderDialog(request, onCreate = { created += it })
        }
        compose.onNodeWithText("New folder…").performClick()
        compose.onNodeWithTag(FolderActionTestTags.NAME_FIELD).performTextReplacement("Graphics")

        compose.onNodeWithTag(FolderActionTestTags.CANCEL).performSemanticsAction(SemanticsActions.OnClick)

        assertThat(created).isEmpty()
        compose.onNodeWithTag(FolderActionTestTags.NAME_FIELD).assertDoesNotExist()
    }

    /** The two flags it replaced were `rememberSaveable`; the request must not lose that. */
    @Test
    fun `an open dialog survives state restoration`() {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            val request = rememberNewFolderRequest()
            androidx.compose.material3.TextButton(onClick = request::open) {
                androidx.compose.material3.Text("New folder…")
            }
            NewFolderDialog(request, onCreate = {})
        }
        compose.onNodeWithText("New folder…").performClick()
        compose.onNodeWithTag(FolderActionTestTags.NAME_FIELD).assertIsDisplayed()

        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag(FolderActionTestTags.NAME_FIELD).assertIsDisplayed()
    }
}
