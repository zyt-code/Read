

# 📘 Android 原生阅读器 PRD（增强版）

## 1. 项目概述

打造一款 Android 原生电子书阅读器，对标 Apple Books / 微信读书，提供：

* 极致排版体验
* 高性能解析引擎
* 流畅翻页动画
* 可定制阅读主题（重点）

---

## 2. 支持格式（新增）

### 2.1 文件格式支持

| 格式   | 支持方式             | 优先级   |
| ---- | ---------------- | ----- |
| EPUB | 解析 DOM + CSS 渲染  | ⭐⭐⭐⭐⭐ |
| MOBI | 转换为 EPUB 或解析 KF8 | ⭐⭐⭐⭐  |
| PDF  | 系统 PdfRenderer   | ⭐⭐⭐   |
| TXT  | 流式解析             | ⭐⭐⭐⭐  |

---

### 2.2 解析策略（关键设计）

#### EPUB（核心）

* 解压 → OPF → Spine → HTML
* CSS 解析（支持部分样式）
* 构建章节结构

👉 渲染方案：

```text
HTML → DOM → 分页引擎 → View
```

---

#### MOBI（建议方案）

```text
mobi → 转 epub（推荐）
```

或：

* 使用第三方库（如 libmobi）
* 只支持 KF8（HTML结构）

---

#### TXT

* 分段规则：

  * 空行 / 标题识别
* 编码检测（UTF-8 / GBK）

---

#### PDF

* 不参与排版引擎
* 直接 bitmap 渲染

---

## 3. 架构设计（核心）

### 3.1 模块划分

```text
app/
 ├── reader-core/        # 阅读引擎
 ├── parser/             # 文件解析
 ├── renderer/           # 排版 + 分页
 ├── ui/                 # Material3 UI
 ├── data/               # SQLite + 文件索引
```

---

### 3.2 阅读数据流

```text
文件 → Parser → BookModel → Renderer → PageModel → UI展示
```

---

## 4. 数据存储设计（重点）

### 4.1 是否存 SQLite？

👉 推荐方案：

**结构化数据入库 + 原文件保留**

---

### 4.2 数据结构

#### Book 表

```sql
CREATE TABLE book (
  id TEXT PRIMARY KEY,
  title TEXT,
  author TEXT,
  cover TEXT,
  path TEXT,
  format TEXT
);
```

---

#### Chapter 表

```sql
CREATE TABLE chapter (
  id TEXT PRIMARY KEY,
  book_id TEXT,
  title TEXT,
  index INTEGER,
  content TEXT
);
```

---

#### Progress 表

```sql
CREATE TABLE progress (
  book_id TEXT PRIMARY KEY,
  chapter_index INTEGER,
  page_index INTEGER,
  offset INTEGER
);
```

---

### 4.3 存储策略

| 数据   | 存储         |
| ---- | ---------- |
| 原书   | 文件         |
| 目录   | SQLite     |
| 章节内容 | SQLite（缓存） |
| 样式   | 动态生成       |

👉 优点：

* 启动快
* 支持全文搜索
* 不占大内存

---

## 5. UI 设计（Material 3）

### 5.1 设计规范

* 使用 Material You（Material 3）
* 支持动态主题（Android 12+）

---

### 5.2 阅读界面结构

```text
┌──────────────────────┐
│        状态栏         │
├──────────────────────┤
│                      │
│       阅读内容        │
│                      │
├──────────────────────┤
│ 进度 | 章节 | 时间    │
└──────────────────────┘
```

---

### 5.3 控件规范

| 元素  | 组件               |
| --- | ---------------- |
| 菜单  | ModalBottomSheet |
| 设置  | Slider / Switch  |
| 目录  | LazyColumn       |
| 阅读页 | 自定义 View         |

---

## 6. 字体系统（重点优化）

### 6.1 内置字体（对标 Apple Books）

推荐内置：

* Serif（类似苹果）
* Sans（类似微信读书）
* 思源宋体（Noto Serif）
* Roboto

---

### 6.2 字体切换

```kotlin
TextStyle(
    fontFamily = currentFont,
    fontSize = currentSize.sp,
    lineHeight = lineHeight.sp
)
```

---

### 6.3 字体优化点

* 字重（400 / 500）
* 行高 1.4~1.8
* 字间距微调

---

## 7. 夜间模式（重点对标 Apple）

### 7.1 主题方案

| 模式 | 背景      | 字体      |
| -- | ------- | ------- |
| 日间 | #FFFFFF | #222222 |
| 夜间 | #121212 | #E6E6E6 |
| 护眼 | #F5E6C8 | #5B4636 |

---

### 7.2 动态切换

```kotlin
MaterialTheme(
    colorScheme = darkColorScheme()
)
```

---

### 7.3 细节优化

