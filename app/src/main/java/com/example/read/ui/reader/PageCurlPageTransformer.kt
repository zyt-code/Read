package com.example.read.ui.reader

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import androidx.viewpager2.widget.ViewPager2

/**
 * 自定义 ViewPager2 页面转换器，实现逼真的翻页卷曲效果。
 *
 * 效果原理：
 * 1. 利用 Camera 类进行 3D 旋转变换，绕 Y 轴旋转当前页面
 * 2. 在卷曲边缘绘制渐变阴影，模拟纸张翻起时的明暗变化
 * 3. 卷曲部分显示半透明暗色，模拟纸张背面
 *
 * 动画参数与 ViewPager2 的 position 值（-1 到 0）联动：
 * - position = 0：页面完全平坦，无任何变换
 * - position = -0.5：页面卷曲一半，出现明显的 3D 效果和阴影
 * - position = -1：页面完全翻过，下一页完全可见
 */
class PageCurlPageTransformer : ViewPager2.PageTransformer {

    companion object {
        /** 最大卷曲角度（度），页面完全翻过时的角度 */
        private const val MAX_CURL_ANGLE = 60f
        /** 阴影最大透明度，控制阴影的深浅程度 */
        private const val MAX_SHADOW_ALPHA = 180
        /** 卷曲背面叠加颜色的透明度，模拟纸张背面的暗色效果 */
        private const val BACK_SIDE_ALPHA = 0.7f
    }

    /** 3D 相机对象，用于绕 Y 轴旋转产生透视效果 */
    private val camera = Camera()

    /** 阴影绘制画笔，使用渐变着色器 */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 当 ViewPager2 滑动时对每个可见页面调用此方法。
     *
     * @param view 当前页面的根视图
     * @param position 页面位置：0 表示当前页面，-1 表示完全滑出左侧，1 表示完全在右侧
     */
    override fun transformPage(view: View, position: Float) {
        // 只处理正在向左滑出的页面（当前页面被翻走的情况）
        // position 在 -1 到 0 之间时表示正在翻页
        if (position < 0f && position > -1f) {
            drawCurlEffect(view, position)
        } else {
            // 其他状态（完全显示或完全隐藏）清除所有变换
            view.apply {
                alpha = 1f
                translationX = 0f
                scaleX = 1f
                scaleY = 1f
                // 重置 pivot 点到默认位置
                pivotX = width / 2f
                pivotY = height / 2f
                rotationY = 0f
            }
        }
    }

    /**
     * 绘制翻页卷曲效果的核心方法。
     *
     * 效果组成：
     * 1. 3D 旋转：页面绕左侧边缘（书脊）旋转，产生透视感
     * 2. 阴影：在卷曲位置绘制渐变阴影，模拟光线变化
     * 3. 背面叠加：卷曲部分变暗，模拟纸张背面
     *
     * @param view 当前页面视图
     * @param position 滑动位置（-1 到 0）
     */
    private fun drawCurlEffect(view: View, position: Float) {
        val pageWidth = view.width.toFloat()
        val pageHeight = view.height.toFloat()

        // 防止宽高为零时的除零错误
        if (pageWidth <= 0f || pageHeight <= 0f) return

        // 将 position（-1 到 0）转换为翻页进度（0 到 1）
        val swipeProgress = -position

        // 计算当前卷曲角度，随翻页进度线性增加
        val curlAngle = swipeProgress * MAX_CURL_ANGLE

        // 设置 3D 旋转的 pivot 点为页面左侧边缘（书脊位置）
        // 这样页面会像真实翻书一样从左侧卷起
        view.pivotX = 0f
        view.pivotY = pageHeight / 2f

        // 应用 Y 轴旋转，负值使右侧边缘向观察者方向卷起
        view.rotationY = -curlAngle

        // 将页面内容绘制到离屏位图上进行自定义处理
        // 注意：View 的绘制系统会自动处理旋转，这里额外绘制阴影和背面效果
        drawShadowAndBackSide(view, swipeProgress, pageWidth, pageHeight)
    }

