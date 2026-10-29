package app.polyphony.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

data class SortOption<T>(val value: T, val label: String)

/**
 * A compact sort control: shows the current sort and direction; tapping the selected option
 * again flips the direction.
 */
@Composable
fun <T> SortMenu(
    options: ImmutableList<SortOption<T>>,
    selected: T,
    ascending: Boolean,
    onChange: (T, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showDirection: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.value == selected }?.label ?: ""
    Box(modifier) {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, modifier = Modifier.padding(start = 6.dp))
            if (showDirection) {
                Icon(
                    if (ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 2.dp).size(16.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val isSelected = option.value == selected
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        if (isSelected && showDirection) onChange(option.value, !ascending) else onChange(option.value, true)
                    },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(
                                if (!showDirection) Icons.Rounded.Check else if (ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun GridListToggle(
    isGrid: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = { onToggle(!isGrid) }, modifier = modifier) {
        Icon(
            if (isGrid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
            contentDescription = if (isGrid) "Show as list" else "Show as grid",
        )
    }
}
