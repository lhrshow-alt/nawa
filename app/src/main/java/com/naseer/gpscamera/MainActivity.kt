package com.naseer.gpscamera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.naseer.gpscamera.databinding.ActivityMainBinding
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

class MainActivity : AppCompatActivity() {

    companion object {
        const val CREDIT = "Developed By Nawazish Ali SSO"
        const val BADGE = "NH PERA"

        // Optional: paste a Google Maps "Maps Static API" key here to use real Google map
        // imagery (with the Google logo) in the stamp. Leave empty to use free Esri satellite tiles.
        const val GOOGLE_MAPS_API_KEY = ""
        const val ZOOM = 17

        // Kept here (not per-activity) so rotating the phone does not lose the current state
        private var lensFacing = CameraSelector.LENS_FACING_BACK
        @Volatile private var location: Location? = null
        @Volatile private var place = Place()
        @Volatile private var mapBmp: Bitmap? = null
        private var lastFetched: Location? = null
        private var lastUri: Uri? = null
        private var lastCaption: String = ""
        private var lastThumb: Bitmap? = null
        private var flashMode = ImageCapture.FLASH_MODE_OFF
    }

    data class Place(val title: String = "", val address: String = "")

    private lateinit var binding: ActivityMainBinding
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var camExec: ExecutorService
    private lateinit var io: ExecutorService
    private lateinit var proc: ExecutorService
    private val sound = MediaActionSound()
    private val logoBmp by lazy { BitmapFactory.decodeResource(resources, R.drawable.pera_logo) }
    private var imageCapture: ImageCapture? = null
    private var locCallback: LocationCallback? = null
    private var hasFlash = false
    private var locDialog: AlertDialog? = null
    private var settingsFlow = false // true while the Google "turn on location" prompt is pending

