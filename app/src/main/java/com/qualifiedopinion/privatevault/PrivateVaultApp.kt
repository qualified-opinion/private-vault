package com.qualifiedopinion.privatevault

import android.app.Application

class PrivateVaultApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
