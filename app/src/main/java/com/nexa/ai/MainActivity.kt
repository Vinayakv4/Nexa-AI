package com.nexa.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nexa.ai.ui.screens.ChatScreen
import com.nexa.ai.ui.screens.SettingsScreen
import com.nexa.ai.ui.screens.Sidebar
import com.nexa.ai.ui.theme.NexaTheme
import com.nexa.ai.vm.ChatViewModel
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexaTheme {
                val viewModel: ChatViewModel = viewModel()
                val settings by viewModel.settings.collectAsState()
                val navController = rememberNavController()
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val backStack by navController.currentBackStackEntryAsState()
                val route = backStack?.destination?.route ?: "chat"
                val uiState by viewModel.ui.collectAsState()

                if (!settings.isConfigured) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        SettingsScreen(viewModel = viewModel, onBack = {
                            if (settings.isConfigured) navController.navigate("chat") { popUpTo(0) }
                        })
                    }
                } else {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet {
                                Sidebar(
                                    viewModel = viewModel,
                                    currentConversationId = uiState.conversationId,
                                    workMode = settings.workModeEnabled && settings.hasWorkspace,
                                    workspaceLabel = if (settings.workspaceUri.isNotBlank())
                                        android.net.Uri.parse(settings.workspaceUri).lastPathSegment?.substringAfterLast(':')
                                    else null,
                                    onNavigate = { scope.launch { drawerState.close() } },
                                    onOpenSettings = { navController.navigate("settings") },
                                    onOpenTerminal = { navController.navigate("terminal") }
                                )
                            }
                        }
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = "chat"
                        ) {
                            composable("chat") {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    ChatScreen(
                                        viewModel = viewModel,
                                        onOpenSidebar = { scope.launch { drawerState.open() } },
                                        onOpenSettings = { navController.navigate("settings") }
                                    )
                                }
                            }
                            composable("settings") {
                                SettingsScreen(viewModel = viewModel, onBack = {
                                    navController.popBackStack()
                                })
                            }
                            composable("terminal") {
                                com.nexa.ai.ui.screens.TerminalScreen(onBack = {
                                    navController.popBackStack()
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}
