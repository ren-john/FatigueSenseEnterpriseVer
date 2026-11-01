package com.example.fatiguesense.enterprise

import android.app.Application
import com.example.fatiguesense.enterprise.enterprise.AppModes

class FatigueSenseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppModes.initialize(this)
    }
}
