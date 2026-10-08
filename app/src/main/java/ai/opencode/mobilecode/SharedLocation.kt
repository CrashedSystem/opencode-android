@file:PortedFrom("OpenCode/SharedLocation.swift", "083cd94")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.RoundedRectangle
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.URL
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/// A location shared from the composer's + menu: one fix and its nearest
/// address. It travels as a tagged block at the top of the prompt's text,
/// readable by the agent as-is, and the transcript draws it as a card.
data class SharedLocation(
    val latitude: Double,
    val longitude: Double,
    /// Horizontal accuracy in meters, when known.
    val accuracy: Double? = null,
    /// The nearest place's name, when it isn't just the street address.
    val place: String? = null,
    /// The nearest address, on one line.
    val address: String? = null,
    val capturedAt: Instant,
) {
    val coordinateText: String
        get() = String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)

    val mapsURL: URL
        get() {
            val query = "ll=" + String.format(Locale.ROOT, "%.6f,%.6f", latitude, longitude) + "&q=" + (place ?: address ?: "Shared Location")
            return URL(URI("https", "maps.apple.com", "/", query, null).toASCIIString())
        }

    /// The block the agent reads.
    val messageText: String
        get() {
            val lines = mutableListOf(openTag)
            if (place != null) lines += "Place: $place"
            if (address != null) lines += "Address: $address"
            lines += "Coordinates: $coordinateText"
            if (accuracy != null) lines += "Accuracy: ±${accuracy.roundToInt()} m"
            lines += "Shared: ${DateTimeFormatter.ISO_INSTANT.format(capturedAt.truncatedTo(ChronoUnit.SECONDS))}"
            lines += "Map: ${mapsURL.absoluteString}"
            lines += closeTag
            return lines.joinToString("\n")
        }

    /// The prompt's text: the location block, then whatever the user typed.
    fun prompt(with: String): String = if (with.isEmpty()) messageText else messageText + "\n\n" + with

    data class Parsed(val location: SharedLocation, val remainder: String)

    companion object {
        const val openTag = "<shared-location>"
        const val closeTag = "</shared-location>"

        /// A prompt that starts with a location block: the location, and the
        /// user's own text after it.
        fun parse(text: String): Parsed? {
            if (!text.startsWith(openTag)) return null
            val close = text.indexOf(closeTag)
            if (close < 0) return null
            val body = text.substring(openTag.length, close)
            val fields = mutableMapOf<String, String>()
            for (line in body.split("\n").filter { it.isNotEmpty() }) {
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                fields[key] = value
            }
            val parts = (fields["Coordinates"] ?: "").split(",").filter { it.isNotEmpty() }.map { it.trim() }
            if (parts.size != 2) return null
            val latitude = parts[0].toDoubleOrNull() ?: return null
            val longitude = parts[1].toDoubleOrNull() ?: return null
            val accuracy = fields["Accuracy"]?.let { value -> value.filter { it.isDigit() || it == '.' }.toDoubleOrNull() }
            val capturedAt = fields["Shared"]?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: distantPast
            val location = SharedLocation(
                latitude = latitude, longitude = longitude, accuracy = accuracy,
                place = fields["Place"], address = fields["Address"], capturedAt = capturedAt,
            )
            val remainder = text.substring(close + closeTag.length).trim()
            return Parsed(location, remainder)
        }

        private val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")
    }
}

sealed class LocationClientError(message: String) : Exception(message) {
    data object denied : LocationClientError("Location access is off for OpenCode. Turn it on in Settings.")

    data object unavailable : LocationClientError("Couldn’t find your location.")
}

/// One fix of where the phone is, with its nearest address.
class LocationClient(
    var current: suspend () -> SharedLocation,
) {
    companion object : DependencyKey<LocationClient> {
        override val liveValue get() = LocationClient(current = { LiveLocation.current() })

        override val testValue get() = LocationClient(current = { throw LocationClientError.unavailable })

        /// Follows the resumed activity, which asks for When In Use access.
        fun install(application: Application) {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumed = activity
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumed == activity) resumed = null
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    if (resumed == activity) resumed = null
                }
            })
        }
    }
}

var DependencyValues.location: LocationClient
    get() = this[LocationClient]
    set(value) {
        this[LocationClient] = value
    }

@Volatile
private var resumed: Activity? = null

private object LiveLocation {
    /// Good enough to name a street; the first fix is often coarse.
    const val wantedAccuracy = 65f

    /// How long to wait for that before taking the best fix so far.
    val settle = 4.seconds
    val timeout = 15.seconds

    suspend fun current(): SharedLocation {
        val context = OpenCodeApp.instance ?: throw LocationClientError.unavailable
        // Asks for When In Use the first time, precise or approximate.
        if (!ensurePermission(context)) throw LocationClientError.denied
        val fix = bestFix(context)
        var location = SharedLocation(
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracy = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null,
            capturedAt = Instant.ofEpochMilli(fix.time),
        )
        val item = nearestAddress(context, fix)
        if (item != null) {
            val address = item.fullAddress()
            location = location.copy(address = address)
            // "Apple Park, 1 Apple Park Way, …": the place as the title, the
            // street address under it. A bare street address stays as is.
            val name = item.featureName
            if (!name.isNullOrEmpty()) {
                if (address != null && address.startsWith("$name, ")) {
                    location = location.copy(place = name, address = address.drop(name.length + 2))
                } else if (!(address ?: "").startsWith(name)) {
                    location = location.copy(place = name)
                }
            }
        }
        return location
    }

