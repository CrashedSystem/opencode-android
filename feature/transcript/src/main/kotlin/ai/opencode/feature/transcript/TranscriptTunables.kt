package ai.opencode.feature.transcript

import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TunableChoice
import ai.opencode.core.tuning.TuningPreset
import ai.opencode.core.tuning.TuningPresets
import ai.opencode.core.tuning.TuningStore
import ai.opencode.feature.media.AgentImageTunables
import ai.opencode.feature.media.SendImageTunables
import ai.opencode.feature.transcript.markdown.ui.SessionLinkTunables
import ai.opencode.feature.transcript.markdown.ui.TextRevealTunables
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The transcript's layout and motion tunables (iOS `TranscriptTunables`, Developer Mode →
 * Tuning Panel → Transcript). Same keys, labels, defaults, ranges and steps as iOS; the
 * declarations live in [Knobs] and each value reads live through its getter, so call sites read
 * plain numbers. Motion parameters are captured when each motion begins, so dragging a slider
 * mid-animation never causes a jump. Points port 1:1 to dp/sp.
 */
object TranscriptTunables {
    /** The Tuning Panel sections it registers (the key segment after the group), in order (iOS `sectionTitles`). */
    val sectionTitles = listOf(
        "Arrivals", "Text Reveal", "Growth", "Follow", "Promotion", "Send", "Jump", "Reveal", "Divider", "Typography", "Row", "Bubble", "Card",
    )

    /** The declarations (iOS `TranscriptTunables.<name>` tunables). */
    object Knobs {
        // Arrivals
        val textFadeDuration = Tunable("transcript.textReveal.duration", "Reveal duration (s)", default = 0.2, range = 0.05..0.8, step = 0.01)
        val workingFinale = Tunable("transcript.arrivals.workingFinale", "Working finale flash", default = 0.55, range = 0.15..1.4, step = 0.05)
        val workingHandoff = Tunable("transcript.arrivals.workingHandoff", "Working → Copy handoff", default = 0.34, range = 0.1..1.0, step = 0.02)

        // Growth
        val growthMinDuration = Tunable("transcript.growth.minDuration", "Min growth duration", default = 0.22, range = 0.08..0.6, step = 0.02)
        val growthMaxDuration = Tunable("transcript.growth.maxDuration", "Max growth duration", default = 0.55, range = 0.2..1.5, step = 0.05)
        val growthMaxFrameChange = Tunable("transcript.growth.maxFrameChange", "Max frame Δ (pt/frame²)", default = 1.5f, range = 0.25..6.0, step = 0.25)
        val growthSoftEdge = Tunable("transcript.growth.softEdge", "Soft edge fade (pt)", default = 28f, range = 0.0..64.0, step = 2.0)

        // Follow
        val arrivalOmega = Tunable("transcript.follow.arrivalOmega", "Arrival spring ω", default = 20.0, range = 10.0..60.0, step = 1.0)
        val viewportOmega = Tunable("transcript.follow.viewportOmega", "Viewport/keyboard ω", default = 18.0, range = 8.0..50.0, step = 1.0)
        val maxFrameStep = Tunable("transcript.follow.maxFrameStep", "Max step (pt/frame)", default = 18.0, range = 4.0..24.0, step = 1.0)
        val arrivalReach = Tunable("transcript.follow.arrivalReach", "Glide window (screens)", default = 2.5f, range = 0.5..6.0, step = 0.25)

        // Promotion
        val promotionLabelRise = Tunable("transcript.promotion.labelRise", "Label exit rise (pt)", default = 6f, range = 0.0..20.0, step = 1.0)

        // Send
        val sendFlightOmega = Tunable("transcript.send.flightOmega", "Send flight ω", default = 14.0, range = 6.0..32.0, step = 0.5)
        /** iOS `sendBubbleWaitMax` (declared with the send flights): how long a send's words hold level over a bubble below them. */
        val sendBubbleWaitMax = Tunable("transcript.send.bubbleWaitMax", "Hold over low bubble (s)", default = 0.3, range = 0.0..0.8, step = 0.02)
        /** iOS `sendLiftSoftness` (dp): how far either side of level the words' lift rounds off. */
        val sendLiftSoftness = Tunable("transcript.send.liftSoftness", "Lift softness (pt)", default = 24.0, range = 0.0..40.0, step = 1.0)

