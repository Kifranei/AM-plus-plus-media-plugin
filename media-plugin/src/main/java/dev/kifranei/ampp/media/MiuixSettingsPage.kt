package dev.kifranei.ampp.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Miuix (HyperOS) 插件设置页。页面标题由 AM++ 全屏页顶栏提供，这里只渲染分组和开关。
 */
@Composable
internal fun MiuixSettingsPage(
    text: MediaSettingsText,
    initial: MediaSettings,
    @Suppress("UNUSED_PARAMETER") hostVersionCode: Int,
    oplusUnsupported: Boolean,
    status: List<String>,
    onPersist: (MediaSettings) -> Unit,
) {
    var state by remember { mutableStateOf(initial) }
    val styles = PopupGlassStyle.values()
    val styleLabels = listOf(text.popupStyleBottomBar, text.popupStyleMoreMenu, text.popupStyleNoFrost)
    val currentStyleIndex = styles.indexOf(state.popupGlassStyle).coerceAtLeast(0)

    val activity = LocalContext.current.findActivity()
    val scrollBehavior = MiuixScrollBehavior()
    MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MiuixTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = text.name,
                    largeTitle = text.name,
                    navigationIcon = {
                        IconButton(onClick = {
                            activity?.let { act ->
                                // Prefer the host's own close bridge; fall back to the
                                // dispatcher, never to Activity.onBackPressed() (which
                                // would finish Apple Music).
                                runCatching {
                                    Class.forName("dev.amenhancer.module.ui.PluginSettingsBackBridge")
                                        .getMethod("close")
                                        .invoke(null)
                                }.onFailure {
                                    runCatching {
                                        val dispatcher = act.javaClass.methods
                                            .firstOrNull { it.name == "getOnBackPressedDispatcher" }
                                            ?.invoke(act)
                                            ?: return@runCatching
                                        dispatcher.javaClass.methods
                                            .firstOrNull { it.name == "onBackPressed" }
                                            ?.invoke(dispatcher)
                                    }
                                }
                            }
                        }) {
                            Text(text = "←", style = TextStyle(fontSize = 24.sp))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    defaultWindowInsetsPadding = false,
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("features") {
                    SmallTitle(text = text.features)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            checked = state.lyricon,
                            onCheckedChange = {
                                state = state.copy(lyricon = it)
                                onPersist(state)
                            },
                            title = text.lyricon,
                        )
                        SwitchPreference(
                            checked = state.meizuTicker,
                            onCheckedChange = {
                                state = state.copy(meizuTicker = it)
                                onPersist(state)
                            },
                            title = text.meizuTicker,
                            summary = text.meizuTickerDescription,
                        )
                        SwitchPreference(
                            checked = state.mediaNotificationLyrics,
                            onCheckedChange = {
                                state = state.copy(mediaNotificationLyrics = it)
                                onPersist(state)
                            },
                            title = text.mediaNotificationLyrics,
                            summary = text.mediaNotificationLyricsDescription,
                        )
                        SwitchPreference(
                            checked = state.superLyric,
                            onCheckedChange = {
                                state = state.copy(superLyric = it)
                                onPersist(state)
                            },
                            title = text.superLyric,
                            summary = text.superLyricDescription,
                        )
                        SwitchPreference(
                            checked = state.lyricGetter,
                            onCheckedChange = {
                                state = state.copy(lyricGetter = it)
                                onPersist(state)
                            },
                            title = text.lyricGetter,
                            summary = text.lyricGetterDescription,
                        )
                        SwitchPreference(
                            checked = state.iosControls,
                            onCheckedChange = {
                                state = state.copy(iosControls = it)
                                onPersist(state)
                            },
                            title = text.iosControls,
                            summary = text.iosControlsDescription,
                        )
                        SwitchPreference(
                            checked = state.sharing,
                            onCheckedChange = {
                                state = state.copy(sharing = it)
                                onPersist(state)
                            },
                            title = text.sharing,
                            summary = text.sharingDescription,
                        )
                        SwitchPreference(
                            checked = state.playerGlass,
                            onCheckedChange = {
                                state = state.copy(playerGlass = it)
                                onPersist(state)
                            },
                            title = text.playerGlass,
                            summary = text.playerGlassDescription,
                        )
                        SwitchPreference(
                            checked = state.playerHandle,
                            onCheckedChange = {
                                state = state.copy(playerHandle = it)
                                onPersist(state)
                            },
                            title = text.playerHandle,
                        )
                        SwitchPreference(
                            checked = state.systemCorners,
                            onCheckedChange = {
                                state = state.copy(systemCorners = it)
                                onPersist(state)
                            },
                            title = text.systemCorners,
                        )
                        SwitchPreference(
                            checked = state.volumeBar && !oplusUnsupported,
                            enabled = !oplusUnsupported,
                            onCheckedChange = {
                                if (!oplusUnsupported) {
                                    state = state.copy(volumeBar = it)
                                    onPersist(state)
                                }
                            },
                            title = text.volumeBar,
                            summary = if (oplusUnsupported) text.volumeBarOplusUnsupported else null,
                        )
                        SwitchPreference(
                            checked = state.contentDownloads,
                            onCheckedChange = {
                                state = state.copy(contentDownloads = it)
                                onPersist(state)
                            },
                            title = text.contentDownloads,
                            summary = text.contentDownloadsDescription,
                        )
                    }
                }

                item("popup") {
                    SmallTitle(text = text.popupGlassStyle)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        OverlayDropdownPreference(
                            items = styleLabels,
                            selectedIndex = currentStyleIndex,
                            title = text.popupGlassStyle,
                            summary = text.popupGlassStyleDescription,
                            onSelectedIndexChange = { index ->
                                styles.getOrNull(index)?.let { style ->
                                    state = state.copy(popupGlassStyle = style)
                                    onPersist(state)
                                }
                            },
                        )
                    }
                }

                item("automix") {
                    SmallTitle(text = text.autoTransition)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            checked = state.autoTransition,
                            onCheckedChange = {
                                state = state.copy(autoTransition = it)
                                onPersist(state)
                            },
                            title = text.autoTransition,
                            summary = text.autoTransitionDescription,
                        )
                        SwitchPreference(
                            checked = state.autoTransitionSpeedMatch,
                            onCheckedChange = {
                                state = state.copy(autoTransitionSpeedMatch = it)
                                onPersist(state)
                            },
                            title = text.autoTransitionSpeedMatch,
                        )
                        SwitchPreference(
                            checked = state.autoTransitionAnimate,
                            onCheckedChange = {
                                state = state.copy(autoTransitionAnimate = it)
                                onPersist(state)
                            },
                            title = text.autoTransitionAnimate,
                        )
                    }
                }

                item("save") {
                    Button(
                        onClick = { onPersist(state) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = text.save)
                    }
                }

                item("status") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(text = text.currentRun)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = status.joinToString("\n"),
                                style = TextStyle(
                                    fontSize = 8.sp,
                                    lineHeight = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
