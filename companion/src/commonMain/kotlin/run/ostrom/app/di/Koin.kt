// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../di/Koin.kt; see NOTICE.
package run.ostrom.app.di

import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import run.ostrom.app.demo.demoModule

/** All app modules, given the platform module (credential store, HTTP engine, preferences, platform details). */
fun appModules(platformModule: Module): List<Module> =
    listOf(infrastructureModule, domainModule, presentationModule, waitsModule, spendingModule, notificationsModule, artifactsModule, demoModule, approvalsModule, platformModule)

/** Starts Koin once at launch; each entry point (Android application, iOS view controller, desktop main) calls it. */
fun initKoin(platformModule: Module, appDeclaration: KoinAppDeclaration = {}): KoinApplication =
    startKoin {
        appDeclaration()
        modules(appModules(platformModule))
    }
