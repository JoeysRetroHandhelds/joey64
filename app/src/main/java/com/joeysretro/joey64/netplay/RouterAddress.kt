package com.joeysretro.joey64.netplay

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/**
 * The router's address for a hosted room's NAT-PMP (upnp.cpp reads N64_NETPLAY_GW): the network's default route,
 * since Android's main route table that libnatpmp reads has none. Set before the room asks the router for its port,
 * both on the room screen (PlayTogether.host) and when a game starts (MainActivity); `--es env N64_NETPLAY_GW=<ip>`
 * wins there.
 */
object RouterAddress {
    fun set(context: Context) {
        runCatching {
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            val gw = cm?.getLinkProperties(cm.activeNetwork)?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            if (gw != null) android.system.Os.setenv("N64_NETPLAY_GW", gw, true) else android.system.Os.unsetenv("N64_NETPLAY_GW")
        }
    }
}
