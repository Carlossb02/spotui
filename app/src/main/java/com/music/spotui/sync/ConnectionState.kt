package com.music.spotui.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ConnectionState {
    private val mutable=MutableStateFlow(true)
    val online=mutable.asStateFlow()
    private var installed=false
    @Synchronized fun install(context:Context) {
        if(installed)return
        val manager=context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun update() {
            val c=manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            mutable.value=c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        update()
        runCatching { manager.registerDefaultNetworkCallback(object:ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network:Network)=update()
            override fun onLost(network:Network)=update()
            override fun onCapabilitiesChanged(network:Network, capabilities:NetworkCapabilities)=update()
        });installed=true }
    }
}
