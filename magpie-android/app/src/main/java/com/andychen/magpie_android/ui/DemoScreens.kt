package com.andychen.magpie_android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andychen.magpie_android.R

internal data class DemoModel(val name: String, val provider: String, val icon: Int)
internal data class DemoAgent(val name: String, val icon: Int, val defaultModel: Int)

internal val demoModels = listOf(
    DemoModel("Claude Sonnet 4.5", "Anthropic", R.drawable.brand_claude_color),
    DemoModel("GPT-6.1-Sol", "OpenAI", R.drawable.brand_openai)
)

internal val demoAgents = listOf(
    DemoAgent("Claude Code", R.drawable.brand_claudecode_color, defaultModel = 0),
    DemoAgent("Codex", R.drawable.brand_codex_color, defaultModel = 1),
    DemoAgent("omp", R.drawable.brand_omp, defaultModel = 1)
)

@Composable
internal fun AgentsScreen() {
    var models by rememberSaveable { mutableStateOf(demoAgents.map { it.defaultModel }.toIntArray()) }
    var connected by rememberSaveable { mutableStateOf(BooleanArray(demoAgents.size) { true }) }
    var savedModels by rememberSaveable { mutableStateOf<IntArray?>(null) }
    var savedConnections by rememberSaveable { mutableStateOf<BooleanArray?>(null) }
    var detailIndex by rememberSaveable { mutableIntStateOf(-1) }

    Text(
        stringResource(R.string.agent_intro),
        modifier = Modifier.padding(horizontal = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    MagpiePanel {
        demoAgents.forEachIndexed { index, agent ->
            if (index > 0) MagpieDivider()
            AgentRow(
                agent = agent,
                modelIndex = models[index],
                connected = connected[index],
                onToggle = { value -> connected = connected.copyOf().also { it[index] = value } },
                onSelect = { value -> models = models.copyOf().also { it[index] = value } },
                onDetails = { detailIndex = index }
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.plans), style = MaterialTheme.typography.labelLarge)
        Box(Modifier.weight(1f)) {
            val planModels = savedModels
            val planConnections = savedConnections
            if (planModels == null || planConnections == null) {
                Text(
                    stringResource(R.string.no_plans),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                TextButton(onClick = {
                    models = planModels
                    connected = planConnections
                }) {
                    Text(stringResource(R.string.apply_plan))
                }
            }
        }
        TextButton(onClick = {
            savedModels = models.copyOf()
            savedConnections = connected.copyOf()
        }) {
            Text(stringResource(R.string.save_current), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    demoAgents.getOrNull(detailIndex)?.let { agent ->
        val model = demoModels[models[detailIndex]]
        InfoDialog(agent.name, onDismiss = { detailIndex = -1 }) {
            Text(connectionLabel(agent, connected[detailIndex]))
            Text("${stringResource(R.string.model)} · ${model.name}")
            Text("${stringResource(R.string.providers)} · ${model.provider}")
        }
    }
}

@Composable
private fun AgentRow(
    agent: DemoAgent,
    modelIndex: Int,
    connected: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
    onDetails: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val switchLabel = stringResource(R.string.agent_switch, agent.name)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BrandIcon(agent.icon, Modifier.padding(top = 13.dp).size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(agent.name, style = MaterialTheme.typography.titleMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            Modifier.size(6.dp)
                                .background(if (connected) colors.secondary else colors.outline, CircleShape)
                        )
                        Text(
                            connectionLabel(agent, connected),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TextButton(onClick = onDetails) {
                    Text(stringResource(R.string.details), color = colors.onSurfaceVariant)
                    Icon(MagpieIcons.Next, null, tint = colors.outline, modifier = Modifier.size(14.dp))
                }
                Switch(
                    checked = connected,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = switchLabel },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.surface,
                        checkedTrackColor = colors.onSurface,
                        checkedBorderColor = colors.onSurface
                    )
                )
            }
            if (connected) ModelPicker(modelIndex, onSelect)
        }
    }
}

@Composable
private fun connectionLabel(agent: DemoAgent, connected: Boolean): String =
    if (connected) stringResource(R.string.agent_on, agent.name) else stringResource(R.string.agent_off)

@Composable
private fun ModelPicker(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val model = demoModels[selected]
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(ControlShape)
                .background(colors.surfaceVariant)
                .clickable(role = Role.Button) { expanded = true }
                .heightIn(min = 48.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandIcon(model.icon, Modifier.size(16.dp))
            Text(model.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(MagpieIcons.Down, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(15.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = ControlShape,
            containerColor = colors.surface
        ) {
            demoModels.forEachIndexed { index, choice ->
                DropdownMenuItem(
                    text = { Text(choice.name) },
                    leadingIcon = { BrandIcon(choice.icon, Modifier.size(18.dp)) },
                    trailingIcon = if (index == selected) {
                        { Icon(MagpieIcons.Check, null) }
                    } else null,
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

private data class DemoSession(
    @field:StringRes val title: Int,
    val project: String,
    val agent: DemoAgent,
    @field:StringRes val excerpt: Int
)

private val demoSessions = listOf(
    DemoSession(R.string.session_android, "magpie-mobile", demoAgents[1], R.string.session_android_excerpt),
    DemoSession(R.string.session_readme, "magpie-mobile", demoAgents[0], R.string.session_readme_excerpt),
    DemoSession(R.string.session_usage, "magpie", demoAgents[1], R.string.session_usage_excerpt)
)

@Composable
internal fun SessionsScreen() {
    var search by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val query = search.trim()
    val matching = demoSessions.withIndex().filter { (_, session) ->
        stringResource(session.title).contains(query, ignoreCase = true) ||
            session.agent.name.contains(query, ignoreCase = true) ||
            session.project.contains(query, ignoreCase = true)
    }

    SearchBox(search, stringResource(R.string.find_session)) { search = it }
    if (matching.isEmpty()) {
        EmptyPanel(stringResource(R.string.no_matches))
    } else {
        MagpiePanel {
            matching.forEachIndexed { position, (index, session) ->
                if (position > 0) MagpieDivider()
                InfoRow(
                    title = stringResource(session.title),
                    subtitle = "${session.agent.name} · ${session.project}",
                    icon = session.agent.icon,
                    onClick = { selected = index }
                )
            }
        }
    }
    demoSessions.getOrNull(selected)?.let { session ->
        InfoDialog(stringResource(session.title), onDismiss = { selected = -1 }) {
            Text("${session.agent.name} · ${session.project}")
            Text(stringResource(session.excerpt))
        }
    }
}

private data class LibraryEntry(@field:StringRes val section: Int, val name: String, @field:StringRes val description: Int)

private val libraryEntries = listOf(
    LibraryEntry(R.string.instructions, "AGENTS.md", R.string.library_instructions_detail),
    LibraryEntry(R.string.mcp_servers, "Filesystem", R.string.library_mcp_detail),
    LibraryEntry(R.string.skills, "Code Review", R.string.library_skills_detail)
)

@Composable
internal fun LibraryScreen() {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var showDetail by rememberSaveable { mutableStateOf(false) }
    val entry = libraryEntries[section]
    val description = stringResource(entry.description)
    Segments(libraryEntries.map { stringResource(it.section) }, section) { section = it }
    MagpiePanel {
        InfoRow(entry.name, description, onClick = { showDetail = true })
    }
    if (showDetail) {
        InfoDialog(entry.name, onDismiss = { showDetail = false }) { Text(description) }
    }
}

@Composable
internal fun ProvidersScreen() {
    var search by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val query = search.trim()
    val matching = demoModels.withIndex().filter { (_, model) ->
        model.provider.contains(query, ignoreCase = true) || model.name.contains(query, ignoreCase = true)
    }

    SearchBox(search, stringResource(R.string.find_provider)) { search = it }
    if (matching.isEmpty()) {
        EmptyPanel(stringResource(R.string.no_matches))
    } else {
        MagpiePanel {
            matching.forEachIndexed { position, (index, model) ->
                if (position > 0) MagpieDivider()
                InfoRow(model.provider, model.name, model.icon, onClick = { selected = index })
            }
        }
    }
    demoModels.getOrNull(selected)?.let { model ->
        InfoDialog(model.provider, onDismiss = { selected = -1 }) {
            Text("${stringResource(R.string.provider_model)} · ${model.name}")
        }
    }
}
