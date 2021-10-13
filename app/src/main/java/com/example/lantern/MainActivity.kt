package com.example.lantern

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Chronometer
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.location.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private const val PACKET_LENGTH = 32 // bytes

// todo -- test
private fun isLittleEndian(): Boolean {
    // apparently we can't trust ByteOrder.nativeOrder() ??
    val i: UInt = 1u // has to be int to do bit manipulation
    // i hate java
    // big-endian: 00000000 00000000 00000000 00000001
    // lit-endian: 00000001 00000000 00000000 00000000
    return (i shr 1) > 0u
}

class MainActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var btnStart: FloatingActionButton
    private lateinit var btnStop: FloatingActionButton

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locReq: LocationRequest
    private lateinit var locCall: LocationCallback
    private lateinit var stopwatch: Chronometer
    private lateinit var displayCodeUI: TextView
    private var startTime: Long = 0
    private var startTimeNano: Long = 0

    private var api: java.net.URL? = null
    private var udp: DatagramSocket? = null
    private var port: Int = 0
    private var addr: InetAddress? = null
    private var key: String? = null
    private var kar: ByteArray? = null
    private var end: String? = null
    private var displayCode: String? = null

    private val scope = CoroutineScope(Job() + Dispatchers.IO) // off Main thread pls

    private fun latlonToBytes(_l: Double, b: Array<Byte>) {
//        var r: Long = 0
//        var l = _l
//        if (l < 0) {
//            r = 1
//            l *= -1
//        }
//        r = (r shl 10) or l.toLong()
//        l = (l-l.toInt()) * 100000
//        r = (r shl 17) or l.toLong()
//        for (i in 0..3) b[i] = (r shr (3-i)*8).toByte()

        val l = _l.toFloat().toBits()
        for (i in 0..3) b[i] = (l shr (3-i)*8).toByte()
    }

    @SuppressLint("GetInstance") // our case of ECB is safe
    private fun transmitPacket(loc: Location) {
        Log.e("???","$addr $port")
        if (udp != null) {
            Log.d("hi", loc.toString())
            val b = ByteArray(PACKET_LENGTH)
            val p = Array<Byte>(4) { 0 } // insane syntax lmao
            latlonToBytes(loc.latitude, p)
            for (i in 0..3) b[i] = p[i]
            latlonToBytes(loc.longitude, p)
            for (i in 0..3) b[i+4] = p[i]
            // verify size of Long ?
            var t = startTime + (loc.elapsedRealtimeNanos - startTimeNano)/1000
            if (isLittleEndian()) {
                t = java.lang.Long.reverseBytes(t)
                Log.e("hi", "reversed bytes")
            }
            for (i in 0..7) b[i+8] = (t shr (7-i)*8).toByte()
            // for (i in 0..7) Log.d("byte", Integer.toBinaryString(b[i].toInt() and 0xff))
            val d = MessageDigest.getInstance("MD5").digest(b.sliceArray(0..15))
            for (i in 0..15) b[i+16] = d[i]
            val c = Cipher.getInstance("AES/ECB/NoPadding") // it's safe enough
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kar, "AES")) // todo
            udp!!.send(DatagramPacket(c.doFinal(b), PACKET_LENGTH, addr, port))
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { isGranted: Map<String, Boolean> ->
            if (isGranted.containsValue(false)) {
                // Explain to the user that the feature is unavailable because the
                // features requires a permission that the user has denied. At the
                // same time, respect the user's decision. Don't link to system
                // settings in an effort to convince the user to change their
                // decision.
                Toast.makeText(this, "need location permission", Toast.LENGTH_SHORT).show()
            } else {
                // Permission is granted. Continue the action or workflow in your
                // app.
                startSession() // ?
            }
        }

    private fun startSessionMiddleware() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                // You can use the API that requires the permission.
                Log.d("TEST", "1")
                startSession()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> {
                // In an educational UI, explain to the user why your app requires this
                // permission for a specific feature to behave as expected. In this UI,
                // include a "cancel" or "no thanks" button that allows the user to
                // continue using your app without granting the permission.
                Log.d("TEST", "2")
                Toast.makeText(this, "we need location (rationale)", Toast.LENGTH_LONG).show()
                // todo
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                } else {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }
            else -> {
                // You can directly ask for the permission.
                // The registered ActivityResultCallback gets the result of this request.
                Log.d("TEST", "3")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                } else {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }
        }
    }

    private fun startSession() {
        // click button to start session
        // generate session encryption key, 32-bytes, to hex string
        // make API request to server HTTPS://URL:PORT/session/start with session key
        // -- must be secure (deny request in nginx)
        // -- if request is denied, generate a new key
        // obtain UDP stream address and end-stream token
        // display a timer on screen
        // grab all location changes, encrypt, and stream to server
        // -- UDP packet description from server README

        // FOR LOCAL TESTING,
        // localhost == 10.0.2.2
        // (https://stackoverflow.com/questions/38668820/how-to-connect-to-a-local-server-from-an-android-emulator)

        // make sure we have location
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return // startSessionMiddleware(view)
        }
        // make sure we have internet
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            // todo (same)
            return
        }
        // generate session key
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        key = b.joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
        kar = b
        // api request
        val sp = PreferenceManager.getDefaultSharedPreferences(this) // shouldn't be null
        api = java.net.URL(
            (if (sp.getBoolean("useHTTPS",true)) "https" else "http") + "://" + sp.getString("apiURL", "localhost:420")
        )
        val queue = Volley.newRequestQueue(this)
        queue.add(
            JsonObjectRequest(
                Request.Method.POST,
                api.toString() + "/session/start",
                JSONObject(mapOf("key" to key)),
                { response ->
                    Log.d("hi", response.toString())
                    // save UDP address
                    udp = DatagramSocket()
                    end = response["token"] as String
                    displayCode = response["code"] as String
                    port = response["port"] as Int
                    addr = InetAddress.getByName(api!!.host)
                    // fetch last location
                    fusedLocationClient.lastLocation.addOnSuccessListener { location : Location? ->
                        Log.d("loc", location.toString())
                        if (location != null) {
                            startTime = location.time
                            startTimeNano = location.elapsedRealtimeNanos
                            // transmitPacket(location)
                            findViewById<TextView>(R.id.locationAccuracyUpdate).text = location.accuracy.toString()
                        } else {
                            startTime = System.currentTimeMillis()
                            startTimeNano = SystemClock.elapsedRealtimeNanos()
                        }
                    }
                    // start location updates
                    fusedLocationClient.requestLocationUpdates(locReq, locCall, fusedLocationClient.looper)
                    // ui updates
                    displayCodeUI.text = displayCode
                    btnStart.hide()
                    btnStop.show()
                    stopwatch.base = SystemClock.elapsedRealtime()
                    stopwatch.start()
                },
                { error ->
                    Log.e("hi", error.toString())

                    Toast.makeText(this, "could not connect to server...", Toast.LENGTH_SHORT).show()
                }
            )
        )
    }

    private fun stopSession() {
        // click button to end session
        // send port and end-stream token to server API
        // obtain whatever data the server sends back

        // todo -- if server shuts down, we can't stop -- this should be fixed

        val queue = Volley.newRequestQueue(this)
        queue.add(
            JsonObjectRequest(
                Request.Method.POST,
                api.toString() + "/session/end",
                JSONObject(mapOf("port" to port, "token" to end)),
                { response ->
                    Log.d("hi", response.toString())
                    fusedLocationClient.removeLocationUpdates(locCall) // stop location updates
                    udp = null
                    key = null
                    kar = null
                    end = null
                    displayCode = null
                    displayCodeUI.text = ""
                    stopwatch.stop()
                    btnStart.show()
                    btnStop.hide()
                },
                { error ->
                    Log.e("hi", error.toString())
                    Log.e("hello", String(error.networkResponse.data))
                    Toast.makeText(this, "could not connect to server...", Toast.LENGTH_SHORT).show()
                }
            )
        )
    }

    private fun startSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // get loc service
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // create location request
        locReq = LocationRequest.create()
        locReq.interval = 30000
        locReq.fastestInterval = 10000
        locReq.smallestDisplacement = 30F
        locReq.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        // create location callback
        locCall = object : LocationCallback() {
            override fun onLocationResult(locRes: LocationResult?) {
                if (locRes == null) {
                    return
                }
                for (location in locRes.locations) {
                    // Update UI with location data
                    findViewById<TextView>(R.id.locationAccuracyUpdate).text = location.accuracy.toString()
                    scope.launch { transmitPacket(location) } // ignore errors
                }
            }
        }
        // other UI elements
        btnStart = findViewById(R.id.start)
        btnStop = findViewById(R.id.stop)
        stopwatch = findViewById(R.id.stopwatch)
        displayCodeUI = findViewById(R.id.displayCode)
        // listeners
        btnStart.setOnClickListener(this)
        btnStop.setOnClickListener(this)
        displayCodeUI.setOnClickListener(this)
        findViewById<FloatingActionButton>(R.id.settings).setOnClickListener(this)
    }

    override fun onClick(v: View?) {
        Log.d("who clicked?", v.toString())
        if (v == null) return
        when (v.id) {
            R.id.settings -> startSettings()
            R.id.start -> startSessionMiddleware()
            R.id.stop -> stopSession()
            R.id.displayCode -> {
                if (displayCode != null) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip: ClipData = ClipData.newPlainText(displayCode, displayCode)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "copied code to clipboard", Toast.LENGTH_SHORT).show()
                }
            }
            else -> return
        }
    }
}
