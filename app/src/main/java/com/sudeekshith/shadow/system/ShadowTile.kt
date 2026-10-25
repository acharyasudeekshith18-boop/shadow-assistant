package com.sudeekshith.shadow.system

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.sudeekshith.shadow.MainActivity

/** Quick Settings tile: pull down the notification shade and tap "Hey Shadow" to talk. */
class ShadowTile : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
