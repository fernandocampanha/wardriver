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
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
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

    // redes com posicao ja carregadas do banco: inicialmente as mais proximas da localizacao,
    // depois o que o usuario for revelando ao navegar pelo mapa (viewport). Nunca a tabela inteira.
    private val loaded = LinkedHashMap<String, Positioned>()
    private var matched: List<Positioned> = emptyList()
    private var totalWithPosition = -1
    private var totalWithoutPosition = -1

    private var myLocation: MyLocationNewOverlay? = null

    private var centeredOnUser = false

    private var query = NetworkSearch.Query.parse("")
    private val applySearchRunnable = Runnable { applyFilter(focus = FocusMode.ONLY_IF_SINGLE) }
    private val loadViewportRunnable = Runnable { loadViewport() }

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

        binding.buttonRefreshMap.setOnClickListener { loadViewport() }
        binding.buttonShowAll.setOnClickListener { fitAll() }
        binding.buttonMyLocation.setOnClickListener { recenterOnUser() }

        binding.mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleViewportLoad()
                return true
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                scheduleViewportLoad()
                return true
            }
        })

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

        loadInitial()
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

    /** Primeira carga: so as redes mais proximas da localizacao conhecida (usuario, ultima posicao ou
     *  centro da nuvem de pontos), nunca a tabela inteira. O resto entra sob demanda via [loadViewport]. */
    private fun loadInitial() {
        lifecycleScope.launch {
            val center = referencePoint()
            if (center != null) {
                val nearest = withContext(Dispatchers.IO) {
                    db.networkDao().getNearest(center.latitude, center.longitude, MAP_POINT_LIMIT)
                }
                mergeLoaded(nearest)
            }
            refreshTotals()
            applyFilter(focus = FocusMode.NONE)

            if (!centeredOnUser) fitAll(stopFollowing = false)
        }
    }

    /** Ponto de referencia para a carga inicial: localizacao do usuario/ultima conhecida, ou o
     *  centro da nuvem de pontos ja coletada (quando ainda nao ha fix de GPS). */
    private suspend fun referencePoint(): GeoPoint? {
        myLocation?.myLocation?.let { return it }
        lastKnownLocation()?.let { return GeoPoint(it) }
        val bounds = withContext(Dispatchers.IO) { db.networkDao().getPositionBounds() }
        val minLat = bounds.minLat; val maxLat = bounds.maxLat
        val minLon = bounds.minLon; val maxLon = bounds.maxLon
        if (minLat == null || maxLat == null || minLon == null || maxLon == null) return null
        return GeoPoint((minLat + maxLat) / 2, (minLon + maxLon) / 2)
    }

    /** Carrega sob demanda o que estiver dentro do viewport atual do mapa, sem repetir o que ja
     *  foi carregado. Chamado (debounced) a cada pan/zoom do usuario. */
    private fun loadViewport() {
        val box = binding.mapView.boundingBox ?: return
        lifecycleScope.launch {
            val inView = withContext(Dispatchers.IO) {
                db.networkDao().getInBoundingBox(box.latSouth, box.latNorth, box.lonWest, box.lonEast, MAP_POINT_LIMIT)
            }
            if (mergeLoaded(inView)) applyFilter(focus = FocusMode.NONE)
        }
    }

    private fun scheduleViewportLoad() {
        handler.removeCallbacks(loadViewportRunnable)
        handler.postDelayed(loadViewportRunnable, VIEWPORT_DEBOUNCE_MS)
    }

    /** Funde redes recem-lidas do banco no cache local (sem descartar o que ja estava carregado).
     *  Retorna true se algo novo ou diferente entrou, para só então valer a pena reconstruir o overlay. */
    private fun mergeLoaded(records: List<NetworkRecord>): Boolean {
        var changed = false
        for (net in records) {
            val lat = net.lat
            val lon = net.lon
            if (lat == null || lon == null) continue
            val positioned = Positioned(net, GeoPoint(lat, lon))
            val previous = loaded.put(net.bssid, positioned)
            if (previous == null || previous.net != net) changed = true
        }
        return changed
    }

    private suspend fun refreshTotals() {
        totalWithPosition = withContext(Dispatchers.IO) { db.networkDao().countWithPosition() }
        val total = withContext(Dispatchers.IO) { db.networkDao().count() }
        totalWithoutPosition = total - totalWithPosition
    }

    private fun observeDatabaseChanges() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.networkDao().observeChanges().collectLatest {
                    delay(LIVE_REFRESH_DEBOUNCE_MS)
                    refreshTotals()
                    loadViewport()
                    if (!query.isEmpty) applyFilter(focus = FocusMode.NONE)
                }
            }
        }
    }

    private fun applyFilter(focus: FocusMode) {
        val q = query
        if (q.isEmpty) {
            matched = loaded.values.toList()
            rebuildOverlay()
            updateCountLabel(q)
            return
        }

        // busca: precisa olhar o banco inteiro (não só o que já está carregado no viewport),
        // já que o usuário pode estar procurando uma rede fora da área visível no momento.
        lifecycleScope.launch {
            val (positionedHits, unpositionedHits) = withContext(Dispatchers.IO) {
                db.networkDao().getAllWithCoordinates().filter { q.matches(it) } to
                    db.networkDao().getAllWithoutCoordinates().count { q.matches(it) }
            }
            mergeLoaded(positionedHits)
            matched = positionedHits.map { Positioned(it, GeoPoint(it.lat!!, it.lon!!)) }
            rebuildOverlay()
            updateCountLabel(q, onlyInListCount = unpositionedHits)

            if (focus == FocusMode.NONE) return@launch
            when {
                matched.isEmpty() -> {
                    if (focus == FocusMode.ALL_MATCHES) {
                        Toast.makeText(this@MapActivity, "Nenhuma rede encontrada no mapa", Toast.LENGTH_SHORT).show()
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
    }

    private fun updateCountLabel(q: NetworkSearch.Query, onlyInListCount: Int = 0) {
        binding.textMapCount.text = if (q.isEmpty) {
            buildString {
                append("${matched.size} rede(s) carregada(s) no mapa")
                if (totalWithPosition >= 0) append(" de $totalWithPosition com posição")
                if (totalWithoutPosition > 0) append(" · $totalWithoutPosition sem posição")
            }
        } else {
            buildString {
                append("${matched.size} encontrada(s) no mapa")
                if (onlyInListCount > 0) append(" · $onlyInListCount achada(s) so na lista (sem posicao)")
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
        lifecycleScope.launch {
            // so os extremos (MIN/MAX), nao a tabela inteira: o viewport resultante carrega o
            // que estiver dentro dele sozinho, via loadViewport, quando o pan/zoom terminar.
            val bounds = withContext(Dispatchers.IO) { db.networkDao().getPositionBounds() }
            val minLat = bounds.minLat; val maxLat = bounds.maxLat
            val minLon = bounds.minLon; val maxLon = bounds.maxLon
            if (minLat == null || maxLat == null || minLon == null || maxLon == null) {
                Toast.makeText(this@MapActivity, "Nenhuma rede com coordenadas ainda", Toast.LENGTH_LONG).show()
                return@launch
            }
            zoomToPoints(listOf(GeoPoint(minLat, minLon), GeoPoint(maxLat, maxLon)))
            scheduleViewportLoad()
        }
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
        handler.removeCallbacks(loadViewportRunnable)
        infoWindow.close()
        binding.mapView.onDetach()
        super.onDestroy()
    }

    companion object {
        private const val USER_ZOOM = 17.0
        private const val SEARCH_HIT_ZOOM = 18.0
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val LIVE_REFRESH_DEBOUNCE_MS = 1200L
        private const val VIEWPORT_DEBOUNCE_MS = 400L
        private const val MIN_BOX_SPAN_DEG = 1e-5

        // teto de redes carregadas por vez (viewport ou carga inicial por proximidade); evita o
        // travamento/crash de carregar e desenhar a tabela inteira de uma vez (item 16/T41 do BACKLOG).
        private const val MAP_POINT_LIMIT = 3000
    }
}
