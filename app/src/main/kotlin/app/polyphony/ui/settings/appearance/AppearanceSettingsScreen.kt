package app.polyphony.ui.settings.appearance

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polyphony.R
import app.polyphony.core.domain.repository.AppearanceSettings
import app.polyphony.core.domain.repository.LibraryLayout
import app.polyphony.core.domain.repository.NavTransitionStyle
import app.polyphony.core.domain.repository.PlayerArtworkStyle
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.ThemeMode
import app.polyphony.navigation.LocalNavigator
import app.polyphony.ui.settings.components.ListOption
import app.polyphony.ui.settings.components.PreferenceCategory
import app.polyphony.ui.settings.components.PreferenceNote
import app.polyphony.ui.settings.components.SegmentedPreference
import app.polyphony.ui.settings.components.SettingsScaffold
import app.polyphony.ui.settings.components.SwitchPreference
import app.polyphony.ui.settings.components.preferenceCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Seed colours offered when dynamic colour is off or unavailable. */
val AccentSeeds: List<Long> = listOf(
    0xFF6750A4, // lavender (default)
    0xFF4A5C92, // indigo
    0xFF0061A4, // blue
    0xFF006874, // teal
    0xFF006C4C, // emerald
    0xFF386A20, // green
    0xFF6D5E0F, // olive
    0xFF7D5700, // amber
    0xFF8E4E00, // orange
    0xFFB3261E, // red
    0xFF984061, // raspberry
    0xFF5B5B5B, // graphite
)

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<AppearanceSettings?> = settingsRepository.settings
        .map { it.appearance }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(appearance = transform(it.appearance)) }
        }
    }
}

