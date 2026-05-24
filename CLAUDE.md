# Read -- Android EPUB 阅读器

## 项目概述

原生 Android EPUB 阅读器，使用 Kotlin + Jetpack Compose 构建。支持从本地导入 EPUB 文件，以 WebView 渲染原始 HTML/CSS 章节内容，提供仿真翻页动画、阅读进度自动保存、字号/行高/字体/背景色等个性化阅读设置。

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.1.0 |
| UI | Jetpack Compose + Material 3 | BOM 2024.12.01 |
| 架构 | MVVM + Repository | - |
| DI | Hilt | 2.53.1 |
| 数据库 | Room (KSP) | 2.6.1 |
| 导航 | Navigation Compose (类型安全路由) | 2.8.5 |
| 图片 | Coil 3 | 3.0.4 |
| HTML 解析 | Jsoup | 1.18.3 |
| 渲染 | WebView | Android 内置 |
| 翻页 | ViewPager2 + PageCurlPageTransformer | 1.1.0 |
| 序列化 | Kotlinx Serialization JSON | 1.7.3 |
| 构建 | Gradle KTS + Version Catalog (AGP) | 8.7.3 |

## 项目结构

```
app/src/main/java/com/example/read/
  ReadApplication.kt                          # @HiltAndroidApp 应用入口
  MainActivity.kt                             # 单 Activity，enableEdgeToEdge
  di/
    AppModule.kt                              # Hilt 模块：Database -> DAO -> Repository
  data/
    local/
      AppDatabase.kt                          # Room 数据库定义 (read.db, version=2)
      Migrations.kt                             # 数据库版本迁移脚本（v1->v2: filePath 改 bookDirPath）
      entity/BookEntity.kt                    # books 表实体 + toDomain()/toEntity() 映射
      dao/BookDao.kt                          # DAO：CRUD + 阅读进度更新
    repository/
      BookRepositoryImpl.kt                   # Repository 实现：EPUB 解包 + 文件系统 + 数据库
  domain/
    model/
      Book.kt                                 # 书籍领域模型（bookDirPath 替代 filePath）
      Chapter.kt                              # 章节领域模型（含 htmlPath，不持久化）
    repository/
      BookRepository.kt                       # Repository 接口（依赖倒置）
  ui/
    bookshelf/
      BookshelfScreen.kt                      # 书架页面：2 列网格 + FAB 导入 + 长按删除
      BookshelfViewModel.kt                   # 书架 ViewModel：书籍列表 + 导入/删除
    reader/
      ReaderScreen.kt                         # 阅读器页面：ViewPager2 + WebView 翻页 + 工具栏 + 目录面板
      ReaderViewModel.kt                      # 阅读器 ViewModel：全局页面流 + 章节加载 + 目录导航 + 设置
      WebViewPaginator.kt                     # WebView 分页组件：JS 注入 + 页数回调
      ChapterWebViewFactory.kt                # WebView 工厂：创建/配置/加载章节 WebView
      PageInfo.kt                             # 全局页面信息（chapterIndex + pageInChapter）
      TextSplitter.kt                         # 文本分页工具（旧版，保留兼容）
      ReadingSettings.kt                      # 阅读设置数据类 + SharedPreferences 持久化
      ReadingSettingsDialog.kt                # 设置底部弹出面板：字号/行高/字体/背景色
      PageCurlPageTransformer.kt              # 仿真卷曲翻页动画
    components/
      BookCard.kt                             # 书籍卡片组件（封面 + 标题）
      EmptyState.kt                           # 空状态引导组件
    navigation/
      NavGraph.kt                             # 导航图：Bookshelf <-> Reader (类型安全路由)
    theme/
      Theme.kt                                # Material 3 主题 (Material You 动态取色)
      Color.kt                                # 棕色系静态配色
      Type.kt                                 # 阅读优化排版 (Serif 字体)
  util/
    EpubParser.kt                             # EPUB 解析器：ZIP 解压 + OPF 解析 + NCX 目录解析 + unpack()
    EpubUnpackResult.kt                       # 解包结果：EpubUnpackResult + BookMetadata + SpineItem + TocItem
```

## 核心功能

### 1. EPUB 导入
- SAF 文件选择器（OpenDocument 协议，MIME: `application/epub+zip`）
- 自动提取元数据：标题、作者、封面图片、章节数
- EPUB 解包到目录结构（`filesDir/books/{id}/`），封面独立存储（`filesDir/covers/`）
- 解包后生成 `metadata.json`（BookMetadata 序列化），记录 spine 阅读顺序、NCX 目录结构和 OPF 目录
- Room 数据库持久化书籍记录

### 2. 书架展示
- 2 列网格（`LazyVerticalGrid` + `GridCells.Fixed(2)`）
- Coil 3 异步加载封面图片（`AsyncImage`），2:3 标准书籍比例
- 无封面时显示占位矢量图
- 长按删除（确认对话框，AlertDialog）
- 按最近阅读时间倒序排序（`ORDER BY lastReadAt DESC`）
- 空状态引导（书本图标 + "点击右下角 + 导入你的第一本书"）

