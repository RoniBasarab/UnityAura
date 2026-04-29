package com.unityaura

import android.app.Application
import com.unityaura.di.ApplicationInjector
import com.unityaura.di.IApplicationInjector

class UnityAuraApplication : Application() {

    companion object {
        lateinit var injector: IApplicationInjector
            internal set
    }

    override fun onCreate() {
        super.onCreate()
        injector = ApplicationInjector(applicationContext)
    }
}