        // Jump
        val jumpDuration = Tunable("transcript.jump.duration", "Jump glide duration", default = 0.5, range = 0.15..1.2, step = 0.02)
        val jumpReach = Tunable("transcript.jump.reach", "Full-glide reach (screens)", default = 2.5f, range = 1.0..6.0, step = 0.25)
        val jumpLead = Tunable("transcript.jump.lead", "Cut-in lead (screens)", default = 1.5f, range = 0.5..4.0, step = 0.25)

        // Reveal
        val newMinDuration = Tunable("transcript.reveal.newMinDuration", "New divider min glide", default = 0.28, range = 0.1..0.8, step = 0.02)
        val newMaxDuration = Tunable("transcript.reveal.newMaxDuration", "New divider max glide", default = 0.62, range = 0.2..1.4, step = 0.02)
        val newOverflow = Tunable("transcript.reveal.newOverflow", "New divider: overflow before it shows (pt)", default = 120f, range = 0.0..320.0, step = 10.0)
        val agentGlowRise = Tunable("transcript.reveal.agentGlowRise", "Agent glow rise (s)", default = 0.07, range = 0.02..0.25, step = 0.01)
        val agentGlowDuration = Tunable("transcript.reveal.agentGlowDuration", "Agent glow decay (s)", default = 1.39, range = 0.4..3.0, step = 0.05)

        // Divider (iOS declares these beside `NewMessagesDivider`)
        val dividerGlowLift = Tunable("transcript.divider.glowLift", "New divider glow lift", default = 0.45, range = 0.0..1.0, step = 0.05)
        val dividerGlowDuration = Tunable("transcript.divider.glowDuration", "New divider glow dim (s)", default = 1.0, range = 0.2..3.0, step = 0.05)

        // Row, Bubble & Card Layout
        val flowHalf = Tunable("transcript.row.flowHalf", "Reply row half-gap", default = 8f, range = 2.0..20.0, step = 1.0)
        val turnHalf = Tunable("transcript.row.turnHalf", "Turn half-gap", default = 12f, range = 4.0..28.0, step = 1.0)
        val sectionHalf = Tunable("transcript.row.sectionHalf", "Section rule half-gap", default = 16f, range = 6.0..36.0, step = 1.0)
        val contentGutter = Tunable("transcript.row.contentGutter", "Horizontal gutter", default = 16f, range = 8.0..32.0, step = 1.0)
        val bubbleVertical = Tunable("transcript.bubble.verticalPadding", "Bubble vertical padding", default = 10f, range = 6.0..20.0, step = 1.0)
        val bubbleCornerRadius = Tunable("transcript.bubble.cornerRadius", "Bubble corner radius", default = 18f, range = 8.0..28.0, step = 1.0)
        val bubbleMaxFraction = Tunable("transcript.bubble.maxFraction", "Bubble max width", default = 0.78f, range = 0.55..0.95, step = 0.01)
        val cardCornerRadius = Tunable("transcript.card.cornerRadius", "Card corner radius", default = 14f, range = 6.0..24.0, step = 1.0)

        // Typography (D-52)
        val bodySize = Tunable("transcript.typography.bodySize", "Body size (pt)", default = 16f, range = 14.0..20.0, step = 0.5)
        val bodyLeading = Tunable("transcript.typography.bodyLeading", "Body leading (pt)", default = 3.5f, range = 0.0..8.0, step = 0.5)
        val bodyTone = Tunable("transcript.typography.bodyTone", "Body tone", default = 0.92, range = 0.75..1.0, step = 0.01)
        val h1Size = Tunable("transcript.typography.h1Size", "H1 size (pt)", default = 22f, range = 18.0..28.0, step = 1.0)
        val h2Size = Tunable("transcript.typography.h2Size", "H2 size (pt)", default = 20f, range = 16.0..24.0, step = 1.0)
        val h3Size = Tunable("transcript.typography.h3Size", "H3 size (pt)", default = 18f, range = 12.0..20.0, step = 0.5)
        val h3Weight = Tunable("transcript.typography.h3Weight", "H3 weight", default = TranscriptFontWeight.Bold)
        val metaSize = Tunable("transcript.typography.metaSize", "Used N & Working size (pt)", default = 16f, range = 11.0..17.0, step = 0.5)
        val listNumSize = Tunable("transcript.typography.listNumSize", "List number size (pt)", default = 13f, range = 11.0..17.0, step = 0.5)
        val codeLeading = Tunable("transcript.typography.codeLeading", "Code leading (pt)", default = 3f, range = 0.0..6.0, step = 0.5)
        /** `0` inherits `flowHalf * 2`; any positive value is used directly. */
        val paragraphGap = Tunable("transcript.typography.paragraphGap", "Paragraph gap (pt, 0 = 2 × flowHalf)", default = 0f, range = 0.0..24.0, step = 1.0)
    }

