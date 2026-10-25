package com.sudeekshith.shadow.system

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.sudeekshith.shadow.core.AppMatcher
import com.sudeekshith.shadow.core.Command
import com.sudeekshith.shadow.data.SettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Performs [Command]s with standard Android intents and returns a short spoken confirmation. */
class ActionExecutor(context: Context, private val settings: SettingsStore) {
    private val ctx = context.applicationContext

    /** Permissions a command needs before it can run. */
    fun permissionsFor(cmd: Command): List<String> = when (cmd) {
        is Command.Call -> buildList {
            if (!looksLikeNumber(cmd.who)) add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
        }
        is Command.WhatsApp -> if (looksLikeNumber(cmd.who)) emptyList() else listOf(Manifest.permission.READ_CONTACTS)
        else -> emptyList()
    }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

    fun execute(cmd: Command): String = try {
        when (cmd) {
            is Command.OpenApp -> openApp(cmd.name)
            is Command.SetAlarm -> {
                start(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, cmd.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute)
                    cmd.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                })
                val t = String.format(Locale.US, "%d:%02d %s", if (cmd.hour % 12 == 0) 12 else cmd.hour % 12, cmd.minute, if (cmd.hour < 12) "AM" else "PM")
                if (cmd.label != null) "Done. I'll remind you at $t to ${cmd.label}." else "Alarm set for $t."
            }
            is Command.SetTimer -> {
                start(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds)
                    cmd.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                })
                val d = durationWords(cmd.seconds)
                if (cmd.label != null) "Okay, I'll remind you in $d to ${cmd.label}." else "Timer started for $d."
            }
            is Command.Call -> call(cmd.who)
            is Command.WhatsApp -> whatsapp(cmd.who, cmd.message)
            is Command.WebSearch -> {
                start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(cmd.query))))
                "Searching for ${cmd.query}."
            }
            is Command.PlayYouTube -> playYouTube(cmd.query)
            is Command.Flashlight -> torch(cmd.on)
            Command.Battery -> {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                "Battery is at $level percent" + if (charging) " and charging." else "."
            }
            Command.Time -> "It's " + SimpleDateFormat("h:mm a", Locale.US).format(Date()) + "."
            Command.Date -> "Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US).format(Date()) + "."
            Command.Greeting -> "Hey ${settings.userName}, I'm listening."
            Command.StopConversation -> "Alright boss, I'll be here when you need me."
            Command.OpenSettings -> "Opening my settings."
        }
    } catch (e: ActivityNotFoundException) {
        "Sorry, I couldn't find an app on this phone that can do that."
    } catch (e: SecurityException) {
        "I need permission for that. Please allow it and ask me again."
    }

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun openApp(raw: String): String {
        val name = raw.lowercase(Locale.ROOT).removePrefix("the ").trim()
        when (name) {
            "camera" -> { start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); return "Opening camera." }
            "settings", "phone settings" -> { start(Intent(Settings.ACTION_SETTINGS)); return "Opening settings." }
            "dialer", "phone", "dial pad", "dialpad" -> { start(Intent(Intent.ACTION_DIAL)); return "Opening the dialer." }
            "wifi settings", "wi-fi settings", "wifi" -> { start(Intent(Settings.ACTION_WIFI_SETTINGS)); return "Opening Wi-Fi settings." }
            "bluetooth", "bluetooth settings" -> { start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return "Opening Bluetooth settings." }
        }
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val best = AppMatcher.best(name, apps.map { it.first })
            ?: return "I couldn't find an app called $raw on your phone."
        val pkg = apps.first { it.first == best }.second
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return "I found $best but couldn't open it."
        start(launch)
        return "Opening $best."
    }

    private fun call(who: String): String {
        val (name, number) = resolve(who) ?: return "I couldn't find $who in your contacts."
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        start(Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        return if (granted) "Calling $name." else "Here's $name's number, tap call."
    }

    private fun whatsapp(who: String, message: String): String {
        val (name, number) = resolve(who) ?: return "I couldn't find $who in your contacts."
        var digits = number.filter { it.isDigit() }
        if (number.trim().startsWith("+")) { /* already international */ }
        else if (digits.startsWith("0")) digits = settings.countryCode + digits.trimStart('0')
        else if (digits.length <= 10) digits = settings.countryCode + digits
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=" + Uri.encode(message))
        val i = Intent(Intent.ACTION_VIEW, uri)
        val wa = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(it) }
        if (wa != null) i.setPackage(wa)
        start(i)
        return "WhatsApp is ready with your message to $name. Just tap send."
    }

    private fun playYouTube(q: String): String {
        val i = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
        try { start(i) } catch (e: ActivityNotFoundException) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        }
        return "Playing $q on YouTube."
    }

    private fun torch(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone doesn't have a flashlight I can use."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun isInstalled(pkg: String) = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private fun looksLikeNumber(s: String) = s.count { it.isDigit() } >= 6 && s.all { it.isDigit() || it in "+ -()" }

    /** Returns (display name, phone number) for a contact name or a raw number. */
    private fun resolve(who: String): Pair<String, String>? {
        if (looksLikeNumber(who)) return who to who
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val target = who.lowercase(Locale.ROOT).trim()
        val found = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                found += n to num
            }
        }
        val best = AppMatcher.best(target, found.map { it.first }.distinct()) ?: return null
        return found.first { it.first == best }
    }

    companion object {
        fun durationWords(sec: Int): String {
            val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
            val parts = mutableListOf<String>()
            if (h > 0) parts += "$h hour" + if (h > 1) "s" else ""
            if (m > 0) parts += "$m minute" + if (m > 1) "s" else ""
            if (s > 0) parts += "$s second" + if (s > 1) "s" else ""
            return parts.joinToString(" ").ifBlank { "0 seconds" }
        }
    }
}
