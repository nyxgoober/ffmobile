package us.crafties.ffmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import us.crafties.ffmobile.permissions.StoragePermissionHelper
import us.crafties.ffmobile.ui.JobViewModel
import us.crafties.ffmobile.ui.screens.*
import us.crafties.ffmobile.ui.theme.FFMobileTheme

sealed class Dest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Convert : Dest("convert", "Convert", Icons.Filled.Sync)
    object Probe : Dest("probe", "Probe", Icons.Filled.Info)
    object Filters : Dest("filters", "Filters", Icons.Filled.Tune)
    object Concat : Dest("concat", "Concat", Icons.AutoMirrored.Filled.PlaylistAdd)
    object Advanced : Dest("advanced", "Advanced", Icons.Filled.Terminal)
    object Settings : Dest("settings", "Settings", Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {

    private val jobViewModel: JobViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as FFMobileApp
            val advancedEnabled by app.preferences.advancedModeEnabled.collectAsState(initial = false)
            val dynamicColor by app.preferences.dynamicColorEnabled.collectAsState(initial = true)

            FFMobileTheme(dynamicColor = dynamicColor) {
                var hasAccess by remember { mutableStateOf(StoragePermissionHelper.hasAllFilesAccess(this)) }

                // Re-check permission state whenever the user returns to the app, e.g. after
                // granting "All files access" in system Settings.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasAccess = StoragePermissionHelper.hasAllFilesAccess(this@MainActivity)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AnimatedContent(targetState = hasAccess, label = "gate") { granted ->
                        if (granted) {
                            AppScaffold(jobViewModel = jobViewModel, advancedEnabled = advancedEnabled)
                        } else {
                            PermissionGateScreen(onGrant = { StoragePermissionHelper.requestAllFilesAccess(this@MainActivity) })
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun PermissionGateScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        AnimatedVisibility(visible = visible, enter = fadeIn() + scaleIn(initialScale = 0.8f)) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Full storage access needed", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "ffmobile browses and converts files directly on your device's storage, " +
                "so it needs the \"All files access\" permission instead of picking files one at a time.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
            Text("Grant access")
        }
    }
}

@Composable
private fun AppScaffold(jobViewModel: JobViewModel, advancedEnabled: Boolean) {
    val navController = rememberNavController()
    val items = buildList {
        add(Dest.Convert); add(Dest.Probe); add(Dest.Filters); add(Dest.Concat)
        if (advancedEnabled) add(Dest.Advanced)
        add(Dest.Settings)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                items.forEach { dest ->
                    val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { padding ->
        // Bottom-nav tab switches slide left/right based on each tab's
        // position in the nav bar, so moving to a tab further right slides
        // the new screen in from the right (and the reverse). Kept to a
        // single translation with no fade layered on top -- a previous pass
        // used slide+fade together and that was visibly laggy on tab
        // switches, so this stays to one animated property only.
        //
        // Direction is computed from a route->index lookup captured once
        // per navigation event via targetState, rather than mutating shared
        // state inside the transition lambdas -- enterTransition and
        // exitTransition aren't guaranteed to run in a particular order
        // relative to each other, so a shared "lastIndex" var read/written
        // from both would be fragile.
        fun indexOf(route: String?) = items.indexOfFirst { it.route == route }.let { if (it == -1) 0 else it }

        NavHost(
            navController = navController,
            startDestination = Dest.Convert.route,
            modifier = Modifier.padding(padding),
            enterTransition = {
                val forward = indexOf(targetState.destination.route) >= indexOf(initialState.destination.route)
                slideInHorizontally(tween(200)) { width -> if (forward) width else -width }
            },
            exitTransition = {
                val forward = indexOf(targetState.destination.route) >= indexOf(initialState.destination.route)
                slideOutHorizontally(tween(200)) { width -> if (forward) -width else width }
            },
            popEnterTransition = { slideInHorizontally(tween(200)) { width -> -width } },
            popExitTransition = { slideOutHorizontally(tween(200)) { width -> width } },
        ) {
            composable(Dest.Convert.route) { ConvertScreen(jobViewModel) }
            composable(Dest.Probe.route) { ProbeScreen(jobViewModel) }
            composable(Dest.Filters.route) { FiltersScreen(jobViewModel) }
            composable(Dest.Concat.route) { ConcatScreen(jobViewModel) }
            composable(Dest.Advanced.route) { AdvancedScreen(jobViewModel) }
            composable(Dest.Settings.route) { SettingsScreen() }
        }
    }
}