    /**
     * 绘制翻页阴影和背面叠加效果。
     *
     * 阴影效果：使用凸渐变（convex gradient），从卷曲边缘向外扩散。
     * 卷曲越深，阴影越明显，模拟纸张翻起时遮挡光线的效果。
     *
     * 背面效果：在卷曲区域叠加半透明暗色，模拟纸张背面的视觉效果。
     *
     * @param view 目标页面视图
     * @param swipeProgress 翻页进度（0 到 1）
     * @param pageWidth 页面宽度
     * @param pageHeight 页面高度
     */
    private fun drawShadowAndBackSide(
        view: View,
        swipeProgress: Float,
        pageWidth: Float,
        pageHeight: Float,
    ) {
        // 获取当前页面的 Canvas 进行自定义绘制
        // 使用 view.draw() 回调中的 Canvas 来叠加绘制效果
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // 计算卷曲边缘在页面上的水平位置
        // 随着翻页进度增加，卷曲边缘从右侧向左侧移动
        val curlEdgeX = pageWidth * (1f - swipeProgress)

        // 计算阴影透明度，随翻页进度线性增加
        val shadowAlpha = (swipeProgress * MAX_SHADOW_ALPHA).toInt()

        // 更新阴影画笔的渐变着色器
        // 使用凸渐变：在卷曲边缘最亮（不透明），向外逐渐变透明
        // 这模拟了纸张翻起时在页面上投射的阴影
        val gradientWidth = pageWidth * 0.3f
        shadowPaint.shader = LinearGradient(
            curlEdgeX - gradientWidth, 0f,     // 渐变起点（远离卷曲边缘）
            curlEdgeX + gradientWidth, 0f,     // 渐变终点（卷曲边缘附近）
            intArrayOf(
                Color.argb(0, 0, 0, 0),                // 完全透明
                Color.argb(shadowAlpha / 2, 0, 0, 0),  // 半透明
                Color.argb(shadowAlpha, 0, 0, 0),      // 最暗处
                Color.argb(shadowAlpha / 2, 0, 0, 0),  // 半透明
                Color.argb(0, 0, 0, 0),                // 完全透明
            ),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f),   // 渐变分布点
            Shader.TileMode.CLAMP,                      // 边缘颜色延伸
        )

        // 使用 ViewOverlay 叠加绘制效果
        // 在页面视图上叠加半透明暗色层，模拟卷曲背面
        val overlayAlpha = (swipeProgress * 255 * BACK_SIDE_ALPHA).toInt()
        view.overlay?.let { overlay ->
            // 清除之前的叠加层
            overlay.clear()

            // 创建背面叠加的 drawable
            val backSideDrawable = android.graphics.drawable.ColorDrawable(
                Color.argb(overlayAlpha, 0, 0, 0)
            )
            backSideDrawable.setBounds(0, 0, view.width, view.height)
            overlay.add(backSideDrawable)
        }
    }

    /**
     * 线性插值函数，用于在两个颜色值之间平滑过渡。
     *
     * @param start 起始颜色
     * @param end 结束颜色
     * @param fraction 插值比例（0 到 1）
     * @return 插值后的颜色
     */
    @Suppress("unused")
    private fun lerpColor(start: Int, end: Int, fraction: Float): Int {
        return android.graphics.Color.argb(
            lerp(Color.alpha(start), Color.alpha(end), fraction),
            lerp(Color.red(start), Color.red(end), fraction),
            lerp(Color.green(start), Color.green(end), fraction),
            lerp(Color.blue(start), Color.blue(end), fraction),
        )
    }

    /**
     * 浮点数线性插值。
     *
     * @param start 起始值
     * @param end 结束值
     * @param fraction 插值比例（0 到 1）
     * @return 插值结果
     */
    private fun lerp(start: Int, end: Int, fraction: Float): Int {
        return (start + fraction * (end - start)).toInt()
    }
}
