package dev.kifranei.ampp.media

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

internal data class GlassDialogAction(val label: String, val destructive: Boolean, val perform: () -> Unit)

/** Same material, radius and buttons as the audio-quality disclosure. */
@Composable
internal fun GlassPlayerDialogMaterial(backdrop: Backdrop, light: Boolean = false, content: @Composable ColumnScope.() -> Unit = {}) {
    val shape = RoundedCornerShape(32.dp)
    val accent = if (light) Color.White else Color(0xFF70B8FF)
    Column(Modifier.fillMaxSize().drawBackdrop(backdrop, shape = { shape }, effects = {
        vibrancy(); blur(20.dp.toPx()); lens(20.dp.toPx(), 32.dp.toPx())
    }, onDrawSurface = { drawRect(if (light) Color(0xFFD1D5D3).copy(alpha = .68f) else Color(0xFF101828).copy(alpha = .35f)) })
        .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = .65f), Color.White.copy(alpha = .1f),
            accent.copy(alpha = .35f))), shape), content = content)
}

@Composable
internal fun GlassDeleteConfirmation(backdrop: Backdrop, title: String, message: String, actions: List<GlassDialogAction>) {
    GlassPlayerDialogMaterial(backdrop, light = true) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.Center) {
            if (title.isNotBlank()) {
                BasicText(title, Modifier.fillMaxWidth(), TextStyle(color = Color.Black, fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start))
                Spacer(Modifier.height(10.dp))
            }
            BasicText(message, Modifier.fillMaxWidth(), TextStyle(color = Color.Black,
                fontSize = 18.sp, textAlign = TextAlign.Start))
            Spacer(Modifier.height(24.dp))
            @Composable fun button(action: GlassDialogAction, modifier: Modifier) {
                // Keep iOS's neutral gray fill even over strongly colored artwork.
                LiquidButton(action.perform, backdrop, modifier.height(48.dp), surfaceColor = Color(0xFFD1D1D6).copy(alpha = .96f)) {
                    val label = if (action.destructive && action.label == "从资料库中删除") "从资料库删除" else action.label
                    BasicText(label, style = TextStyle(color = if (action.destructive) Color(0xFFFF3B30) else Color.Black,
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
                }
            }
            if (actions.size <= 2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEach { button(it, Modifier.weight(1f)) }
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { button(it, Modifier.fillMaxWidth()) }
            }
        }
    }
}
