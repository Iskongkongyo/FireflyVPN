package xyz.a202132.app

import android.app.Application

class VpnApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
    
    companion object {
        lateinit var instance: VpnApplication
            private set
    }
}
