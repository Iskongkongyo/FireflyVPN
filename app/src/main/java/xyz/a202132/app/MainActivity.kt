package xyz.a202132.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import xyz.a202132.app.ui.components.StartupSplashOverlay
import xyz.a202132.app.ui.components.NodeListScreen
import xyz.a202132.app.ui.dialogs.NetworkToolboxScreen
import xyz.a202132.app.ui.navigation.AppRoute
import xyz.a202132.app.ui.screens.MainScreen
import xyz.a202132.app.ui.screens.LanProxyScreen
import xyz.a202132.app.ui.screens.OtherConfigScreen
import xyz.a202132.app.ui.screens.PerAppProxyScreen
import xyz.a202132.app.ui.screens.QrScannerScreen
import xyz.a202132.app.ui.screens.RuntimeLogScreen
import xyz.a202132.app.ui.screens.AddSubscriptionGroupScreen
import xyz.a202132.app.ui.screens.EditSubscriptionGroupScreen
import xyz.a202132.app.ui.screens.SubscriptionManagementScreen
import xyz.a202132.app.ui.screens.UnlockTestScreen
import xyz.a202132.app.rules.ui.RuleDetailScreen
import xyz.a202132.app.rules.ui.RuleManagementScreen
import xyz.a202132.app.rules.ui.RuleManagementViewModel
import xyz.a202132.app.ui.theme.FireflyVPNTheme
import xyz.a202132.app.viewmodel.MainViewModel
import xyz.a202132.app.viewmodel.NodeImportResult

private const val NODE_LOADING_MESSAGE = "正在后台更新节点，不影响其他操作"

private data class AppSnackbarMessage(
    val text: String,
    val durationMillis: Long
)

private fun appMessageDurationMillis(message: String): Long {
    val readableCharacterCount = message.codePointCount(0, message.length)
    return (4_500L + readableCharacterCount * 140L).coerceIn(6_500L, 16_000L)
}

private fun newGroupMessageDurationMillis(message: String): Long {
    val readableCharacterCount = message.codePointCount(0, message.length)
    return (3_000L + readableCharacterCount * 100L).coerceIn(4_000L, 6_000L)
}

class MainActivity : ComponentActivity() {

    private var pendingVpnAction: (() -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingVpnAction?.invoke()
        } else {
            Toast.makeText(this, "VPN 权限被拒绝", Toast.LENGTH_SHORT).show()
        }
        pendingVpnAction = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        requestNotificationPermission()

