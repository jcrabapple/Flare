package dev.dimension.flare.ui.screen.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.dimension.flare.R
import dev.dimension.flare.data.push.PushAccount
import dev.dimension.flare.data.push.PushAccountState
import dev.dimension.flare.data.push.PushCategory
import dev.dimension.flare.data.push.PushNotificationService
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.push.PushRegistrar
import dev.dimension.flare.ui.component.AvatarComponent
import dev.dimension.flare.ui.component.BackButton
import dev.dimension.flare.ui.component.FlareLargeFlexibleTopAppBar
import dev.dimension.flare.ui.component.FlareScaffold
import dev.dimension.flare.ui.component.RichText
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.presenter.settings.AccountsPresenter
import dev.dimension.flare.ui.theme.screenHorizontalPadding
import dev.dimension.flare.ui.theme.segmentedShapes2
import dev.dimension.flare.ui.theme.single
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val NTFY_DOCS_URL = "https://ntfy.sh/docs/subscribe/phone/"

/** Read-only view of push settings for [NotificationSettingsContent]. */
@Stable
internal interface NotificationSettingsState {
    val enabled: Boolean
    val categories: Set<PushCategory>
    val accounts: List<PushAccount>

    /** Display profile per account key (avatar + display name, from [AccountsPresenter]). */
    val profiles: Map<MicroBlogKey, UiProfile?>
    val distributors: List<String>
    val currentDistributor: String?

    /** Distributor package name to application label. */
    val distributorLabels: Map<String, String>
    val permissionDenied: Boolean
}

/** User actions on the push settings screen. */
@Immutable
internal class NotificationSettingsCallbacks(
    val onSetEnabled: (Boolean) -> Unit,
    val onSetAccountEnabled: (MicroBlogKey, Boolean) -> Unit,
    val onSetCategory: (PushCategory, Boolean) -> Unit,
    val onSelectDistributor: (String) -> Unit,
    val onInstallNtfy: () -> Unit = {},
    val onOpenSystemSettings: () -> Unit = {},
)

