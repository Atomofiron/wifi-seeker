package ru.raslav.wirelessscan

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.*
import android.net.wifi.WifiManager
import android.os.*
import ru.raslav.wirelessscan.utils.Point
import ru.raslav.wirelessscan.connection.Connection.WHAT.*
import android.app.PendingIntent
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ru.raslav.wirelessscan.utils.OuiManager
import kotlin.collections.ArrayList

@Suppress("DEPRECATION") // I don't care
class ScanService : IntentService("ScanService") {
    companion object {
        private const val ACTION_PAUSE = "ACTION_PAUSE"
        private const val ACTION_RESUME = "ACTION_RESUME"
        private const val ACTION_ALLOW = "ACTION_ALLOW"
        private const val ACTION_TURN_WIFI_ON = "ACTION_TURN_WIFI_ON"

        private const val EXTRA_ID = "EXTRA_ID"
        private const val EXTRA_POINT = "EXTRA_POINT"

        private const val SECOND = 1000L
        private const val SCAN_DELAY_OFFSET = 2
        private const val SCAN_DELAY = SECOND * SCAN_DELAY_OFFSET
        private const val WIFI_WAITING_PERIOD = 300L

        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL = "channel_wtf"

        private var boundCount = 0
        fun connected() = boundCount++
        fun disconnected() = boundCount--
    }
    private lateinit var mainPendingIntent: PendingIntent
    private lateinit var receiver: BroadcastReceiver
    private lateinit var wifiManager: WifiManager
    private lateinit var commandMessenger: Messenger
    private val sp: SharedPreferences by unsafeLazy { sp() }
    private lateinit var ouiManager: OuiManager
    private lateinit var notificationManager: NotificationManager
    private var resultMessenger: Messenger? = null
    private val points = ArrayList<Point>()
    private var trustedPoints: ArrayList<Point> = ArrayList()
    private var isStartedForeground = false
    private var lastBoundCount = 0
    private var delay = 10
    private var process = false
    private var code = 1

