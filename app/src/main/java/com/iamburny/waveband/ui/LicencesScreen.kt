package com.iamburny.waveband.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamburny.waveband.R
import com.iamburny.waveband.ui.theme.Dab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LicencesScreen(onClose: () -> Unit) {
    val c = Dab.colors
    val resources = LocalResources.current
    // Split into paragraphs so the long licence texts are laid out lazily.
    val paragraphs by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() }
                .split(Regex("\n\\s*\n"))
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DabIconButton(DabIcons.Back, "Back", size = 64.dp, iconSize = 32.dp, background = c.btn, tint = c.text, onClick = onClose)
            ScreenTitle("Open-source licences")
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(c.card, RoundedCornerShape(22.dp))
                .dabBorder(2.dp, c.cardBorder, RoundedCornerShape(22.dp)),
            contentPadding = PaddingValues(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(paragraphs) { paragraph ->
                Text(paragraph.trimEnd(), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, lineHeight = 22.sp), color = c.sub)
            }
        }
    }
}
