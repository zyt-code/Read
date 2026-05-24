package com.example.read.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 书架"导入中"占位卡片（P1-NEW-1 修复引入）。
 *
 * 背景：
 * - P1-1 在 BookDao.getAllBooks() 的 SQL 层过滤了 `PREPARING_*` 占位记录后，
 *   书架的 LazyVerticalGrid 不会再为这些占位渲染 BookCard。
 * - 用户从触发 SAF 导入到 startImport 完成（可能几秒到几十秒），
 *   原本承载"导入进度环"的卡片实例消失，整段导入过程 UI 完全静默。
 *
 * 解决方式：
 * - BookshelfViewModel 维护一个独立 placeholderBooks StateFlow，
 *   prepareImport 成功后立刻向其追加一项；进度回调时更新对应项 progress；
 *   导入结束（成功 / 失败）从中移除。
 * - 本组件作为该 StateFlow 的渲染单元，与 BookCard 保持视觉一致
 *   （2:3 封面比例 + 圆角 + 标题位），但不可点击、不可长按删除，
 *   只在封面位置展示半透明遮罩 + CircularProgressIndicator + 百分比。
 *
 * 设计取舍：
 * - 不复用 BookCard：BookCard 与 Book 领域模型强耦合（要求传入 Book 实例），
 *   占位卡片不持有完整 Book 对象（仅有 id / titleHint / progress 三元组），
 *   构造一个"假" Book 会污染领域层；因此另开一个独立 Composable。
 * - 不复用 EmptyState：占位卡片是网格项，需要遵守 LazyVerticalGrid 的项布局规则，
 *   与全屏占位的 EmptyState 用途不同。
 *
 * @param titleHint 可选的标题提示（如导入文件名），为 null 时显示通用文案"正在导入"
 * @param progress 当前导入进度（0.0~1.0），用于 CircularProgressIndicator 与百分比文本
 * @param modifier 外部修饰符，允许父布局自定义尺寸
 */
@Composable
fun PlaceholderBookCard(
    titleHint: String?,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    // 安全裁剪：异常进度值统一约束到 [0.0, 1.0]，避免 CircularProgressIndicator 抛异常
    val safeProgress = progress.coerceIn(0f, 1f)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            // 封面区域：纯色占位 + 进度叠加层（与 BookCard 的 aspectRatio 保持一致）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    // 使用半透明的 surfaceVariant 作为"伪封面"底色，强调"未完成"状态
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 进度环：与 BookCard 内的导入态进度环视觉一致
                        CircularProgressIndicator(
                            progress = { safeProgress },
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp,
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                        Text(
                            text = "${(safeProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = "导入中",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }
            // 标题占位：使用 titleHint（如导入文件名），否则显示通用文案
            // 与 BookCard 的标题样式保持一致（titleSmall, 最多 2 行 + ellipsis）
            Text(
                text = titleHint ?: "正在导入…",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