* 对比度增强（重点）
* 减少纯黑 (#000 → #121212)
* 字体抗锯齿优化

---

## 8. 翻页动画（核心体验）

### 8.1 支持模式

| 模式   | 实现                |
| ---- | ----------------- |
| 仿真翻页 | Canvas            |
| 滑动   | ViewPager2        |
| 覆盖   | Compose Animation |

---

### 8.2 推荐实现

```text
自定义 View + Canvas（最佳体验）
```

---

### 8.3 性能优化

* 预加载前后页
* Bitmap缓存
* 分页缓存

---

## 9. 性能设计

### 9.1 分页策略

```text
章节 → 分页 → Page缓存
```

---

### 9.2 缓存机制

| 类型   | 数量  |
| ---- | --- |
| 当前页  | 1   |
| 前后页  | 2~3 |
| 章节缓存 | LRU |

---

### 9.3 大文件优化

* 分段加载
* 延迟解析
* 避免一次性 DOM

---

## 10. 可扩展能力

### 10.1 后续功能

* 听书（TTS）
* 云同步
* AI 摘要
* 书评推荐

---

## 11. 技术选型

| 模块 | 技术                          |
| -- | --------------------------- |
| UI | Jetpack Compose + Material3 |
| 数据 | Room (SQLite)               |
| 渲染 | 自定义 Layout                  |
| 动画 | Canvas / Compose            |
| 解析 | 自研 + 开源库                    |

---

## 12. 关键结论（结合你前面问题）

### ✅ 是否存 SQLite？

✔ 必须存（结构化 + 加速）

### ✅ 是否直接读文件？

✔ 不推荐（性能差）

### ✅ RN / Expo 是否适合阅读器？

❌ 不适合核心阅读引擎

### ✅ 最优架构（你这个场景）

```text
React Native / Expo → 外壳
Android 原生 → 阅读核心
```

---

## 13. MVP 范围（建议）

第一版只做：

* EPUB + TXT
* 基础翻页
* 字体切换
* 夜间模式
* 目录


# 📂 14. 本地导入功能（新增 PRD 模块）

## 14.1 功能目标

支持用户从本地设备导入电子书文件，自动解析并加入书架。

---

## 14.2 支持导入方式

### 方式一：系统文件选择（推荐）

```text
用户点击「导入」 → 打开系统文件选择器 → 选择文件 → 导入
```

支持：

* 单文件导入
* 多文件导入（可选）

---

### 方式二：扫描本地目录

```text
扫描 Downloads / Documents / 指定目录
```

👉 可选能力（建议二期）：

* 自动识别电子书文件
* 批量导入

---

### 方式三：分享导入（非常重要）

```text
其他 App → 分享 → 本阅读器 → 自动导入
```

👉 支持来源：

* 浏览器下载
* 微信 / QQ 文件
* 文件管理器

---

## 14.3 支持文件类型

```text
.epub
.mobi
.pdf
.txt
```

---

## 14.4 Android 权限设计（关键）

### Android 10+

使用：

```kotlin
ACTION_OPEN_DOCUMENT
```

👉 无需存储权限（推荐）

---

### Android 13+

如果使用扫描：

```xml
READ_MEDIA_IMAGES
READ_MEDIA_VIDEO
READ_MEDIA_AUDIO
```

⚠️ 注意：

* 电子书属于“文档”，不能直接全盘扫描
* 推荐优先 SAF（Storage Access Framework）

---

## 14.5 核心流程设计

```text
选择文件
   ↓
复制到 App 私有目录
   ↓
生成 BookId
   ↓
解析元数据（标题 / 作者 / 封面）
   ↓
写入 SQLite
   ↓
加入书架
```

---

## 14.6 文件存储策略（关键）

### 方案：统一托管（推荐）

```text
/data/data/your.app/files/books/
```

结构：

```text
books/
 ├── {bookId}/
 │    ├── origin.epub
 │    ├── cover.jpg
 │    └── meta.json
```

---

### 为什么必须复制？

| 原因  | 说明       |
| --- | -------- |
| 权限  | 外部文件可能失效 |
| 稳定性 | URI 可能过期 |
| 性能  | 避免重复 IO  |
| 安全  | 避免文件被删除  |

---

## 14.7 Kotlin 实现（核心代码）

### 打开文件选择器

```kotlin
val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    type = "*/*"
    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
        "application/epub+zip",
        "application/pdf",
        "text/plain"
    ))
}
startActivityForResult(intent, REQUEST_CODE)
```

---

### 处理返回文件

```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    val uri = data?.data ?: return

    contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION
    )

    importBook(uri)
}
```

---

### 复制文件到私有目录

```kotlin
fun copyToInternal(uri: Uri, bookId: String): File {
    val input = contentResolver.openInputStream(uri)!!
    val file = File(filesDir, "books/$bookId/origin.epub")

    file.parentFile?.mkdirs()

    file.outputStream().use { output ->
        input.copyTo(output)
    }

    return file
}
```

---

## 14.8 元数据解析

### EPUB

* title
* author
* cover

👉 来自：

```text
META-INF/container.xml → content.opf
```

---

### TXT

* 文件名作为 title
* 无 author

---

### PDF

* 使用 PdfRenderer 或 metadata

---

## 14.9 UI 设计（Material 3）

### 入口

* 书架页右下角 FAB（+）
* 点击弹出：

```text
导入书籍
 ├── 选择文件
 ├── 扫描本地（可选）
```

---

### 导入过程 UI

```text
导入中...
[进度条]
正在解析书籍信息
```

---

### 导入完成

```text
✔ 已加入书架
```

---

### 错误提示

| 场景    | 提示       |
| ----- | -------- |
| 格式不支持 | 不支持该文件类型 |
| 文件损坏  | 文件解析失败   |
| 重复导入  | 已存在该书    |

---

## 14.10 去重策略

```text
MD5(file) 或 文件路径
```

---

## 14.11 性能设计

* 导入异步（Coroutine）
* 解析放 IO 线程
* UI 不阻塞

---

## 14.12 边界情况

| 场景      | 处理           |
| ------- | ------------ |
| 超大文件    | 分段解析         |
| mobi 失败 | fallback txt |
| 无封面     | 默认封面         |

---

## 14.13 扩展能力

后续可加：

* 云导入（WebDAV / OSS）
* OPDS 书库
* 自动下载封面
