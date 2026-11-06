package co.edu.unal.reto9gps

import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

// =====================================================================
// MODELOS DE DATOS
// =====================================================================

/** Un punto de interés (POI) que se pinta en el mapa. */
data class Poi(
    val name: String,            // Nombre (lo que pide el reto mostrar)
    val typeLabel: String,       // "Hospital", "Museo", ...
    val group: PoiGroup,         // Grupo, para elegir el color del pin
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,  // Distancia desde el centro de búsqueda
    val address: String?         // Dirección, si OpenStreetMap la tiene
)

/** Resultado de convertir una dirección escrita en coordenadas. */
data class GeocodeResult(
    val latitude: Double,
    val longitude: Double,
    val displayName: String
)

/** Grupos de lugares: cada uno tiene un color de pin distinto. */
enum class PoiGroup(val color: Int) {
    HEALTH(Color.rgb(229, 57, 53)),      // rojo
    TOURISM(Color.rgb(67, 160, 71)),     // verde
    FOOD(Color.rgb(251, 140, 0)),        // naranja
    PHARMACY(Color.rgb(0, 137, 123)),    // verde azulado
    EDUCATION(Color.rgb(30, 136, 229)),  // azul
    OTHER(Color.rgb(142, 36, 170))       // morado
}

// Filtros en el lenguaje de Overpass. "~" significa "coincide con la expresión".
// El "\$" es solo el símbolo $ escapado para Kotlin.
private const val FILTER_HEALTH = "[\"amenity\"~\"^(hospital|clinic)\$\"]"
private const val FILTER_TOURISM =
    "[\"tourism\"~\"^(attraction|museum|viewpoint|gallery|zoo|theme_park)\$\"]"
private const val FILTER_FOOD = "[\"amenity\"~\"^(restaurant|cafe|fast_food)\$\"]"
private const val FILTER_PHARMACY = "[\"amenity\"=\"pharmacy\"]"
private const val FILTER_EDUCATION = "[\"amenity\"~\"^(school|university|college)\$\"]"

/**
 * Categorías que el usuario elige en Ajustes.
 * [key] debe coincidir con los valores de @array/category_values.
 */
enum class PoiCategory(val key: String, val labelRes: Int, val filters: List<String>) {
    HOSPITAL("hospital", R.string.category_hospital, listOf(FILTER_HEALTH)),
    TOURISM("tourism", R.string.category_tourism, listOf(FILTER_TOURISM)),
    FOOD("food", R.string.category_food, listOf(FILTER_FOOD)),
    PHARMACY("pharmacy", R.string.category_pharmacy, listOf(FILTER_PHARMACY)),
    EDUCATION("education", R.string.category_education, listOf(FILTER_EDUCATION)),
    ALL(
        "all", R.string.category_all,
        listOf(FILTER_HEALTH, FILTER_TOURISM, FILTER_PHARMACY, FILTER_EDUCATION)
    );

    companion object {
        fun fromKey(key: String?): PoiCategory = entries.firstOrNull { it.key == key } ?: HOSPITAL
    }
}

// =====================================================================
// REPOSITORIO: habla con los servicios gratuitos de OpenStreetMap
//   - Overpass API  → busca lugares cerca de unas coordenadas
//   - Nominatim     → dirección ⇄ coordenadas
// =====================================================================

class OsmRepository {

    companion object {
        // Si el primer servidor de Overpass está ocupado, se prueba el segundo
        private val OVERPASS_URLS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
        private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org"

        // OpenStreetMap exige identificar la app que hace las peticiones
        private const val USER_AGENT = "Reto9GPS/1.0 (co.edu.unal.reto9gps; app educativa)"

        // Máximo de marcadores a dibujar (los más cercanos), para que el mapa no se ponga lento
        private const val MAX_POIS = 150
    }

    /**
     * Busca los lugares de [category] que estén a menos de [radiusMeters]
     * de (lat, lon). Se ejecuta en un hilo de fondo (Dispatchers.IO) porque
     * usar internet en el hilo principal congelaría la app.
     */
    suspend fun searchPois(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        category: PoiCategory
    ): List<Poi> = withContext(Dispatchers.IO) {
        val query = buildOverpassQuery(lat, lon, radiusMeters, category)
        val body = "data=" + URLEncoder.encode(query, "UTF-8")

        var lastError: Exception? = null
        for (url in OVERPASS_URLS) {
            try {
                val json = httpPost(url, body)
                return@withContext parseOverpass(json, lat, lon, radiusMeters)
            } catch (e: IOException) {
                lastError = e  // probamos con el siguiente servidor
            }
        }
        throw lastError ?: IOException("Sin respuesta de Overpass")
    }

