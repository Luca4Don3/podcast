package com.podcastapp.ui

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.Session
import com.podcastapp.data.filemedia.FileMediaSession
import com.podcastapp.playback.PlaybackHolder
import com.podcastapp.ui.adaptive.AdaptiveWindow
import com.podcastapp.ui.adaptive.FoldFeature
import com.podcastapp.ui.adaptive.NavigationMode
import com.podcastapp.ui.adaptive.PlacePanes
import com.podcastapp.ui.adaptive.ScreenProfile
import com.podcastapp.ui.adaptive.ScreenSize
import com.podcastapp.ui.adaptive.ScreenSplit
import com.podcastapp.ui.auth.AuthScreen
import com.podcastapp.ui.discover.DiscoverScreen
import com.podcastapp.ui.filemedia.FileMediaScreen
import com.podcastapp.ui.library.LibraryScreen
import com.podcastapp.ui.player.MiniPlayer
import com.podcastapp.ui.player.PlayerSheet
import com.podcastapp.ui.player.PlayerViewModel
import com.podcastapp.ui.podcast.PodcastDetailScreen
import com.podcastapp.ui.radio.RadioScreen
import com.podcastapp.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("library", "资料库", Icons.Filled.LibraryMusic),
    Tab("discover", "导入", Icons.Filled.Explore),
    Tab("radio", "电台", Icons.Filled.Radio),
    Tab("settings", "设置", Icons.Filled.Settings),
)