    @SuppressLint("MissingPermission")
    private suspend fun bestFix(context: Context): Location {
        val manager = context.getSystemService(LocationManager::class.java) ?: throw LocationClientError.unavailable
        // Location Services off for the whole phone reads as denied, as on iOS.
        if (!manager.isLocationEnabled) throw LocationClientError.denied
        val precise = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val providers = manager.getProviders(true).filter {
            it == LocationManager.FUSED_PROVIDER || it == LocationManager.NETWORK_PROVIDER || (precise && it == LocationManager.GPS_PROVIDER)
        }
        if (providers.isEmpty()) throw LocationClientError.unavailable
        val fixes = Channel<Location>(Channel.UNLIMITED)
        val listener = LocationListener { fixes.trySend(it) }
        val request = LocationRequest.Builder(1_000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
        try {
            providers.forEach { manager.requestLocationUpdates(it, request, context.mainExecutor, listener) }
            val started = TimeSource.Monotonic.markNow()
            var best: Location? = null
            while (true) {
                val wait = (if (best == null) timeout else settle) - started.elapsedNow()
                if (!wait.isPositive()) break
                val fix = withTimeoutOrNull(wait) { fixes.receive() } ?: continue
                if (!fix.hasAccuracy()) continue
                if (best == null || fix.accuracy < best.accuracy) best = fix
                if (fix.accuracy <= wantedAccuracy) return fix
            }
            return best ?: throw LocationClientError.unavailable
        } finally {
            manager.removeUpdates(listener)
        }
    }

    private suspend fun nearestAddress(context: Context, fix: Location): Address? {
        if (!Geocoder.isPresent()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context).getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull()
            }.getOrNull()
        }
    }

    private suspend fun ensurePermission(context: Context): Boolean {
        if (isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return true
        }
        val activity = resumed as? ComponentActivity ?: return false
        return suspendCancellableCoroutine { continuation ->
            lateinit var launcher: ActivityResultLauncher<Array<String>>
            launcher = activity.activityResultRegistry.register("shared-location", ActivityResultContracts.RequestMultiplePermissions()) { results ->
                launcher.unregister()
                continuation.resume(results.values.any { it })
            }
            continuation.invokeOnCancellation { activity.runOnUiThread { launcher.unregister() } }
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun isGranted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/// The address on one line without its country, as iOS's
/// `fullAddress(includingRegion: false, singleLine: true)` gives it.
private fun Address.fullAddress(): String? {
    val line = (0..maxAddressLineIndex).mapNotNull { getAddressLine(it) }.joinToString(", ")
    if (line.isEmpty()) return null
    val country = countryName ?: return line
    return line.removeSuffix(", $country")
}

/// A shared location in the transcript and the composer: a map with a pin,
/// the place or address, and the coordinates. A tap opens it in Maps.
@Composable
fun SharedLocationCard(
    location: SharedLocation,
    isPending: Boolean = false,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val openURL = LocalUriHandler.current
    val title = location.place ?: location.address ?: "Shared Location"
    val subtitle = if (location.place != null) location.address else null
    // The transcript animates a pending message's promotion.
    val opacity by animateFloatAsState(
        if (isPending) 0.6f else 1f,
        animationSpec = if (LocalReduceMotion.current) snap() else tween(350, easing = EaseOut),
        label = "pending",
    )
    Column(
        Modifier
            .width(if (compact) 220.dp else 260.dp)
            .alpha(opacity)
            .clip(RoundedRectangle(TranscriptMetrics.cardCornerRadius.dp))
            .background(SystemColors.secondarySystemBackground)
            .clickable(role = Role.Button) {
                // The phone's maps app, through a geo: link; iOS opens Apple Maps.
                runCatching { context.startActivity(geoIntent(location, title)) }
                    .onFailure { if (it is ActivityNotFoundException) openURL.openUri(location.mapsURL.absoluteString) }
            }
            .clearAndSetSemantics { contentDescription = "Location: $title${subtitle?.let { ", $it" } ?: ""}. Opens in Maps" },
    ) {
        SharedLocationMap(Modifier.fillMaxWidth().height(if (compact) 72.dp else 120.dp))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemImage(
                    "location.fill",
                    null,
                    Modifier.symbolSize(TextStyles.caption).alignBy { (it.measuredHeight * 0.8f).toInt() },
                    tint = SystemColors.systemBlue,
                )
                Text(
                    title,
                    Modifier.alignByBaseline(),
                    style = TextStyles.subheadline.semibold,
                    color = SystemColors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Text(subtitle, style = TextStyles.caption, color = SystemColors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                location.coordinateText + (if (compact) "" else location.accuracy?.let { " · ±${it.roundToInt()} m" } ?: ""),
                style = TextStyles.caption2.copy(fontFamily = FontFamily.Monospace),
                color = SystemColors.tertiaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/// A still map of the spot. Android has no system map snapshotter, so it keeps
/// the placeholder iOS shows until its snapshot lands: the fill and the pin.
@Composable
private fun SharedLocationMap(modifier: Modifier) {
    Box(modifier.background(SystemColors.tertiarySystemFill), contentAlignment = Alignment.Center) {
        MapPin()
    }
}

/// `mappin.circle.fill` in the palette iOS draws it with: a white pin on red.
@Composable
private fun MapPin() {
    Canvas(Modifier.size(26.dp).shadow(2.dp, CircleShape).background(SystemColors.systemRed, CircleShape)) {
        val side = size.minDimension
        drawCircle(Color.White, radius = side * 0.17f, center = Offset(side / 2, side * 0.38f))
        drawLine(
            Color.White,
            start = Offset(side / 2, side * 0.5f),
            end = Offset(side / 2, side * 0.78f),
            strokeWidth = side * 0.07f,
            cap = StrokeCap.Round,
        )
    }
}

private fun geoIntent(location: SharedLocation, title: String): Intent {
    val coordinates = String.format(Locale.ROOT, "%.6f,%.6f", location.latitude, location.longitude)
    return Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=$coordinates(${Uri.encode(title)})"))
}
