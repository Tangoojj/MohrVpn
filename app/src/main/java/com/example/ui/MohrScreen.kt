package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.ConnectState
import com.example.engine.EngineId
import com.example.engine.Engines
import com.example.BuildConfig
import com.example.engine.Phase
import com.example.engine.fa
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

// ===== MohrVpn v2 palette (dark, UAC-style layout with MohrVpn branding) =====
internal object Mohr {
    val Bg0 = Color(0xFF05080F)
    val Bg1 = Color(0xFF0A1220)
    val Card = Color(0xFF0E1726)
    val CardBorder = Color(0xFF1C2A3E)
    val Green = Color(0xFF2BE38A)
    val GreenDeep = Color(0xFF0F7A4A)
    val Cyan = Color(0xFF2CD3F5)
    val CyanDeep = Color(0xFF0E6E8C)
    val Blue = Color(0xFF3B9CFF)
    val Red = Color(0xFFF25C5C)
    val Text = Color(0xFFF2F6FC)
    val Sub = Color(0xFF8A97AD)
    val Muted = Color(0xFF55627A)
    val Idle = Color(0xFF3A4A66)
}

internal val Audiowide = FontFamily(Font(R.font.audiowide))
internal val Vazir = FontFamily(
    Font(R.font.vazirmatn, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

private fun faText(size: Int, color: Color = Mohr.Text, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Vazir, fontSize = size.sp, color = color, fontWeight = weight, textDirection = TextDirection.Rtl)

@Composable
fun MohrScreen(
    state: ConnectState,
    selected: EngineId,
    logCount: Int,
    onPower: () -> Unit,
    onSelectEngine: (EngineId) -> Unit,
    onOpenMenu: () -> Unit,
    onOpenStages: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val phase = state.phase
    val connected = phase == Phase.CONNECTED
    val busy = phase == Phase.CONNECTING || phase == Phase.DISCONNECTING
    // Positions follow the reference layout (menu left, engine rail right) regardless of RTL.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Mohr.Bg0, Mohr.Bg1, Mohr.Bg0))),
        ) {
            // Our statue photo, faded under the whole screen.
            Image(
                painter = painterResource(R.drawable.mohr_bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.14f),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Mohr.Bg0.copy(alpha = 0.55f), Color.Transparent, Mohr.Bg0.copy(alpha = 0.75f)))))

            ParticleWave(
                color = if (connected) Mohr.Green else Mohr.Cyan,
                active = connected || busy,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(86.dp).navigationBarsPadding(),
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(40.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_m_menu), contentDescription = "منو", tint = Mohr.Text,
                        modifier = Modifier.align(Alignment.CenterStart).size(34.dp).clip(CircleShape).clickable(onClick = onOpenMenu).padding(4.dp),
                    )
                    CreatorTag(connected, Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.height(2.dp))
                BrandTitle(connected)
                // Engine choice sits right above the connect button (full build only).
                if (Engines.available.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    EngineSwitcher(
                        selected = selected,
                        enabled = phase == Phase.IDLE || phase == Phase.ERROR,
                        onSelect = onSelectEngine,
                    )
                    Spacer(Modifier.height(4.dp))
                } else {
                    Spacer(Modifier.height(12.dp))
                }
                PowerButton(phase = phase, onClick = onPower, diameter = if (Engines.available.size > 1) 204.dp else 224.dp)
                Spacer(Modifier.height(10.dp))
                StatusBlock(state)
                Spacer(Modifier.height(6.dp))
                SelectionRow(state, selected, onClick = onOpenStages)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TrafficCard("دانلود", R.drawable.ic_m_down, state.traffic.rxBytes, state.traffic.rxPerSec, connected)
                    TrafficCard("آپلود", R.drawable.ic_m_up, state.traffic.txBytes, state.traffic.txPerSec, connected)
                }
                Spacer(Modifier.height(10.dp))
                if (connected) InfoCard(state, logCount, onOpenLog) else FeaturesCard()
                Spacer(Modifier.height(72.dp))
            }

            AnimatedVisibility(
                visible = phase == Phase.CONNECTING,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp, start = 12.dp, end = 12.dp),
            ) {
                ProgressCard(state, onClick = onOpenStages)
            }
        }
    }
}