    // Arrivals
    val textFadeDuration: Double get() = Knobs.textFadeDuration.value
    val workingFinale: Double get() = Knobs.workingFinale.value
    val workingHandoff: Double get() = Knobs.workingHandoff.value

    // Growth
    val growthMinDuration: Double get() = Knobs.growthMinDuration.value
    val growthMaxDuration: Double get() = Knobs.growthMaxDuration.value
    val growthMaxFrameChange: Float get() = Knobs.growthMaxFrameChange.value
    val growthSoftEdge: Float get() = Knobs.growthSoftEdge.value

    // Follow
    val arrivalOmega: Double get() = Knobs.arrivalOmega.value
    val viewportOmega: Double get() = Knobs.viewportOmega.value
    val maxFrameStep: Double get() = Knobs.maxFrameStep.value
    val arrivalReach: Float get() = Knobs.arrivalReach.value

    // Promotion
    val promotionLabelRise: Float get() = Knobs.promotionLabelRise.value

    // Send
    val sendFlightOmega: Double get() = Knobs.sendFlightOmega.value
    val sendBubbleWaitMax: Double get() = Knobs.sendBubbleWaitMax.value
    val sendLiftSoftness: Double get() = Knobs.sendLiftSoftness.value
    /** iOS `sendImageDuration` (the knob is `:feature:media`'s [SendImageTunables], which the composer reads too). */
    val sendImageDuration: Double get() = SendImageTunables.duration

    // Jump
    val jumpDuration: Double get() = Knobs.jumpDuration.value
    val jumpReach: Float get() = Knobs.jumpReach.value
    val jumpLead: Float get() = Knobs.jumpLead.value

    // Reveal
    val newMinDuration: Double get() = Knobs.newMinDuration.value
    val newMaxDuration: Double get() = Knobs.newMaxDuration.value
    val agentGlowRise: Double get() = Knobs.agentGlowRise.value
    val agentGlowDuration: Double get() = Knobs.agentGlowDuration.value
    /** What's under the New divider may run this far past the screen and still read from the end (pt). */
    val newOverflow: Float get() = Knobs.newOverflow.value

    // Divider
    /** The first time a mark's divider is on screen it starts this much brighter and dims. */
    val dividerGlowLift: Double get() = Knobs.dividerGlowLift.value
    val dividerGlowDuration: Double get() = Knobs.dividerGlowDuration.value

    // Row, bubble & card layout
    val flowHalf: Float get() = Knobs.flowHalf.value
    val turnHalf: Float get() = Knobs.turnHalf.value
    val sectionHalf: Float get() = Knobs.sectionHalf.value
    val contentGutter: Float get() = Knobs.contentGutter.value
    val bubbleVertical: Float get() = Knobs.bubbleVertical.value
    val bubbleCornerRadius: Float get() = Knobs.bubbleCornerRadius.value
    val bubbleMaxFraction: Float get() = Knobs.bubbleMaxFraction.value
    val cardCornerRadius: Float get() = Knobs.cardCornerRadius.value

    // Typography (D-52)
    val bodySize: Float get() = Knobs.bodySize.value
    val bodyLeading: Float get() = Knobs.bodyLeading.value
    val bodyTone: Double get() = Knobs.bodyTone.value
    val h1Size: Float get() = Knobs.h1Size.value
    val h2Size: Float get() = Knobs.h2Size.value
    val h3Size: Float get() = Knobs.h3Size.value
    val h3Weight: FontWeight get() = Knobs.h3Weight.value.weight
    val metaSize: Float get() = Knobs.metaSize.value
    val listNumSize: Float get() = Knobs.listNumSize.value
    val codeLeading: Float get() = Knobs.codeLeading.value

