package com.andychen.magpie_android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andychen.magpie_android.R

internal val PanelShape = RoundedCornerShape(12.dp)
internal val ControlShape = RoundedCornerShape(8.dp)

@Composable
internal fun MagpiePanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SectionLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun EmptyPanel(message: String) {
    MagpiePanel {
        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MagpieDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun BrandIcon(@DrawableRes icon: Int, modifier: Modifier = Modifier) {
    val monochrome = icon in listOf(R.drawable.brand_magpie, R.drawable.brand_omp, R.drawable.brand_openai)
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = modifier,
        tint = if (monochrome) MaterialTheme.colorScheme.onSurface else Color.Unspecified
    )
}

@Composable
internal fun Segments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier.weight(1f)
                    .clip(ControlShape)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                    .semantics { contentDescription = label }
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = if (active) colors.surface else colors.surfaceContainer,
                    border = if (active) BorderStroke(1.dp, colors.outlineVariant) else null
                ) {
                    Box(
                        modifier = Modifier.heightIn(min = 34.dp).padding(horizontal = 4.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            modifier = Modifier.clearAndSetSemantics {},
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchBox(query: String, label: String, onQueryChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ControlShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            singleLine = true,
            decorationBox = { input ->
                Row(
                    modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(MagpieIcons.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(label, color = colors.onSurfaceVariant)
                        input()
                    }
                }
            }
        )
    }
}

@Composable
internal fun InfoRow(
    title: String,
    subtitle: String,
    icon: Int? = null,
    trailing: String? = null,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) BrandIcon(icon, Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Icon(MagpieIcons.Next, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun InfoDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        shape = PanelShape,
        containerColor = MaterialTheme.colorScheme.surface
    )
}
