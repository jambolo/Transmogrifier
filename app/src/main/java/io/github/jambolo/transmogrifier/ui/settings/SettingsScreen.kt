package io.github.jambolo.transmogrifier.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jambolo.transmogrifier.AppContainer
import io.github.jambolo.transmogrifier.R
import io.github.jambolo.transmogrifier.llm.Effort
import io.github.jambolo.transmogrifier.llm.LlmException
import io.github.jambolo.transmogrifier.llm.ModelInfo
import io.github.jambolo.transmogrifier.llm.NetworkFailure
import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.settings.Settings
import io.github.jambolo.transmogrifier.ui.effortLabel
import io.github.jambolo.transmogrifier.ui.providerLabel
import io.github.jambolo.transmogrifier.ui.theme.Citrus
import io.github.jambolo.transmogrifier.ui.theme.Ink
import io.github.jambolo.transmogrifier.ui.theme.TransmogrifierTheme

@Composable
fun SettingsRoute(container: AppContainer, onBack: () -> Unit) {
    val viewModel = viewModel {
        SettingsViewModel(container.settingsRepository, container.providers)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state,
        onBack = onBack,
        onSaveKey = viewModel::saveKey,
        onClearKey = viewModel::clearKey,
        onTestKey = viewModel::testKey,
        onDefaultProvider = viewModel::setDefaultProvider,
        onDefaultModel = viewModel::setDefaultModel,
        onEffort = viewModel::setEffort,
        onMaxToolIterations = viewModel::setMaxToolIterations,
        onMaxOutputTokens = viewModel::setMaxOutputTokens,
        onAppInstructions = viewModel::setAppInstructions,
        onAllowAppNetwork = viewModel::setAllowAppNetwork
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSaveKey: (ProviderId, String) -> Unit,
    onClearKey: (ProviderId) -> Unit,
    onTestKey: (ProviderId) -> Unit,
    onDefaultProvider: (ProviderId) -> Unit,
    onDefaultModel: (ProviderId, String) -> Unit,
    onEffort: (Effort) -> Unit,
    onMaxToolIterations: (Int) -> Unit,
    onMaxOutputTokens: (Int) -> Unit,
    onAppInstructions: (String) -> Unit,
    onAllowAppNetwork: (Boolean) -> Unit
) {
    val settings = state.settings
    var advanced by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (settings.apiKeyHints.keys.none { it.enabled }) {
                Surface(
                    color = Citrus,
                    contentColor = Ink,
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.brand_tagline),
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(stringResource(R.string.brand_welcome))
                    }
                }
            }
            SectionTitle(stringResource(R.string.api_keys))
            Text(
                stringResource(R.string.setup_disclosure),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (provider in ProviderId.enabledEntries) {
                ApiKeyCard(
                    provider,
                    hint = settings.apiKeyHints[provider],
                    test = state.keyTests[provider],
                    onSave = { onSaveKey(provider, it) },
                    onClear = { onClearKey(provider) },
                    onTest = { onTestKey(provider) }
                )
            }

            OutlinedButton(
                onClick = { advanced = !advanced },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (advanced) {
                            R.string.hide_advanced_settings
                        } else {
                            R.string.advanced_settings
                        }
                    )
                )
            }
            if (advanced) {
                Text(
                    stringResource(R.string.advanced_help),
                    style = MaterialTheme.typography.bodyMedium
                )
                val providers = ProviderId.enabledEntries
                val provider = settings.newAppProvider
                if (providers.size > 1) {
                    SectionTitle(stringResource(R.string.default_provider))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        providers.forEachIndexed { i, choice ->
                            SegmentedButton(
                                selected = provider == choice,
                                onClick = { onDefaultProvider(choice) },
                                shape = SegmentedButtonDefaults.itemShape(i, providers.size)
                            ) { Text(providerLabel(choice)) }
                        }
                    }
                }
                ModelField(
                    provider,
                    settings.defaultModel(provider),
                    state.models[provider].orEmpty(),
                    onDefaultModel
                )

                SectionTitle(stringResource(R.string.effort))
                Text(
                    stringResource(R.string.effort_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (effort in Effort.entries) {
                        FilterChip(
                            selected = settings.effort == effort,
                            onClick = { onEffort(effort) },
                            label = { Text(effortLabel(effort)) }
                        )
                    }
                }

                SectionTitle(stringResource(R.string.limits))
                Text(
                    stringResource(R.string.limits_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NumberField(
                    stringResource(R.string.max_tool_iterations),
                    settings.maxToolIterations,
                    1..200,
                    onMaxToolIterations
                )
                NumberField(
                    stringResource(R.string.max_output_tokens),
                    settings.maxOutputTokens,
                    1_024..128_000,
                    onMaxOutputTokens
                )

                SectionTitle(stringResource(R.string.app_instructions))
                Text(
                    stringResource(R.string.app_instructions_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InstructionsField(settings.appInstructions, onAppInstructions)

                SectionTitle(stringResource(R.string.app_network))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = settings.allowAppNetwork,
                            role = Role.Checkbox,
                            onValueChange = onAllowAppNetwork
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = settings.allowAppNetwork, onCheckedChange = null)
                    Text(
                        stringResource(R.string.app_network_label),
                        Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    stringResource(R.string.app_network_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InstructionsField(value: String, onValue: (String) -> Unit) {
    val max = Settings.MAX_APP_INSTRUCTIONS_LENGTH
    var text by rememberSaveable { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.take(max)
            onValue(text)
        },
        label = { Text(stringResource(R.string.app_instructions)) },
        supportingText = { Text(stringResource(R.string.character_count, text.length, max)) },
        minLines = 3,
        maxLines = 10,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ApiKeyCard(
    provider: ProviderId,
    hint: String?,
    test: KeyTest?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onTest: () -> Unit
) {
    // Deliberately not rememberSaveable: a saved draft would put the key in the saved-state Bundle.
    var draft by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val fieldLabel = if (hint == null) R.string.api_key else R.string.replace_key
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(providerLabel(provider), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.api_key_help, providerLabel(provider)),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                if (hint == null) {
                    stringResource(R.string.key_missing)
                } else {
                    stringResource(R.string.key_saved, hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(fieldLabel)) },
                singleLine = true,
                visualTransformation = if (visible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { visible = !visible }) {
                        Text(stringResource(if (visible) R.string.hide else R.string.show))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onSave(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank()
                ) { Text(stringResource(R.string.save)) }
                OutlinedButton(
                    onClick = onTest,
                    enabled = hint != null && test != KeyTest.Running
                ) {
                    Text(stringResource(R.string.test))
                }
                if (hint != null) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
                }
            }
            when (test) {
                null -> Unit

                KeyTest.Running -> Text(
                    stringResource(R.string.key_testing),
                    style = MaterialTheme.typography.bodyMedium
                )

                is KeyTest.Passed -> Text(
                    pluralStringResource(
                        R.plurals.key_test_passed,
                        test.modelCount,
                        test.modelCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                is KeyTest.Failed -> {
                    Text(
                        keyErrorMessage(provider, test.error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    keyErrorDetail(test.error)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun keyErrorMessage(provider: ProviderId, error: LlmException): String {
    val label = providerLabel(provider)
    return when (error) {
        is LlmException.MissingKey -> stringResource(R.string.key_error_missing)
        is LlmException.InvalidKey -> stringResource(R.string.key_error_invalid, label)
        is LlmException.PermissionDenied -> stringResource(R.string.key_error_permission, label)
        is LlmException.RateLimited -> stringResource(R.string.key_error_rate_limited, label)
        is LlmException.Server -> stringResource(
            if (error.overloaded) R.string.key_error_overloaded else R.string.key_error_server,
            label
        )
        is LlmException.Rejected -> stringResource(R.string.key_error_rejected, label)
        is LlmException.Network -> stringResource(
            when (error.failure) {
                NetworkFailure.HOST_NOT_FOUND -> R.string.key_error_host_not_found
                NetworkFailure.TIMED_OUT -> R.string.key_error_timed_out
                NetworkFailure.CONNECTION_REFUSED -> R.string.key_error_connection_refused
                NetworkFailure.INSECURE -> R.string.key_error_insecure
                NetworkFailure.OTHER -> R.string.key_error_network
            },
            label
        )
    }
}

/** The service's own explanation and status, or the underlying cause of an unclassified network failure. */
@Composable
private fun keyErrorDetail(error: LlmException): String? {
    val status = error.statusCode
    val detail = error.detail
    return when {
        error is LlmException.Network ->
            error.message.takeIf { error.failure == NetworkFailure.OTHER }
        status != null && detail != null ->
            stringResource(R.string.key_error_detail, status, detail)
        status != null -> stringResource(R.string.key_error_code, status)
        else -> detail
    }
}

@Composable
private fun ModelField(
    provider: ProviderId,
    model: String,
    models: List<ModelInfo>,
    onModel: (ProviderId, String) -> Unit
) {
    var text by rememberSaveable(provider) { mutableStateOf(model) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (it.isNotBlank()) onModel(provider, it.trim())
        },
        label = { Text(stringResource(R.string.default_model)) },
        singleLine = true,
        isError = text.isBlank(),
        modifier = Modifier.fillMaxWidth()
    )
    if (models.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (info in models) {
                FilterChip(
                    selected = text == info.id,
                    onClick = {
                        text = info.id
                        onModel(provider, info.id)
                    },
                    label = { Text(info.displayName) }
                )
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in range } == true
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit)
            text.toIntOrNull()?.takeIf { it in range }?.let(onValue)
        },
        label = { Text(label) },
        supportingText = { Text(stringResource(R.string.range, range.first, range.last)) },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SettingsScreenPreview() {
    TransmogrifierTheme {
        SettingsScreen(
            SettingsUiState(
                settings = Settings(apiKeyHints = mapOf(ProviderId.ANTHROPIC to "x9Qa")),
                keyTests = mapOf(ProviderId.ANTHROPIC to KeyTest.Passed(3)),
                models = mapOf(
                    ProviderId.ANTHROPIC to listOf(
                        ModelInfo("claude-opus-5-5", "Claude Opus 5.5"),
                        ModelInfo("claude-sonnet-5-5", "Claude Sonnet 5.5")
                    )
                )
            ),
            onBack = {},
            onSaveKey = { _, _ -> },
            onClearKey = {},
            onTestKey = {},
            onDefaultProvider = {},
            onDefaultModel = { _, _ -> },
            onEffort = {},
            onMaxToolIterations = {},
            onMaxOutputTokens = {},
            onAppInstructions = {},
            onAllowAppNetwork = {}
        )
    }
}