    /** `0` inherits `flowHalf * 2` (`MarkdownStyle.inheritedParagraphGap`). */
    val paragraphGap: Float get() = Knobs.paragraphGap.value

    /** Backwards-compatible alias for `maxFrameStep * 60` (points per second). */
    val arrivalSpeedPointsPerSecond: Double get() = maxFrameStep * 60

    fun resolvedParagraphGap(paragraphGap: Float, flowHalf: Float): Float =
        if (paragraphGap > 0) paragraphGap else flowHalf * 2

    /**
     * Session view typography presets (D-52): 1 is what shipped (Refined Standard with Used N
     * and Working kept at body size), 2 is the pre-D-52 baseline. Android's body is Material 3's
     * bodyLarge (16 sp on a ~24 sp line) where iOS uses SF's 17 pt `.body`.
     */
    val typography: TuningPresets by lazy {
        with(Knobs) {
            TuningPresets(
                "transcript.typography", "Typography",
                listOf(
                    TuningPreset("Today"),
                    TuningPreset(
                        "Before D-52",
                        bodyLeading.preset(4f), bodyTone.preset(1.0),
                        h3Size.preset(17f), h3Weight.preset(TranscriptFontWeight.Semibold),
                        codeLeading.preset(0f),
                    ),
                    TuningPreset(
                        "Crisp Technical",
                        bodySize.preset(16f), bodyLeading.preset(3.5f), bodyTone.preset(0.88),
                        h1Size.preset(20f), h2Size.preset(17f), h3Size.preset(13f), h3Weight.preset(TranscriptFontWeight.Semibold),
                        metaSize.preset(12f), codeLeading.preset(4f), paragraphGap.preset(14f),
                    ),
                    TuningPreset(
                        "Editorial",
                        bodyLeading.preset(5f), bodyTone.preset(0.9),
                        h1Size.preset(23f), h3Size.preset(13f), h3Weight.preset(TranscriptFontWeight.Semibold),
                        metaSize.preset(13f), paragraphGap.preset(18f),
                    ),
                ),
            )
        }
    }

