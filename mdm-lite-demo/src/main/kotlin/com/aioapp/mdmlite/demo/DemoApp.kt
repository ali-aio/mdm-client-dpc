package com.aioapp.mdmlite.demo

import android.app.Application
import com.aioapp.mdmlite.AioMdm
import com.aioapp.mdmlite.MdmConfig

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AioMdm.init(this, MdmConfig(
            serverUrl = BuildConfig.MDM_SERVER_URL,
            enrollToken = BuildConfig.MDM_ENROLL_TOKEN,
            checkinSeconds = 30,
        ))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AioMdm.reportTrimMemory(level)
    }
}
