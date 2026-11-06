package com.wardriver.sync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wardriver.sync.databinding.ActivityMapBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.api.IGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import java.io.File





























class MapActivity : AppCompatActivity() {


    private class Positioned(val net: NetworkRecord, val point: GeoPoint)


    private class NetworkInfoWindow(mapView: MapView) :
        InfoWindow(R.layout.map_info_window, mapView) {
        override fun onOpen(item: Any?) {
            val net = item as? NetworkRecord ?: return
            mView.findViewById<TextView>(R.id.bubble_title).text =
                net.ssid.ifBlank { "(SSID oculto)" }
            mView.findViewById<TextView>(R.id.bubble_description).text =
                "BSSID: ${net.bssid}\n" +
                    "Canal: ${net.channel}  RSSI: ${net.rssi} dBm\n" +
                    "Criptografia: ${net.encryption}\n" +
                    "Vista ${net.timesSeen}x"
        }
        override fun onClose() {}
    }

    private lateinit var binding: ActivityMapBinding
    private val db by lazy { WardriverDatabase.getInstance(this) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var infoWindow: NetworkInfoWindow



    private var pointsOverlay: SimpleFastPointOverlay? = null



    private var openBssid: String? = null

    private var allWithPosition: List<Positioned> = emptyList()
    private var matched: List<Positioned> = emptyList()
    private var withoutCoordinates: List<NetworkRecord> = emptyList()

    private var myLocation: MyLocationNewOverlay? = null



    private var centeredOnUser = false

    private var query = NetworkSearch.Query.parse("")
    private val applySearchRunnable = Runnable { applyFilter(focus = FocusMode.ONLY_IF_SINGLE) }

    private enum class FocusMode { NONE, ONLY_IF_SINGLE, ALL_MATCHES }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMyLocation()
        } else {
            Toast.makeText(this, R.string.map_location_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = File(cacheDir, "osmdroid")
        Configuration.getInstance().osmdroidTileCache = File(cacheDir, "osmdroid/tiles")

        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        infoWindow = NetworkInfoWindow(binding.mapView)

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(USER_ZOOM)

        binding.buttonRefreshMap.setOnClickListener { loadNetworks() }
        binding.buttonShowAll.setOnClickListener { fitAll() }
        binding.buttonMyLocation.setOnClickListener { recenterOnUser() }

        binding.editMapSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = NetworkSearch.Query.parse(s?.toString().orEmpty())

                handler.removeCallbacks(applySearchRunnable)
                handler.postDelayed(applySearchRunnable, SEARCH_DEBOUNCE_MS)
            }
        })
        binding.editMapSearch.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                handler.removeCallbacks(applySearchRunnable)
                applyFilter(focus = FocusMode.ALL_MATCHES)
                true
            } else {
                false
            }
        }



        if (hasLocationPermission()) {
            startMyLocation()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        loadNetworks()
        observeDatabaseChanges()
    }





    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED


    private fun startMyLocation() {
        if (myLocation != null || !hasLocationPermission()) return

        val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView)
        overlay.isDrawAccuracyEnabled = true
        overlay.enableMyLocation()
        overlay.enableFollowLocation()
        binding.mapView.overlays.add(overlay)
        myLocation = overlay



        val known = lastKnownLocation()
        if (known != null) {
            centerOn(GeoPoint(known), USER_ZOOM)
        } else {
            Toast.makeText(this, R.string.map_waiting_fix, Toast.LENGTH_SHORT).show()
        }



        overlay.runOnFirstFix {
            runOnUiThread {
                overlay.myLocation?.let { centerOn(it, USER_ZOOM) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider ->
                try {
                    lm.getLastKnownLocation(provider)
                } catch (e: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }

    private fun centerOn(point: GeoPoint, zoom: Double) {
        centeredOnUser = true
        binding.mapView.controller.setZoom(zoom)
        binding.mapView.controller.setCenter(point)
    }


    private fun recenterOnUser() {
        if (!hasLocationPermission()) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val overlay = myLocation
        if (overlay == null) {
            startMyLocation()
            return
        }
        overlay.enableFollowLocation()
        val here = overlay.myLocation ?: lastKnownLocation()?.let { GeoPoint(it) }
        if (here == null) {
            Toast.makeText(this, R.string.map_waiting_fix, Toast.LENGTH_SHORT).show()
            return
        }
        binding.mapView.controller.setZoom(maxOf(binding.mapView.zoomLevelDouble, USER_ZOOM))
        binding.mapView.controller.animateTo(here)
    }





    private fun loadNetworks() {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { db.networkDao().getAll() }
            reload(all)
            applyFilter(focus = FocusMode.NONE)



            if (!centeredOnUser) {


                fitAll(stopFollowing = false)
            }
        }
    }




    private fun observeDatabaseChanges() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.networkDao().observeChanges().collectLatest {
                    delay(LIVE_REFRESH_DEBOUNCE_MS)
                    val all = withContext(Dispatchers.IO) { db.networkDao().getAll() }
                    reload(all)
                    applyFilter(focus = FocusMode.NONE)
                }
            }
        }
    }




    private fun reload(all: List<NetworkRecord>) {
        val positioned = ArrayList<Positioned>(all.size)
        val noCoordinates = ArrayList<NetworkRecord>()
        for (net in all) {
            val lat = net.lat
            val lon = net.lon
            if (lat == null || lon == null) noCoordinates.add(net) else positioned.add(Positioned(net, GeoPoint(lat, lon)))
        }
        allWithPosition = positioned
        withoutCoordinates = noCoordinates
    }


    private fun applyFilter(focus: FocusMode) {
        val q = query
        matched = if (q.isEmpty) allWithPosition else allWithPosition.filter { q.matches(it.net) }

        rebuildOverlay()

        binding.textMapCount.text = if (q.isEmpty) {
            buildString {
                append("${matched.size} rede(s) no mapa")
                if (withoutCoordinates.isNotEmpty()) append(" · ${withoutCoordinates.size} sem posicao")
            }
        } else {
            val onlyInList = withoutCoordinates.count { q.matches(it) }
            buildString {
                append("${matched.size} de ${allWithPosition.size} no mapa")
                if (onlyInList > 0) append(" · $onlyInList achada(s) so na lista (sem posicao)")
            }
        }

        if (q.isEmpty || focus == FocusMode.NONE) return
        when {
            matched.isEmpty() -> {
                if (focus == FocusMode.ALL_MATCHES) {
                    Toast.makeText(this, "Nenhuma rede encontrada no mapa", Toast.LENGTH_SHORT).show()
                }
            }
            matched.size == 1 -> {
                myLocation?.disableFollowLocation()
                val only = matched[0]
                binding.mapView.controller.setZoom(SEARCH_HIT_ZOOM)
                binding.mapView.controller.animateTo(only.point)
                openInfo(only)
            }
            focus == FocusMode.ALL_MATCHES -> {
                myLocation?.disableFollowLocation()
                zoomToPoints(matched.map { it.point })
            }
        }
    }





    private fun rebuildOverlay() {
        pointsOverlay?.let { binding.mapView.overlays.remove(it) }
        val points: List<IGeoPoint> = matched.map { it.point }
        val theme = SimplePointTheme(points, false)
        val style = SimpleFastPointOverlayOptions.getDefaultStyle()
            .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
            .setRadius(6f)
            .setSelectedRadius(14f)
            .setPointStyle(solidPaint(ContextCompat.getColor(this, R.color.signal)))
            .setSelectedPointStyle(solidPaint(ContextCompat.getColor(this, R.color.radar_blip)))
            .setIsClickable(true)
        val overlay = SimpleFastPointOverlay(theme, style)
        overlay.setOnClickListener { _, index ->
            if (index != null && index >= 0 && index < matched.size) openInfo(matched[index])
        }

        binding.mapView.overlays.add(0, overlay)
        pointsOverlay = overlay
        binding.mapView.invalidate()
    }

    private fun solidPaint(color: Int) = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        this.color = color
    }


    private fun openInfo(entry: Positioned) {
        if (infoWindow.isOpen && openBssid == entry.net.bssid) {
            infoWindow.close()
            openBssid = null
            return
        }
        infoWindow.close()
        infoWindow.open(entry.net, entry.point, 0, 0)
        openBssid = entry.net.bssid
    }



    private fun fitAll(stopFollowing: Boolean = true) {
        if (stopFollowing) myLocation?.disableFollowLocation()
        val points = allWithPosition.map { it.point }
        if (points.isEmpty()) {
            Toast.makeText(this, "Nenhuma rede com coordenadas ainda", Toast.LENGTH_LONG).show()
            return
        }
        zoomToPoints(points)
    }

    private fun zoomToPoints(points: List<GeoPoint>) {
        if (points.size == 1) {
            binding.mapView.controller.setZoom(SEARCH_HIT_ZOOM)
            binding.mapView.controller.setCenter(points.first())
            return
        }
        val box = BoundingBox.fromGeoPoints(points)
        if (box.latitudeSpan < MIN_BOX_SPAN_DEG && box.longitudeSpanWithDateLine < MIN_BOX_SPAN_DEG) {

            binding.mapView.controller.setZoom(SEARCH_HIT_ZOOM)
            binding.mapView.controller.setCenter(box.centerWithDateLine)
            return
        }

        binding.mapView.post { binding.mapView.zoomToBoundingBox(box, true, 100) }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        myLocation?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        myLocation?.disableMyLocation()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(applySearchRunnable)
        infoWindow.close()
        binding.mapView.onDetach()
        super.onDestroy()
    }

    companion object {
        private const val USER_ZOOM = 17.0
        private const val SEARCH_HIT_ZOOM = 18.0
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val LIVE_REFRESH_DEBOUNCE_MS = 1200L
        private const val MIN_BOX_SPAN_DEG = 1e-5
    }
}
