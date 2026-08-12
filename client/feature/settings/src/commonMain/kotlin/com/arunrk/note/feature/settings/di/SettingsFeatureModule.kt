package com.arunrk.note.feature.settings.di

import com.arunrk.note.feature.settings.SettingsViewModel
import com.arunrk.note.feature.settings.password.ChangePasswordViewModel
import com.arunrk.note.feature.settings.profile.ProfileViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingsFeatureModule: Module = module {
    viewModelOf(::SettingsViewModel)
    viewModelOf(::ProfileViewModel)
    viewModelOf(::ChangePasswordViewModel)
}
