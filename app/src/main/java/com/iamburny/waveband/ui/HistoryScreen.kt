package com.iamburny.waveband.ui

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamburny.waveband.DabTunerApp
import com.iamburny.waveband.RadioController
import com.iamburny.waveband.backend.DabServiceInfo
import com.iamburny.waveband.data.SongPlayEntity
import com.iamburny.waveband.ui.theme.Dab
import com.iamburny.waveband.ui.theme.dabText
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Songs heard on DAB, newest first and grouped by day; tapping one searches for it on YouTube. */
@Composable
fun HistoryScreen(radio: RadioController) {
    val c = Dab.colors
    val context = LocalContext.current
    val history = DabTunerApp.from(context).songHistory
    val plays by history.plays.collectAsStateWithLifecycle()
    val stations by radio.stations.collectAsStateWithLifecycle()
    val byKey = remember(stations) { stations.associateBy { it.key } }
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(plays) { plays.groupBy { Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate() }.toList() }
    // Clearing takes a second tap within a few seconds.
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(4_000)
            confirmClear = false
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle("History")
            Text(
                when (plays.size) {
                    0 -> ""
                    1 -> "1 song"
                    else -> "${plays.size} songs"
                },
                style = dabText(20),
                color = c.muted,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            if (plays.isNotEmpty()) {
                PillButton(
                    if (confirmClear) "Tap again to clear" else "Clear history",
                    background = if (confirmClear) c.invBg else c.chip,
                    foreground = if (confirmClear) c.invFg else c.text,
                ) {
                    if (confirmClear) {
                        history.clear()
                        confirmClear = false
                    } else {
                        confirmClear = true
                    }
                }
            }
        }
        if (plays.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(c.card, RoundedCornerShape(22.dp))
                    .dabBorder(3.dp, c.idleBorder, RoundedCornerShape(22.dp))
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "Songs appear here as you listen, from DAB stations that send the artist and title.",
                    style = dabText(24, FontWeight.SemiBold),
                    color = c.sub,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((day, dayPlays) in days) {
                    item(key = "day-$day") { DayHeader(day) }
                    items(dayPlays, key = { it.id }) { play ->
                        SongRow(play, byKey[play.stationKey]?.info, zone) {
                            if (!searchYouTube(context, "${play.artist} ${play.title}")) radio.reportError("No app can open YouTube")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DAY_FORMAT)
    }
    Text(
        label.uppercase(),
        style = dabText(20, FontWeight.Bold),
        color = Dab.colors.accentText,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun SongRow(play: SongPlayEntity, station: DabServiceInfo?, zone: ZoneId, onClick: () -> Unit) {
    val c = Dab.colors
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(c.card, shape)
            .dabBorder(3.dp, c.idleBorder, shape)
            .pressable(shape, onClickLabel = "Search on YouTube", onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            Instant.ofEpochMilli(play.playedAt).atZone(zone).format(TIME_FORMAT),
            style = dabText(22, FontWeight.SemiBold),
            color = c.muted,
            maxLines = 1,
            modifier = Modifier.width(72.dp),
        )
        if (station != null) {
            StationTile(station, size = 64.dp, corner = 14.dp, fontSize = 22)
        } else {
            MonogramTile(play.stationKey.substringBefore('@').toLongOrNull(16) ?: 0L, play.stationLabel, size = 64.dp, corner = 14.dp, fontSize = 22)
        }
        Column(Modifier.weight(1f)) {
            Text(play.title, style = dabText(28, FontWeight.ExtraBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${play.artist} · ${play.stationLabel}",
                style = dabText(20),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(DabIcons.Open, contentDescription = null, tint = c.muted, modifier = Modifier.size(30.dp))
    }
}

/** Opens a YouTube search for [query]: in the YouTube app if there is one, else in a browser. */
private fun searchYouTube(context: Context, query: String): Boolean {
    val app = Intent(Intent.ACTION_SEARCH)
        .setPackage("com.google.android.youtube")
        .putExtra(SearchManager.QUERY, query)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    for (intent in listOf(app, web)) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
        }
    }
    return false
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM")
