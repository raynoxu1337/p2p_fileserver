package com.raynoxu1337.p2pfileserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the server back after a reboot or app update, if it was running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Prefs(context)
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        val update = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if ((boot && p.startOnBoot) || (update && p.wantRunning)) {
            runCatching { ServerService.start(context) }
                .onFailure { ServerState.log("app", "auto-start failed: ${it.message}") }
        }
    }
}
