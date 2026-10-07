@file:PortedFrom("OpenCode/AttachmentCameraLenses.swift", "553a407")

package ai.opencode.mobilecode

import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the camera seam reports about the active capture device, in the
 * device's own zoom space (a virtual device's 1.0 is its widest lens).
 */
data class CameraDeviceDescription(
    var position: Position,
    /** Physical lenses behind the device, any order. */
    var lenses: List<Lens>,
    /** `virtualDeviceSwitchOverVideoZoomFactors`: where each narrower lens takes over. */
    var switchOverFactors: List<Double> = emptyList(),
    var minZoom: Double = 1.0,
    var maxZoom: Double = 1.0,
    /** `secondaryNativeResolutionZoomFactors`: full-quality sensor crops, like Camera's 2×. */
    var nativeCropFactors: List<Double> = emptyList(),
) {
    enum class Position { back, front }

    enum class Lens { ultraWide, wide, telephoto }
}

/**
 * Lens buttons and zoom limits for one device. Displayed factors are relative
 * to the wide lens, so 1× is the wide lens even when the device's 1.0 is the
 * ultra wide.
 */
class CameraZoomModel(device: CameraDeviceDescription) {
    class Preset(
        val lens: CameraDeviceDescription.Lens,
        val deviceFactor: Double,
        val displayFactor: Double,
    ) {
        val id: Double get() = displayFactor

        val accessibilityLabel: String
            get() = when (lens) {
                CameraDeviceDescription.Lens.ultraWide -> "Ultra Wide"
                CameraDeviceDescription.Lens.wide ->
                    if (displayFactor > 1.01) "Wide ${number(displayFactor)}×" else "Wide"
                CameraDeviceDescription.Lens.telephoto -> "Telephoto ${number(displayFactor)}×"
            }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Preset) return false
            return lens == other.lens && deviceFactor == other.deviceFactor && displayFactor == other.displayFactor
        }

        override fun hashCode(): Int {
            var result = lens.hashCode()
            result = 31 * result + deviceFactor.hashCode()
            result = 31 * result + displayFactor.hashCode()
            return result
        }
    }

    /** Device factor shown as 1×. */
    val wideFactor: Double

    val presets: List<Preset>

    /** Usable device zoom range. */
    val range: ClosedFloatingPointRange<Double>

    init {
        val lenses = device.lenses.toSet().sortedBy { it.ordinal }
        // The widest lens starts at 1.0; each narrower one at its switch-over factor.
        val factors = mutableListOf<Pair<CameraDeviceDescription.Lens, Double>>()
        for ((index, lens) in lenses.withIndex()) {
            if (index == 0) {
                factors.add(lens to 1.0)
            } else if (index - 1 < device.switchOverFactors.size) {
                factors.add(lens to device.switchOverFactors[index - 1])
            }
        }
        if (factors.isEmpty()) factors.add(CameraDeviceDescription.Lens.wide to 1.0)
        val wide = factors.firstOrNull { it.first == CameraDeviceDescription.Lens.wide }?.second ?: factors[0].second
        wideFactor = wide

        val lower = max(1.0, device.minZoom)
        val cap = if (device.position == CameraDeviceDescription.Position.front) {
            maxDisplayZoom.front
        } else {
            maxDisplayZoom.back
        }
        val longestLens = factors.maxOf { it.second }
        val upper = max(lower, min(max(device.maxZoom, lower), max(wideFactor * cap, longestLens)))
        val zoomRange = lower..upper
        range = zoomRange

        val built = factors
            .filter { zoomRange.contains(it.second) }
            .map { Preset(it.first, it.second, it.second / wideFactor) }
            .toMutableList()
        // Native crops of the wide lens (Camera's 2×), short of the next lens.
        val nextLens = factors.firstOrNull { it.second > wideFactor }?.second ?: Double.POSITIVE_INFINITY
        for (crop in device.nativeCropFactors.sorted()) {
            if (crop > wideFactor && crop < nextLens && zoomRange.contains(crop)) {
                val display = crop / wideFactor
                if (built.none { abs(it.displayFactor - display) < 0.1 }) {
                    built.add(Preset(CameraDeviceDescription.Lens.wide, crop, display))
                }
            }
        }
        presets = built.sortedBy { it.displayFactor }
    }

    val canZoom: Boolean get() = range.endInclusive - range.start > 0.01

    val defaultFactor: Double get() = clamp(wideFactor)

    fun clamp(factor: Double): Double = min(max(factor, range.start), range.endInclusive)

    fun displayed(factor: Double): Double = factor / wideFactor

    /** Direct manipulation: the pinch's scale multiplies the zoom it began at. */
    fun pinched(from: Double, scale: Double): Double = clamp(from * max(scale, 0.01))

    /** The lens button standing for `factor`: the narrowest preset at or below it. */
    fun activePreset(at: Double): Preset? {
        val display = displayed(at) + 0.005
        return presets.lastOrNull { it.displayFactor <= display } ?: presets.firstOrNull()
    }

    /** VoiceOver's adjustable stops: every lens plus each whole displayed zoom. */
    fun step(from: Double, up: Boolean): Double {
        val stops = presets.map { it.deviceFactor }.toMutableSet()
        var whole = 1.0
        while (whole * wideFactor <= range.endInclusive + 0.001) {
            stops.add(whole * wideFactor)
            whole += 1
        }
        stops.add(range.start)
        stops.add(range.endInclusive)
        val sorted = stops.filter { range.contains(it) }.sorted()
        val target = if (up) {
            sorted.firstOrNull { it > from + 0.01 }
        } else {
            sorted.lastOrNull { it < from - 0.01 }
        }
        return target ?: from
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraZoomModel) return false
        return wideFactor == other.wideFactor && presets == other.presets && range == other.range
    }

    override fun hashCode(): Int {
        var result = wideFactor.hashCode()
        result = 31 * result + presets.hashCode()
        result = 31 * result + range.hashCode()
        return result
    }

    companion object {
        data class MaxDisplayZoom(val back: Double, val front: Double)

        val maxDisplayZoom = MaxDisplayZoom(back = 10.0, front = 2.0)

        /** "1.6×" for the live value, ".5" or "3" for an inactive lens button. */
        fun label(display: Double, active: Boolean): String {
            val text = number(display)
            if (active) return text + "×"
            return if (text.startsWith("0.")) text.drop(1) else text
        }

        fun number(display: Double): String {
            val tenths = (display * 10).roundToInt() / 10.0
            if (display >= 10 || abs(tenths - tenths.roundToInt()) < 0.001) {
                return display.roundToInt().toString()
            }
            return String.format(Locale.US, "%.1f", tenths)
        }
    }
}

