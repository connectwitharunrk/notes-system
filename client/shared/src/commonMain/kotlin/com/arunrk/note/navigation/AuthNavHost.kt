package com.arunrk.note.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.arunrk.note.core.common.navigation.ForgotPasswordRoute
import com.arunrk.note.core.common.navigation.LoginRoute
import com.arunrk.note.core.common.navigation.RegisterRoute
import com.arunrk.note.feature.auth.forgotpassword.ForgotPasswordScreen
import com.arunrk.note.feature.auth.login.LoginScreen
import com.arunrk.note.feature.auth.register.RegisterScreen

/**
 * The signed-out graph.
 *
 * Note what is absent: no "clear the back stack and navigate to notes" on a
 * successful sign-in. The root swaps this whole NavHost for the main one as
 * soon as the session becomes authenticated, so there is no partially-cleared
 * stack to get wrong and no window in which a back press returns to a login
 * form for an account that is already signed in.
 */
@Composable
fun AuthNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = LoginRoute,
        modifier = modifier,
    ) {
        composable<LoginRoute> {
            LoginScreen(
                // Handled by the root swapping graphs when the session changes.
                onNavigateToNotes = {},
                onNavigateToRegister = { navController.navigate(RegisterRoute) },
                onNavigateToForgotPassword = { navController.navigate(ForgotPasswordRoute) },
            )
        }

        composable<RegisterRoute> {
            RegisterScreen(
                onNavigateToNotes = {},
                onNavigateToLogin = { navController.popBackStack() },
            )
        }

        composable<ForgotPasswordRoute> {
            ForgotPasswordScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
