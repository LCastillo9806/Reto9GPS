package co.edu.unal.reto9gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import co.edu.unal.reto9gps.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.infowindow.InfoWindow
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * RETO 9: ACCEDIENDO AL GPS
 *
 * Flujo general:
 *  1. Pedir permiso de ubicación (en tiempo de ejecución).
 *  2. Leer la posición con LocationManager (GPS o red).
 *  3. Buscar en OpenStreetMap los lugares cercanos dentro del radio (km) de Ajustes.
 *  4. Dibujar en el mapa: mi posición, el círculo del radio y un pin con nombre por lugar.
 *
 * También se puede escribir una DIRECCIÓN (menú 🔍) en lugar de usar el GPS.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Claves de las preferencias (iguales a las de res/xml/preferences.xml)
        const val PREF_RADIUS_KM = "radius_km"
        const val PREF_CATEGORY = "poi_category"
        const val PREF_SHOW_LABELS = "show_labels"

        // Si me muevo más de esta distancia, se vuelven a buscar lugares
        private const val RESEARCH_DISTANCE_M = 300.0

        // Claves para guardar el estado al girar la pantalla
        private const val STATE_LAT = "state_lat"
        private const val STATE_LON = "state_lon"
        private const val STATE_USING_GPS = "state_using_gps"
        private const val STATE_LABEL = "state_label"
    }

    /** Parámetros con los que se hizo la última búsqueda (para saber si cambiaron). */
    private data class SearchParams(
        val radiusKm: Int,
        val category: PoiCategory,
        val showLabels: Boolean
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var locationManager: LocationManager
    private lateinit var prefs: SharedPreferences
    private val repository = OsmRepository()

    // ---- Estado ----
    private var searchCenter: GeoPoint? = null   // centro de la búsqueda actual
    private var centerLabel: String? = null      // dirección del centro
    private var usingGps = true                  // true = GPS, false = dirección escrita
    private var lastParams: SearchParams? = null
    private var searchJob: Job? = null
    private var searchCounter = 0
    private var currentLocationSignal: CancellationSignal? = null
    private var receivingUpdates = false
    private var pendingCheckOnResume = false     // volvimos de los ajustes del sistema

    // ---- Elementos dibujados en el mapa ----
    private var myLocationMarker: Marker? = null
    private var addressMarker: Marker? = null
    private var radiusCircle: Polygon? = null
    private val poiMarkers = mutableListOf<Marker>()

    // =================================================================
    // 1. PERMISOS EN TIEMPO DE EJECUCIÓN
    // =================================================================

    /**
     * "Lanzador" que muestra el diálogo del sistema "¿Permitir que la app
     * acceda a la ubicación?" y nos entrega la respuesta del usuario.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            startLocation()  // ¡Permiso concedido!
        } else {
            onPermissionDenied()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** Punto de entrada: revisa permisos y, si todo está bien, obtiene la ubicación. */
    private fun checkPermissionsAndLocate() {
        when {
            hasLocationPermission() -> startLocation()

            // El usuario ya dijo "no" una vez: le explicamos por qué lo necesitamos
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.permission_title)
                    .setMessage(R.string.permission_rationale)
                    .setPositiveButton(R.string.permission_allow) { _, _ -> requestPermissions() }
                    .setNegativeButton(R.string.cancel) { _, _ -> onPermissionDenied() }
                    .show()

            else -> requestPermissions()
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun onPermissionDenied() {
        setLoading(false)
        binding.txtInfo.setText(R.string.permission_denied)

        // Si se negó "para siempre", solo se puede activar desde los ajustes de la app
        if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_title)
                .setMessage(R.string.permission_permanently_denied)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    pendingCheckOnResume = true
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // =================================================================
    // 2. OBTENER LA UBICACIÓN GPS (LocationManager, incluido en Android)
    // =================================================================

    /** Se llama cada vez que llega una nueva posición mientras la app está abierta. */
    private val locationListener = LocationListenerCompat { location ->
        onNewLocation(location, fromUpdates = true)
    }

    /**
     * Elige de dónde sacar la ubicación:
     *  - GPS_PROVIDER: satélites, muy precisa (necesita permiso FINE)
     *  - NETWORK_PROVIDER: wifi/antenas, aproximada
     */
    private fun bestProvider(): String? {
        val gpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkOn = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        return when {
            gpsOn && hasFinePermission() -> LocationManager.GPS_PROVIDER
            networkOn -> LocationManager.NETWORK_PROVIDER
            // Desde Android 12 el permiso aproximado también puede usar el GPS
            gpsOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> LocationManager.GPS_PROVIDER
            else -> null
        }
    }

    @SuppressLint("MissingPermission") // los permisos se revisan antes de llamar
    private fun startLocation() {
        if (!hasLocationPermission()) return

        // ¿La ubicación del teléfono está encendida?
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_off_title)
                .setMessage(R.string.location_off_message)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    pendingCheckOnResume = true
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val provider = bestProvider() ?: run {
            binding.txtInfo.setText(R.string.no_provider)
            return
        }

        setLoading(true)
        binding.txtInfo.setText(R.string.getting_location)

        // Pedimos UNA ubicación "fresca"
        currentLocationSignal?.cancel()
        val signal = CancellationSignal()
        currentLocationSignal = signal
        LocationManagerCompat.getCurrentLocation(
            locationManager, provider, signal, ContextCompat.getMainExecutor(this)
        ) { location: Location? ->
            // Si no llega, usamos la última conocida
            val result = location ?: lastKnownLocation()
            if (result != null) {
                onNewLocation(result, fromUpdates = false)
            } else {
                setLoading(false)
                binding.txtInfo.setText(R.string.error_location)
            }
        }

        // Y además seguimos escuchando cambios (por si el usuario se mueve)
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? =
        locationManager.getProviders(true)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (receivingUpdates || !hasLocationPermission()) return
        val provider = bestProvider() ?: return
        // Como máximo una actualización cada 5 s, y solo si me moví 20 m o más
        val request = LocationRequestCompat.Builder(5_000L)
            .setMinUpdateDistanceMeters(20f)
            .build()
        LocationManagerCompat.requestLocationUpdates(
            locationManager, provider, request, ContextCompat.getMainExecutor(this), locationListener
        )
        receivingUpdates = true
    }

    private fun stopLocationUpdates() {
        if (!receivingUpdates) return
        LocationManagerCompat.removeUpdates(locationManager, locationListener)
        receivingUpdates = false
    }

    /** Llega una posición nueva: movemos el punto azul y, si hace falta, buscamos lugares. */
    private fun onNewLocation(location: Location, fromUpdates: Boolean) {
        val point = GeoPoint(location.latitude, location.longitude)
        updateMyLocationMarker(point, location.accuracy)

        if (!usingGps) return  // estamos viendo una dirección escrita, no la del GPS

        val center = searchCenter
        val movedFar = center == null || center.distanceToAsDouble(point) > RESEARCH_DISTANCE_M
        if (!fromUpdates || movedFar) {
            searchCenter = point
            centerLabel = null
            searchPlaces()
        }
    }

    // =================================================================
    // 3. BUSCAR LUGARES CERCANOS (OpenStreetMap / Overpass)
    // =================================================================

    private fun currentParams() = SearchParams(
        radiusKm = prefs.getInt(PREF_RADIUS_KM, 2).coerceIn(1, 20),
        category = PoiCategory.fromKey(prefs.getString(PREF_CATEGORY, PoiCategory.HOSPITAL.key)),
        showLabels = prefs.getBoolean(PREF_SHOW_LABELS, true)
    )

    private fun searchPlaces() {
        val center = searchCenter ?: return
        val params = currentParams()
        lastParams = params

        searchJob?.cancel()   // si había una búsqueda en curso, la cancelamos
        val myId = ++searchCounter

        setLoading(true)
        binding.txtInfo.text = getString(R.string.searching_places, params.radiusKm)
        drawSearchArea(center, params.radiusKm)

        // lifecycleScope.launch = corrutina: la búsqueda corre "en paralelo"
        // y la pantalla no se congela mientras llega la respuesta.
        searchJob = lifecycleScope.launch {
            // La dirección se pide al mismo tiempo que los lugares
            val addressTask = async {
                centerLabel ?: runCatching {
                    repository.reverseGeocode(center.latitude, center.longitude)
                }.getOrNull()
            }
            try {
                val pois = repository.searchPois(
                    center.latitude, center.longitude, params.radiusKm * 1000, params.category
                )
                drawPois(pois, params)
                centerLabel = addressTask.await()
                showInfo(center, params, pois.size)
            } catch (e: CancellationException) {
                throw e  // búsqueda cancelada a propósito: no es un error
            } catch (e: Exception) {
                binding.txtInfo.setText(R.string.error_search)
                Toast.makeText(this@MainActivity, R.string.error_search, Toast.LENGTH_LONG).show()
            } finally {
                if (myId == searchCounter) setLoading(false)
            }
        }
    }

    /** Escribe en la franja superior: dirección, coordenadas, radio y cuántos lugares hay. */
    private fun showInfo(center: GeoPoint, params: SearchParams, count: Int) {
        val address = getString(R.string.info_address, centerLabel ?: getString(R.string.unknown_address))
        val coords = String.format(
            Locale.getDefault(), getString(R.string.info_coordinates),
            center.latitude, center.longitude, params.radiusKm
        )
        val result = if (count == 0) getString(R.string.info_none)
        else getString(R.string.info_count, getString(params.category.labelRes), count)
        binding.txtInfo.text = "$address\n$coords\n$result"
    }

    /** Busca una dirección escrita por el usuario y la usa como centro. */
    private fun showAddressSearchDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.search_title)
            .setView(container)
            .setPositiveButton(R.string.search_button) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) geocodeAndSearch(text)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun geocodeAndSearch(address: String) {
        setLoading(true)
        lifecycleScope.launch {
            val result = runCatching { repository.geocode(address) }.getOrNull()
            if (result == null) {
                setLoading(false)
                Toast.makeText(this@MainActivity, R.string.search_not_found, Toast.LENGTH_LONG).show()
                return@launch
            }
            usingGps = false
            searchCenter = GeoPoint(result.latitude, result.longitude)
            centerLabel = result.displayName
            searchPlaces()
        }
    }

    // =================================================================
    // 4. DIBUJAR EN EL MAPA (osmdroid)
    // =================================================================

    private fun setupMap() {
        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)  // estilo estándar de OpenStreetMap
            setMultiTouchControls(true)               // zoom con dos dedos
            controller.setZoom(15.0)
            // Posición inicial mientras llega el GPS: Bogotá
            controller.setCenter(GeoPoint(4.6381, -74.0840))
        }
    }

    /** Punto azul "Estás aquí". */
    private fun updateMyLocationMarker(point: GeoPoint, accuracy: Float) {
        val map = binding.map
        val marker = myLocationMarker ?: Marker(map).apply {
            icon = AppCompatResources.getDrawable(this@MainActivity, R.drawable.ic_me)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = getString(R.string.you_are_here)
        }.also { myLocationMarker = it }

        marker.position = point
        marker.snippet = getString(R.string.accuracy, accuracy.roundToInt())
        bringToFront(marker)
        map.invalidate()
    }

    /** Dibuja el círculo del radio (y el pin de la dirección buscada) y ajusta el zoom. */
    private fun drawSearchArea(center: GeoPoint, radiusKm: Int) {
        val map = binding.map
        InfoWindow.closeAllInfoWindowsOn(map)

        radiusCircle?.let { map.overlays.remove(it) }
        val circlePoints = Polygon.pointsAsCircle(center, radiusKm * 1000.0)
        radiusCircle = Polygon(map).apply {
            points = circlePoints
            fillPaint.color = Color.argb(30, 25, 118, 210)
            outlinePaint.color = Color.rgb(25, 118, 210)
            outlinePaint.strokeWidth = 3 * resources.displayMetrics.density
            setOnClickListener { _, _, _ -> false }  // tocar el círculo no abre nada
        }
        map.overlays.add(0, radiusCircle)  // índice 0 = queda debajo de los pines

        addressMarker?.let { map.overlays.remove(it) }
        addressMarker = null
        if (!usingGps) {
            val (icon, anchorV) = createPinIcon(null, Color.BLACK)
            addressMarker = Marker(map).apply {
                position = center
                this.icon = icon
                setAnchor(Marker.ANCHOR_CENTER, anchorV)
                title = getString(R.string.searched_address)
                snippet = centerLabel
            }
            map.overlays.add(addressMarker)
        }

        // Zoom para que todo el círculo quepa en pantalla
        map.post {
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(circlePoints), true, 60)
        }
        myLocationMarker?.let { bringToFront(it) }
        map.invalidate()
    }

    /** Un pin de color por cada lugar; al tocarlo aparece su nombre, tipo y distancia. */
    private fun drawPois(pois: List<Poi>, params: SearchParams) {
        val map = binding.map
        map.overlays.removeAll(poiMarkers.toSet())
        poiMarkers.clear()

        for (poi in pois) {
            val (icon, anchorV) = createPinIcon(
                if (params.showLabels) poi.name else null,
                poi.group.color
            )
            val marker = Marker(map).apply {
                position = GeoPoint(poi.latitude, poi.longitude)
                this.icon = icon
                setAnchor(Marker.ANCHOR_CENTER, anchorV)
                title = poi.name                                      // ← el NOMBRE del lugar
                snippet = "${poi.typeLabel} · ${formatDistance(poi.distanceMeters)}"
                subDescription = poi.address
            }
            poiMarkers += marker
        }
        map.overlays.addAll(poiMarkers)
        addressMarker?.let { bringToFront(it) }
        myLocationMarker?.let { bringToFront(it) }
        map.invalidate()
    }

    /** Pone un elemento encima de los demás en el mapa. */
    private fun bringToFront(overlay: Overlay) {
        binding.map.overlays.remove(overlay)
        binding.map.overlays.add(overlay)
    }

    /**
     * Crea la imagen del marcador: un pin del color indicado y, si hay
     * [label], el nombre escrito encima (con borde blanco para que se lea).
     * Devuelve también dónde está la punta del pin (anchorV), para que la
     * punta quede exactamente sobre la coordenada.
     */
    private fun createPinIcon(label: String?, color: Int): Pair<Drawable, Float> {
        val density = resources.displayMetrics.density
        val pin = AppCompatResources.getDrawable(this, R.drawable.ic_pin)!!.mutate()
        DrawableCompat.setTint(pin, color)
        val pinSize = (36 * density).toInt()

        val text = label?.let { if (it.length > 28) it.take(26) + "…" else it }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
            )
            typeface = Typeface.DEFAULT_BOLD
            this.color = Color.rgb(33, 33, 33)
        }
        val haloPaint = Paint(textPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            this.color = Color.WHITE
        }
        val fm = textPaint.fontMetrics
        val textWidth = text?.let { textPaint.measureText(it) } ?: 0f
        val textHeight = if (text != null) ceil(fm.descent - fm.ascent + 2 * density).toInt() else 0

        val width = max(pinSize, ceil(textWidth + 8 * density).toInt())
        val height = textHeight + pinSize
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (text != null) {
            val x = (width - textWidth) / 2f
            val y = -fm.ascent + density
            canvas.drawText(text, x, y, haloPaint)
            canvas.drawText(text, x, y, textPaint)
        }
        val left = (width - pinSize) / 2
        pin.setBounds(left, textHeight, left + pinSize, height)
        pin.draw(canvas)

        // En ic_pin.xml la punta está en y=22 de 24
        val tipY = textHeight + pinSize * 22f / 24f
        return BitmapDrawable(resources, bitmap) to (tipY / height)
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) "${meters.roundToInt()} m"
        else String.format(Locale.getDefault(), "%.1f km", meters / 1000)

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    // =================================================================
    // CICLO DE VIDA DE LA ACTIVIDAD
    // =================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // Guarda los valores por defecto de Ajustes la primera vez (radio = 2 km...)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        // osmdroid: configuración OBLIGATORIA antes de crear el MapView.
        // El "user agent" identifica a la app ante los servidores de OpenStreetMap.
        Configuration.getInstance().load(applicationContext, prefs)
        Configuration.getInstance().userAgentValue = packageName

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setSupportActionBar(binding.toolbar)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        setupMap()

        binding.fabLocate.setOnClickListener {
            usingGps = true
            centerLabel = null
            checkPermissionsAndLocate()
        }

        // Si se giró la pantalla, recuperamos dónde estábamos
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_LAT)) {
            searchCenter = GeoPoint(
                savedInstanceState.getDouble(STATE_LAT),
                savedInstanceState.getDouble(STATE_LON)
            )
            usingGps = savedInstanceState.getBoolean(STATE_USING_GPS, true)
            centerLabel = savedInstanceState.getString(STATE_LABEL)
            searchPlaces()
        } else {
            checkPermissionsAndLocate()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()  // osmdroid necesita saber cuándo la pantalla está visible

        if (pendingCheckOnResume) {
            // Volvimos de los ajustes del sistema: intentamos otra vez
            pendingCheckOnResume = false
            if (hasLocationPermission()) startLocation()
        } else if (hasLocationPermission()) {
            startLocationUpdates()
        }

        // Si se cambió el radio/tipo en Ajustes, volvemos a buscar
        if (searchCenter != null && lastParams != null && lastParams != currentParams()) {
            searchPlaces()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        stopLocationUpdates()  // ahorrar batería cuando la app no se ve
    }

    override fun onDestroy() {
        currentLocationSignal?.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        searchCenter?.let {
            outState.putDouble(STATE_LAT, it.latitude)
            outState.putDouble(STATE_LON, it.longitude)
            outState.putBoolean(STATE_USING_GPS, usingGps)
            outState.putString(STATE_LABEL, centerLabel)
        }
    }

    // =================================================================
    // MENÚ
    // =================================================================

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> {
            showAddressSearchDialog(); true
        }
        R.id.action_refresh -> {
            if (searchCenter != null) searchPlaces() else checkPermissionsAndLocate()
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