/**
 * The zoom the controls show, reconciling direct manipulation with the
 * device's reports. Pinch and VoiceOver set the zoom directly; only a lens
 * button's ramp takes the device's progress, matched by the ramp's ID so late
 * reports from an earlier ramp never pull the zoom back.
 */
class CameraZoomState {
    data class Ramp(
        val id: UUID,
        val target: Double,
    )

    data class Selected(
        val target: Double,
        val ramp: UUID?,
    )

    /** The device's zoom as last set or reported. */
    var factor: Double = 1.0
        private set

    var isPinching: Boolean = false
        private set

    /** A lens button's ramp still under way. */
    var ramp: Ramp? = null
        private set

    private var pinchStart = 1.0

    /** A tapped lens shows as chosen from the tap, not as the ramp passes the lenses between. */
    val shownFactor: Double get() = ramp?.target ?: factor

    fun reset(to: Double) {
        factor = to
        isPinching = false
        ramp = null
    }

    fun beginPinch() {
        isPinching = true
        ramp = null
        pinchStart = factor
    }

    /** The zoom to set, or nil when no pinch began. */
    fun pinch(scale: Double, model: CameraZoomModel): Double? {
        if (!isPinching) return null
        factor = model.pinched(pinchStart, scale)
        return factor
    }

    fun endPinch() {
        isPinching = false
    }

    /** The zoom to set and, when it animates, the ramp whose progress to accept. */
    fun select(
        preset: CameraZoomModel.Preset,
        model: CameraZoomModel,
        animated: Boolean,
        id: UUID = UUID.randomUUID(),
    ): Selected {
        val target = model.clamp(preset.deviceFactor)
        isPinching = false
        if (!animated || abs(target - factor) <= 0.001) {
            ramp = null
            factor = target
            return Selected(target, null)
        }
        ramp = Ramp(id, target)
        return Selected(target, id)
    }

    fun step(up: Boolean, model: CameraZoomModel): Double {
        factor = model.step(shownFactor, up)
        ramp = null
        return factor
    }

    fun rampProgressed(id: UUID, value: Double) {
        val current = ramp ?: return
        if (current.id != id) return
        factor = value
        if (abs(value - current.target) < 0.001) ramp = null
    }
}

enum class AttachmentCameraFlash {
    off, auto, on;

    val next: AttachmentCameraFlash
        get() = when (this) {
            off -> auto
            auto -> on
            on -> off
        }

    val systemImage: String
        get() = when (this) {
            off -> "bolt.slash.fill"
            auto -> "bolt.badge.automatic.fill"
            on -> "bolt.fill"
        }

    val accessibilityValue: String
        get() = when (this) {
            off -> "Off"
            auto -> "Auto"
            on -> "On"
        }
}
