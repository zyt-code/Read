package com.example.read.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.read.R
import com.example.read.domain.model.Book
import java.io.File

/**
 * 书籍卡片组件，用于书架网格中的单本书展示。
 *
 * 布局：上方是封面图片（2:3 比例），下方是标题文本（最多 2 行）。
 * 交互：点击进入阅读器，长按触发删除确认。
 *
 * 导入状态：importProgress 非 null 时，封面置灰并显示进度环和百分比。
 *
 * combinedClickable 来自 foundation 库，支持同时处理点击和长按事件，
 * 比 Material 3 的 Card(onClick = ...) 更灵活。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: Book,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    importProgress: Float? = null,
    modifier: Modifier = Modifier,
) {
    val isImporting = importProgress != null

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                enabled = !isImporting, // 导入中禁止交互
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            // 封面区域：导入时置灰 + 进度叠加层
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            ) {
                // 封面图片，导入时通过饱和度为 0 实现灰度效果
                AsyncImage(
                    model = book.coverPath?.let { File(it) } ?: R.drawable.placeholder_cover,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    colorFilter = if (isImporting) {
                        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                    } else null,
                )
                // 导入中：半透明遮罩 + 进度环 + 百分比
                if (isImporting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                progress = { importProgress!! },
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp,
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f),
                            )
                            Text(
                                text = "${(importProgress!! * 100).toInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                text = "导入中",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
            // 书籍标题：最多 2 行，超出显示省略号
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
