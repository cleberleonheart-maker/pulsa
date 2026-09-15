package com.pulsa.player.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

object ThreadPool {
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun post(block: () -> Unit) {
        executor.execute(block)
    }

    fun onUi(block: () -> Unit) {
        main.post(block)
    }
}