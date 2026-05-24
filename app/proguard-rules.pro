# ============================================================================
# Read Android EPUB 阅读器 ProGuard / R8 规则
# ----------------------------------------------------------------------------
# release 构建启用 isMinifyEnabled = true 时由 R8 应用本文件。
# 项目同时使用 kotlinx.serialization / Room / Hilt / @JavascriptInterface /
# Coil / Navigation Compose 类型安全路由，每一项都需要保留特定符号，
# 否则 release 包会在启动或运行时抛 ClassNotFoundException /
# MissingFieldException / SerializationException。
# ============================================================================

# ---------- 通用 ----------
# 保留所有注解，序列化、Hilt、Room、Navigation 均依赖运行时注解
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable

# 保留 R8 报错时的源信息，便于排查崩溃堆栈
-renamesourcefileattribute SourceFile

# ---------- Kotlin 反射与协程 ----------
# Kotlin 反射（kotlinx.serialization 在内部使用）
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.jvm.internal.**

# 协程内部类，避免警告
-dontwarn kotlinx.coroutines.**

# ---------- Kotlinx Serialization ----------
# 序列化器编译生成 $serializer 内部类，必须完整保留（含名称、方法、字段）
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclasseswithmembers class kotlinx.serialization.** { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}

# 保留所有标注 @Serializable 的数据类（含 Companion 和 serializer() 方法）
-keep @kotlinx.serialization.Serializable class * { *; }

# BookMetadata / SpineItem / TocItem 是序列化到 metadata.json 的关键载体
-keep class com.example.read.util.BookMetadata { *; }
-keep class com.example.read.util.BookMetadata$Companion { *; }
-keep class com.example.read.util.BookMetadata$$serializer { *; }
-keep class com.example.read.util.SpineItem { *; }
-keep class com.example.read.util.SpineItem$Companion { *; }
-keep class com.example.read.util.SpineItem$$serializer { *; }
-keep class com.example.read.util.TocItem { *; }
-keep class com.example.read.util.TocItem$Companion { *; }
-keep class com.example.read.util.TocItem$$serializer { *; }

# Navigation Compose 类型安全路由（@Serializable data class / object）
-keep class com.example.read.ui.navigation.** { *; }

# ---------- Room ----------
# Room 生成的 _Impl 类、表实体、DAO 接口必须 keep
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class androidx.room.** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}
-dontwarn androidx.room.paging.**

# 项目自定义的 Entity / Migration / Database
-keep class com.example.read.data.local.AppDatabase { *; }
-keep class com.example.read.data.local.AppDatabase_Impl { *; }
-keep class com.example.read.data.local.entity.BookEntity { *; }
-keep class com.example.read.data.local.dao.BookDao { *; }
-keep class com.example.read.data.local.dao.BookDao_Impl { *; }
-keep class com.example.read.data.local.MigrationsKt { *; }

# ---------- Hilt / Dagger ----------
# Hilt 生成大量 _HiltModules / _Factory 类，运行时反射访问
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.* { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
-keepclassmembers class * {
    @dagger.hilt.* *;
    @javax.inject.* *;
}
-keep @dagger.Module class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep class com.example.read.ReadApplication { *; }
-keep class com.example.read.ReadApplication_HiltComponents** { *; }
-keep class com.example.read.di.** { *; }

# ---------- WebView JavascriptInterface ----------
# @JavascriptInterface 方法在 JS 端通过反射调用，签名不能被混淆
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.read.ui.reader.WebViewPaginator { *; }
-keep class com.example.read.ui.reader.WebViewPaginator$PaginationBridge { *; }
-keep class com.example.read.ui.reader.WebViewPaginator$* { *; }

# ---------- Coil 3 ----------
-dontwarn coil3.**
-keep class coil3.** { *; }

# ---------- Jsoup ----------
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ---------- Compose / Material 3 ----------
# Compose 编译插件保留必要符号，这里补充防御性规则
-dontwarn androidx.compose.**

# ---------- 其他三方库 ----------
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
