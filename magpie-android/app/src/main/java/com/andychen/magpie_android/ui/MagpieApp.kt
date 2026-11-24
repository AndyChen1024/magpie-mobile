package com.andychen.magpie_android.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.andychen.magpie_android.R
import com.andychen.magpie_android.ui.theme.MagpieTheme

private enum class Page(@field:StringRes val title: Int, val icon: ImageVector) {
    Agents(R.string.agents, MagpieIcons.Agents),
    Sessions(R.string.sessions, MagpieIcons.Sessions),
    Library(R.string.library, MagpieIcons.Library),
    Providers(R.string.providers, MagpieIcons.Providers),
    Routing(R.string.routing, MagpieIcons.Routing),
    Gateway(R.string.gateway, MagpieIcons.Gateway),
    Usage(R.string.usage, MagpieIcons.Usage),
    Masking(R.string.masking, MagpieIcons.Masking),
    Plugins(R.string.plugins, MagpieIcons.Plugins),
    Settings(R.string.settings, MagpieIcons.Settings)
}

internal enum class ThemeMode(@field:StringRes val title: Int) {
    System(R.string.theme_system), Light(R.string.theme_light), Dark(R.string.theme_dark)
}

private val mainPages = listOf(Page.Agents, Page.Sessions, Page.Library, Page.Providers)
private val overflowPages = listOf(Page.Routing, Page.Gateway, Page.Usage, Page.Masking, Page.Plugins)

@Composable
internal fun MagpieApp(
    theme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    usageContent: @Composable () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(Page.Agents) }
    var previousPage by rememberSaveable { mutableStateOf(Page.Agents) }
    val pageStates = rememberSaveableStateHolder()

    fun navigate(destination: Page) {
        if (destination == Page.Settings) previousPage = page
        page = destination
    }

    fun goBack() {
        page = if (page == Page.Settings) previousPage else Page.Agents
    }

    BackHandler(enabled = page != Page.Agents, onBack = ::goBack)

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { MagpieHeader(page, onSettings = { navigate(Page.Settings) }, onBack = ::goBack) },
        bottomBar = { MagpieNavigation(page, ::navigate) }
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            pageStates.SaveableStateProvider(page.name) {
                if (page == Page.Usage) {
                    Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) { usageContent() }
                    return@SaveableStateProvider
                }
                Column(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (page) {
                        Page.Agents -> AgentsScreen()
                        Page.Sessions -> SessionsScreen()
                        Page.Library -> LibraryScreen()
                        Page.Providers -> ProvidersScreen()
                        Page.Usage -> Unit
                        Page.Settings -> SettingsScreen(theme, onThemeChange)
                        else -> {
                            SectionLabel(stringResource(page.title))
                            EmptyPanel(stringResource(R.string.not_connected))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MagpieHeader(page: Page, onSettings: () -> Unit, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (page == Page.Settings) {
            IconButton(onClick = onBack) {
                Icon(MagpieIcons.Back, stringResource(R.string.back), tint = colors.onSurfaceVariant)
            }
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
        } else {
            BrandIcon(R.drawable.brand_magpie, Modifier.size(20.dp))
            Text("magpie", style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (page != Page.Usage && page != Page.Settings) {
                Text(
                    stringResource(R.string.demo_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
        if (page != Page.Settings) {
            IconButton(onClick = onSettings) {
                Icon(MagpieIcons.Settings, stringResource(R.string.settings), tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MagpieNavigation(page: Page, onNavigate: (Page) -> Unit) {
    var showMore by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 4.dp)
                    .selectableGroup()
                    .semantics { testTagsAsResourceId = true }
            ) {
                for (item in mainPages) {
                    NavigationItem(
                        title = stringResource(item.title),
                        icon = item.icon,
                        selected = page == item,
                        onClick = { onNavigate(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Box(Modifier.weight(1f)) {
                    NavigationItem(
                        title = stringResource(R.string.more),
                        icon = MagpieIcons.More,
                        selected = page in overflowPages,
                        onClick = { showMore = true },
                        modifier = Modifier.fillMaxWidth().testTag("nav_more")
                    )
                    DropdownMenu(
                        expanded = showMore,
                        onDismissRequest = { showMore = false },
                        modifier = Modifier.width(248.dp),
                        shape = RoundedCornerShape(12.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        for (item in overflowPages) {
                            DropdownMenuItem(
                                text = { Text(stringResource(item.title)) },
                                leadingIcon = { Icon(item.icon, contentDescription = null) },
                                trailingIcon = if (page == item) {
                                    { Icon(MagpieIcons.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    showMore = false
                                    onNavigate(item)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.onSurface else colors.onSurfaceVariant
    Column(
        modifier = modifier
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = title }
            .heightIn(min = 52.dp)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(23.dp))
        Text(
            title,
            modifier = Modifier.clearAndSetSemantics {},
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsScreen(theme: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    SectionLabel(stringResource(R.string.appearance))
    Segments(
        labels = ThemeMode.entries.map { stringResource(it.title) },
        selected = theme.ordinal,
        onSelect = { onThemeChange(ThemeMode.entries[it]) }
    )
}

@Preview(name = "Magpie · Android", widthDp = 390, heightDp = 844)
@Composable
private fun MagpieAppPreview() {
    MagpieTheme(ThemeMode.Light) {
        MagpieApp(ThemeMode.Light, onThemeChange = {}) { UsageScreen() }
    }
}