    private val resolutionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        settingsFlow = false
        if (result.resultCode == RESULT_OK) startLocation() else showLocationOffDialog()
    }


    // Photos follow how the phone is held, even when auto-rotate is locked
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val rot = when (o) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rot
            }
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startAll()
        else toast("Camera and location permissions are required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fused = LocationServices.getFusedLocationProviderClient(this)
        camExec = Executors.newSingleThreadExecutor()
        io = Executors.newFixedThreadPool(2)
        proc = Executors.newSingleThreadExecutor()
        sound.load(MediaActionSound.SHUTTER_CLICK)

        updateLive()
        lastThumb?.let {
            binding.thumbView.setImageBitmap(it)
            binding.thumbView.visibility = android.view.View.VISIBLE
            binding.shareButton.visibility = android.view.View.VISIBLE
        }

        binding.captureButton.setOnClickListener { capture() }
        updateFlashIcon()
        binding.flashButton.setOnClickListener {
            if (!hasFlash) {
                toast("Flash is not available on this camera")
            } else {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
                imageCapture?.flashMode = flashMode
                updateFlashIcon()
            }
        }
        binding.shareButton.setOnClickListener { shareWhatsApp() }
        binding.thumbView.setOnClickListener { viewLast() }
        binding.switchButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        val perms = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startAll()
        else permLauncher.launch(perms)
    }

    private fun startAll() {
        startCamera()
        ensureLocationEnabled()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isLocationOn(): Boolean =
        (getSystemService(LOCATION_SERVICE) as LocationManager).isLocationEnabled

    // Asks Google Play services to show the standard "Turn on device location?" prompt
    private fun ensureLocationEnabled() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        val settings = LocationSettingsRequest.Builder().addLocationRequest(req).setAlwaysShow(true).build()
        settingsFlow = true
        LocationServices.getSettingsClient(this).checkLocationSettings(settings)
            .addOnSuccessListener {
                settingsFlow = false
                startLocation()
            }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    try {
                        resolutionLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
                    } catch (_: Exception) {
                        settingsFlow = false
                        showLocationOffDialog()
                    }
                } else {
                    settingsFlow = false
                    showLocationOffDialog()
                }
            }
    }

    private fun showLocationOffDialog() {
        if (isFinishing || locDialog?.isShowing == true) return
        locDialog = AlertDialog.Builder(this)
            .setTitle("Location is turned off")
            .setMessage("GPS CAM needs location to put the address and coordinates on your photos. Please turn on location.")
            .setCancelable(false)
            .setPositiveButton("Turn on") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ---------------- Camera ----------------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            val available = try { provider.hasCamera(selector) } catch (e: Exception) { false }
            if (!available) {
                toast("This camera is not available")
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            }
            val previewRes = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(previewRes).build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            // ~12 MP cap + latency mode = much faster capture and processing
            val captureRes = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(captureRes)
                .build()
            imageCapture = ic
            provider.unbindAll()
            val cam = provider.bindToLifecycle(this, selector, preview, ic)
            hasFlash = cam.cameraInfo.hasFlashUnit()
            ic.flashMode = flashMode
            updateFlashIcon()
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- Location / address / map ----------------
    @Suppress("MissingPermission")
    private fun startLocation() {
        if (locCallback != null) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateDistanceMeters(1f).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                val loc = r.lastLocation ?: return
                location = loc
                refreshPlace(loc)
                updateLive()
            }
        }
        locCallback = cb
        fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    @Suppress("DEPRECATION")
    private fun refreshPlace(loc: Location) {
        val prev = lastFetched
        if (prev != null && prev.distanceTo(loc) < 20f) return
        lastFetched = loc
        io.execute {
            try {
                val a = Geocoder(this, Locale.getDefault())
                    .getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                if (a != null) {
                    val title = listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea, a.countryName)
                        .filter { it.isNotBlank() }.distinct().joinToString(", ")
                    place = Place(title, a.getAddressLine(0) ?: "")
                }
            } catch (_: Exception) { /* offline: keep coordinates only */ }
            try {
                fetchMap(loc.latitude, loc.longitude)?.let { mapBmp = it }
            } catch (_: Exception) { }
            runOnUiThread { updateLive() }
        }
    }

    private fun download(u: String): ByteArray? = try {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "NH-PERA-GPSCamera/1.0")
        conn.inputStream.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    private fun fetchMap(lat: Double, lon: Double): Bitmap? {
        if (GOOGLE_MAPS_API_KEY.isNotBlank()) {
            val url = "https://maps.googleapis.com/maps/api/staticmap?center=$lat,$lon&zoom=18" +
                "&size=400x400&scale=2&maptype=hybrid&markers=color:red%7C$lat,$lon&key=$GOOGLE_MAPS_API_KEY"
            val bytes = download(url) ?: return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        // Free satellite tiles (Esri World Imagery), composed around the current position.
        val worldPx = (1 shl ZOOM) * 256.0
        val px = (lon + 180.0) / 360.0 * worldPx
        val latRad = Math.toRadians(lat)
        val py = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * worldPx
        val size = 512
        val left = px - size / 2
        val top = py - size / 2
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.DKGRAY)
        val tx0 = floor(left / 256).toInt()
        val tx1 = floor((left + size) / 256).toInt()
        val ty0 = floor(top / 256).toInt()
        val ty1 = floor((top + size) / 256).toInt()
        var got = 0
        for (tx in tx0..tx1) for (ty in ty0..ty1) {
            val bytes = download(
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$ZOOM/$ty/$tx"
            ) ?: continue
            val t = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            c.drawBitmap(t, (tx * 256 - left).toFloat(), (ty * 256 - top).toFloat(), null)
            got++
        }
        if (got == 0) return null
        drawPin(c, size / 2f, size / 2f)
        return out
    }

    private fun drawPin(c: Canvas, cx: Float, cy: Float) {
        val r = 30f
        val headY = cy - 62f
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
        val path = Path().apply {
            moveTo(cx, cy)
            lineTo(cx - r * 0.88f, headY + r * 0.45f)
            lineTo(cx + r * 0.88f, headY + r * 0.45f)
            close()
        }
        c.drawPath(path, red)
        c.drawCircle(cx, headY, r, red)
        c.drawCircle(cx, headY, r * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 20, 20) })
    }

    // ---------------- Text helpers ----------------
    private fun coordsLine(loc: Location) =
        String.format(Locale.US, "Lat %.6f° Long %.6f°", loc.latitude, loc.longitude)

    private fun dateLine(): String {
        val d = Date()
        return SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale.ENGLISH).format(d) + " " +
            SimpleDateFormat("'GMT' XXX", Locale.ENGLISH).format(d)
    }

    private fun updateLive() {
        val loc = location
        val sb = StringBuilder()
        if (place.title.isNotBlank()) sb.appendLine(place.title)
        if (place.address.isNotBlank()) sb.appendLine(place.address)
        if (loc != null) sb.appendLine(coordsLine(loc)) else sb.appendLine("Waiting for GPS…")
        sb.appendLine(dateLine())
        sb.append(CREDIT)
        binding.infoText.text = sb.toString()
        mapBmp?.let { binding.mapThumb.setImageBitmap(it) }
    }

    // ---------------- Capture ----------------
    private fun setBusy(busy: Boolean) {
        binding.captureButton.isEnabled = !busy
        binding.captureButton.alpha = if (busy) 0.5f else 1f
    }

    private fun updateFlashIcon() {
        val res = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        }
        binding.flashButton.setImageResource(res)
        binding.flashButton.alpha = if (hasFlash) 1f else 0.4f
    }

    private fun shutterFeedback(done: AtomicBoolean) {
        if (done.compareAndSet(false, true)) {
            sound.play(MediaActionSound.SHUTTER_CLICK)
            runOnUiThread { flash() }
        }
    }

    private fun flash() {
        binding.flashView.animate().cancel()
        binding.flashView.alpha = 0.7f
        binding.flashView.animate().alpha(0f).setDuration(180).start()
    }

    private fun capture() {
        val ic = imageCapture ?: return
        setBusy(true)
        val feedbackDone = AtomicBoolean(false)
        val loc = location
        val pl = place
        val map = mapBmp
        val dateStr = dateLine()
        ic.takePicture(camExec, object : ImageCapture.OnImageCapturedCallback() {
            // fires at the real shutter moment (when the flash fires), so sound and flash stay in sync
            override fun onCaptureStarted() {
                shutterFeedback(feedbackDone)
            }

            override fun onCaptureSuccess(image: ImageProxy) {
                shutterFeedback(feedbackDone) // fallback if onCaptureStarted was not delivered
                val rotation = image.imageInfo.rotationDegrees
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                image.close()
                // camera is free again immediately; stamping/saving continues in the background
                runOnUiThread { setBusy(false) }
                try { proc.execute { process(bytes, rotation, loc, pl, map, dateStr) } } catch (_: Exception) { }
            }

            override fun onError(e: ImageCaptureException) {
                runOnUiThread {
                    toast("Capture failed: ${e.message}")
                    setBusy(false)
                }
            }
        })
    }

    private fun process(bytes: ByteArray, rotation: Int, loc: Location?, pl: Place, map: Bitmap?, dateStr: String) {
        try {
            val opts = BitmapFactory.Options().apply { inMutable = true }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: throw IllegalStateException("decode failed")
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (r !== bmp) bmp.recycle()
                bmp = r
            }
            val stamped = stamp(bmp, loc, pl, map, dateStr)
            val uri = save(stamped, loc)
            val thumb = Bitmap.createScaledBitmap(
                stamped, 200, (200f * stamped.height / stamped.width).toInt().coerceAtLeast(1), true
            )
            stamped.recycle()
            val caption = buildString {
                if (pl.title.isNotBlank()) appendLine(pl.title)
                if (loc != null) append("https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
            }.trim()
            runOnUiThread {
                if (uri != null) {
                    lastUri = uri
                    lastCaption = caption
                    lastThumb = thumb
                    binding.thumbView.setImageBitmap(thumb)
                    binding.thumbView.visibility = android.view.View.VISIBLE
                    binding.shareButton.visibility = android.view.View.VISIBLE
                    toast("Saved to Pictures/GPSCamera")
                } else {
                    toast("Save failed")
                }
            }
        } catch (e: Exception) {
            runOnUiThread { toast("Error: ${e.message}") }
        }
    }

    private fun stamp(src: Bitmap, loc: Location?, pl: Place, map: Bitmap?, dateStr: String): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        // portrait photos get a relatively bigger stamp (like the reference GPS Map Camera look)
        val u = bmp.width / (if (bmp.width < bmp.height) 1150f else 1400f)
        val margin = 36f * u
        val gap = 12f * u
        val pad = 22f * u
        val mapSize = 270f * u
        val boxLeft = margin + mapSize + gap
        val boxRight = bmp.width - margin
        val textW = (boxRight - boxLeft - pad * 2).toInt()

        fun paint(sizeU: Float, bold: Boolean, col: Int = Color.WHITE): TextPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = col
                textSize = sizeU * u
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setShadowLayer(2f * u, 1f, 1f, Color.BLACK)
            }

        fun layout(t: String, p: TextPaint, maxLines: Int): StaticLayout =
            StaticLayout.Builder.obtain(t, 0, t.length, p, textW)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()

        val layouts = mutableListOf<StaticLayout>()
        val title = when {
            pl.title.isNotBlank() -> pl.title
            loc != null -> "Location"
            else -> "Location not available"
        }
        layouts += layout(title, paint(54f, true), 2)
        if (pl.address.isNotBlank()) layouts += layout(pl.address, paint(33f, false), 2)
        if (loc != null) layouts += layout(coordsLine(loc), paint(38f, false), 1)
        layouts += layout(dateStr, paint(35f, false), 1)
        layouts += layout(CREDIT, paint(27f, true, Color.rgb(255, 213, 79)), 1)

        val spacing = 4f * u
        val total = layouts.sumOf { it.height.toDouble() }.toFloat() + spacing * (layouts.size - 1)
        val boxH = max(mapSize, total + pad * 2)
        val boxBottom = bmp.height - margin
        val boxTop = boxBottom - boxH

        // dark text box
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        c.drawRoundRect(RectF(boxLeft, boxTop, boxRight, boxBottom), 22f * u, 22f * u, boxPaint)

        // map thumbnail (bottom-left)
        val mapRect = RectF(margin, boxBottom - mapSize, margin + mapSize, boxBottom)
        val rr = 18f * u
        c.save()
        c.clipPath(Path().apply { addRoundRect(mapRect, rr, rr, Path.Direction.CW) })
        if (map != null) {
            c.drawBitmap(map, null, mapRect, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            c.drawRect(mapRect, Paint().apply { color = 0xFF37474F.toInt() })
        }
        c.restore()
        c.drawRoundRect(mapRect, rr, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * u
            color = Color.WHITE
        })
        if (map != null && GOOGLE_MAPS_API_KEY.isBlank()) {
            c.drawText("© Esri", mapRect.left + 10f * u, mapRect.bottom - 10f * u, paint(15f, false))
        }

        // text
        var y = boxTop + pad
        for (l in layouts) {
            c.save()
            c.translate(boxLeft + pad, y)
            l.draw(c)
            c.restore()
            y += l.height + spacing
        }

        // badge: PERA logo + "NH PERA" above the box (top-right)
        val logo = logoBmp
        val bp = paint(30f, true, 0xFF1B1B1B.toInt()).apply { clearShadowLayer() }
        val logoH = 46f * u
        val logoW = logoH * logo.width / logo.height
        val vPad = 6f * u
        val hPad = 14f * u
        val pillW = hPad + logoW + 8f * u + bp.measureText(BADGE) + hPad
        val pillH = logoH + vPad * 2
        val pill = RectF(boxRight - pillW, boxTop - gap - pillH, boxRight, boxTop - gap)
        c.drawRoundRect(pill, pillH / 2, pillH / 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 255, 255, 255) })
        c.drawBitmap(logo, null,
            RectF(pill.left + hPad, pill.top + vPad, pill.left + hPad + logoW, pill.top + vPad + logoH),
            Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawText(BADGE, pill.left + hPad + logoW + 8f * u, pill.centerY() + bp.textSize * 0.35f, bp)

        return bmp
    }

    private fun save(bmp: Bitmap, loc: Location?): Uri? {
        val name = "NHPERA_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GPSCamera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (loc != null) {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setLatLong(loc.latitude, loc.longitude)
                exif.setAltitude(loc.altitude)
                exif.saveAttributes()
            }
        }
        return uri
    }

    private fun shareWhatsApp() {
        val uri = lastUri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, lastCaption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                startActivity(Intent(send).setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) { }
        }
        // WhatsApp not installed: let the user pick any app
        startActivity(Intent.createChooser(send, "Share photo"))
    }

    private fun viewLast() {
        val uri = lastUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            toast("No gallery app found")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        if (hasLocationPermission() && !settingsFlow) {
            if (isLocationOn()) {
                locDialog?.dismiss()
                startLocation()
            } else {
                showLocationOffDialog()
            }
        }
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        locCallback?.let { fused.removeLocationUpdates(it) }
        camExec.shutdown()
        io.shutdown()
        proc.shutdown()
        sound.release()
    }
}
