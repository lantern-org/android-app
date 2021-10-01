package com.example.lantern

import android.Manifest
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
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom


class MainActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var btnStart: FloatingActionButton
    private lateinit var btnStop: FloatingActionButton

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locReq: LocationRequest
    private lateinit var locCall: LocationCallback
    private lateinit var stopwatch: Chronometer

    private var api: java.net.URL? = null
    private var udp: DatagramSocket? = null
    private var key: String? = null
    private var end: String? = null

    private fun transmitPacket(loc: Location) {
        if (udp != null) {
            Log.d("hi", loc.toString())
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

        // make sure we have location
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
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
        // generate session key
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        key = b.joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

        // api request
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            // todo
            return
        }
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
                    if (api == null) {
                        return@JsonObjectRequest
                    }
                    udp = DatagramSocket(response["port"] as Int, InetAddress.getByName(api!!.host))
                    // start location updates
                    fusedLocationClient.requestLocationUpdates(locReq, locCall, fusedLocationClient.looper)
                },
                { error ->
                    Log.e("hi", error.toString())
                }
            )
        )

        // fetch last location
        fusedLocationClient.lastLocation.addOnSuccessListener { location : Location? ->
            Log.d("loc", location.toString())
            if (location != null) {
                // transmitPacket(location)
                findViewById<TextView>(R.id.locationAccuracyUpdate).text = location.accuracy.toString()
            }
        }

//        fusedLocationClient.locationAvailability.addOnSuccessListener { la : LocationAvailability? ->
//            Log.d("locA", la.toString())
//            findViewById<TextView>(R.id.locationAvailUpdate).text = la.toString()
//            if (la != null && la.isLocationAvailable) {
//                Log.d("??", fusedLocationClient.lastLocation.toString())
//            } else {
//                fusedLocationClient.requestLocationUpdates(
//                    LocationRequest.create(),
//                    object : LocationCallback() {
//                        override fun onLocationResult(locationResult: LocationResult?) {
//                            locationResult ?: return
//                            locationResult.locations[0].time
//                            Log.d("uh?", locationResult.toString())
//                        }
//                    },
//                    fusedLocationClient.looper)
//            }
//        }

        btnStart.hide()
        btnStop.show()
        stopwatch.base = SystemClock.elapsedRealtime()
        stopwatch.start()
    }

    private fun stopSession() {
        // click button to end session
        // send port and end-stream token to server API
        // obtain whatever data the server sends back
        fusedLocationClient.removeLocationUpdates(locCall) // stop location updates
        val queue = Volley.newRequestQueue(this)
        queue.add(
            JsonObjectRequest(
                Request.Method.POST,
                api.toString() + "/session/stop",
                JSONObject(mapOf("port" to udp?.port, "token" to end)),
                { response ->
                    Log.d("hi", response.toString())
                    udp = null
                    key = null
                    end = null
                },
                { error ->
                    Log.e("hi", error.toString())
                }
            )
        )
        stopwatch.stop()
        btnStart.show()
        btnStop.hide()
    }

    private fun startSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
                    transmitPacket(location)
                }
            }
        }
        // other UI elements
        btnStart = findViewById(R.id.start)
        btnStop = findViewById(R.id.stop)
        stopwatch = findViewById(R.id.stopwatch)
        // listeners
        btnStart.setOnClickListener(this)
        btnStop.setOnClickListener(this)
        findViewById<FloatingActionButton>(R.id.settings).setOnClickListener(this)
    }

    override fun onClick(v: View?) {
        Log.d("what?", v.toString())
        if (v == null) return
        when (v.id) {
            R.id.settings -> startSettings()
            R.id.start -> startSessionMiddleware()
            R.id.stop -> stopSession()
            else -> return
        }
    }

}
