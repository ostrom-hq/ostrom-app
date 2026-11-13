// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.android

import android.app.Application
import run.ostrom.app.di.initKoin
import run.ostrom.app.di.platformModule

class OstromApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(platformModule(applicationContext))
    }
}
