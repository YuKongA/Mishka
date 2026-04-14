package top.yukonga.mishka

import android.app.Application

class MishkaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: MishkaApplication
            private set
    }
}