/** Small creator line at the top of the home screen: gray while disconnected, green once connected. */
@Composable
private fun CreatorTag(connected: Boolean, modifier: Modifier = Modifier) {
    val color by animateColorAsState(if (connected) Mohr.Green else Mohr.Sub, tween(600), label = "creator")
    Text("Hosseinmbat", style = faText(12, color), modifier = modifier.testTag("creatorTag"))
}

@Composable
private fun BrandTitle(connected: Boolean) {
    val color by animateColorAsState(if (connected) Mohr.Green else Mohr.Text, tween(600), label = "title")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF3A6BE0), Color(0xFF1B2F7A)))),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.mohr_icon_fg), contentDescription = null, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "MohrVpn",
                style = TextStyle(
                    fontFamily = Audiowide, fontSize = 34.sp, color = color,
                    shadow = if (connected) Shadow(Mohr.Green.copy(alpha = 0.8f), Offset.Zero, 28f) else Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), 6f),
                ),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.width(210.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(listOf(Color.Transparent, if (connected) Mohr.Green else Mohr.Cyan, Color.Transparent))),
        )
        Spacer(Modifier.height(6.dp))
        Text(BuildConfig.VERSION_LABEL_FA, style = faText(12, Mohr.Sub))
    }
}

@Composable
private fun PowerButton(phase: Phase, onClick: () -> Unit, diameter: Dp = 224.dp) {
    val connected = phase == Phase.CONNECTED
    val busy = phase == Phase.CONNECTING || phase == Phase.DISCONNECTING
    val ring by animateColorAsState(
        when {
            connected -> Mohr.Green
            busy -> Mohr.Cyan
            phase == Phase.ERROR -> Mohr.Red
            else -> Mohr.Idle
        },
        tween(500), label = "ring",
    )
    val glow by animateFloatAsState(if (connected || busy) 1f else 0.25f, tween(600), label = "glow")
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "angle")
    val pulse by spin.animateFloat(0.92f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")

    Box(
        Modifier.size(diameter).testTag("powerButton").clip(CircleShape).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            // Soft outer glow.
            for (i in 0 until 6) {
                drawCircle(ring.copy(alpha = 0.05f * glow * (6 - i) / 6f * (if (busy) pulse else 1f)), radius = r - i * 3.dp.toPx())
            }
            val ringR = r - 18.dp.toPx()
            drawCircle(ring.copy(alpha = 0.18f + 0.25f * glow), radius = ringR + 7.dp.toPx(), style = Stroke(2.dp.toPx()))
            drawCircle(
                Brush.sweepGradient(listOf(ring, ring.copy(alpha = 0.55f), Color.White.copy(alpha = 0.85f * glow), ring)),
                radius = ringR, style = Stroke(11.dp.toPx()),
            )
            if (busy) {
                rotate(angle) {
                    drawArc(
                        Color.White.copy(alpha = 0.85f), startAngle = 0f, sweepAngle = 70f, useCenter = false,
                        topLeft = Offset(center.x - ringR, center.y - ringR), size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2),
                        style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            val inner = ringR - 14.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(Color(0xFF15233A), Color(0xFF0A1322)), center, inner), radius = inner)
            drawCircle(Color(0xFF2A3A55), radius = inner, style = Stroke(1.5.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_m_power), contentDescription = null, tint = ring.takeIf { phase != Phase.IDLE } ?: Mohr.Sub, modifier = Modifier.size(54.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    connected -> "قطع اتصال"
                    busy -> "لغو"
                    else -> "اتصال"
                },
                style = faText(23, ring.takeIf { phase != Phase.IDLE } ?: Mohr.Text, FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun StatusBlock(state: ConnectState) {
    val (title, color) = when (state.phase) {
        Phase.CONNECTED -> "وصل شد" to Mohr.Green
        Phase.CONNECTING -> "در حال اتصال…" to Mohr.Cyan
        Phase.DISCONNECTING -> "در حال قطع اتصال…" to Mohr.Cyan
        Phase.ERROR -> "اتصال برقرار نشد" to Mohr.Red
        Phase.IDLE -> "متصل نیستید" to Mohr.Text
    }
    val sub = when (state.phase) {
        Phase.CONNECTED -> state.status.ifBlank { "اتصال شما امنه" }
        Phase.ERROR -> state.error
        Phase.IDLE -> "کیفیت اتصال بستگی به اپراتور شما دارد"
        else -> state.status
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = faText(21, color, FontWeight.Bold))
        Spacer(Modifier.height(2.dp))
        Text(sub, style = faText(15, Mohr.Sub), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SelectionRow(state: ConnectState, selected: EngineId, onClick: () -> Unit) {
    val label = when (selected) {
        EngineId.XRAY -> state.selection.takeIf { state.engine == EngineId.XRAY && it.isNotBlank() } ?: "Auto · خودکار"
        EngineId.TOR -> state.selection.takeIf { state.engine == EngineId.TOR && it.isNotBlank() } ?: "WebTunnel · خودکار"
        EngineId.WARP -> state.selection.takeIf { state.engine == EngineId.WARP && it.isNotBlank() } ?: "WARP + Psiphon · خودکار"
    }
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(Mohr.Blue))
        Spacer(Modifier.width(8.dp))
        Text("انتخاب‌شده", style = faText(15, Mohr.Sub))
        Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(22.dp).background(Mohr.CardBorder))
        Text(label, style = faText(18, Mohr.Blue, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Icon(painterResource(R.drawable.ic_m_chevron), null, tint = Mohr.Blue, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RowScope.TrafficCard(title: String, icon: Int, total: Long, perSec: Long, connected: Boolean) {
    val accent = if (connected) Mohr.Green else Mohr.Cyan
    val (value, unit) = bytes(total)
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(Mohr.Card.copy(alpha = 0.9f))
            .border(1.5.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(26.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(title, style = faText(15, Mohr.Sub), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)).border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(icon), null, tint = accent, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    Text(value, style = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold, color = accent))
                    Spacer(Modifier.width(3.dp))
                    Text(unit, style = TextStyle(fontSize = 13.sp, color = Mohr.Sub))
                }
                val (sv, su) = bytes(perSec)
                Text("$sv $su/s", style = TextStyle(fontSize = 13.sp, color = Mohr.Sub))
            }
        }
    }
}

@Composable
private fun FeaturesCard() {
    BottomCard {
        Feature(R.drawable.ic_m_shield_check, "امن", "رمزگذاری‌شده")
        Divider()
        Feature(R.drawable.ic_m_bolt, "سریع", "بهینه")
        Divider()
        Feature(R.drawable.ic_m_wifi, "پایدار", "قابل‌اعتماد")
    }
}

@Composable
private fun InfoCard(state: ConnectState, logCount: Int, onOpenLog: () -> Unit) {
    BottomCard {
        Info(R.drawable.ic_m_gauge, "پینگ", if (state.pingMs >= 0) "${fa(state.pingMs)} ms" else "—", Mohr.Green)
        Divider()
        val code = state.countryCode
        val country = if (code.isBlank()) "…" else "${flagOf(code)} ${countryName(code)}"
        Info(R.drawable.ic_m_globe, "کشور", country, Mohr.Text)
        Divider()
        Info(R.drawable.ic_m_doc, "لاگ", fa(logCount), Mohr.Text, onClick = onOpenLog)
    }
}

@Composable
private fun BottomCard(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(100.dp).testTag("bottomCard").clip(RoundedCornerShape(26.dp)).background(Mohr.Card.copy(alpha = 0.9f))
            .border(1.dp, Mohr.CardBorder, RoundedCornerShape(26.dp)).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RowScope.Feature(icon: Int, title: String, sub: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), null, tint = Mohr.Cyan, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(4.dp))
        Text(title, style = faText(17, Mohr.Text, FontWeight.Bold))
        Text(sub, style = faText(13, Mohr.Sub))
    }
}

@Composable
private fun RowScope.Info(icon: Int, title: String, value: String, valueColor: Color, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.weight(1f).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), null, tint = Mohr.Blue, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(title, style = faText(14, Mohr.Text))
        Spacer(Modifier.height(3.dp))
        // Long values (country names) get a smaller font so they fit the narrow cell.
        val size = when { value.length > 12 -> 13; value.length > 9 -> 15; else -> 18 }
        Text(value, style = faText(size, valueColor, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).fillMaxHeight(0.6f).background(Mohr.CardBorder))
}

/**
 * «موتور اتصال»: segmented Xray | Tor | WARP control, laid out right-to-left (Xray on the right).
 * Only changeable while disconnected; while a connection runs it is dimmed and a tap explains why.
 */
@Composable
private fun EngineSwitcher(selected: EngineId, enabled: Boolean, onSelect: (EngineId) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("engineSwitcher"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (enabled) "موتور اتصال" else "موتور اتصال · برای تغییر، اول قطع کنید",
            style = faText(13, if (enabled) Mohr.Sub else Mohr.Muted, FontWeight.Medium),
        )
        Spacer(Modifier.height(5.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(Mohr.Card.copy(alpha = 0.92f))
                    .border(1.dp, Mohr.CardBorder, RoundedCornerShape(23.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Engines.available.forEach { id ->
                    val on = id == selected
                    val icon = when (id) {
                        EngineId.XRAY -> R.drawable.ic_m_bolt
                        EngineId.TOR -> R.drawable.ic_m_onion
                        EngineId.WARP -> R.drawable.ic_m_shield_check
                    }
                    val fg = when {
                        on && enabled -> Color.White
                        on -> Color.White.copy(alpha = 0.8f)
                        enabled -> Mohr.Sub
                        else -> Mohr.Muted
                    }
                    Row(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(19.dp))
                            .background(
                                if (on) Brush.verticalGradient(listOf(Color(0xFF2F7F9A), Color(0xFF1A4458)))
                                else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
                            )
                            .border(if (on) 1.dp else 0.dp, if (on) Mohr.Cyan.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(19.dp))
                            .alpha(if (enabled || on) 1f else 0.6f)
                            .clickable { onSelect(id) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(icon), contentDescription = null, tint = if (on) Mohr.Cyan else fg, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(id.title, style = TextStyle(fontSize = 16.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = fg))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(state: ConnectState, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsed = ((now - state.startedAt).coerceAtLeast(0) / 1000).toInt()
    val steps = state.steps.ifEmpty { listOf("") }
    val last = steps.size - 1
    val title = if (state.step >= last) "در حال راه‌اندازی" else "در حال اتصال"
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xF20D1727))
            .border(1.5.dp, Color(0xFF1E3A55), RoundedCornerShape(28.dp)).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Mohr.Cyan))
                    Spacer(Modifier.width(12.dp))
                    Text(title, style = faText(19, Mohr.Text, FontWeight.Bold))
                    // Engine chip only when the build offers an engine choice.
                    if (Engines.available.size > 1) Spacer(Modifier.width(12.dp))
                    if (Engines.available.size > 1) Text(
                        state.engine.chip,
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Mohr.Cyan),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Mohr.Cyan.copy(alpha = 0.13f)).padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
            Text("${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}", style = TextStyle(fontSize = 17.sp, color = Mohr.Sub))
        }
        Spacer(Modifier.height(14.dp))
        Stepper(steps, state.step.coerceIn(0, last))
        Spacer(Modifier.height(12.dp))
        val fraction by animateFloatAsState(
            if (last == 0) 1f else ((state.step.coerceIn(0, last) + 0.5f) / (last + 1)).coerceIn(0.08f, 0.92f) + if (state.step >= last) 0.0f else 0f,
            tween(500), label = "bar",
        )
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF1B2B40))) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Mohr.Cyan))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            state.detail.ifBlank { state.status },
            style = faText(14, Mohr.Sub).copy(textDirection = TextDirection.Content),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Stepper(steps: List<String>, current: Int) {
    Box(Modifier.fillMaxWidth()) {
        val n = steps.size
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            if (n < 2) return@Canvas
            val seg = size.width / n
            val y = size.height / 2
            for (i in 0 until n - 1) {
                val x0 = seg * (i + 0.5f)
                val x1 = seg * (i + 1.5f)
                drawLine(if (i < current) Mohr.Green else Color(0xFF2A3A52), Offset(x0, y), Offset(x1, y), strokeWidth = 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth()) {
            steps.forEachIndexed { i, label ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
                        when {
                            i < current -> Box(Modifier.size(12.dp).clip(CircleShape).background(Mohr.Green))
                            i == current -> Box(Modifier.size(20.dp).clip(CircleShape).background(Mohr.Cyan))
                            else -> Box(Modifier.size(12.dp).clip(CircleShape).background(Mohr.Bg1).border(2.dp, Color(0xFF55627A), CircleShape))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        label,
                        style = faText(13, if (i == current) Mohr.Text else Mohr.Sub, if (i == current) FontWeight.Bold else FontWeight.Normal),
                        maxLines = 1, overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

@Composable
private fun ParticleWave(color: Color, active: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(if (active) 4200 else 9000, easing = LinearEasing)), label = "phase")
    Canvas(modifier) {
        val cols = 90
        val rows = 4
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val fx = c / (cols - 1f)
                val x = fx * size.width
                val envelope = 0.35f + 0.65f * (0.5f + 0.5f * sin(fx * 2.6f * PI.toFloat() - phase * 0.5f))
                val y = size.height * (0.55f - 0.12f * r) + sin(fx * 9f + phase + r) * size.height * 0.18f * envelope
                val rad = (0.9f + 2.6f * envelope * (if (active) 1f else 0.6f)) * (1f - r * 0.18f)
                drawCircle(color.copy(alpha = (0.18f + 0.6f * envelope) * (1f - r * 0.2f) * (if (active) 1f else 0.6f)), radius = rad.dp.toPx() / 2f, center = Offset(x, y))
            }
        }
    }
}

internal fun bytes(v: Long): Pair<String, String> {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var x = v.toDouble().coerceAtLeast(0.0)
    var i = 0
    while (x >= 1024 && i < units.lastIndex) {
        x /= 1024
        i++
    }
    val text = if (i == 0 || x >= 100) x.toLong().toString() else String.format(Locale.US, "%.1f", x)
    return text to units[i]
}

internal fun flagOf(code: String): String {
    if (code.length != 2) return ""
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(base + code[0].uppercaseChar().code)) + String(Character.toChars(base + code[1].uppercaseChar().code))
}

/** Short Persian names for common exit countries whose full names do not fit the info cell. */
private val shortCountry = mapOf(
    "US" to "آمریکا", "GB" to "انگلیس", "AE" to "امارات", "NL" to "هلند", "DE" to "آلمان",
    "RU" to "روسیه", "KR" to "کره جنوبی", "CZ" to "چک", "BA" to "بوسنی", "SA" to "عربستان",
    "ZA" to "آفریقای جنوبی", "DO" to "دومینیکن", "CF" to "آفریقای مرکزی", "HK" to "هنگ‌کنگ",
)

internal fun countryName(code: String): String = shortCountry[code.uppercase()] ?:
    runCatching { Locale("", code).getDisplayCountry(Locale("fa")) }.getOrNull()?.takeIf { it.isNotBlank() } ?: code
