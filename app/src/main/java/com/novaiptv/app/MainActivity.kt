package com.novaiptv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale

data class Channel(val id: String, val name: String, val logoUrl: String?, val group: String?, val streamUrl: String)
data class Program(val id: String, val title: String, val startTime: Long, val endTime: Long, val isCatchup: Boolean = false) {
    val progress: Float
        get() {
            val now = System.currentTimeMillis()
            if (now < startTime) return 0f
            if (now > endTime) return 1f
            val total = endTime - startTime
            return if (total > 0) (now - startTime).toFloat() / total.toFloat() else 0f
        }
}

object M3uParser {
    private val LOGO_REGEX = """tvg-logo="([^"]*)"""".toRegex()
    private val GROUP_REGEX = """group-title="([^"]*)"""".toRegex()

    fun parse(content: String): List<Channel> {
        val list = mutableListOf<Channel>()
        var logo: String? = null
        var group: String? = null
        var name = ""

        for (line in content.lines()) {
            val t = line.trim()
            if (t.startsWith("#EXTINF:")) {
                logo = LOGO_REGEX.find(t)?.groupValues?.get(1)
                group = GROUP_REGEX.find(t)?.groupValues?.get(1)
                name = t.substringAfterLast(",").trim()
            } else if (t.isNotEmpty() && !t.startsWith("#")) {
                if (name.isNotEmpty()) {
                    list.add(Channel(name, name, logo, group ?: "General", t))
                }
                logo = null; group = null; name = ""
            }
        }
        return list
    }
}

class MainViewModel : ViewModel() {
    private val client = OkHttpClient()
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()
    private val _selectedChannel = MutableStateFlow<Channel?>(null)
    val selectedChannel: StateFlow<Channel?> = _selectedChannel.asStateFlow()
    private val _currentStreamUrl = MutableStateFlow<String?>(null)
    val currentStreamUrl: StateFlow<String?> = _currentStreamUrl.asStateFlow()
    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init { loadChannels("https://raw.githubusercontent.com/iptv-org/iptv/refs/heads/master/streams/in.m3u") }

    fun loadChannels(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    val parsed = M3uParser.parse(body)
                    withContext(Dispatchers.Main) {
                        _channels.value = parsed
                        if (parsed.isNotEmpty()) selectChannel(parsed.first())
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun selectChannel(channel: Channel) {
        _selectedChannel.value = channel
        _currentStreamUrl.value = channel.streamUrl
    }

    fun setCategory(c: String) { _selectedCategory.value = c }
    fun setSearchQuery(q: String) { _searchQuery.value = q }

    fun getPrograms(channel: Channel): List<Program> {
        val now = System.currentTimeMillis()
        val slot = 3600000L
        val start = now - (now % slot)
        return listOf(
            Program("1", "${channel.name} Previous Episode", start - slot, start, true),
            Program("2", "${channel.name} Live Broadcast", start, start + slot, false),
            Program("3", "${channel.name} Upcoming Show", start + slot, start + (2 * slot), false)
        )
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00E5FF), background = Color(0xFF0A0C13))) {
                IPTVAppScreen(viewModel)
            }
        }
    }
}

@Composable
fun IPTVAppScreen(viewModel: MainViewModel) {
    val channels by viewModel.channels.collectAsState()
    val selectedChannel by viewModel.selectedChannel.collectAsState()
    val streamUrl by viewModel.currentStreamUrl.collectAsState()
    val search by viewModel.searchQuery.collectAsState()
    val category by viewModel.selectedCategory.collectAsState()

    val categories = remember(channels) { listOf("All") + channels.mapNotNull { it.group }.distinct().sorted() }
    val filtered = remember(channels, search, category) {
        channels.filter { (category == "All" || it.group == category) && it.name.contains(search, true) }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0C13))) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            VideoPlayer(streamUrl = streamUrl, modifier = Modifier.fillMaxSize())
        }

        OutlinedTextField(
            value = search,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("Search channel...", color = Color.Gray) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp)
        )

        LazyRow(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { cat ->
                val isSel = cat == category
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSel) Color(0xFF00E5FF) else Color(0xFF1C2233))
                        .clickable { viewModel.setCategory(cat) }
                        .focusable()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(cat, color = if (isSel) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id + it.streamUrl }) { ch ->
                val isSel = ch.id == selectedChannel?.id
                val programs = remember(ch.id) { viewModel.getPrograms(ch) }
                val live = programs[1]
                val next = programs[2]
                var expanded by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                        .border(1.dp, if (isSel) Color(0xFF00E5FF) else Color(0xFF1C2233), RoundedCornerShape(12.dp))
                        .focusable()
                        .clickable { viewModel.selectChannel(ch); expanded = !expanded },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131722))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = ch.logoUrl,
                                contentDescription = ch.name,
                                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ch.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text("NOW: ${live.title}", color = Color(0xFF00E5FF), fontSize = 11.sp, maxLines = 1)
                                LinearProgressIndicator(
                                    progress = { live.progress },
                                    modifier = Modifier.fillMaxWidth().height(2.dp).padding(vertical = 2.dp),
                                    color = Color(0xFF00E5FF),
                                    trackColor = Color(0xFF283144)
                                )
                                Text("NEXT: ${next.title}", color = Color.LightGray, fontSize = 10.sp, maxLines = 1)
                            }
                        }

                        AnimatedVisibility(visible = expanded) {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                HorizontalDivider(color = Color(0xFF283144), thickness = 0.5.dp)
                                programs.forEach { prog ->
                                    val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(prog.title, color = Color.White, fontSize = 11.sp)
                                            Text("${fmt.format(prog.startTime)} - ${fmt.format(prog.endTime)}", color = Color.Gray, fontSize = 9.sp)
                                        }
                                        if (prog.id == "2") {
                                            Text("LIVE", color = Color.Red, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(streamUrl: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    LaunchedEffect(streamUrl) {
        if (!streamUrl.isNullOrEmpty()) {
            exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
        },
        modifier = modifier
    )
}
