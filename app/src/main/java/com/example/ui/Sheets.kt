package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.ConnectEvent
import com.example.engine.EngineId
import com.example.engine.Engines
import com.example.BuildConfig

enum class Sheet { NONE, MENU, STAGES, LOG, LICENSES, ABOUT }

private val rtl = TextStyle(fontFamily = Vazir, textDirection = TextDirection.Rtl)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MohrSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF0D1727),
        contentColor = Mohr.Text,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp)) { content() }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = rtl.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Mohr.Text))
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Mohr.CardBorder))
    Spacer(Modifier.height(8.dp))
}

@Composable
fun MenuSheet(
    fragment: Boolean = false,
    fragmentLocked: Boolean = false,
    onFragment: (Boolean) -> Unit = {},
    onPick: (Sheet) -> Unit,
) {
    SheetTitle("MohrVpn · " + BuildConfig.VERSION_LABEL_FA)
    FragmentToggle(fragment, fragmentLocked, onFragment)
    Box(Modifier.fillMaxWidth().height(1.dp).background(Mohr.CardBorder))
    listOf(
        Sheet.STAGES to "مراحل اتصال",
        Sheet.LOG to "لاگ‌ها",
        Sheet.LICENSES to "مجوزهای نرم‌افزارهای متن‌باز",
        Sheet.ABOUT to "درباره",
    ).forEach { (sheet, label) ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(sheet) }.padding(vertical = 14.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = rtl.copy(fontSize = 17.sp, color = Mohr.Text), modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_m_chevron), null, tint = Mohr.Sub, modifier = Modifier.size(18.dp))
        }
    }
}

/** «فرگمنت (Fragment)» switch: off by default, locked while a session is open (applies on next connect). */
@Composable
private fun FragmentToggle(checked: Boolean, locked: Boolean, onChange: (Boolean) -> Unit) {
    val hint = when {
        locked -> "برای تغییر، اول قطع کنید"
        Engines.available.size > 1 -> "تکه‌تکه کردن سلام TLS · فقط موتور Xray"
        else -> "تکه‌تکه کردن سلام TLS برای عبور از فیلترینگ"
    }
    Row(
        Modifier.fillMaxWidth().testTag("fragmentToggle").clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !locked) { onChange(!checked) }.padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("فرگمنت (Fragment)", style = rtl.copy(fontSize = 17.sp, color = Mohr.Text))
            Text(hint, style = rtl.copy(fontSize = 13.sp, color = if (locked) Mohr.Red.copy(alpha = 0.85f) else Mohr.Sub))
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = { if (!locked) onChange(it) },
            enabled = !locked,
            modifier = Modifier.testTag("fragmentSwitch"),
            colors = SwitchDefaults.colors(
                checkedTrackColor = Mohr.Green, checkedThumbColor = Color.White,
                // Locked while connected: still readable as on/off.
                disabledCheckedTrackColor = Mohr.Green.copy(alpha = 0.45f), disabledCheckedThumbColor = Color.White.copy(alpha = 0.8f),
            ),
        )
    }
}

@Composable
fun StagesSheet(events: List<ConnectEvent>) {
    SheetTitle("مراحل اتصال")
    if (events.isEmpty()) {
        Text("هنوز اتصالی شروع نشده است.", style = rtl.copy(fontSize = 15.sp, color = Mohr.Sub))
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(events.asReversed()) { e ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background((if (e.ok) Mohr.Green else Mohr.Red).copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(if (e.ok) R.drawable.ic_m_check else R.drawable.ic_m_close), null, tint = if (e.ok) Mohr.Green else Mohr.Red, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = rtl.copy(fontSize = 16.sp, color = Mohr.Text))
                    if (e.detail.isNotBlank()) {
                        Text(e.detail, style = rtl.copy(fontSize = 13.sp, color = if (e.ok) Mohr.Sub else Mohr.Red, textDirection = TextDirection.Content))
                    }
                }
            }
        }
    }
}

@Composable
fun LogSheet(lines: List<String>, onCopy: () -> Unit) {
    SheetTitle("لاگ‌ها")
    Text(
        "کپی لاگ",
        style = rtl.copy(fontSize = 15.sp, color = Mohr.Blue, fontWeight = FontWeight.Medium),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onCopy).padding(vertical = 6.dp, horizontal = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(lines.asReversed()) { line ->
                Text(line, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Mohr.Sub, textDirection = TextDirection.Content), modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
fun LicensesSheet(text: String) {
    SheetTitle("مجوزهای نرم‌افزارهای متن‌باز")
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Mohr.Sub))
        }
    }
}

@Composable
fun AboutSheet() {
    SheetTitle("درباره MohrVpn")
    Text(
        ABOUT_MOHR,
        style = rtl.copy(fontSize = 16.sp, color = Mohr.Text, lineHeight = 28.sp, textAlign = TextAlign.Justify),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
    listOf(
        BuildConfig.VERSION_LABEL_FA,
        if (Engines.available.size > 1) "موتور Xray: انتخاب خودکار کانفیگ بر اساس اپراتور و نوع فیلترینگ، با هسته رسمی Xray-core."
        else "انتخاب خودکار کانفیگ بر اساس اپراتور و نوع فیلترینگ.",
        "موتور Tor: شبکه Tor با پل‌های WebTunnel.".takeIf { EngineId.TOR in Engines.available },
        "موتور WARP: لایه WARP (MASQUE) با لایه درونی Psiphon.".takeIf { EngineId.WARP in Engines.available },
        "برای هر اپراتور و هر Wi‑Fi، بهترین مسیر کارکرده ذخیره می‌شود تا اتصال بعدی سریع‌تر باشد.",
    ).filterNotNull().forEach {
        Text(it, style = rtl.copy(fontSize = 15.sp, color = Mohr.Sub), modifier = Modifier.padding(vertical = 4.dp))
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "سازنده: Hosseinmbat",
        style = rtl.copy(fontSize = 12.sp, color = Mohr.Sub.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth().testTag("aboutCreator"),
    )
}

/** User-supplied About paragraph (exact text, keeps its ZWNJ characters). */
private const val ABOUT_MOHR = "مهر، شهرستانی در جنوب استان فارس و دروازه‌ی خلیج فارس است که به «نگین فارس» شهرت دارد؛ سرزمینی با قدمتی چند هزار ساله، از یادگارهای هخامنشی و ساسانی تنگ مُهر، آبشار چک‌چک و نرگس‌زارهای اسیر"