### 3. 阅读器
- ViewPager2 实现水平翻页，每页是一个 WebView 渲染的章节页面
- PageCurlPageTransformer 仿真卷曲翻页动画（Camera 3D 旋转 + 渐变阴影）
- WebView 渲染原始 HTML/CSS，通过 JavaScript 测量 scrollHeight 精确分页
- 全局连续页面流（PageInfo），跨章节无缝翻页
- 相邻章节预加载：后台创建临时 WebView 计算页数后销毁
- 点击区域控制：左 1/4 翻前页，右 1/4 翻后页，中间 1/2 切换工具栏
- 顶栏：书名 + 章节标题 + 返回按钮 + 目录按钮 + 设置按钮
- 底栏：进度滑块快速跳转 + 页码指示 + 上一章/下一章按钮
- 顶栏/底栏滑入/滑出动画（`AnimatedVisibility`）
- 目录底部弹出面板（`ModalBottomSheet`）：NCX 解析的章节目录，支持层级缩进、当前章节高亮、点击跳转

### 4. 阅读设置
- 字号：14-28sp（滑块，步进 1）
- 行高：1.2-2.5 倍（滑块，步进 0.1）
- 字体：宋体（Serif）/ 黑体（Sans-serif）/ 等宽（Monospace）
- 背景色：白色 / 护眼（羊皮纸色）/ 暗黑（深色）
- 设置变更实时生效，自动重新分页并按百分比保持阅读位置
- SharedPreferences 持久化（`ReadingSettingsManager`）

### 5. 进度保存
- 章节索引（0-based）+ 章内页码（SharedPreferences）+ 时间戳（epoch millis）
- 章节索引保存到 Room 数据库，章内页码按 bookId+chapterIndex 保存到 SharedPreferences
- 每次章节切换实时保存，ViewModel 销毁时保存当前章内页码
- 启动时自动恢复到上次阅读位置（先加载章节，再等 WebView 回调后跳转）

## 架构详解

### 分层架构

```
┌─────────────────────────────────────────────────┐
│  UI 层 (ui/)                                    │
│  Compose Screen + ViewModel + Components        │
│  状态收集：StateFlow.collectAsState()            │
├─────────────────────────────────────────────────┤
│  领域层 (domain/)                                │
│  纯 Kotlin 模型 (Book, Chapter)                 │
│  Repository 接口 (BookRepository)                │
├─────────────────────────────────────────────────┤
│  数据层 (data/)                                  │
│  Repository 实现 (BookRepositoryImpl)            │
│  Room Entity + DAO + Database                   │
│  文件系统操作（内部存储）                          │
├─────────────────────────────────────────────────┤
│  工具层 (util/)                                  │
│  EpubParser（ZIP + OPF + NCX 解析） + EpubUnpackResult  │
└─────────────────────────────────────────────────┘
```

### 数据流

```
Room Flow (getAllBooks) --> Repository.map(toDomain) --> ViewModel.stateIn --> Compose collectAsState --> UI 重组

阅读器数据流：
EpubParser.unpack() --> 解包目录 + metadata.json（含 NCX 目录） --> Repository.getChapterHtmlFile()
  --> ReaderViewModel (globalPages + chapterPageCounts + tocItems) --> PagedChapterAdapter
  --> ChapterWebViewFactory.loadHtml() --> WebViewPaginator JS --> onPageCountReady 回调
```

### 依赖注入关系图

```
ApplicationContext
       |
       v
AppDatabase (@Singleton, "read.db")
       |
       v
BookDao (由 Database 实例提供)
       |
       v
BookRepositoryImpl (@Singleton, 绑定到 BookRepository 接口)
       |
       v
BookshelfViewModel / ReaderViewModel (构造注入)
```

## 测试

### 单元测试（JVM，`app/src/test/`）

| 测试类 | 测试范围 | 测试数量 |
|--------|----------|----------|
| `EpubParserTest` | HTML 转纯文本（Jsoup）：嵌套标签、实体字符、Unicode、空输入 | 11 |
| `BookEntityMapperTest` | Entity/Domain 双向映射、往返转换、边界值、特殊字符 | 9 |
| `BookshelfViewModelTest` | 状态管理、删除、错误处理、clearError 幂等性 | 6 |
| `ReaderViewModelTest` | 书籍加载、章节切换、边界检查、进度保存 | 12 |

工具：MockK（模拟）、kotlinx-coroutines-test（协程测试）、Turbine（Flow 测试）

### 集成测试（Android，`app/src/androidTest/`）

| 测试类 | 测试范围 | 测试数量 |
|--------|----------|----------|
| `BookDaoTest` | CRUD、排序、Flow 响应式更新、null 处理 | 12 |
| `BookRepositoryImplTest` | Repository 对 DAO 的协调、Entity-Domain 转换、Flow 响应式 | 9 |

工具：Room 内存数据库、Turbine（Flow 测试）

## 编码规范

1. 每段代码都要有完整的中文业务逻辑注释
2. 遵循 Kotlin 编码规范
3. MVVM 单向数据流
4. Repository 模式抽象数据源
5. Hilt 依赖注入
6. Material 3 设计规范

## 构建与运行

### 环境要求
- JDK 17+
- Android SDK 35（compileSdk = 35, targetSdk = 35）
- minSdk = 26
- Gradle 8.11.1+（需先生成 Wrapper）

### 构建步骤
```bash
# 1. 生成 Gradle Wrapper（如未存在）
gradle wrapper --gradle-version 8.11.1

# 2. 同步项目
./gradlew build

# 3. 安装到设备
./gradlew installDebug
```

### 运行测试
```bash
# 单元测试（JVM）
./gradlew test

# 集成测试（需要设备/模拟器）
./gradlew connectedAndroidTest
```

## 包名

`com.example.read`
