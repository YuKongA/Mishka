package top.yukonga.mishka

import android.app.Application
import top.yukonga.mishka.service.NotificationHelper

class MishkaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.createChannels(this)
    }

    companion object {
        lateinit var instance: MishkaApplication
            private set
    }
}