@Composable
fun AppearanceSettingsScreen(viewModel: AppearanceViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    SettingsScaffold(title = stringResource(R.string.settings_appearance), onBack = { navigator.back() }) {
        val s = settings ?: return@SettingsScaffold
        val dynamicActive = supportsDynamic && s.dynamicColor

        item(key = "preview") { ThemePreviewCard() }

        preferenceCategory(key = "theme", titleRes = R.string.settings_appearance_theme) {
            SegmentedPreference(
                title = stringResource(R.string.settings_appearance_mode),
                options = listOf(
                    ListOption(ThemeMode.SYSTEM, stringResource(R.string.settings_appearance_mode_system)),
                    ListOption(ThemeMode.LIGHT, stringResource(R.string.settings_appearance_mode_light)),
                    ListOption(ThemeMode.DARK, stringResource(R.string.settings_appearance_mode_dark)),
                ),
                selected = s.themeMode,
                onSelected = { mode -> viewModel.update { it.copy(themeMode = mode) } },
            )
            if (supportsDynamic) {
                SwitchPreference(
                    title = stringResource(R.string.settings_appearance_dynamic),
                    summary = stringResource(R.string.settings_appearance_dynamic_summary),
                    checked = s.dynamicColor,
                    onCheckedChange = { on -> viewModel.update { it.copy(dynamicColor = on) } },
                )
            }
            AccentSwatches(
                selectedArgb = s.accentSeedArgb,
                enabled = !dynamicActive,
                onSelect = { seed -> viewModel.update { it.copy(accentSeedArgb = seed) } },
            )
            if (dynamicActive) {
                PreferenceNote(text = stringResource(R.string.settings_appearance_accent_disabled))
            }
            SwitchPreference(
                title = stringResource(R.string.settings_appearance_pure_black),
                summary = stringResource(R.string.settings_appearance_pure_black_summary),
                checked = s.pureBlack,
                onCheckedChange = { on -> viewModel.update { it.copy(pureBlack = on) } },
            )
        }

        preferenceCategory(key = "player", titleRes = R.string.settings_appearance_player) {
            SwitchPreference(
                title = stringResource(R.string.settings_appearance_artwork_colors),
                summary = stringResource(R.string.settings_appearance_artwork_colors_summary),
                checked = s.artworkColorsInPlayer,
                onCheckedChange = { on -> viewModel.update { it.copy(artworkColorsInPlayer = on) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_appearance_blur),
                summary = stringResource(R.string.settings_appearance_blur_summary),
                checked = s.blurredPlayerBackground,
                onCheckedChange = { on -> viewModel.update { it.copy(blurredPlayerBackground = on) } },
            )
            SegmentedPreference(
                title = stringResource(R.string.settings_appearance_artwork_style),
                options = listOf(
                    ListOption(PlayerArtworkStyle.ROUNDED, stringResource(R.string.settings_appearance_style_rounded)),
                    ListOption(PlayerArtworkStyle.SQUARE, stringResource(R.string.settings_appearance_style_square)),
                    ListOption(PlayerArtworkStyle.CIRCLE, stringResource(R.string.settings_appearance_style_circle)),
                ),
                selected = s.playerArtworkStyle,
                onSelected = { style -> viewModel.update { it.copy(playerArtworkStyle = style) } },
            )
        }

        preferenceCategory(key = "motion", titleRes = R.string.settings_appearance_motion) {
            SegmentedPreference(
                title = stringResource(R.string.settings_appearance_nav_transition),
                summary = stringResource(R.string.settings_appearance_nav_transition_summary),
                options = listOf(
                    ListOption(NavTransitionStyle.NONE, stringResource(R.string.settings_transition_none)),
                    ListOption(NavTransitionStyle.FADE, stringResource(R.string.settings_transition_fade)),
                    ListOption(NavTransitionStyle.SLIDE, stringResource(R.string.settings_transition_slide)),
                ),
                selected = s.navTransition,
                onSelected = { t -> viewModel.update { it.copy(navTransition = t) } },
            )
        }

        preferenceCategory(key = "library", titleRes = R.string.settings_appearance_library) {
            val layoutOptions = listOf(
                ListOption(LibraryLayout.LIST, stringResource(R.string.settings_layout_list)),
                ListOption(LibraryLayout.GRID, stringResource(R.string.settings_layout_grid)),
            )
            SegmentedPreference(
                title = stringResource(R.string.settings_appearance_layout_artists),
                options = layoutOptions,
                selected = s.artistsLayout,
                onSelected = { l -> viewModel.update { it.copy(artistsLayout = l) } },
            )
            SegmentedPreference(
                title = stringResource(R.string.settings_appearance_layout_albums),
                options = layoutOptions,
                selected = s.albumsLayout,
                onSelected = { l -> viewModel.update { it.copy(albumsLayout = l) } },
            )
            SegmentedPreference(
                title = stringResource(R.string.settings_appearance_layout_playlists),
                options = layoutOptions,
                selected = s.playlistsLayout,
                onSelected = { l -> viewModel.update { it.copy(playlistsLayout = l) } },
            )
            SegmentedPreference(
                title = stringResource(R.string.settings_appearance_grid_columns),
                options = listOf(
                    ListOption(0, stringResource(R.string.settings_auto)),
                    ListOption(2, "2"),
                    ListOption(3, "3"),
                    ListOption(4, "4"),
                ),
                selected = s.gridColumns,
                onSelected = { n -> viewModel.update { it.copy(gridColumns = n) } },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_appearance_track_numbers),
                summary = stringResource(R.string.settings_appearance_track_numbers_summary),
                checked = s.showTrackNumbers,
                onCheckedChange = { on -> viewModel.update { it.copy(showTrackNumbers = on) } },
            )
        }
    }
}

/** A tiny mock of a list row and chips so colour choices read instantly. */
@Composable
private fun ThemePreviewCard() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = scheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(scheme.onSurface),
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.35f)
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(scheme.onSurfaceVariant),
                    )
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewChip(scheme.primaryContainer, scheme.onPrimaryContainer, stringResource(R.string.settings_appearance_preview_primary))
                PreviewChip(scheme.secondaryContainer, scheme.onSecondaryContainer, stringResource(R.string.settings_appearance_preview_secondary))
                PreviewChip(scheme.tertiaryContainer, scheme.onTertiaryContainer, stringResource(R.string.settings_appearance_preview_tertiary))
            }
        }
    }
}

@Composable
private fun PreviewChip(container: Color, content: Color, label: String) {
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AccentSwatches(
    selectedArgb: Long,
    enabled: Boolean,
    onSelect: (Long) -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Text(stringResource(R.string.settings_appearance_accent), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_appearance_accent_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccentSeeds.forEach { seed ->
                val color = Color(seed)
                val selected = seed == selectedArgb
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier,
                        )
                        .clickable(enabled = enabled) { onSelect(seed) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }
    }
}
