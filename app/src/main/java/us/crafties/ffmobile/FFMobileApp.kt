package us.crafties.ffmobile

import android.app.Application
import us.crafties.ffmobile.util.PreferencesManager

class FFMobileApp : Application() {
    lateinit var preferences: PreferencesManager
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = PreferencesManager(this)
    }
}
