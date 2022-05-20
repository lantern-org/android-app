package dev.justgood.lantern

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Chronometer
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import java.net.InetAddress
import java.security.SecureRandom

private const val FILE_DATASTORE: String = "data.json"

class MainActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var btnStart: FloatingActionButton
    private lateinit var btnStop: FloatingActionButton

    private lateinit var stopwatch: Chronometer
    private lateinit var displayCodeUI: TextView
    private lateinit var displayStatus: TextView
    private lateinit var displayLocAcc: TextView

    private var api: java.net.URL? = null
    private var key: String? = null // encryption key hex-string-form
    private var port: Int = 0
    private var end: String? = null // token to end the UDP session
    private var displayCode: String? = null // 4-char code to get session info on front-end
    private var start: Long = 0
    private var running: Boolean = false

    private lateinit var sp: SharedPreferences

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
            ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED && (
                    (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) ||
                    (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            ) -> {
                // You can use the API that requires the permission.
                Log.d("TEST", "1")
                startSession()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION) -> {
                // In an educational UI, explain to the user why your app requires this
                // permission for a specific feature to behave as expected. In this UI,
                // include a "cancel" or "no thanks" button that allows the user to
                // continue using your app without granting the permission.
                Log.d("TEST", "2")
                Toast.makeText(this, "we need location (rationale)", Toast.LENGTH_LONG).show()
                // todo
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
            else -> {
                // You can directly ask for the permission.
                // The registered ActivityResultCallback gets the result of this request.
                Log.d("TEST", "3")
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
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
        // start-up the foreground location tracker service

        // FOR LOCAL TESTING,
        // localhost == 10.0.2.2
        // (https://stackoverflow.com/questions/38668820/how-to-connect-to-a-local-server-from-an-android-emulator)

        Toast.makeText(this, "contacting server", Toast.LENGTH_SHORT).show()

        // generate session key
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        key = b.joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
        // api request
        api = java.net.URL(
            (if (sp.getBoolean("useHTTPS",true)) "https" else "http") + "://" + sp.getString("apiURL", "localhost:420")
        )
        val queue = Volley.newRequestQueue(this)
        queue.add(
            JsonObjectRequest(
                Request.Method.POST,
                api.toString() + "/session/start",
                JSONObject(
                    mapOf(
                        "username" to sp.getString("username",""),
                        "password" to sp.getString("password",""),
                        "key" to key
                    )
                ),
                { response ->
                    Thread { // need for InetAddress.getByName
                        Log.d("hi", response.toString())
                        displayCode = response["code"] as String
                        end = response["token"] as String
                        port = response["port"] as Int
                        // start location updates
                        Intent(this, LocationTracker::class.java).also { intent ->
                            intent.putExtra("end", end)
                            intent.putExtra("port", port)
                            intent.putExtra("addr", InetAddress.getByName(api!!.host)) // save UDP address
                            intent.putExtra("kar", b)
                            startForegroundService(intent)
                        }
                        start = SystemClock.elapsedRealtime()
                        openFileOutput(FILE_DATASTORE, Context.MODE_PRIVATE).use { fos ->
                            fos.write(JSONObject(mapOf(
                                "code" to displayCode,
                                "token" to end,
                                "port" to port,
                                "start" to start.toString(),
                            )).toString().toByteArray())
                        }
                        indicateRunning()
                    }.start()
                },
                { error ->
                    Log.e("hi", error.toString())
                    Toast.makeText(this, "could not connect to server...", Toast.LENGTH_SHORT).show()
                }
            ).setRetryPolicy(
                DefaultRetryPolicy(
                    5000,
                    0,
                    DefaultRetryPolicy.DEFAULT_BACKOFF_MULT // doesn't matter?
                )
            )
        )
    }

    private fun stopSession() {
        // click button to end session
        // send port and end-stream token to server API
        // obtain whatever data the server sends back

        // todo -- if server shuts down, we can't stop -- this should be fixed
        // we want to assume the server is 100% uptime and will always respond
        // but if it doesn't respond to our http request, we need to handle that
        //

        val queue = Volley.newRequestQueue(this)
        queue.add(
            JsonObjectRequest(
                Request.Method.POST,
                api.toString() + "/session/stop",
                JSONObject(
                    mapOf(
                        "port" to port,
                        "token" to end
                    )
                ),
                { response ->
                    Log.d("hi", response.toString())
                    // TODO stop location tracking service
                    key = null
                    end = null
                    displayCode = null
                    displayStatus.text = getString(R.string.status_not_running)
                    displayCodeUI.text = ""
                    displayLocAcc.text = ""
                    stopwatch.stop()
                    btnStart.show()
                    btnStop.hide()
                    Intent(this, LocationTracker::class.java).also { intent ->
                        stopService(intent)
                    }
                    openFileOutput(FILE_DATASTORE, Context.MODE_PRIVATE).use { fos ->
                        fos.write(ByteArray(0))
                    }
                    running = false
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
        // https://wh0.github.io/2020/08/12/closeguard.html
        StrictMode.setVmPolicy(
            VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .build()
        )
        //
        sp = PreferenceManager.getDefaultSharedPreferences(this) // shouldn't be null
        // Log.e("ENDIAN", ByteOrder.nativeOrder().toString())
        setContentView(R.layout.activity_main)
        // other UI elements
        displayStatus = findViewById(R.id.status)
        btnStart = findViewById(R.id.start)
        btnStop = findViewById(R.id.stop)
        stopwatch = findViewById(R.id.stopwatch)
        displayCodeUI = findViewById(R.id.displayCode)
        displayLocAcc = findViewById(R.id.locationAccuracyUpdate)
        // listeners
        btnStart.setOnClickListener(this)
        btnStop.setOnClickListener(this)
        displayCodeUI.setOnClickListener(this)
        findViewById<FloatingActionButton>(R.id.settings).setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()
        // test if we're recording
        if (LocationTracker.running && !running) {
            // re-set state
            openFileInput(FILE_DATASTORE).use { fis ->
                val res = JSONObject(fis.bufferedReader().readText())
                displayCode = res["code"] as String?
                end = res["token"] as String?
                port = res["port"] as Int
                start = (res["start"] as String).toLong()
                //
                api = java.net.URL(
                    (if (sp.getBoolean("useHTTPS",true)) "https" else "http") + "://" + sp.getString("apiURL", "localhost:420")
                )
            }
            indicateRunning()
        }
        //
        val p = sp.getInt("packetDuplicationFactor", 1)
        val g = sp.getInt("gpsInterval", 500)
        // (LocationTracker.PacketInfo.PACKET_LENGTH / 1024f / 1024f) * p / g * 1000 * 60 * 60
        findViewById<TextView>(R.id.dataEst).text = String.format("~ %.2f MB/Hr", 3.4332275390625 * (LocationTracker.PacketInfo.TRANSMIT_LENGTH) * p / g)
    }

    override fun onClick(v: View?) {
        Log.d("MAIN_ACTIVITY", "clicked on ${v.toString()}")
        if (v == null) return
        when (v.id) {
            R.id.settings -> startSettings()
            R.id.start -> startSessionMiddleware()
            R.id.stop -> stopSession()
            R.id.displayCode -> {
                if (displayCode != null) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip: ClipData = ClipData.newPlainText(displayCode, sp.getString("copyCodeFormat", "%s")?.let { String.format(it, displayCode!!.uppercase()) })
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "copied code to clipboard", Toast.LENGTH_SHORT).show()
                }
            }
            else -> return
        }
    }

    private fun indicateRunning() {
        running = true
        // ui updates
        runOnUiThread {
            displayStatus.text = getString(R.string.status_running)
            displayCodeUI.text = displayCode
            btnStart.hide()
            btnStop.show()
            stopwatch.base = start
            stopwatch.start()
        }
    }
}
