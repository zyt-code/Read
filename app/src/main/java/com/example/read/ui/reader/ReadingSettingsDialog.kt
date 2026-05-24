package com.example.read.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 阅读设置底部弹出面板，允许用户实时调整阅读参数。
 *
 * 提供的设置项：
 * 1. 字号调节（14-28sp 滑块）
 * 2. 行高倍数调节（1.2-2.5 滑块）
 * 3. 字体选择（Serif/Sans-serif/Monospace 三按钮）
 * 4. 背景颜色选择（白/护眼/暗黑 三色圆圈）
 *
 * 交互设计：
 * - 使用 ModalBottomSheet 实现底部弹出效果
 * - 所有修改实时生效（通过回调通知 ViewModel）
 * - 点击确认按钮关闭面板
 *
 * @param currentSettings 当前阅读设置，用于初始化控件状态
 * @param onSettingsChange 设置变更回调，每次调整都会触发
 * @param onDismiss 关闭面板的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsDialog(
    currentSettings: ReadingSettings,
    onSettingsChange: (ReadingSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    // 本地临时状态，用于在滑块拖动过程中实时预览效果
    // 用户松手后才通过 onSettingsChange 回调更新 ViewModel
    var fontSize by remember { mutableFloatStateOf(currentSettings.fontSize) }
    var lineHeight by remember { mutableFloatStateOf(currentSettings.lineHeightMultiplier) }
    var fontFamily by remember { mutableStateOf(currentSettings.fontFamily) }
    var bgColor by remember { mutableStateOf(currentSettings.backgroundColor) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // 标题
            Text(
                text = "阅读设置",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // 字号调节滑块
            FontSizeSlider(
                fontSize = fontSize,
                onFontSizeChange = { newSize ->
                    fontSize = newSize
                    // 实时通知 ViewModel 更新设置
                    onSettingsChange(
                        currentSettings.copy(
                            fontSize = newSize,
                            lineHeightMultiplier = lineHeight,
                            fontFamily = fontFamily,
                            backgroundColor = bgColor,
                        )
                    )
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 行高倍数调节滑块
            LineHeightSlider(
                lineHeight = lineHeight,
                onLineHeightChange = { newHeight ->
                    lineHeight = newHeight
                    onSettingsChange(
                        currentSettings.copy(
                            fontSize = fontSize,
                            lineHeightMultiplier = newHeight,
                            fontFamily = fontFamily,
                            backgroundColor = bgColor,
                        )
                    )
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 字体系列选择器
            FontFamilySelector(
                selectedFont = fontFamily,
                onFontSelected = { newFont ->
                    fontFamily = newFont
                    onSettingsChange(
                        currentSettings.copy(
                            fontSize = fontSize,
                            lineHeightMultiplier = lineHeight,
                            fontFamily = newFont,
                            backgroundColor = bgColor,
                        )
                    )
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 背景颜色选择器
            BackgroundColorSelector(
                selectedColor = bgColor,
                onColorSelected = { newColor ->
                    bgColor = newColor
                    onSettingsChange(
                        currentSettings.copy(
                            fontSize = fontSize,
                            lineHeightMultiplier = lineHeight,
                            fontFamily = fontFamily,
                            backgroundColor = newColor,
                        )
                    )
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 确认按钮，关闭设置面板
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("完成")
            }
        }
    }
}

/**
 * 字号调节滑块组件。
 *
 * 显示当前字号值和可拖动的滑块，范围 14-28sp。
 * 拖动过程中实时显示数值变化。
 *
 * @param fontSize 当前字号值
 * @param onFontSizeChange 字号变更回调
 */
@Composable
private fun FontSizeSlider(
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
) {
    Column {
        // 标题行：显示设置名称和当前值
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("字号", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${fontSize.toInt()} sp",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 字号滑块，范围 14-28，步进 1
        Slider(
            value = fontSize,
            onValueChange = onFontSizeChange,
            valueRange = 14f..28f,
            steps = 13, // (28-14) 个间隔 = 13 个步进点
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/**
 * 行高倍数调节滑块组件。
 *
 * 显示当前行高倍数和可拖动的滑块，范围 1.2-2.5。
 * 行高倍数越大，行间距越宽，阅读体验更宽松。
 *
 * @param lineHeight 当前行高倍数
 * @param onLineHeightChange 行高变更回调
 */
@Composable
private fun LineHeightSlider(
    lineHeight: Float,
    onLineHeightChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("行高", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${String.format("%.1f", lineHeight)}x",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 行高滑块，范围 1.2-2.5，步进 0.1
        Slider(
            value = lineHeight,
            onValueChange = onLineHeightChange,
            valueRange = 1.2f..2.5f,
            steps = 12, // (2.5-1.2)/0.1 = 13 个点，12 个间隔
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/**
 * 字体系列选择器组件。
 *
 * 提供三种字体选项，使用文字按钮切换：
 * - 宋体（Serif）：经典衬线字体，适合长文阅读
 * - 黑体（Sans-serif）：无衬线字体，现代感强
 * - 等宽（Monospace）：等宽字体，适合代码或精确排版
 *
 * @param selectedFont 当前选中的字体名称
 * @param onFontSelected 字体选择回调
 */
@Composable
private fun FontFamilySelector(
    selectedFont: String,
    onFontSelected: (String) -> Unit,
) {
    Column {
        Text("字体", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))

        // 字体选项按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // 字体选项列表：显示名称和实际字体名称
            val fontOptions = listOf(
                "宋体" to "Serif",
                "黑体" to "Sans-serif",
                "等宽" to "Monospace",
            )

            fontOptions.forEach { (displayName, fontName) ->
                val isSelected = selectedFont == fontName
                TextButton(
                    onClick = { onFontSelected(fontName) },
                ) {
                    Text(
                        text = displayName,
                        // 选中状态使用主色调，未选中使用默认颜色
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        // 选中状态加粗显示
                        fontWeight = if (isSelected) {
                            androidx.compose.ui.text.font.FontWeight.Bold
                        } else {
                            androidx.compose.ui.text.font.FontWeight.Normal
                        },
                    )
                }
            }
        }
    }
}

/**
 * 背景颜色选择器组件。
 *
 * 提供三种阅读背景选项，使用彩色圆圈表示：
 * - 白色：纯白背景，适合光线充足环境
 * - 护眼：羊皮纸色，减少蓝光刺激
 * - 暗黑：深色背景，适合夜间阅读
 *
 * @param selectedColor 当前选中的背景颜色
 * @param onColorSelected 颜色选择回调
 */
@Composable
private fun BackgroundColorSelector(
    selectedColor: ReadingSettings.BackgroundColor,
    onColorSelected: (ReadingSettings.BackgroundColor) -> Unit,
) {
    Column {
        Text("背景", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))

        // 颜色选项行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ReadingSettings.BackgroundColor.entries.forEach { bgColor ->
                val isSelected = selectedColor == bgColor
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(bgColor.composeColor)
                        // 选中状态显示主色调边框
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    width = 3.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                )
                            } else {
                                // 未选中显示浅灰色边框
                                Modifier.border(
                                    width = 1.dp,
                                    color = Color.LightGray,
                                    shape = CircleShape,
                                )
                            }
                        )
                        .clickable { onColorSelected(bgColor) },
                    contentAlignment = Alignment.Center,
                ) {
                    // 选中状态显示勾号标记
                    if (isSelected) {
                        Text(
                            text = "✓", // Unicode 勾号
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 20.sp,
                        )
                    }
                }
            }
        }

        // 颜色名称标签行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ReadingSettings.BackgroundColor.entries.forEach { bgColor ->
                Text(
                    text = when (bgColor) {
                        ReadingSettings.BackgroundColor.WHITE -> "白色"
                        ReadingSettings.BackgroundColor.SEPIA -> "护眼"
                        ReadingSettings.BackgroundColor.DARK -> "暗黑"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