        setContent {
            val viewModel: MainViewModel = viewModel()
            val ruleViewModel: RuleManagementViewModel = viewModel()
            val appThemeMode by viewModel.appThemeMode.collectAsState()
            val fireflyAccessBanDialogVisible by
                viewModel.fireflyAccessBanDialogVisible.collectAsState()
            val isLoading by viewModel.isLoading.collectAsState()
            val appSnackbarHostState = remember { SnackbarHostState() }
            val appSnackbarMessageQueue = remember {
                Channel<AppSnackbarMessage>(Channel.UNLIMITED)
            }

            LaunchedEffect(isLoading) {
                if (isLoading) {
                    appSnackbarHostState.showSnackbar(
                        message = NODE_LOADING_MESSAGE,
                        duration = SnackbarDuration.Indefinite
                    )
                } else if (
                    appSnackbarHostState.currentSnackbarData?.visuals?.message == NODE_LOADING_MESSAGE
                ) {
                    appSnackbarHostState.currentSnackbarData?.dismiss()
                }
            }

            LaunchedEffect(viewModel) {
                viewModel.error.filterNotNull().collectLatest { message ->
                    viewModel.clearError()
                    appSnackbarMessageQueue.send(
                        AppSnackbarMessage(message, appMessageDurationMillis(message))
                    )
                }
            }

            LaunchedEffect(viewModel) {
                viewModel.newBuiltInGroupMessages.collect { message ->
                    appSnackbarMessageQueue.send(
                        AppSnackbarMessage(message, newGroupMessageDurationMillis(message))
                    )
                }
            }

            LaunchedEffect(appSnackbarMessageQueue) {
                for (message in appSnackbarMessageQueue) {
                    if (viewModel.isLoading.value) {
                        viewModel.isLoading.filter { loading -> !loading }.first()
                    }
                    withTimeoutOrNull(message.durationMillis) {
                        appSnackbarHostState.showSnackbar(
                            message = message.text,
                            duration = SnackbarDuration.Indefinite
                        )
                    }
                }
            }

            FireflyVPNTheme(themeMode = appThemeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val nodes by viewModel.nodes.collectAsState()
                    val allNodeListNodes by viewModel.allNodeListNodes.collectAsState()
                    val nodeGroups by viewModel.nodeGroups.collectAsState()
                    val selectedNodeId by viewModel.selectedNodeId.collectAsState()
                    val selectedNodeGroupId by viewModel.selectedNodeGroupId.collectAsState()
                    val favoriteSourceNodeIds by viewModel.favoriteSourceNodeIds.collectAsState()
                    val skipFavoriteRemovalConfirmation by
                        viewModel.skipFavoriteRemovalConfirmation.collectAsState()
                    val isTesting by viewModel.isTesting.collectAsState()
                    val testingLabel by viewModel.testingLabel.collectAsState()
                    val startupUpdateCheckCompleted by viewModel.startupUpdateCheckCompleted.collectAsState()
                    val splashDurationSeconds = AppConfig.STARTUP_SPLASH_DURATION_SECONDS.coerceAtLeast(0)
                    var splashCountdownSeconds by remember(splashDurationSeconds) {
                        mutableIntStateOf(splashDurationSeconds)
                    }
                    var splashTimedOut by remember(splashDurationSeconds) {
                        mutableStateOf(splashDurationSeconds == 0)
                    }
                    var splashSkipped by remember { mutableStateOf(false) }
                    val showStartupSplash =
                        splashDurationSeconds > 0 &&
                            !splashSkipped &&
                            !splashTimedOut &&
                            !startupUpdateCheckCompleted

                    LaunchedEffect(splashDurationSeconds) {
                        if (splashDurationSeconds == 0) return@LaunchedEffect
                        splashCountdownSeconds = splashDurationSeconds
                        splashTimedOut = false
                        repeat(splashDurationSeconds) {
                            delay(1000)
                            splashCountdownSeconds = (splashCountdownSeconds - 1).coerceAtLeast(0)
                        }
                        splashTimedOut = true
                    }

                    if (showStartupSplash) {
                        StartupSplashOverlay(
                            countdownSeconds = splashCountdownSeconds,
                            onSkip = { splashSkipped = true }
                        )
                    } else {
                        val navigateTo: (String) -> Unit = { route ->
                            navController.navigate(route) {
                                launchSingleTop = true
                            }
                        }
                        val importNodesToFavorites: (String, Boolean, ((Boolean) -> Unit)?) -> Unit = { text, returnToNodeList, onComplete ->
                            viewModel.importNodesToFavoritesFromText(text) { result, importedCount ->
                                val success = result == NodeImportResult.IMPORTED
                                Toast.makeText(
                                    this@MainActivity,
                                    when (result) {
                                        NodeImportResult.IMPORTED -> "\u5bfc\u5165${importedCount}\u4e2a\u8282\u70b9\u5230\u6536\u85cf\u8282\u70b9"
                                        NodeImportResult.DUPLICATE -> "\u8282\u70b9\u5df2\u5b58\u5728\uff0c\u65e0\u9700\u91cd\u590d\u5bfc\u5165"
                                        NodeImportResult.INVALID -> "\u672a\u627e\u5230\u53ef\u5bfc\u5165\u8282\u70b9"
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                                onComplete?.invoke(success)
                                if (success && returnToNodeList) {
                                    navController.popBackStack(AppRoute.NODE_LIST, false)
                                }
                            }
                        }

                        NavHost(
                            navController = navController,
                            startDestination = AppRoute.MAIN,
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None }
                        ) {
                            composable(AppRoute.MAIN) {
                                MainScreen(
                                    viewModel = viewModel,
                                    onStartVpn = { action -> requestVpnPermission(action) },
                                    onOpenPerAppProxy = { navigateTo(AppRoute.PER_APP_PROXY) },
                                    onOpenSubscriptionManagement = { navigateTo(AppRoute.SUBSCRIPTION_MANAGEMENT) },
                                    onOpenRuleManagement = { navigateTo(AppRoute.RULE_MANAGEMENT) },
                                    onOpenNodeList = { navigateTo(AppRoute.NODE_LIST) },
                                    onOpenNetworkToolbox = { navigateTo(AppRoute.NETWORK_TOOLBOX) },
                                    onOpenUnlockTest = { navigateTo(AppRoute.UNLOCK_TEST) },
                                    onOpenOtherConfig = { navigateTo(AppRoute.OTHER_CONFIG) },
                                    onOpenLanProxy = { navigateTo(AppRoute.LAN_PROXY) },
                                    onOpenRuntimeLog = { navigateTo(AppRoute.RUNTIME_LOG) }
                                )
                            }

                            composable(AppRoute.PER_APP_PROXY) {
                                var isLeavingPerAppProxy by remember { mutableStateOf(false) }

                                Box(modifier = Modifier.fillMaxSize()) {
                                    PerAppProxyScreen(
                                        onBack = { hasChanges ->
                                            if (isLeavingPerAppProxy) return@PerAppProxyScreen
                                            isLeavingPerAppProxy = true
                                            navController.popBackStack()
                                            if (hasChanges) {
                                                viewModel.restartVpnIfNeeded()
                                            }
                                        }
                                    )

                                    if (isLeavingPerAppProxy) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) { }
                                        )
                                    }
                                }
                            }

                            composable(AppRoute.NODE_LIST) {
                                NodeListScreen(
                                    allNodes = allNodeListNodes,
                                    groups = nodeGroups,
                                    selectedNodeId = selectedNodeId,
                                    selectedGroupId = selectedNodeGroupId,
                                    favoriteSourceNodeIds = favoriteSourceNodeIds,
                                    skipFavoriteRemovalConfirmation = skipFavoriteRemovalConfirmation,
                                    isTesting = isTesting,
                                    testingLabel = testingLabel,
                                    onNodeSelected = { node ->
                                        viewModel.selectNode(node)
                                        navController.popBackStack(AppRoute.MAIN, false)
                                    },
                                    onGroupChange = { viewModel.setSelectedNodeGroup(it) },
                                    onToggleFavorite = { viewModel.toggleFavoriteNode(it) },
                                    onSkipFavoriteRemovalConfirmationForSession = {
                                        viewModel.skipFavoriteRemovalConfirmationForSession()
                                    },
                                    onImportFromText = { importNodesToFavorites(it, false, null) },
                                    onScanQrCode = { navigateTo(AppRoute.QR_SCANNER) },
                                    onRefresh = { viewModel.refreshNodesWithDefaultTest() },
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.SUBSCRIPTION_MANAGEMENT) {
                                SubscriptionManagementScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onAddGroup = { navigateTo(AppRoute.SUBSCRIPTION_GROUP_ADD) },
                                    onEditGroup = { navigateTo(AppRoute.editSubscriptionGroup(it)) }
                                )
                            }

                            composable(AppRoute.SUBSCRIPTION_GROUP_ADD) {
                                AddSubscriptionGroupScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(
                                route = AppRoute.SUBSCRIPTION_GROUP_EDIT,
                                arguments = listOf(
                                    navArgument(AppRoute.SUBSCRIPTION_GROUP_EDIT_ARGUMENT) {
                                        type = NavType.StringType
                                    }
                                )
                            ) { backStackEntry ->
                                EditSubscriptionGroupScreen(
                                    viewModel = viewModel,
                                    groupId = backStackEntry.arguments
                                        ?.getString(AppRoute.SUBSCRIPTION_GROUP_EDIT_ARGUMENT)
                                        .orEmpty(),
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.RULE_MANAGEMENT) {
                                RuleManagementScreen(
                                    viewModel = ruleViewModel,
                                    onBack = { navController.popBackStack() },
                                    onRuleClick = { navigateTo(AppRoute.ruleDetail(it)) }
                                )
                            }

                            composable(
                                route = AppRoute.RULE_DETAIL,
                                arguments = listOf(
                                    navArgument(AppRoute.RULE_DETAIL_ARGUMENT) {
                                        type = NavType.StringType
                                    }
                                )
                            ) { backStackEntry ->
                                RuleDetailScreen(
                                    viewModel = ruleViewModel,
                                    ruleId = backStackEntry.arguments
                                        ?.getString(AppRoute.RULE_DETAIL_ARGUMENT)
                                        .orEmpty(),
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.QR_SCANNER) {
                                QrScannerScreen(
                                    onBack = { navController.popBackStack() },
                                    onQrCodeScanned = { text, onComplete ->
                                        importNodesToFavorites(text, true, onComplete)
                                    }
                                )
                            }

                            composable(AppRoute.NETWORK_TOOLBOX) {
                                NetworkToolboxScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.UNLOCK_TEST) {
                                UnlockTestScreen(
                                    visibleNodes = nodes,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.OTHER_CONFIG) {
                                OtherConfigScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.LAN_PROXY) {
                                LanProxyScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(AppRoute.RUNTIME_LOG) {
                                RuntimeLogScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }

                    if (fireflyAccessBanDialogVisible) {
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("访问受限") },
                            text = {
                                Text("抱歉，您已被管理员封禁！当前无法获取节点等信息，软件其他功能则不受影响！")
                            },
                            confirmButton = {
                                TextButton(onClick = viewModel::retryFireflyAccessAfterBan) {
                                    Text("重新检测")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = viewModel::dismissFireflyAccessBanDialog) {
                                    Text("关闭")
                                }
                            },
                            properties = DialogProperties(
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false
                            )
                        )
                    }

                    SnackbarHost(
                        hostState = appSnackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface
                        )
                    }
                    }
                }
            }
        }
    }

    private fun requestVpnPermission(action: () -> Unit) {
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            pendingVpnAction = action
            vpnPermissionLauncher.launch(intent)
        } else {
            action()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
    }
}
