package com.notrace.messenger.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.notrace.messenger.AppContainer
import com.notrace.messenger.crypto.ui.ChatScreen
import com.notrace.messenger.group.ui.GroupChatScreen
import com.notrace.messenger.group.ui.GroupsListScreen
import com.notrace.messenger.identity.ui.ContactsScreen
import com.notrace.messenger.identity.ui.IdentitySetupScreen
import com.notrace.messenger.identity.ui.SettingsScreen
import com.notrace.messenger.identity.domain.RandomIdGenerator
import com.notrace.messenger.ui.screens.OnboardingScreen

/**
 * Navigation graph.
 *
 * Onboarding -> IdentitySetup (Phase 3, one-time) -> Contacts (Phase 3,
 * home screen) -> Settings (Phase 3) / Chat (Phase 4, per-contact) /
 * GroupsList -> GroupChat (Phase 10).
 */
sealed class NoTraceDestination(val route: String) {
    data object Onboarding : NoTraceDestination("onboarding")
    data object IdentitySetup : NoTraceDestination("identity_setup")
    data object Contacts : NoTraceDestination("contacts")
    data object Settings : NoTraceDestination("settings")
    data object Chat : NoTraceDestination("chat/{contactRandomId}") {
        fun route(contactRandomId: String) = "chat/$contactRandomId"
    }
    data object GroupsList : NoTraceDestination("groups")
    data object GroupChat : NoTraceDestination("group_chat/{groupId}") {
        fun route(groupId: String) = "group_chat/$groupId"
    }
}

@Composable
fun NoTraceNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = NoTraceDestination.Onboarding.route
    ) {
        composable(NoTraceDestination.Onboarding.route) {
            OnboardingScreen(
                onContinue = {
                    navController.navigate(NoTraceDestination.IdentitySetup.route) {
                        popUpTo(NoTraceDestination.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }
        composable(NoTraceDestination.IdentitySetup.route) {
            IdentitySetupScreen(
                repository = container.identityRepository,
                onContinue = {
                    navController.navigate(NoTraceDestination.Contacts.route) {
                        popUpTo(NoTraceDestination.IdentitySetup.route) { inclusive = true }
                    }
                }
            )
        }
        composable(NoTraceDestination.Contacts.route) {
            ContactsScreen(
                repository = container.contactRepository,
                onOpenSettings = { navController.navigate(NoTraceDestination.Settings.route) },
                onOpenChat = { contactRandomId ->
                    navController.navigate(NoTraceDestination.Chat.route(contactRandomId))
                },
                onOpenGroups = { navController.navigate(NoTraceDestination.GroupsList.route) }
            )
        }
        composable(NoTraceDestination.Settings.route) {
            SettingsScreen(
                repository = container.identityRepository,
                signalingSettingsStore = container.signalingSettingsStore,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = NoTraceDestination.Chat.route,
            arguments = listOf(navArgument("contactRandomId") { type = NavType.StringType })
        ) { backStackEntry ->
            val contactRandomId = backStackEntry.arguments?.getString("contactRandomId").orEmpty()
            val configuredUrl = container.signalingSettingsStore.getServerUrl()
            if (configuredUrl.isNotBlank()) {
                container.signalingClient.updateServerUrl(configuredUrl)
            }
            ChatScreen(
                contactRandomId = contactRandomId,
                contactLabel = RandomIdGenerator.format(contactRandomId),
                repository = container.messagingRepository,
                coordinator = container.p2pSessionCoordinator.takeIf { configuredUrl.isNotBlank() },
                attachmentManager = container.attachmentTransferManager.takeIf { configuredUrl.isNotBlank() },
                callManager = container.callManager.takeIf { configuredUrl.isNotBlank() },
                contactRepository = container.contactRepository,
                destructionManager = container.conversationDestructionManager,
                onBack = { navController.popBackStack() }
            )
        }
        composable(NoTraceDestination.GroupsList.route) {
            GroupsListScreen(
                groupRepository = container.groupRepository,
                contactRepository = container.contactRepository,
                coordinator = container.groupCoordinator,
                onOpenGroup = { groupId -> navController.navigate(NoTraceDestination.GroupChat.route(groupId)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = NoTraceDestination.GroupChat.route,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId").orEmpty()
            GroupChatScreen(
                groupId = groupId,
                ownRandomId = container.ownRandomId,
                groupRepository = container.groupRepository,
                coordinator = container.groupCoordinator,
                destructionManager = container.conversationDestructionManager,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