/** 登录前也可打开服务器设置；等待本地会话加载后再显示页面。 */
@Composable
internal fun PodcastAppRoot(
    activity: Activity? = null,
    folds: List<FoldFeature> = emptyList(),
    appViewModel: AppViewModel = viewModel(),
) {
    val session by appViewModel.session.collectAsState()
    val toast by appViewModel.toast.collectAsState()
    val busy by appViewModel.busy.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { PlaybackHolder.checkpoint() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbarHost.showSnackbar(it.message)
            appViewModel.consumeToast()
        }
    }

    AdaptiveWindow(activity, folds) { profile ->
        val current = session
        if (current != null && current.isLoggedIn) {
            key(current.baseUrl, current.userId, current.loginId) {
                MainShell(appViewModel, current, busy, profile, snackbarHost)
            }
        } else {
            PlacePanes(profile.singlePane, main = {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHost) },
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        if (current == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else AuthScreen(viewModel = appViewModel, busy = busy, session = current)
                    }
                }
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    appViewModel: AppViewModel,
    session: Session,
    busy: Boolean,
    profile: ScreenProfile,
    snackbarHost: SnackbarHostState,
) {
    val navController = rememberNavController()
    val playerViewModel: PlayerViewModel = viewModel()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val online by ServiceLocator.online.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val selectedRoute = when (currentRoute) {
        "filemedia" -> "discover"
        "podcast/{id}" -> navController.previousBackStackEntry?.destination?.route
        else -> currentRoute
    }
    fun openTab(route: String) {
        if (currentRoute == route) return
        if (selectedRoute == route && navController.popBackStack(route, inclusive = false)) return
        navController.navigate(route) {
            popUpTo("library") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val play: (com.podcastapp.data.remote.EpisodeDto, String, String) -> Unit = { episode, title, artwork ->
        PlaybackHolder.play(context.applicationContext, episode, title, artwork)
        showPlayer = true
    }

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val split = profile.split
    val primary = when (split) {
        is ScreenSplit.LeftRight -> if (rtl) split.right else split.left
        is ScreenSplit.Tabletop -> split.top
        null -> profile.singlePane
    }
    val secondary = when (split) {
        is ScreenSplit.LeftRight -> if (rtl) split.left else split.right
        is ScreenSplit.Tabletop -> split.bottom
        null -> null
    }
    val rail = primary.navigation == NavigationMode.RAIL
    val narrow = primary.size == ScreenSize.NARROW_TALL || primary.widthDp < 360f
    val constrainPlayer = profile.avoidsFold || profile.usesModelCutout
    val playerInMainPane = constrainPlayer && secondary == null && showPlayer
    val player: @Composable (() -> Unit) -> Unit = { collapse ->
        PlayerSheet(
            state = playerState,
            onCollapse = collapse,
            onToggle = playerViewModel::togglePlayPause,
            onSeek = playerViewModel::seek,
            onSkipBack = playerViewModel::skipBack,
            onSkipForward = playerViewModel::skipForward,
            onSpeed = playerViewModel::setSpeed,
            onSleepTimer = playerViewModel::setSleepTimer,
        )
    }

    PlacePanes(primary, secondary, main = {
      Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (rail) {
                NavigationRail(windowInsets = WindowInsets(0, 0, 0, 0)) {
                    tabs.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedRoute == tab.route,
                            onClick = { openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { NavigationLabel(tab.label) },
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                snackbarHost = { SnackbarHost(snackbarHost) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
            Column {
                if (!online) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        androidx.compose.foundation.layout.Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "离线模式：进度会保存在本地，联网后自动同步",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (secondary == null && playerState.title.isNotBlank()) {
                    MiniPlayer(
                        state = playerState,
                        onExpand = { showPlayer = true },
                        onToggle = playerViewModel::togglePlayPause,
                    )
                }
                if (!rail) NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedRoute == tab.route,
                            onClick = { openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = if (narrow && selectedRoute != tab.route) tab.label else null) },
                            label = { NavigationLabel(tab.label) },
                            alwaysShowLabel = !narrow,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        NavHost(
            navController = navController,
            startDestination = "library",
            modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
        ) {
            composable("library") {
                LibraryScreen(
                    onPodcastClick = { id -> navController.navigate("podcast/" + id) },
                    onEpisodeClick = { episode, title, artwork -> play(episode, title, artwork) },
                    onImport = { openTab("discover") },
                    onRadio = { openTab("radio") },
                )
            }
            composable("discover") {
                DiscoverScreen(
                    onOpenPodcast = { id -> navController.navigate("podcast/" + id) },
                    onOpenFileMedia = { navController.navigate("filemedia") { launchSingleTop = true } },
                )
            }
            composable("filemedia") {
                val client by ServiceLocator.client.collectAsState()
                FileMediaScreen(
                    session = FileMediaSession.from(session, client),
                    onBack = { navController.popBackStack() },
                    onPlay = { file ->
                        PlaybackHolder.playFile(context.applicationContext, file)
                        showPlayer = true
                    },
                )
            }
            composable("podcast/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                PodcastDetailScreen(
                    podcastId = id,
                    onBack = { navController.popBackStack() },
                    onPlay = { episode, title, artwork -> play(episode, title, artwork) },
                )
            }
            composable("radio") {
                RadioScreen(
                    onPlay = { station ->
                        PlaybackHolder.playStation(context.applicationContext, station)
                        showPlayer = true
                    },
                )
            }
            composable("settings") {
                SettingsScreen(viewModel = appViewModel, session = session, busy = busy)
            }
        }
        }
    }
        }
        if (playerInMainPane) {
            BackHandler { showPlayer = false }
            Surface(Modifier.fillMaxSize()) { player { showPlayer = false } }
        }
      }
    }, supporting = {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            if (showPlayer) {
                BackHandler { showPlayer = false }
                player { showPlayer = false }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("正在播放", style = MaterialTheme.typography.titleMedium)
                    if (playerState.title.isBlank()) {
                        Text("选择节目或电台开始播放", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        MiniPlayer(playerState, { showPlayer = true }, playerViewModel::togglePlayPause)
                    }
                }
            }
        }
    })

    if (showPlayer && !constrainPlayer && secondary == null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showPlayer = false }, sheetState = sheetState) {
            player { scope.launch { sheetState.hide() }.invokeOnCompletion { showPlayer = false } }
        }
    }
}

@Composable
private fun NavigationLabel(label: String) {
    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// A very short window may not fit four vertically stacked navigation items.
internal fun useSideNavigation(widthDp: Float, heightDp: Float): Boolean = widthDp >= 600f && heightDp >= 320f
