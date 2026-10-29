package app.polyphony.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polyphony.R
import app.polyphony.core.domain.model.ServerType
import app.polyphony.navigation.PolyphonyNavigator

@Composable
fun ConnectServerScreen(
    navigator: PolyphonyNavigator,
    viewModel: ConnectServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ConnectEvent.Done -> if (event.firstServer) navigator.finishOnboarding() else navigator.back()
            }
        }
    }

    val canStepBack = state.serverType != null && !state.typeFixed
    BackHandler(enabled = canStepBack) { viewModel.selectType(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    state.serverType?.let { Text(stringResource(R.string.connect_title, it.shortLabel())) }
                },
                navigationIcon = {
                    IconButton(onClick = { if (canStepBack) viewModel.selectType(null) else navigator.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.connect_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = state.serverType,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                }
            },
            label = "connectStep",
        ) { type ->
            if (type == null) {
                ServerTypeStep(onSelect = viewModel::selectType)
            } else {
                ConnectForm(type = type, state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun ServerType.shortLabel(): String = when (this) {
    ServerType.SUBSONIC -> stringResource(R.string.server_type_subsonic_title)
    ServerType.JELLYFIN -> stringResource(R.string.server_type_jellyfin_title)
}

@Composable
private fun ServerTypeStep(onSelect: (ServerType) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.server_type_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.server_type_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ServerTypeCard(
            icon = Icons.Rounded.Dns,
            title = stringResource(R.string.server_type_subsonic_title),
            description = stringResource(R.string.server_type_subsonic_desc),
            onClick = { onSelect(ServerType.SUBSONIC) },
        )
        ServerTypeCard(
            icon = Icons.Rounded.LibraryMusic,
            title = stringResource(R.string.server_type_jellyfin_title),
            description = stringResource(R.string.server_type_jellyfin_desc),
            onClick = { onSelect(ServerType.JELLYFIN) },
        )
    }
}

@Composable
private fun ServerTypeCard(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectForm(type: ServerType, state: ConnectFormState, viewModel: ConnectServerViewModel) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = state.url,
            onValueChange = viewModel::setUrl,
            label = { Text(stringResource(R.string.connect_url)) },
            placeholder = { Text(stringResource(R.string.connect_url_hint)) },
            supportingText = {
                Text(stringResource(R.string.connect_url_help, type.defaultPort ?: 4533))
            },
            leadingIcon = { Icon(Icons.Rounded.Link, null) },
            isError = state.urlError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.username,
            onValueChange = viewModel::setUsername,
            label = { Text(stringResource(R.string.connect_username)) },
            leadingIcon = { Icon(Icons.Rounded.Person, null) },
            isError = state.usernameError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::setPassword,
            label = { Text(stringResource(R.string.connect_password)) },
            leadingIcon = { Icon(Icons.Rounded.Lock, null) },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (showPassword) R.string.connect_hide_password else R.string.connect_show_password),
                    )
                }
            },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.connect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text(stringResource(R.string.connect_name)) },
            supportingText = { Text(stringResource(R.string.connect_name_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.connect() }),
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(onClick = viewModel::toggleAdvanced) {
            Icon(if (state.showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.connect_advanced))
        }
        AnimatedVisibility(visible = state.showAdvanced) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (type == ServerType.SUBSONIC) {
                    SwitchRow(
                        title = stringResource(R.string.connect_legacy_auth),
                        description = stringResource(R.string.connect_legacy_auth_desc),
                        checked = state.legacyAuth,
                        onCheckedChange = viewModel::setLegacyAuth,
                    )
                }
                SwitchRow(
                    title = stringResource(R.string.connect_trust_self_signed),
                    description = stringResource(R.string.connect_trust_self_signed_desc),
                    checked = state.trustSelfSigned,
                    onCheckedChange = viewModel::setTrustSelfSigned,
                )
                Text(stringResource(R.string.connect_custom_headers), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.connect_custom_headers_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.headers.forEach { header ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = header.name,
                            onValueChange = { viewModel.updateHeader(header.id, name = it) },
                            label = { Text(stringResource(R.string.connect_header_name)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = header.value,
                            onValueChange = { viewModel.updateHeader(header.id, value = it) },
                            label = { Text(stringResource(R.string.connect_header_value)) },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f),
                        )
                        IconButton(onClick = { viewModel.removeHeader(header.id) }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.connect_remove_header))
                        }
                    }
                }
                TextButton(onClick = viewModel::addHeader) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.connect_add_header))
                }
            }
        }

        AnimatedVisibility(visible = state.error != null) {
            val message = state.error?.let { errorMessage(it) }.orEmpty()
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.ErrorOutline, null)
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = viewModel::connect,
            enabled = !state.isConnecting,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            if (state.isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.connect_connecting))
            } else {
                Text(stringResource(R.string.connect_button), style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun errorMessage(error: ConnectError): String = when (error) {
    ConnectError.InvalidUrl -> stringResource(R.string.connect_error_url)
    ConnectError.MissingUsername -> stringResource(R.string.connect_error_username)
    ConnectError.Unauthorized -> stringResource(R.string.connect_error_unauthorized)
    ConnectError.NotFound -> stringResource(R.string.connect_error_not_found)
    is ConnectError.Network -> stringResource(R.string.connect_error_network, error.detail)
    is ConnectError.Server -> stringResource(R.string.connect_error_server, error.code, error.detail)
    is ConnectError.Unsupported -> stringResource(R.string.connect_error_unsupported, error.detail)
    is ConnectError.Unknown -> stringResource(R.string.connect_error_unknown, error.detail)
}
