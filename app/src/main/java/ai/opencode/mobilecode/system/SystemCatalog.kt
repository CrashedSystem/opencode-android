package ai.opencode.mobilecode.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemCatalog(modifier: Modifier = Modifier, scroll: Boolean = false) {
    val scrollState = rememberScrollState()
    Column(
        modifier
            .background(SystemColors.systemGroupedBackground)
            .then(if (scroll) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("System", color = SystemColors.label, style = TextStyles.title3.semibold)
        GlassSamples()
        ColorSamples()
        TextSamples()
        WordmarkSamples()
        MenuSamples()
        SheetSamples()
        SwipeSample()
        SymbolSamples()
    }
}

@Composable
private fun GlassSamples() {
    Section("Glass") {
        GlassBackdrop(Modifier.fillMaxWidth().height(132.dp), content = {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFF5AC8FA)))
                Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFFFF9500)))
                Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFF34C759)))
                Box(Modifier.weight(1f).fillMaxSize().background(Color(0xFFAF52DE)))
            }
        }) {
            Row(
                Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(width = 108.dp, height = 36.dp).glassEffect(GlassSurface.Capsule),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Capsule", color = SystemColors.label, style = TextStyles.footnote.semibold)
                }
                Box(
                    Modifier.size(44.dp).glassEffect(GlassSurface.Circle),
                    contentAlignment = Alignment.Center,
                ) {
                    SystemImage("gearshape.fill", contentDescription = "Settings")
                }
                Box(
                    Modifier.size(width = 96.dp, height = 56.dp).glassEffect(GlassSurface.rounded(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Panel", color = SystemColors.label, style = TextStyles.footnote)
                }
            }
        }
    }
}

@Composable
private fun ColorSamples() {
    Section("Colors") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                listOf(
                    "label" to SystemColors.label,
                    "secondary" to SystemColors.secondaryLabel,
                    "tertiary" to SystemColors.tertiaryLabel,
                    "background" to SystemColors.systemBackground,
                    "grouped" to SystemColors.systemGroupedBackground,
                    "fill" to SystemColors.tertiarySystemFill,
                ),
                listOf(
                    "separator" to SystemColors.separator,
                    "blue" to SystemColors.systemBlue,
                    "orange" to SystemColors.systemOrange,
                    "red" to SystemColors.systemRed,
                    "green" to SystemColors.systemGreen,
                    "yellow" to SystemColors.systemYellow,
                ),
            ).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (name, color) -> Swatch(name, color) }
                }
            }
        }
    }
}

@Composable
private fun TextSamples() {
    Section("Text") {
        Sample("Large Title", TextStyles.largeTitle)
        Sample("Title", TextStyles.title)
        Sample("Title 2", TextStyles.title2)
        Sample("Title 3", TextStyles.title3)
        Sample("Headline", TextStyles.headline)
        Sample("Body", TextStyles.body)
        Sample("Callout", TextStyles.callout)
        Sample("Subheadline", TextStyles.subheadline)
        Sample("Footnote", TextStyles.footnote)
        Sample("Caption", TextStyles.caption)
        Sample("Caption 2", TextStyles.caption2)
        Text("code 13 tool", color = SystemColors.label, style = TextStyles.tool)
        Text("Berkeley Mono", color = SystemColors.label, style = TextStyles.callout.copy(fontFamily = FontFamilies.BerkeleyMono))
    }
}

@Composable
private fun WordmarkSamples() {
    Section("Wordmark") {
        OpenCodeWordmark(Modifier.height(28.dp).width(156.dp))
        OpenCodeWordmark(Modifier.height(22.dp).width(120.dp), simple = true)
    }
}

@Composable
private fun MenuSamples() {
    Section("Menu") {
        var expanded by remember { mutableStateOf(false) }
        Surface(
            color = SystemColors.secondarySystemGroupedBackground,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column {
                SystemMenuSections(catalogMenu())
                Box(Modifier.padding(8.dp)) {
                    Text(
                        "Open menu",
                        color = SystemColors.systemBlue,
                        style = TextStyles.footnote.semibold,
                        modifier = Modifier
                            .clickable { expanded = true }
                            .padding(8.dp),
                    )
                    SystemMenu(expanded, { expanded = false }, catalogMenu())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetSamples() {
    Section("Sheet") {
        var open by remember { mutableStateOf(false) }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SystemColors.secondarySystemGroupedBackground,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BottomSheetDefaults.DragHandle()
                Text("Session", color = SystemColors.label, style = TextStyles.title3.semibold)
                Text("Medium and large detents", color = SystemColors.secondaryLabel, style = TextStyles.footnote)
                Text(
                    "Open sheet",
                    color = SystemColors.systemBlue,
                    style = TextStyles.footnote.semibold,
                    modifier = Modifier
                        .clickable { open = true }
                        .padding(12.dp),
                )
            }
        }
        if (open) {
            SystemSheet(onDismissRequest = { open = false }, initialDetent = SheetDetent.Medium) {
                Text("Session", modifier = Modifier.padding(16.dp), style = TextStyles.title3.semibold)
            }
        }
    }
}

@Composable
private fun SwipeSample() {
    Section("Swipe to delete") {
        SwipeToDeleteRow(onDelete = {}, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(
                "Recents row",
                modifier = Modifier
                    .fillMaxSize()
                    .background(SystemColors.systemBackground)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                color = SystemColors.label,
                style = TextStyles.body,
            )
        }
    }
}

@Composable
private fun SymbolSamples() {
    Section("Symbols ${SymbolNames.all.size}") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SymbolNames.all.chunked(14).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { name ->
                        SystemImage(name, contentDescription = name, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = SystemColors.secondaryLabel, style = TextStyles.caption.semibold)
        content()
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color)
                .border(0.5.dp, SystemColors.separator, RoundedCornerShape(6.dp)),
        )
        Text(name, color = SystemColors.secondaryLabel, style = TextStyles.caption2)
    }
}

@Composable
private fun Sample(name: String, style: TextStyle) {
    Text(name, color = SystemColors.label, style = style)
}

internal fun catalogMenu() = listOf(
    SystemMenuSection(
        listOf(
            SystemMenuItem("Pin", "pin") {},
            SystemMenuItem("Rename", "pencil") {},
            SystemMenuItem("Copy Session ID", "square.on.square") {},
            SystemMenuItem("Mark as Read", "envelope.open") {},
        ),
    ),
    SystemMenuSection(
        listOf(
            SystemMenuItem("Compact", "rectangle.compress.vertical") {},
            SystemMenuItem("Fork", "arrow.triangle.branch") {},
            SystemMenuItem("Export", "square.and.arrow.up") {},
            SystemMenuItem("Session Info", "info.circle") {},
        ),
    ),
    SystemMenuSection(
        listOf(SystemMenuItem("Delete Session", "trash", destructive = true) {}),
    ),
)
