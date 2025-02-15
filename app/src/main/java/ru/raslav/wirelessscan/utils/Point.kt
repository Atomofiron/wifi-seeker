package ru.raslav.wirelessscan.utils

import android.content.Context
import android.graphics.Color
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import android.os.Parcel
import android.os.Parcelable
import androidx.core.content.ContextCompat
import org.simpleframework.xml.Element
import org.simpleframework.xml.Root
import ru.raslav.wirelessscan.R

@Root(name = "point")
class Point private constructor(): Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(level)
        dest.writeInt(frequency)
        dest.writeString(capabilities)
        dest.writeString(essid)
        dest.writeString(bssid)
        dest.writeInt(ch)
        dest.writeString(manufacturer)
        dest.writeString(manufacturerDesc)
    }

    override fun describeContents(): Int = 0

    @get:Element(name = "level")
    @set:Element(name = "level")
    var level = 0
        set(value) { field = value; pwColor = getPowerColor(value)
        }
    @get:Element(name = "frequency")
    @set:Element(name = "frequency")
    var frequency = 0
        set(value) { field = value; parseFrequency(value) }
    @get:Element(name = "capabilities")
    @set:Element(name = "capabilities")
    var capabilities = ""
        set(value) { field = value; parseCapabilities(value) }
    @field:Element(name = "essid", required = false) // empty values couldn't be required (WTF)
    var essid = ""
    @field:Element(name = "bssid")
    var bssid = ""
    var hex = ""

    @field:Element(name = "channel")
    var ch = 0
    lateinit var enc: String
        private set
    lateinit var chi: String
        private set
    lateinit var wps: String
        private set
    @field:Element(name = "manufacturer", required = false)
    var manufacturer = ""
    @field:Element(name = "manufacturerDesc", required = false)
    var manufacturerDesc = ""

    // todo move this into adapter/holder
    var pwColor = 0
        private set
    var chColor = 0
        private set
    var encColor = 0
        private set
    var chiColor = 0
        private set
    var wpsColor = 0
        private set

    constructor(sr: ScanResult) : this() {
        level = sr.level
        frequency = sr.frequency
        ch = getChanel(frequency)
        capabilities = sr.capabilities
        essid = sr.SSID
        bssid = sr.BSSID
    }

    private fun is5G(): Boolean = frequency >= 4915

    private fun parseFrequency(frequency: Int) {
        ch = getChanel(frequency)
        chColor = if (is5G()) blue_light else grey
    }

    private fun parseCapabilities(capabilities: String) {
        specifyEnc(capabilities)
        specifyChi(capabilities)
        specifyWps(capabilities)
    }

    private fun specifyEnc(capabilities: String) {
        enc = "OPN"
        encColor = green
        if (capabilities.contains("WPA")) {
            enc = if (capabilities.contains("WPA2")) "WPA2" else "WPA"
            encColor = yellow_middle
        } else if (capabilities.contains("WEP")) {
            enc = "WEP"
            encColor = sky_light
        }
        if (capabilities.contains("EAP"))
            encColor = red_light
    }

    private fun specifyChi(capabilities: String) {
        chi = if (capabilities.contains("CCMP")) "CCMP" else ""
        chiColor = grey

        if (capabilities.contains("TKIP")) {
            chi = if (chi.isEmpty()) "  TKIP" else "+TKIP"
            chiColor = if (capabilities.contains("preauth")) sky else sky_white
        }
    }

    private fun specifyWps(capabilities: String) {
        val yes = capabilities.contains("WPS")

        wps = if (yes) "yes" else "no"
        wpsColor = if (yes) green_high else red_high
    }

    override fun equals(other: Any?): Boolean {
        if (other == null || other::class.java != Point::class.java)
            return false

        val o = other as Point
        return o.essid == essid && o.bssid == bssid
    }

    fun getNotEmptyESSID(): String = if (essid.isEmpty()) bssid else essid

    companion object {
		private val MAX_INDICATOR_LEVEL = 512
        val MIN_LEVEL = -100 // WifiManager.MIN_LEVEL

        // todo move this into adapter/holder
        var transparent = 0
            private set
        var black_lite = 0
            private set
        var red_lite= 0
            private set
        var red_dark_lite = 0
            private set

        private var red_middle = 0
        var grey = 0; private set
        private var blue_light = 0
        private var green = 0
        private var yellow_middle = 0
        private var sky_light = 0
        private var red_light = 0
        private var sky = 0
        private var sky_white = 0
        private var green_high = 0
        private var red_high = 0
        var yellow = 0; private set
        var green_light = 0; private set

        fun initColors(co: Context) {
            transparent = ContextCompat.getColor(co, R.color.transparent)
            black_lite = ContextCompat.getColor(co, R.color.black_lite)
            red_lite = ContextCompat.getColor(co, R.color.red_lite)
            red_dark_lite = ContextCompat.getColor(co, R.color.red_dark_lite)
            red_middle = ContextCompat.getColor(co, R.color.red_middle)
            grey = ContextCompat.getColor(co, R.color.grey)
            blue_light = ContextCompat.getColor(co, R.color.blue_light)
            green = ContextCompat.getColor(co, R.color.green)
            yellow_middle = ContextCompat.getColor(co, R.color.yellow_middle)
            sky_light = ContextCompat.getColor(co, R.color.sky_light)
            red_light = ContextCompat.getColor(co, R.color.red_light)
            sky = ContextCompat.getColor(co, R.color.sky)
            sky_white = ContextCompat.getColor(co, R.color.sky_white)
            green_high = ContextCompat.getColor(co, R.color.green_high)
            red_high = ContextCompat.getColor(co, R.color.red_high)
            yellow = ContextCompat.getColor(co, R.color.yellow)
            green_light = ContextCompat.getColor(co, R.color.green_light)
        }

        private fun getPowerColor(level: Int): Int {
			/* не знаю в чём причина, но, начиная с Android 8,
			   функция WifiManager.calculateSignalLevel(int, int)
			   возвращает неадекватные значения */
            val pwr = when {
                SDK_INT >= O -> MAX_INDICATOR_LEVEL * (Math.min(level, -50) + 100) / 50
                else -> WifiManager.calculateSignalLevel(level, MAX_INDICATOR_LEVEL)
            }

            var red = if (pwr <= MAX_INDICATOR_LEVEL / 2) "ff" else Integer.toHexString(MAX_INDICATOR_LEVEL - pwr)
            var green = if (pwr >= MAX_INDICATOR_LEVEL / 2) "ff" else Integer.toHexString(pwr)

            if (red.length < 2)
                red = "0" + red

            if (green.length < 2)
                green = "0" + green

            return Color.parseColor("#ff$red${green}00")
        }

        private fun getChanel(frequency: Int): Int {
            var fr = frequency
            var ans = 0
            if (fr in 2412..2484) {
                if (fr == 2484) return 14
                while (fr >= 2412) {
                    fr -= 5
                    ans++
                }
            } else if (fr in 3658..3692) {
                ans = 130
                while (fr >= 3655) {
                    fr -= 5
                    ans++
                }
            } else if (fr in 4940..4990
                    && fr % 5 != 0) {
                ans = 19
                while (fr >= 4940) {
                    fr -= 7
                    ans++
                }
            } else if (fr in 4915..4980) {
                ans = 182
                while (fr >= 4915) {
                    fr -= 5
                    ans++
                }
            } else if (fr in 5035..5825) {
                ans = 6
                while (fr >= 5035) {
                    fr -= 5
                    ans++
                }
            }
            return ans
        }

        @JvmField
        val CREATOR: Parcelable.Creator<Point> = object : Parcelable.Creator<Point> {
            override fun createFromParcel(parcel: Parcel): Point {
                val point = Point()
                point.level = parcel.readInt()
                point.frequency = parcel.readInt()
                point.capabilities = parcel.readString()!!
                point.essid = parcel.readString()!!
                point.bssid = parcel.readString()!!
                point.ch = parcel.readInt()
                point.manufacturer = parcel.readString()!!
                point.manufacturerDesc = parcel.readString()!!
                return point
            }

            override fun newArray(size: Int): Array<Point?> = arrayOfNulls(size)
        }
    }
}