    /** Card-sized choices: each variant resets only the family it names. */
    val cardPresets: List<TuningPresets> by lazy {
        with(Knobs) {
            listOf(
                TuningPresets(
                    "transcript.arrivals.presets", "Arrivals",
                    listOf(
                        TuningPreset("Native"),
                        TuningPreset("Snappy", textFadeDuration.preset(0.14), workingFinale.preset(0.4), workingHandoff.preset(0.24)),
                        TuningPreset("Gentle", textFadeDuration.preset(0.26), workingFinale.preset(0.65), workingHandoff.preset(0.4)),
                    ),
                    resetting = listOf(textFadeDuration, workingFinale, workingHandoff).map { it.descriptor },
                ),
                TuningPresets(
                    "transcript.growth.presets", "Growth",
                    listOf(
                        TuningPreset("Native"),
                        TuningPreset("Snappy", growthMinDuration.preset(0.16), growthMaxDuration.preset(0.4), growthSoftEdge.preset(20f)),
                        TuningPreset("Gentle", growthMinDuration.preset(0.3), growthMaxDuration.preset(0.65), growthSoftEdge.preset(32f)),
                    ),
                    resetting = listOf(growthMinDuration, growthMaxDuration, growthMaxFrameChange, growthSoftEdge).map { it.descriptor },
                ),
                TuningPresets(
                    "transcript.follow.presets", "Following",
                    listOf(
                        TuningPreset("Native"),
                        TuningPreset("Snappy", arrivalOmega.preset(26.0), viewportOmega.preset(24.0)),
                        TuningPreset("Gentle", arrivalOmega.preset(16.0), viewportOmega.preset(15.0), maxFrameStep.preset(15.0), arrivalReach.preset(2.25f)),
                    ),
                    resetting = listOf(arrivalOmega.descriptor, viewportOmega.descriptor, maxFrameStep.descriptor, arrivalReach.descriptor),
                ),
                TuningPresets(
                    "transcript.promotion.presets", "Steering",
                    listOf(
                        TuningPreset("Native"),
                        TuningPreset("Snappy", promotionLabelRise.preset(4f)),
                        TuningPreset("Gentle", promotionLabelRise.preset(5f)),
                    ),
                    resetting = listOf(promotionLabelRise.descriptor),
                ),
                TuningPresets(
                    "transcript.send.presets", "Sending",
                    listOf(
                        TuningPreset("Native"),
                        TuningPreset("Snappy", sendFlightOmega.preset(18.0)),
                        TuningPreset("Gentle", sendFlightOmega.preset(11.0)),
                    ),
                    resetting = listOf(sendFlightOmega.descriptor),
                ),
                TuningPresets(
                    "transcript.jump.presets", "Jump to Latest",
                    listOf(
                        TuningPreset("Native"),
                        TuningPreset("Snappy", jumpDuration.preset(0.35), jumpReach.preset(2f)),
                        TuningPreset("Gentle", jumpDuration.preset(0.65), jumpReach.preset(3f), jumpLead.preset(1.75f)),
                    ),
                    resetting = listOf(jumpDuration.descriptor, jumpReach.descriptor, jumpLead.descriptor),
                ),
                TuningPresets(
                    "transcript.reveal.presets", "Return & Glow",
                    listOf(
                        TuningPreset("Native"),
                        TuningPreset("Snappy", newMinDuration.preset(0.2), newMaxDuration.preset(0.46), agentGlowRise.preset(0.05), agentGlowDuration.preset(1.05)),
                        TuningPreset("Gentle", newMinDuration.preset(0.36), newMaxDuration.preset(0.76), agentGlowRise.preset(0.1), agentGlowDuration.preset(1.7)),
                    ),
                    resetting = listOf(newMinDuration.descriptor, newMaxDuration.descriptor, agentGlowRise.descriptor, agentGlowDuration.descriptor),
                ),
                TuningPresets(
                    "transcript.typography.presets", "Typography",
                    listOf(TuningPreset("Native")) + typography.presets.drop(1),
                    resetting = listOf(
                        bodySize.descriptor, bodyLeading.descriptor, bodyTone.descriptor,
                        h1Size.descriptor, h2Size.descriptor, h3Size.descriptor, h3Weight.descriptor,
                        metaSize.descriptor, listNumSize.descriptor, codeLeading.descriptor, paragraphGap.descriptor,
                    ),
                ),
            )
        }
    }

    /**
     * iOS `TranscriptTunables.register(in:)`: lists the transcript's knobs (and the text reveal's
     * and motion's) in the Tuning Panel before their first read. A no-op while tuning is off.
     */
    fun register(store: TuningStore = TuningStore.shared) {
        if (!store.isEnabled) return
        with(Knobs) {
            store.register(
                textFadeDuration, workingFinale, workingHandoff,
                growthMinDuration, growthMaxDuration,
            )
            store.register(growthMaxFrameChange, growthSoftEdge)
            store.register(arrivalOmega, viewportOmega, maxFrameStep)
            store.register(arrivalReach)
            store.register(sendFlightOmega, sendBubbleWaitMax, sendLiftSoftness, jumpDuration, SendImageTunables.sendImageDuration)
            store.register(promotionLabelRise, jumpReach, jumpLead)
            store.register(newMinDuration, newMaxDuration, agentGlowRise, agentGlowDuration)
            store.register(dividerGlowLift, dividerGlowDuration)
            store.register(newOverflow)
            store.register(typography)
            for (presets in cardPresets) store.register(presets)
            TextRevealTunables.register(store)
            // iOS registers these through `InlineLinkTunables.register`.
            SessionLinkTunables.register(store)
            MotionTunables.register(store)
            store.register(flowHalf, turnHalf, sectionHalf, contentGutter, bubbleVertical, bubbleCornerRadius, bubbleMaxFraction, cardCornerRadius)
            store.register(bodySize, bodyLeading, h1Size, h2Size, h3Size, metaSize, listNumSize, codeLeading, paragraphGap)
            store.register(bodyTone)
            store.register(h3Weight)
        }
    }