@Composable
internal fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val service = koinInject<PushNotificationService>()
    val registrar = koinInject<PushRegistrar>()
    val scope = rememberCoroutineScope()
    val pushEnabled by service.enabled.collectAsState(initial = false)
    val pushCategories by service.categories.collectAsState(initial = emptySet())
    val pushAccounts by service.accounts.collectAsState(initial = emptyList())
    val accountsState = remember { AccountsPresenter() }.invoke()
    val profiles =
        accountsState.accounts
            .takeSuccess()
            .orEmpty()
            .associate { it.account.accountKey to it.profile.takeSuccess() }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val beforeEnable =
        rememberNotificationPermission(
            onGranted = { permissionDenied = false },
            onDenied = { permissionDenied = true },
        )
    val distributors = remember { registrar.distributors() }
    val currentDistributor = remember { registrar.currentDistributor() }
    val distributorLabels =
        remember(distributors) {
            distributors.associateWith { packageName ->
                runCatching {
                    context.packageManager
                        .getApplicationLabel(
                            context.packageManager.getApplicationInfo(packageName, 0),
                        ).toString()
                }.getOrDefault(packageName)
            }
        }
    val callbacks =
        NotificationSettingsCallbacks(
            onSetEnabled = { value ->
                if (!value) {
                    permissionDenied = false
                }
                scope.launch { service.setEnabled(value) }
            },
            onSetAccountEnabled = { accountKey, value ->
                scope.launch { service.setAccountEnabled(accountKey, value) }
            },
            onSetCategory = { category, value ->
                scope.launch {
                    service.setCategories(if (value) pushCategories + category else pushCategories - category)
                }
            },
            onSelectDistributor = { packageName ->
                registrar.selectDistributor(packageName)
                scope.launch { registrar.sync() }
            },
            onInstallNtfy = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, NTFY_DOCS_URL.toUri()))
                }.onFailure { it.printStackTrace() }
            },
            onOpenSystemSettings = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                            Settings.EXTRA_APP_PACKAGE,
                            context.packageName,
                        ),
                    )
                }.onFailure { it.printStackTrace() }
            },
        )
    val state =
        object : NotificationSettingsState {
            override val enabled = pushEnabled
            override val categories = pushCategories
            override val accounts = pushAccounts
            override val profiles = profiles
            override val distributors = distributors
            override val currentDistributor = currentDistributor
            override val distributorLabels = distributorLabels
            override val permissionDenied = permissionDenied
        }
    NotificationSettingsContent(
        state = state,
        callbacks = callbacks,
        beforeEnable = beforeEnable,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationSettingsContent(
    state: NotificationSettingsState,
    callbacks: NotificationSettingsCallbacks,
    beforeEnable: (() -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showDistributorPicker by rememberSaveable { mutableStateOf(false) }
    val setEnabled: (Boolean) -> Unit = { checked ->
        if (checked) {
            beforeEnable { callbacks.onSetEnabled(true) }
        } else {
            callbacks.onSetEnabled(false)
        }
    }
    FlareScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FlareLargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(id = R.string.push_settings_title))
                },
                navigationIcon = {
                    BackButton(onBack = onBack)
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = screenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val enableLabel = stringResource(id = R.string.push_enable)
            SegmentedListItem(
                onClick = { setEnabled(!state.enabled) },
                shapes = ListItemDefaults.single(),
                content = {
                    Text(text = enableLabel)
                },
                trailingContent = {
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = setEnabled,
                        modifier = Modifier.semantics { contentDescription = enableLabel },
                    )
                },
            )
            if (state.permissionDenied) {
                SegmentedListItem(
                    onClick = callbacks.onOpenSystemSettings,
                    shapes = ListItemDefaults.single(),
                    content = {
                        Text(
                            text = stringResource(id = R.string.push_permission_denied),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = callbacks.onOpenSystemSettings) {
                            Text(text = stringResource(id = R.string.push_open_system_settings))
                        }
                    },
                )
            }
            if (state.distributors.isEmpty()) {
                SegmentedListItem(
                    onClick = callbacks.onInstallNtfy,
                    shapes = ListItemDefaults.single(),
                    content = {
                        Text(text = stringResource(id = R.string.push_distributor_none))
                    },
                    supportingContent = {
                        Text(text = stringResource(id = R.string.push_distributor_none_description))
                    },
                    trailingContent = {
                        TextButton(onClick = callbacks.onInstallNtfy) {
                            Text(text = stringResource(id = R.string.push_install_ntfy))
                        }
                    },
                )
            } else {
                SegmentedListItem(
                    onClick = {
                        if (state.distributors.size > 1) {
                            showDistributorPicker = true
                        }
                    },
                    shapes = ListItemDefaults.single(),
                    content = {
                        Text(text = stringResource(id = R.string.push_distributor_title))
                    },
                    supportingContent = {
                        val label =
                            when {
                                state.distributors.size == 1 -> {
                                    state.distributorLabels[state.distributors.first()]
                                }

                                else -> {
                                    state.currentDistributor?.let { state.distributorLabels[it] }
                                }
                            }
                        Text(text = label.orEmpty())
                    },
                )
            }
            if (state.accounts.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.push_accounts_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    state.accounts.forEachIndexed { index, account ->
                        val setAccountEnabled: (Boolean) -> Unit = { checked ->
                            callbacks.onSetAccountEnabled(account.accountKey, checked)
                        }
                        SegmentedListItem(
                            onClick = {
                                if (account.supported) {
                                    setAccountEnabled(!account.enabled)
                                }
                            },
                            enabled = account.supported,
                            shapes = ListItemDefaults.segmentedShapes2(index, state.accounts.size),
                            leadingContent = {
                                AvatarComponent(data = state.profiles[account.accountKey]?.avatar)
                            },
                            content = {
                                val profile = state.profiles[account.accountKey]
                                if (profile != null) {
                                    RichText(profile.name, maxLines = 1)
                                } else {
                                    Text(text = account.accountKey.toString())
                                }
                            },
                            supportingContent = {
                                Text(text = stateLabel(account))
                            },
                            trailingContent = {
                                Switch(
                                    checked = account.enabled && account.supported,
                                    onCheckedChange = if (account.supported) setAccountEnabled else null,
                                    enabled = account.supported,
                                    modifier =
                                        Modifier.semantics {
                                            contentDescription = account.accountKey.toString()
                                        },
                                )
                            },
                        )
                    }
                }
            }
            Text(
                text = stringResource(id = R.string.push_categories_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PushCategory.entries.forEachIndexed { index, category ->
                    val checked = category in state.categories
                    val setCategory: (Boolean) -> Unit = { value ->
                        callbacks.onSetCategory(category, value)
                    }
                    val label = categoryLabel(category)
                    SegmentedListItem(
                        onClick = { setCategory(!checked) },
                        shapes = ListItemDefaults.segmentedShapes2(index, PushCategory.entries.size),
                        content = {
                            Text(text = label)
                        },
                        trailingContent = {
                            Switch(
                                checked = checked,
                                onCheckedChange = setCategory,
                                modifier = Modifier.semantics { contentDescription = label },
                            )
                        },
                    )
                }
            }
        }
    }
    if (showDistributorPicker) {
        AlertDialog(
            onDismissRequest = { showDistributorPicker = false },
            title = {
                Text(text = stringResource(id = R.string.push_distributor_pick))
            },
            text = {
                Column {
                    state.distributors.forEach { packageName ->
                        val label = state.distributorLabels[packageName] ?: packageName
                        val select: () -> Unit = {
                            showDistributorPicker = false
                            callbacks.onSelectDistributor(packageName)
                        }
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = select)
                                    .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = packageName == state.currentDistributor,
                                onClick = select,
                            )
                            Text(text = label)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun stateLabel(account: PushAccount): String {
    if (!account.supported) {
        return stringResource(id = R.string.push_state_unsupported)
    }
    return when (account.state) {
        PushAccountState.Off -> stringResource(id = R.string.push_state_off)
        PushAccountState.Registering -> stringResource(id = R.string.push_state_registering)
        PushAccountState.Active -> stringResource(id = R.string.push_state_active)
        PushAccountState.ServerDisabled -> stringResource(id = R.string.push_state_server_disabled)
        PushAccountState.ReauthRequired -> stringResource(id = R.string.push_state_reauth)
        PushAccountState.Error -> stringResource(id = R.string.push_state_error, account.lastError.orEmpty())
        PushAccountState.Unsupported -> stringResource(id = R.string.push_state_unsupported)
    }
}

@Composable
private fun categoryLabel(category: PushCategory): String =
    when (category) {
        PushCategory.Mention -> stringResource(id = R.string.push_category_mention)
        PushCategory.Follow -> stringResource(id = R.string.push_category_follow)
        PushCategory.FollowRequest -> stringResource(id = R.string.push_category_follow_request)
        PushCategory.Favourite -> stringResource(id = R.string.push_category_favourite)
        PushCategory.Reblog -> stringResource(id = R.string.push_category_reblog)
        PushCategory.Poll -> stringResource(id = R.string.push_category_poll)
        PushCategory.Other -> stringResource(id = R.string.push_category_other)
    }

/**
 * Gates the wrapped action behind the POST_NOTIFICATIONS permission on API 33+. The action runs
 * immediately when the permission is already granted (or not needed); after a denial the caller
 * shows the [R.string.push_permission_denied] state via [onDenied].
 */
@Composable
private fun rememberNotificationPermission(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                onGranted()
                pending?.invoke()
            } else {
                onDenied()
            }
            pending = null
        }
    return { action ->
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
            action()
        } else {
            pending = action
            launcher.launch(permission)
        }
    }
}
