package com.example.read.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.tooling.preview.Preview
import com.example.read.data.model.Book
import com.example.read.reader.ReaderChapter
import com.example.read.reader.ReaderCanvasView
import kotlin.math.roundToInt

@Composable
fun ReadApp(
    state: ReadUiState = ReadUiState(),
    onOpenBook: (Book) -> Unit = {},
    onCloseReader: () -> Unit = {},
    importedBookTitle: String? = null,
    onImportedBookConsumed: () -> Unit = {},
    onOpenFilePicker: () -> Unit = {},
) {
    var darkTheme by rememberSaveable { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = if (darkTheme) QuietReadDarkColors else QuietReadLightColors,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            ReadHomeScreen(
                state = state,
                importedBookTitle = importedBookTitle,
                onImportedBookConsumed = onImportedBookConsumed,
                darkTheme = darkTheme,
                onDarkThemeChange = { darkTheme = it },
                onOpenFilePicker = onOpenFilePicker,
                onOpenBook = onOpenBook,
                onCloseReader = onCloseReader,
            )
        }
    }
}

@Composable
private fun ReadHomeScreen(
    state: ReadUiState,
    importedBookTitle: String?,
    onImportedBookConsumed: () -> Unit,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onOpenFilePicker: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onCloseReader: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.Home) }
    var readingBookTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var readerSettingsVisible by rememberSaveable { mutableStateOf(false) }
    var readerSettings by rememberSaveable(stateSaver = ReaderDisplaySettingsSaver) {
        mutableStateOf(ReaderDisplaySettings())
    }

    LaunchedEffect(importedBookTitle) {
        importedBookTitle?.let { title ->
            readingBookTitle = title
            onImportedBookConsumed()
        }
    }

    val selectedReader = state.selectedReader
    if (selectedReader != null) {
        ReaderShell(
            title = selectedReader.title,
            pageLabel = selectedReader.pageLabel,
            progress = selectedReader.progress,
            chapter = ReaderChapter(
                id = selectedReader.chapterId,
                title = selectedReader.chapterTitle,
                content = selectedReader.chapterContent,
            ),
            settings = readerSettings,
            darkTheme = darkTheme,
            onBack = onCloseReader,
            onOpenSettings = { readerSettingsVisible = true },
        )
        if (readerSettingsVisible) {
            ReaderSettingsSheet(
                settings = readerSettings,
                darkTheme = darkTheme,
                onSettingsChange = { readerSettings = it.normalized() },
                onDarkThemeChange = onDarkThemeChange,
                onDismiss = { readerSettingsVisible = false },
            )
        }
        return
    }

    if (readingBookTitle != null) {
        ReaderShell(
            title = readingBookTitle.orEmpty(),
            pageLabel = "Page 1 of 1",
            progress = 0.12f,
            chapter = ReaderChapter(
                id = "sample-chapter",
                title = "Chapter 1",
                content = SAMPLE_READER_TEXT,
            ),
            settings = readerSettings,
            darkTheme = darkTheme,
            onBack = { readingBookTitle = null },
            onOpenSettings = { readerSettingsVisible = true },
        )
        if (readerSettingsVisible) {
            ReaderSettingsSheet(
                settings = readerSettings,
                darkTheme = darkTheme,
                onSettingsChange = { readerSettings = it.normalized() },
                onDarkThemeChange = onDarkThemeChange,
                onDismiss = { readerSettingsVisible = false },
            )
        }
        return
    }

    Scaffold(
        floatingActionButton = {
            if (selectedTab == HomeTab.Home) {
                ImportBookFab(onClick = onOpenFilePicker)
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == HomeTab.Home,
                    onClick = { selectedTab = HomeTab.Home },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Home,
                            contentDescription = homeTabIconName(HomeTab.Home),
                        )
                    },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.Settings,
                    onClick = { selectedTab = HomeTab.Settings },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = homeTabIconName(HomeTab.Settings),
                        )
                    },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        when (selectedTab) {
            HomeTab.Home -> ReadingCenter(
                state = state,
                onOpenBook = onOpenBook,
                modifier = Modifier.padding(padding),
            )
            HomeTab.Settings -> SettingsScreen(
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

internal enum class HomeTab {
    Home,
    Settings,
}

internal enum class HomeImportActionPlacement {
    BottomEndFloatingActionButton,
}

internal data class HomeImportActionSpec(
    val placement: HomeImportActionPlacement,
    val iconName: String,
    val contentDescription: String,
)

internal fun homeImportActionSpec(): HomeImportActionSpec = HomeImportActionSpec(
    placement = HomeImportActionPlacement.BottomEndFloatingActionButton,
    iconName = "Add",
    contentDescription = "Import book",
)

internal fun homeTabIconName(tab: HomeTab): String = when (tab) {
    HomeTab.Home -> "Home"
    HomeTab.Settings -> "Settings"
}

internal data class ReaderDisplaySettings(
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.55f,
    val horizontalPaddingDp: Float = 28f,
) {
    fun normalized(): ReaderDisplaySettings = copy(
        fontSizeSp = fontSizeSp.coerceIn(MIN_READER_FONT_SP, MAX_READER_FONT_SP),
        lineHeightMultiplier = lineHeightMultiplier.coerceIn(
            MIN_READER_LINE_HEIGHT,
            MAX_READER_LINE_HEIGHT,
        ),
        horizontalPaddingDp = horizontalPaddingDp.coerceIn(
            MIN_READER_PADDING_DP,
            MAX_READER_PADDING_DP,
        ),
    )
}

internal val ReaderDisplaySettingsSaver: Saver<ReaderDisplaySettings, List<Float>> = Saver(
    save = {
        listOf(
            it.fontSizeSp,
            it.lineHeightMultiplier,
            it.horizontalPaddingDp,
        )
    },
    restore = {
        ReaderDisplaySettings(
            fontSizeSp = it.getOrElse(0) { 18f },
            lineHeightMultiplier = it.getOrElse(1) { 1.55f },
            horizontalPaddingDp = it.getOrElse(2) { 28f },
        ).normalized()
    },
)

internal fun readerProgressLabel(pageLabel: String, progress: Float): String {
    val percent = (progress.coerceIn(0f, 1f) * 100).roundToInt()
    return "$pageLabel · $percent%"
}

@Composable
private fun ReadingCenter(
    state: ReadUiState,
    onOpenBook: (Book) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.books.isEmpty() && state.importMessage == null && !state.isImporting) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
    ) {
        if (state.importMessage != null || state.isImporting) {
            item {
                ImportStatusCard(
                    isImporting = state.isImporting,
                    message = state.importMessage ?: "Importing book...",
                )
            }
        }
        items(
            items = state.books,
            key = { it.id },
        ) { book ->
            BookshelfBookRow(
                book = book,
                onClick = { onOpenBook(book) },
            )
        }
    }
}

