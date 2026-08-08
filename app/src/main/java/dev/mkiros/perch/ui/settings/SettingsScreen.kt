package dev.mkiros.perch.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.theme.Dimens
import dev.mkiros.perch.ui.theme.ThemeMode
import dev.mkiros.perch.work.RefreshInterval
import java.io.IOException

/** Nodes a test needs to reach that a reader reaches by reading. */
object SettingsTags {
    const val INTERVAL_ROW = "settings-interval"
    const val THEME_ROW = "settings-theme"
    const val SHOW_READ_SWITCH = "settings-show-read"
    const val IMPORT_ROW = "settings-import"
    const val EXPORT_ROW = "settings-export"
}

/**
 * What the SAF create-document dialog is told an OPML file is.
 *
 * `text/xml` rather than the truthful `text/x-opml`: the reader has to be able to hand the
 * result to another reader, and a picker that does not recognise the type may refuse to
 * save it at all. The extension in the file name is what actually carries the format.
 */
private const val OPML_MIME = "text/xml"

/**
 * What the open-document dialog will list.
 *
 * Deliberately everything. OPML arrives from other readers as `text/xml`,
 * `application/octet-stream`, or whatever a cloud provider decided to label it, and a
 * picker that hides the reader's own export is a worse failure than one that lists too
 * much — the file is validated on read, so a wrong pick is a message, not a corruption.
 */
private val OPML_PICKABLE = arrayOf("*/*")

/**
 * Settings (DESIGN.md §5, SPEC.md §9).
 *
 * Every control writes through the ViewModel to DataStore and reads back from it; nothing
 * on this screen holds a preference in composition state. That is what makes the theme
 * choice arrive at `MainActivity` and "show read entries" arrive at home without either
 * screen knowing this one exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val snackbars = remember { SnackbarHostState() }

    var intervalDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(OPML_MIME),
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportOpml { text -> resolver.writeText(uri, text) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importOpml { resolver.readText(uri) }
    }

    val text = message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (text != null) {
            snackbars.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            modifier = Modifier.size(Dimens.icon),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Dimens.xl),
        ) {
            SectionHeader(stringResource(R.string.settings_section_reading))
            SwitchRow(
                title = stringResource(R.string.settings_show_read_title),
                body = stringResource(R.string.settings_show_read_body),
                checked = state.settings.showReadEntries,
                onCheckedChange = viewModel::setShowReadEntries,
                modifier = Modifier.testTag(SettingsTags.SHOW_READ_SWITCH),
            )

            SectionHeader(stringResource(R.string.settings_section_refresh))
            ChoiceRow(
                title = stringResource(R.string.settings_interval_title),
                value = stringResource(state.settings.refreshInterval.labelRes()),
                onClick = { intervalDialog = true },
                modifier = Modifier.testTag(SettingsTags.INTERVAL_ROW),
            )

            SectionHeader(stringResource(R.string.settings_section_appearance))
            ChoiceRow(
                title = stringResource(R.string.settings_theme_title),
                value = stringResource(state.settings.themeMode.labelRes()),
                onClick = { themeDialog = true },
                modifier = Modifier.testTag(SettingsTags.THEME_ROW),
            )

            SectionHeader(stringResource(R.string.settings_section_subscriptions))
            ChoiceRow(
                title = stringResource(R.string.settings_import_title),
                value = stringResource(R.string.settings_import_body),
                onClick = { importLauncher.launch(OPML_PICKABLE) },
                modifier = Modifier.testTag(SettingsTags.IMPORT_ROW),
            )
            ChoiceRow(
                title = stringResource(R.string.settings_export_title),
                value = stringResource(R.string.settings_export_body),
                onClick = { exportLauncher.launch(state.exportFileName) },
                modifier = Modifier.testTag(SettingsTags.EXPORT_ROW),
            )

            SectionHeader(stringResource(R.string.settings_section_about))
            Column(
                modifier = Modifier.padding(
                    horizontal = Dimens.rowHorizontal,
                    vertical = Dimens.rowVertical,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.xs),
            ) {
                Text(
                    text = stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_version, state.versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (intervalDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_interval_title),
            options = RefreshInterval.entries,
            selected = state.settings.refreshInterval,
            label = { stringResource(it.labelRes()) },
            onSelect = {
                viewModel.setRefreshInterval(it)
                intervalDialog = false
            },
            onDismiss = { intervalDialog = false },
        )
    }

    if (themeDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme_title),
            options = ThemeMode.entries,
            selected = state.settings.themeMode,
            label = { stringResource(it.labelRes()) },
            onSelect = {
                viewModel.setThemeMode(it)
                themeDialog = false
            },
            onDismiss = { themeDialog = false },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    HorizontalDivider(
        modifier = Modifier.padding(top = Dimens.sm),
        thickness = Dimens.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = Dimens.rowHorizontal,
            end = Dimens.rowHorizontal,
            top = Dimens.lg,
            bottom = Dimens.sm,
        ),
    )
}

/** Title over an explanatory line, with a switch on the right. */
@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title = title, body = body, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Title over its current value, or over what tapping it will do. */
@Composable
private fun ChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.rowHorizontal, vertical = Dimens.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title = title, body = value, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RowText(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one dialog shape both enum settings use. Choosing commits immediately — there is no
 * OK button, because there is nothing to confirm about a radio button that already shows
 * what it did.
 */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            )
                            .padding(vertical = Dimens.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = Dimens.md),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun messageText(message: SettingsMessage): String = when (message) {
    is SettingsMessage.Imported -> if (message.folders > 0) {
        stringResource(
            R.string.settings_import_result_folders,
            message.added,
            message.duplicates,
            message.invalid,
            message.folders,
        )
    } else {
        stringResource(
            R.string.settings_import_result,
            message.added,
            message.duplicates,
            message.invalid,
        )
    }

    is SettingsMessage.ImportRejected ->
        stringResource(R.string.settings_import_rejected, message.reason)

    SettingsMessage.Exported -> stringResource(R.string.settings_export_done)
    SettingsMessage.TransferFailed -> stringResource(R.string.settings_transfer_failed)
}

/** Visible so a test can name a choice the way the reader sees it, not by enum constant. */
internal fun RefreshInterval.labelRes(): Int = when (this) {
    RefreshInterval.Manual -> R.string.settings_interval_manual
    RefreshInterval.Every15Minutes -> R.string.settings_interval_15m
    RefreshInterval.Hourly -> R.string.settings_interval_hourly
    RefreshInterval.Every3Hours -> R.string.settings_interval_3h
    RefreshInterval.Every6Hours -> R.string.settings_interval_6h
}

internal fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.System -> R.string.settings_theme_system
    ThemeMode.Light -> R.string.settings_theme_light
    ThemeMode.Dark -> R.string.settings_theme_dark
}

/** A SAF document is a stream, not a file: no path, and it may vanish between picks. */
private fun ContentResolver.writeText(uri: Uri, text: String) {
    val stream = openOutputStream(uri) ?: throw IOException("Cannot write $uri")
    stream.use { it.write(text.toByteArray()) }
}

private fun ContentResolver.readText(uri: Uri): String {
    val stream = openInputStream(uri) ?: throw IOException("Cannot read $uri")
    return stream.use { it.readBytes().toString(Charsets.UTF_8) }
}