    override fun onCreate() {
        report("ScanService: onCreate()")
        super.onCreate()

        val filter = IntentFilter()
        filter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION)
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) = detectAttacksIfNeeded()
        }, filter)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        commandMessenger = Messenger(@SuppressLint("HandlerLeak")
        object : Handler() {
            override fun handleMessage(msg: Message) {
                super.handleMessage(msg)
                this@ScanService.handleMessage(msg)
            }
        })

        ouiManager = OuiManager(baseContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mainPendingIntent = PendingIntent.getActivity(
            baseContext,
            code++,
            Intent(baseContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            notificationManager.createNotificationChannel(NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "channelName",
                    NotificationManager.IMPORTANCE_LOW
            ))
    }

    override fun onDestroy() {
        super.onDestroy()
        report("ScanService: onDestroy()")
        unregisterReceiver(receiver)
        ouiManager.close()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
            if (isNotificationAction(intent) || process)
                START_NOT_STICKY
            else
                super.onStartCommand(intent, flags, startId)

    override fun onHandleIntent(intent: Intent?) {
        report("ScanService: onHandleIntent()")
        showNotification(true)
        sendStarted()

        process = true
        while (process)
            scan()
    }

    override fun onBind(intent: Intent?): IBinder = commandMessenger.binder

    private fun isNotificationAction(intent: Intent?): Boolean {
        when (intent?.action) {
            ACTION_PAUSE -> stop() // немного не соответствует, но так надо, потому что сервис не знает что такое пауза и как продолжить
            ACTION_RESUME -> startService(Intent(applicationContext, ScanService::class.java))
            ACTION_TURN_WIFI_ON -> {
                wifiManager.isWifiEnabled = true
                notificationManager.cancel(intent.getIntExtra(EXTRA_ID, 0))
            }
            ACTION_ALLOW -> {
                trustedPoints.add(intent.getParcelableExtra(EXTRA_POINT)!!)

                wifiManager.isWifiEnabled = true
                notificationManager.cancel(intent.getIntExtra(EXTRA_ID, 0))
            }
            else -> return false
        }
        return true
    }

    private fun scan() {
        report("scan()")

        if (!waitForWifi())
            return

        sendStartScan()
        wifiManager.startScan()
        sleepAndUpdateNotificationIfNeeded(SCAN_DELAY)

        if (waitForWifi()) {
            updatePoints()
            sendResults()
            detectAttacksIfNeeded()
        }

        var i = SCAN_DELAY_OFFSET
        while ((i++ < delay || scanningIsNotRequired()) && process)
            sleepAndUpdateNotificationIfNeeded(SECOND)
    }

    private fun scanningIsNotRequired(): Boolean =
            sp.getBoolean(Const.PREF_AUTO_OFF_WIFI, false) && sp.getBoolean(Const.PREF_NO_SCAN_IN_BG, false) && boundCount <= 1

    /** @return process */
    private fun waitForWifi(): Boolean {
        while (!wifiManager.isWifiEnabled || scanningIsNotRequired()) {
            sleepAndUpdateNotificationIfNeeded(WIFI_WAITING_PERIOD)

            if (!process)
                return false
        }
        return process
    }

    private fun sleepAndUpdateNotificationIfNeeded(millis: Long) {
        updateNotificationIfNeeded()
        Thread.sleep(millis)
        updateNotificationIfNeeded()
    }

    private fun updateNotificationIfNeeded() {
        if (isStartedForeground && (lastBoundCount <= 1 && boundCount > 1 || lastBoundCount > 1 && boundCount <=1))
            showNotification(true)

        lastBoundCount = boundCount
    }

    private fun stop() {
        process = false
        sendStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        showNotification(false)
    }

    @SuppressLint("MissingPermission") // ask permission before, on button click
    private fun updatePoints() {
        val currentPoints = Point.parseScanResults(wifiManager.scanResults)

        currentPoints.forEach { it.manufacturer = ouiManager.find(it.bssid) }

        points.removeAll(currentPoints)
        points.forEach { it.level = Point.MIN_LEVEL }

        points.addAll(currentPoints)
        points.sortWith { o1, o2 -> o2.level - o1.level }
    }

    private fun newMessage(what: Int): Message {
        val message = Message()
        message.what = what
        return message
    }

    private fun sendStartScan() = resultMessenger?.send(newMessage(START_SCAN.ordinal))

    private fun sendStarted() = resultMessenger?.send(newMessage(STARTED.ordinal))

    private fun sendStopped() = resultMessenger?.send(newMessage(STOPPED.ordinal))

    private fun sendResults() {
        val message = newMessage(RESULTS.ordinal)
        message.arg1 = process.toInt()
        message.obj = points
        resultMessenger?.send(message)
    }

    fun handleMessage(msg: Message?) {
        report("CH: what: ${msg?.what}")
        if (msg != null) {
            resultMessenger = msg.replyTo ?: resultMessenger

            when (msg.what) {
                GET.ordinal -> sendResults()
                CLEAR.ordinal-> points.clear()
                STOP.ordinal -> stop()
                DELAY.ordinal -> delay = msg.arg1
            }
        }
    }

    private fun detectAttacksIfNeeded() {
        if (!sp.getBoolean(Const.PREF_DETECT_ATTACKS, false))
            return

        val bssid = wifiManager.connectionInfo.bssid ?: ""
        var essid = wifiManager.connectionInfo.ssid
        val hidden = wifiManager.connectionInfo.hiddenSSID
        essid = essid.substring(1, essid.length - 1) // necessary

        // todo replace ArrayList with MutableList
        val extras = ArrayList<String>(sp.getString(Const.PREF_EXTRAS, "")!!.split("\n"))
        val current = points.find { it.compare(bssid, essid, hidden) }
        if (current != null && !extras.contains(current.essid)) {
            val smart = sp.getBoolean(Const.PREF_SMART_DETECTION, false)

            if (sp.getBoolean(Const.PREF_AUTO_OFF_WIFI, false) && !trustedPoints.contains(current)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {

                if (trustedPoints.find { it.isSimilar(current, smart) } != null) {
                    wifiManager.isWifiEnabled = false
                    request(current)
                } else
                    trustedPoints.add(current)
            }

            points.filter {
                it.level > Point.MIN_LEVEL
                        && it.isSimilar(current, smart)
                        && !trustedPoints.contains(it)
            }.forEach { warning(it) }
        }
    }

    private fun showNotification(foreground: Boolean) {
        isStartedForeground = foreground

        val co = applicationContext
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			Notification.Builder(co, NOTIFICATION_CHANNEL)
		} else {
			Notification.Builder(co)
		}
        builder.setContentText(getString(R.string.touch_to_look))
                .setContentIntent(mainPendingIntent)
                .setSmallIcon(R.drawable.ws)
                .setContentTitle(getString(
                        if (foreground)
                            if (scanningIsNotRequired())
                                R.string.scanning_battery_save
                            else
                                R.string.scanning
                        else
                            R.string.scanning_was_paused
                ))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            builder.setLargeIcon(Icon.createWithResource(co, R.mipmap.ic_launcher))

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
            builder.addAction(
                    if (foreground) R.drawable.ic_pause else R.drawable.ic_resume,
                    getString(if (foreground) R.string.pause else R.string.resume),
                    PendingIntent.getService(
                            co, code++,
                            Intent(co, ScanService::class.java)
                                    .setAction(if (foreground) ACTION_PAUSE else ACTION_RESUME),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
            ).build()
        else
            builder.notification

        if (foreground) // todo request notification permission
            ServiceCompat.startForeground(this, FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun warning(point: Point) {
        val co = applicationContext
        val id = point.bssid.hashCode()

        val builder = NotificationCompat.Builder(co, NOTIFICATION_CHANNEL)
		builder.setTicker(getString(R.string.clone_detected))
                .setContentTitle(getString(R.string.clone_detected))
                .setContentText("${point.manufacturer} - ${point.bssid}")
                .setContentIntent(mainPendingIntent)
                .setSmallIcon(R.drawable.ws_yellow)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            builder.setLargeIcon(Icon.createWithResource(co, R.mipmap.ic_launcher))

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.addAction(
                    R.drawable.ic_check,
                    getString(R.string.allow_network),
                    PendingIntent.getService(co, code++,
                            Intent(co, ScanService::class.java)
                                    .setAction(ACTION_ALLOW)
                                    .putExtra(EXTRA_ID, id)
                                    .putExtra(EXTRA_POINT, point),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
            ).build()
        } else
            builder.notification

        notificationManager.notify(id, notification)
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun request(point: Point) {
        val co = applicationContext
        val id = point.bssid.hashCode() + 1

        val builder = NotificationCompat.Builder(co, NOTIFICATION_CHANNEL)
		builder.setTicker(getString(R.string.clone_detected))
                .setContentTitle(getString(R.string.wifi_was_disabled))
                .setContentText("${point.manufacturer} - ${point.bssid}")
                .setContentIntent(mainPendingIntent)
                .setSmallIcon(R.drawable.ws_red)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            builder.setLargeIcon(Icon.createWithResource(co, R.mipmap.ic_launcher))

        val notification = builder
                .addAction(
                        R.drawable.ic_check,
                        getString(R.string.allow_network),
                        PendingIntent.getService(co, code++,
                                Intent(co, ScanService::class.java)
                                        .setAction(ACTION_ALLOW)
                                        .putExtra(EXTRA_ID, id)
                                        .putExtra(EXTRA_POINT, point),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                ).addAction(
                R.drawable.ic_wifi,
                        getString(R.string.turn_wifi_on),
                        PendingIntent.getService(co, code++,
                                Intent(co, ScanService::class.java)
                                        .setAction(ACTION_TURN_WIFI_ON)
                                        .putExtra(EXTRA_ID, id),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                ).build()

        notificationManager.notify(id, notification)
    }
}