@Composable
private fun ImportStatusCard(
    isImporting: Boolean,
    message: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.44f),
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isImporting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.56f),
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(if (isImporting) 2f else 1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BookshelfBookRow(
    book: Book,
    onClick: () -> Unit,
) {
    val item = book.toBookshelfItem()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open ${item.title}" },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 42.dp, height = 56.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(6.dp),
                    ),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ImportBookFab(
    onClick: () -> Unit,
) {
    val spec = homeImportActionSpec()
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .size(64.dp)
            .semantics { contentDescription = spec.contentDescription },
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun SettingsScreen(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Night mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Lower contrast for long sessions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = darkTheme,
                    onCheckedChange = onDarkThemeChange,
                    modifier = Modifier.semantics { contentDescription = "Night mode" },
                )
            }
        }
    }
}

@Composable
private fun ReaderShell(
    title: String,
    pageLabel: String,
    progress: Float,
    chapter: ReaderChapter,
    settings: ReaderDisplaySettings,
    darkTheme: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val safeSettings = settings.normalized()
    val background = if (darkTheme) Color(0xFF14120F) else Color(0xFFF8F5EF)
    val foreground = if (darkTheme) Color(0xFFF1E7D7) else Color(0xFF211D18)
    val quietLine = foreground.copy(alpha = 0.08f)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = background,
        contentColor = foreground,
        topBar = {
            ReaderTopBar(
                title = title,
                chapterTitle = chapter.title,
                foreground = foreground,
                dividerColor = quietLine,
                onBack = onBack,
                onOpenSettings = onOpenSettings,
            )
        },
        bottomBar = {
            ReaderBottomBar(
                pageLabel = pageLabel,
                progress = progress,
                foreground = foreground,
            )
        },
    ) { padding ->
        AndroidView(
            factory = { context ->
                ReaderCanvasView(context).apply {
                    renderPage(
                        text = chapter.content,
                        foregroundColor = foreground.toArgb(),
                        pageBackgroundColor = background.toArgb(),
                        fontSizeSp = safeSettings.fontSizeSp,
                        lineHeightMultiplier = safeSettings.lineHeightMultiplier,
                        paddingDp = safeSettings.horizontalPaddingDp,
                    )
                }
            },
            update = { view ->
                view.renderPage(
                    text = chapter.content,
                    foregroundColor = foreground.toArgb(),
                    pageBackgroundColor = background.toArgb(),
                    fontSizeSp = safeSettings.fontSizeSp,
                    lineHeightMultiplier = safeSettings.lineHeightMultiplier,
                    paddingDp = safeSettings.horizontalPaddingDp,
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .semantics {
                    contentDescription = "${chapter.title}. ${chapter.content.trim()}"
                },
        )
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    chapterTitle: String,
    foreground: Color,
    dividerColor: Color,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .height(44.dp)
                    .semantics { contentDescription = "Back" },
            ) {
                Text("Back")
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = foreground.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .height(44.dp)
                    .semantics { contentDescription = "Reader settings" },
            ) {
                Text("Aa")
            }
        }
        HorizontalDivider(color = dividerColor)
    }
}

