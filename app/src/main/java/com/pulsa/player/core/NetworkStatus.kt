package com.pulsa.player.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Item 291 — a rede atual é medida (isto é, cobra os dados do usuário)?
 *
 * Wi-Fi e ethernet são "não medidos"; 4G/5G e afins são medidos. Sem rede ativa devolve
 * `false`: não há o que economizar, e um `true` errado aqui só serviria para rebaixar a
 * qualidade de um vídeo local, que nem passa pela rede.
 */
object NetworkStatus {

    fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
