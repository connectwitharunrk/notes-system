package com.arunrk.note.feature.auth.di

import com.arunrk.note.feature.auth.forgotpassword.ForgotPasswordViewModel
import com.arunrk.note.feature.auth.login.LoginViewModel
import com.arunrk.note.feature.auth.register.RegisterViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * ViewModels are scoped per screen, never singletons: a shared LoginViewModel
 * would still hold the previous attempt's typed password and error state the
 * next time the screen opened.
 */
val authFeatureModule: Module = module {
    viewModelOf(::LoginViewModel)
    viewModelOf(::RegisterViewModel)
    viewModelOf(::ForgotPasswordViewModel)
}