@Composable
private fun ReaderBottomBar(
    pageLabel: String,
    progress: Float,
    foreground: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = foreground.copy(alpha = 0.52f),
            trackColor = foreground.copy(alpha = 0.10f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pageLabel,
                style = MaterialTheme.typography.labelMedium,
                color = foreground.copy(alpha = 0.64f),
            )
            Text(
                text = "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = foreground.copy(alpha = 0.64f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    settings: ReaderDisplaySettings,
    darkTheme: Boolean,
    onSettingsChange: (ReaderDisplaySettings) -> Unit,
    onDarkThemeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val safeSettings = settings.normalized()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Reading settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ReaderNightModeRow(
                darkTheme = darkTheme,
                onDarkThemeChange = onDarkThemeChange,
            )
            ReaderSettingSlider(
                title = "Font size",
                valueLabel = "${safeSettings.fontSizeSp.roundToInt()} sp",
                value = safeSettings.fontSizeSp,
                valueRange = MIN_READER_FONT_SP..MAX_READER_FONT_SP,
                steps = 7,
                onValueChange = {
                    onSettingsChange(safeSettings.copy(fontSizeSp = it).normalized())
                },
            )
            ReaderSettingSlider(
                title = "Line height",
                valueLabel = "${(safeSettings.lineHeightMultiplier * 100).roundToInt()}%",
                value = safeSettings.lineHeightMultiplier,
                valueRange = MIN_READER_LINE_HEIGHT..MAX_READER_LINE_HEIGHT,
                steps = 3,
                onValueChange = {
                    onSettingsChange(safeSettings.copy(lineHeightMultiplier = it).normalized())
                },
            )
            ReaderSettingSlider(
                title = "Margins",
                valueLabel = "${safeSettings.horizontalPaddingDp.roundToInt()} dp",
                value = safeSettings.horizontalPaddingDp,
                valueRange = MIN_READER_PADDING_DP..MAX_READER_PADDING_DP,
                steps = 5,
                onValueChange = {
                    onSettingsChange(safeSettings.copy(horizontalPaddingDp = it).normalized())
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ReaderNightModeRow(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Night mode",
                style = MaterialTheme.typography.titleMedium,
            )
            Switch(
                checked = darkTheme,
                onCheckedChange = onDarkThemeChange,
                modifier = Modifier.semantics { contentDescription = "Night mode" },
            )
        }
    }
}

@Composable
private fun ReaderSettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
    }
}

private const val MIN_READER_FONT_SP = 16f
private const val MAX_READER_FONT_SP = 24f
private const val MIN_READER_LINE_HEIGHT = 1.35f
private const val MAX_READER_LINE_HEIGHT = 1.75f
private const val MIN_READER_PADDING_DP = 18f
private const val MAX_READER_PADDING_DP = 36f

private const val SAMPLE_READER_TEXT = """
夜色安静下来以后，书页上的字会显得更慢一点。

这是一段用于阅读页视觉校准的样章。Canvas 负责正文排版，Compose 只保留返回、进度和阅读设置。字号、行高、边距和夜间模式会立即影响当前页面，让阅读页先具备可用的核心手感。

后续接入真实章节分页后，这里会展示 EPUB 或 TXT 的当前章节内容，并保持相同的阅读设置与进度模型。
"""

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ReaderShellPreview() {
    MaterialTheme(colorScheme = QuietReadLightColors) {
        ReaderShell(
            title = "安静阅读样章",
            pageLabel = "Page 1 of 1",
            progress = 0.12f,
            chapter = ReaderChapter(
                id = "preview",
                title = "Chapter 1",
                content = SAMPLE_READER_TEXT,
            ),
            settings = ReaderDisplaySettings(),
            darkTheme = false,
            onBack = {},
            onOpenSettings = {},
        )
    }
}
