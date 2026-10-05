package com.mymusiccurator.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mymusiccurator.ui.screens.DashboardScreen
import com.mymusiccurator.ui.screens.RecommendScreen
import com.mymusiccurator.ui.screens.SettingsScreen
import com.mymusiccurator.ui.screens.SetupBanner
import com.mymusiccurator.ui.screens.TrackDetailScreen
import com.mymusiccurator.ui.screens.TracksScreen
import com.mymusiccurator.ui.screens.rememberPermissionState
import com.mymusiccurator.ui.theme.MyMusicCuratorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MyMusicCuratorTheme { App() } }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "통계", Icons.Default.Insights),
    Tracks("tracks", "곡 목록", Icons.AutoMirrored.Filled.QueueMusic),
    Recommend("recommend", "추천", Icons.Default.Recommend),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val currentTab = Tab.entries.firstOrNull { it.route == route }
    val context = LocalContext.current
    val perms = rememberPermissionState()
    val openTrack: (Long) -> Unit = { id -> nav.navigate("track/$id") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentTab?.label ?: if (route == "settings") "설정" else "곡 정보") },
                navigationIcon = {
                    if (currentTab == null) {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                    }
                },
                actions = {
                    if (route != "settings") {
                        IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Default.Settings, "설정") }
                    }
                },
            )
        },
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Dashboard.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Dashboard.route) {
                DashboardScreen(
                    onTrackClick = openTrack,
                    setupBanner = {
                        SetupBanner(perms) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    },
                )
            }
            composable(Tab.Tracks.route) { TracksScreen(onTrackClick = openTrack) }
            composable(Tab.Recommend.route) { RecommendScreen(onTrackClick = openTrack) }
            composable("settings") { SettingsScreen() }
            composable("track/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                val vm: TrackDetailViewModel = viewModel(
                    factory = viewModelFactory { initializer { TrackDetailViewModel(this[APPLICATION_KEY]!!, id) } },
                )
                TrackDetailScreen(vm, onDeleted = { nav.popBackStack() })
            }
        }
    }
}
