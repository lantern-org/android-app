package dev.justgood.lantern

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec


private const val CHANNEL_ID = "locationTrackerNotificationID"
private const val ONGOING_NOTIFICATION_ID = 1 // ensure unique?

private const val PACKET_PROTOCOL_VERSION: UInt = 1u // treat as UShort

// - big-endian: 00000000 00000000 00000000 00000001 -> =0 yield 0b00000000
// - lit-endian: 00000001 00000000 00000000 00000000 -> >0 yield 0b10000000
//private val t: UByte = if ((1u shr 1) > 0u) 0x80u else 0x00u
private val ENDIAN: UByte = 0x00u // if little-endian, this would be 0x80u
// so basically, the code's frame-of-reference has all values in big-endian
// but on the cpu-side, data are represented in little-endian
// that's why bit-manipulations appear to work like big-endian
// yet ByteOrder.nativeOrder() (usually) returns little-endian

class LocationTracker: Service() {

    object PacketInfo {
        // bytes
        // https://en.wikipedia.org/wiki/User_Datagram_Protocol
        private const val UDP_HEADER_LENGTH = 8 // IPv4=20, IPv6=48
        const val PACKET_LENGTH = 48
        const val TRANSMIT_LENGTH = UDP_HEADER_LENGTH + PACKET_LENGTH
        // ...
    }

    private var udp: DatagramSocket? = null
    private var port: Int = 0
    private var addr: InetAddress? = null
    private var startTime: Long = 0
    private var startTimeNano: Long = 0
    private var kar: ByteArray? = null // encryption key bytearray-form
    private var end: String? = null // token to end the UDP session
    private val scope = CoroutineScope(Job() + Dispatchers.IO) // off Main thread pls
    private var index: UInt = 0u

    private var signal: Int = 0

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locReq: LocationRequest
    private lateinit var locCall: LocationCallback

    private lateinit var sp: SharedPreferences

    private fun floatToBytes(_l: Float, b: Array<Byte>) {
        val l = _l.toBits()
        for (i in 0..3) b[i] = (l shr (3 - i) * 8).toByte()
    }

    @SuppressLint("GetInstance") // our case of ECB is safe
    private fun transmitPacket(loc: Location) {
        // Log.e("???","$addr $port")
        if (udp != null) {
            Log.d("LOCATION_TRACKER", loc.toString())
            val b = ByteArray(PacketInfo.PACKET_LENGTH) // all elements are init to 0
            // BIG-ENDIAN
            // misc
            // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-u-byte/to-byte.html
            // "The resulting Byte value has the same binary representation as this UByte value."
            b[0] = (ENDIAN or signal.toUByte()).toByte()
            //b[1] = 0
            b[2] =
                (PACKET_PROTOCOL_VERSION shr 8).toByte() // code's frame of reference is big-endian
            b[3] = PACKET_PROTOCOL_VERSION.toByte()
            // index
            for (i in 0..3) b[i + 4] = (index shr (3 - i) * 8).toByte()
            // time
            val t = startTime + (loc.elapsedRealtimeNanos - startTimeNano) / 1000
            for (i in 0..7) b[i + 8] = (t shr (7 - i) * 8).toByte()
            // lat
            val p = Array<Byte>(4) { 0 } // insane syntax lmao
            floatToBytes(loc.latitude.toFloat(), p)
            for (i in 0..3) b[i + 16] = p[i]
            // lon
            floatToBytes(loc.longitude.toFloat(), p)
            for (i in 0..3) b[i + 20] = p[i]
            // accuracy
            floatToBytes(loc.accuracy, p)
            for (i in 0..3) b[i + 24] = p[i]
            // reserved
            //for (i in 0..3) b[i+28] = 0
            // sum
            val d = MessageDigest.getInstance("MD5").digest(b.sliceArray(0..31))
            for (i in 0..15) b[i + 32] = d[i]
            // encrypt
            val c = Cipher.getInstance("AES/ECB/NoPadding") // it's safe enough
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kar, "AES")) // todo
            val pac = c.doFinal(b)
            // transmit
            try {
                for (i in 1..sp.getInt("packetDuplicationFactor", 1))
                    udp!!.send(DatagramPacket(pac, PacketInfo.PACKET_LENGTH, addr, port))
                //
            } catch (e: Exception) {
                // could be network error
                Log.e("LOCATION_TRACKER", e.toString())
            }
            index += 1u
        }
    }

    override fun onCreate() {
        sp = PreferenceManager.getDefaultSharedPreferences(this) // shouldn't be null
        index = 0u
        // udp
        udp = DatagramSocket()
        // location vars
        // - get loc service
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // - create location request
        locReq = LocationRequest.create()

        locReq.interval = sp.getInt("gpsInterval", 1000).toLong() // milliseconds
        locReq.fastestInterval = 250 // milliseconds
        locReq.smallestDisplacement = 0.5F // meters
        locReq.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        // - create location callback
        locCall = object : LocationCallback() {
            override fun onLocationResult(p0: LocationResult) {
                for (location in p0.locations) { // is size ever > 1 here?
                    // Update UI with location data?
                    // displayLocAcc.text = location.accuracy.toString() // TODO update notification
                    scope.launch { transmitPacket(location) } // ignore errors
                }
            }
        }
        val telephonyManager: TelephonyManager =
            getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.registerTelephonyCallback(
            mainExecutor,
            object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    signal = signalStrength.level
                    Log.d("LOCATION_TRACKER", "new signalStrength.level=$signal")
                }
            }
        )
        // Start up the thread running the service.  Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block.  We also make it
        // background priority so CPU-intensive work will not disrupt our UI.
        HandlerThread("ServiceStartArguments", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
        }
    }

    // grab all location changes, encrypt, and stream to server
    // -- UDP packet description from server README
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.INTERNET
            ) != PackageManager.PERMISSION_GRANTED && (
                    ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED ||
                            ActivityCompat.checkSelfPermission(
                                this,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) != PackageManager.PERMISSION_GRANTED
                    )
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return START_NOT_STICKY // don't restart, get permissions instead
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location Tracker",
            NotificationManager.IMPORTANCE_NONE
        )
        channel.lightColor = Color.BLUE
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(channel)
        //
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    this,
                    0,
                    notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE + PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
        //
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getText(R.string.notification_title))
            .setContentText(getText(R.string.notification_message))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setTicker(getText(R.string.ticker_text))
            .build()
        // Notification ID cannot be 0.
        startForeground(ONGOING_NOTIFICATION_ID, notification)

        port = intent.getIntExtra("port", 0)
        end = intent.getStringExtra("end")
        addr = intent.getSerializableExtra("addr") as InetAddress // todo possible crash?
        kar = intent.getByteArrayExtra("kar")

        fusedLocationClient.requestLocationUpdates(
            locReq,
            locCall,
            fusedLocationClient.looper
        )

        Toast.makeText(this, "service starting", Toast.LENGTH_SHORT).show()
        // If we get killed, after returning from here, restart
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        // We don't provide binding, so return null
        return null
    }

    override fun onDestroy() {
        // TODO cancel `scope` ?
        stopForeground(true)
        fusedLocationClient.removeLocationUpdates(locCall) // stop location updates
        Toast.makeText(this, "service done", Toast.LENGTH_SHORT).show()
    }
}