    /** Coordenadas → dirección legible (ej: "Calle 26, Teusaquillo, Bogotá"). */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val url = String.format(
            Locale.US,
            "%s/reverse?format=jsonv2&zoom=18&lat=%.6f&lon=%.6f",
            NOMINATIM_URL, lat, lon
        )
        val json = JSONObject(httpGet(url))
        json.optString("display_name").takeIf { it.isNotBlank() }?.let(::shortenAddress)
    }

    /** Dirección escrita → coordenadas. Devuelve null si no se encuentra. */
    suspend fun geocode(address: String): GeocodeResult? = withContext(Dispatchers.IO) {
        val url = "$NOMINATIM_URL/search?format=jsonv2&limit=1&q=" +
                URLEncoder.encode(address, "UTF-8")
        val results = JSONArray(httpGet(url))
        if (results.length() == 0) return@withContext null
        val first = results.getJSONObject(0)
        GeocodeResult(
            latitude = first.getString("lat").toDouble(),
            longitude = first.getString("lon").toDouble(),
            displayName = shortenAddress(first.optString("display_name"))
        )
    }

    // -----------------------------------------------------------------
    // Detalles internos
    // -----------------------------------------------------------------

    /**
     * Arma la consulta Overpass. Ejemplo para hospitales a 2 km:
     *
     *   [out:json][timeout:25];
     *   (
     *     nwr["amenity"~"^(hospital|clinic)$"]["name"](around:2000,4.638100,-74.084000);
     *   );
     *   out body center 500;
     *
     * - nwr = nodos, vías y relaciones (un hospital puede ser un punto o un edificio)
     * - ["name"] = solo lugares que tengan nombre (el reto pide mostrarlo)
     * - around:radio,lat,lon = dentro del círculo
     * - center = para edificios, devuelve su punto central
     *
     * Ojo: se usa Locale.US para que los decimales salgan con punto (4.63)
     * y no con coma (4,63), que es lo que haría un teléfono en español.
     */
    private fun buildOverpassQuery(
        lat: Double, lon: Double, radiusMeters: Int, category: PoiCategory
    ): String {
        val around = String.format(Locale.US, "(around:%d,%.6f,%.6f)", radiusMeters, lat, lon)
        val lines = category.filters.joinToString("\n") { filter ->
            "  nwr$filter[\"name\"]$around;"
        }
        return "[out:json][timeout:25];\n(\n$lines\n);\nout body center 500;"
    }

    /** Convierte la respuesta JSON de Overpass en una lista de [Poi]. */
    private fun parseOverpass(json: String, lat: Double, lon: Double, radiusMeters: Int): List<Poi> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val center = GeoPoint(lat, lon)
        val seen = HashSet<String>()
        val pois = ArrayList<Poi>()

        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: continue
            val name = tags.optString("name").trim()
            if (name.isEmpty()) continue

            // Los nodos traen lat/lon; los edificios traen "center"
            val point = when {
                element.has("lat") -> GeoPoint(element.getDouble("lat"), element.getDouble("lon"))
                element.has("center") -> element.getJSONObject("center")
                    .let { GeoPoint(it.getDouble("lat"), it.getDouble("lon")) }
                else -> continue
            }

            val distance = center.distanceToAsDouble(point)
            if (distance > radiusMeters) continue

            // Evitar duplicados (mismo nombre en casi el mismo sitio)
            val key = String.format(Locale.US, "%s|%.3f|%.3f", name, point.latitude, point.longitude)
            if (!seen.add(key)) continue

            val tagValue = tags.optString("amenity").ifEmpty { tags.optString("tourism") }
            val street = tags.optString("addr:street")
            val number = tags.optString("addr:housenumber")
            val address = listOf(street, number).filter { it.isNotBlank() }
                .joinToString(" # ").ifBlank { null }

            pois += Poi(
                name = name,
                typeLabel = typeLabel(tagValue),
                group = groupOf(tagValue),
                latitude = point.latitude,
                longitude = point.longitude,
                distanceMeters = distance,
                address = address
            )
        }
        // Primero los más cercanos
        return pois.sortedBy { it.distanceMeters }.take(MAX_POIS)
    }

    private fun groupOf(tag: String): PoiGroup = when (tag) {
        "hospital", "clinic" -> PoiGroup.HEALTH
        "attraction", "museum", "viewpoint", "gallery", "zoo", "theme_park" -> PoiGroup.TOURISM
        "restaurant", "cafe", "fast_food" -> PoiGroup.FOOD
        "pharmacy" -> PoiGroup.PHARMACY
        "school", "university", "college" -> PoiGroup.EDUCATION
        else -> PoiGroup.OTHER
    }

    private fun typeLabel(tag: String): String = when (tag) {
        "hospital" -> "Hospital"
        "clinic" -> "Clínica"
        "attraction" -> "Atracción turística"
        "museum" -> "Museo"
        "viewpoint" -> "Mirador"
        "gallery" -> "Galería"
        "zoo" -> "Zoológico"
        "theme_park" -> "Parque temático"
        "restaurant" -> "Restaurante"
        "cafe" -> "Café"
        "fast_food" -> "Comida rápida"
        "pharmacy" -> "Farmacia"
        "school" -> "Colegio"
        "university" -> "Universidad"
        "college" -> "Instituto"
        else -> "Lugar de interés"
    }

    /** Nominatim devuelve direcciones muy largas; dejamos las 4 primeras partes. */
    private fun shortenAddress(full: String): String =
        full.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(4).joinToString(", ")

    private fun httpPost(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 40_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", "es")
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
