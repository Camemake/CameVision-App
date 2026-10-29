package eu.camemake.camevision.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import eu.camemake.camevision.AppPrefs
import eu.camemake.camevision.AppRole
import eu.camemake.camevision.R

@Composable
fun FirstRunScreen() {
    val context = LocalContext.current
    val language by AppPrefs.language.collectAsState()
    var rolePick by rememberSaveable { mutableStateOf("") }
    val ready = rolePick == "usb" || rolePick == "ble"
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
    ) {
        Text(
            stringResource(R.string.first_title),
            color = CameInk,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 32.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.first_sub), color = CameMuted, fontSize = 15.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(22.dp))
        SectionLabel(stringResource(R.string.lang_label))
        Spacer(Modifier.height(8.dp))
        LanguageSwitch(language.ifBlank { "en" }) { AppPrefs.selectLanguage(context, it) }
        Spacer(Modifier.height(22.dp))
        SectionLabel(stringResource(R.string.role_label))
        Spacer(Modifier.height(8.dp))
        ChoiceCard(
            stringResource(R.string.role_usb),
            stringResource(R.string.role_usb_help),
            rolePick == "usb",
        ) { rolePick = "usb" }
        Spacer(Modifier.height(10.dp))
        ChoiceCard(
            stringResource(R.string.role_ble),
            stringResource(R.string.role_ble_help),
            rolePick == "ble",
        ) { rolePick = "ble" }
        Spacer(Modifier.height(28.dp))
        PinkButton(
            stringResource(R.string.first_continue),
            Modifier
                .fillMaxWidth()
                .alpha(if (ready) 1f else 0.45f),
        ) {
            if (!ready) return@PinkButton
            if (AppPrefs.language.value.isBlank()) AppPrefs.setLanguage(context, "en")
            AppPrefs.setRole(context, if (rolePick == "ble") AppRole.Bluetooth else AppRole.Usb)
        }
    }
}

@Composable
fun LanguageSwitch(selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, CameLine, RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        LangChip("en", stringResource(R.string.lang_en), selected == "en", Modifier.weight(1f), onSelect)
        LangChip("zh", stringResource(R.string.lang_zh), selected == "zh", Modifier.weight(1f), onSelect)
    }
}

@Composable
private fun LangChip(
    tag: String,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CameTeal else Color.Transparent)
            .clickable { onSelect(tag) }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.White else CameInk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
    }
}

@Composable
fun ChoiceCard(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFFE7F2F4) else Color.White)
            .border(1.dp, if (selected) CameTeal else CameLine, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(title, color = CameInk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(body, color = CameMuted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