    /** iOS `layoutSnapshot(in:)`: the live layout values. */
    fun layoutSnapshot(): TranscriptLayoutTuning {
        val flow = flowHalf
        return TranscriptLayoutTuning(
            flowHalf = flow,
            turnHalf = turnHalf,
            sectionHalf = sectionHalf,
            contentGutter = contentGutter,
            bubbleVertical = bubbleVertical,
            bubbleCornerRadius = bubbleCornerRadius,
            bubbleMaxFraction = bubbleMaxFraction,
            cardCornerRadius = cardCornerRadius,
            bodySize = bodySize,
            bodyLeading = bodyLeading,
            bodyTone = bodyTone,
            h1Size = h1Size,
            h2Size = h2Size,
            h3Size = h3Size,
            h3Weight = h3Weight,
            metaSize = metaSize,
            listNumSize = listNumSize,
            codeLeading = codeLeading,
            paragraphGap = resolvedParagraphGap(paragraphGap, flow),
            linkMaxHeight = AgentImageTunables.linkMaxHeight.value,
        )
    }
}

/** iOS `DrawerFontWeight` as the H3 weight tunable holds it (same raw values). */
enum class TranscriptFontWeight(override val rawValue: String, val weight: FontWeight) : TunableChoice {
    Regular("regular", FontWeight.Normal),
    Medium("medium", FontWeight.Medium),
    Semibold("semibold", FontWeight.SemiBold),
    Bold("bold", FontWeight.Bold),
}

/**
 * Snapshot of layout-affecting transcript tunables (iOS `TranscriptLayoutTuning`). Motion-only
 * tunables are excluded so adjusting one never re-lays rows out.
 */
@Immutable
data class TranscriptLayoutTuning(
    val flowHalf: Float = TranscriptTunables.flowHalf,
    val turnHalf: Float = TranscriptTunables.turnHalf,
    val sectionHalf: Float = TranscriptTunables.sectionHalf,
    val contentGutter: Float = TranscriptTunables.contentGutter,
    val bubbleVertical: Float = TranscriptTunables.bubbleVertical,
    val bubbleCornerRadius: Float = TranscriptTunables.bubbleCornerRadius,
    val bubbleMaxFraction: Float = TranscriptTunables.bubbleMaxFraction,
    val cardCornerRadius: Float = TranscriptTunables.cardCornerRadius,
    val bodySize: Float = TranscriptTunables.bodySize,
    val bodyLeading: Float = TranscriptTunables.bodyLeading,
    val bodyTone: Double = TranscriptTunables.bodyTone,
    val h1Size: Float = TranscriptTunables.h1Size,
    val h2Size: Float = TranscriptTunables.h2Size,
    val h3Size: Float = TranscriptTunables.h3Size,
    val h3Weight: FontWeight = TranscriptTunables.h3Weight,
    val metaSize: Float = TranscriptTunables.metaSize,
    val listNumSize: Float = TranscriptTunables.listNumSize,
    val codeLeading: Float = TranscriptTunables.codeLeading,
    val paragraphGap: Float = TranscriptTunables.resolvedParagraphGap(TranscriptTunables.paragraphGap, TranscriptTunables.flowHalf),
    /** `AgentImageTunables.linkMaxHeight` default. */
    val linkMaxHeight: Float = 220f,
)

/** iOS `EnvironmentValues.transcriptLayoutTuning`. */
val LocalTranscriptLayoutTuning = staticCompositionLocalOf { TranscriptLayoutTuning() }

// iOS `TranscriptMetrics` is `:feature:media`'s [ai.opencode.feature.media.TranscriptMetrics] (media renderers use it).

/** iOS `InlineRowMetrics`: Used N rows inside the band sit on a 28 pt pitch. */
object InlineRowMetrics {
    /** Above and below each row's line inside the band. */
    val rowPadding: Dp = 4.dp
    /** iOS `TranscriptBandStyle.verticalPadding` (12) minus [rowPadding]. */
    val bandPadding: Dp = 8.dp
}

/** A row's outer halves (iOS `rowInsets`), in dp. */
data class RowInsets(val top: Dp, val bottom: Dp)
