package com.example.read.di

import android.content.Context
import androidx.room.Room
import com.example.read.data.local.AppDatabase
import com.example.read.data.local.MIGRATION_1_2
import com.example.read.data.local.MIGRATION_2_3
import com.example.read.data.local.dao.BookDao
import com.example.read.data.local.dao.BookmarkDao
import com.example.read.data.repository.BookRepositoryImpl
import com.example.read.domain.repository.BookRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块，提供应用级的单例依赖。
 *
 * 依赖关系链：
 * AppDatabase → BookDao / BookmarkDao → BookRepository
 *
 * - AppDatabase：单例，整个应用共享同一个数据库实例
 * - BookDao / BookmarkDao：从 Database 实例获取，Database 存活期间复用
 * - BookRepository：单例，封装所有数据访问逻辑（含 v6 书签）
 *
 * @InstallIn(SingletonComponent::class) 表示这些依赖的生命周期与应用一致。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * 提供 Room 数据库实例。
     * 使用 databaseBuilder 创建或打开名为 "read.db" 的 SQLite 数据库。
     * @Singleton 确保整个应用只有一个数据库连接实例。
     *
     * 迁移链：
     * - MIGRATION_1_2：filePath -> bookDirPath
     * - MIGRATION_2_3：新增 bookmarks 表（v6 书签）
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "read.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    /**
     * 从数据库实例获取 BookDao。
     * Room 自动生成 BookDao 的实现类，无需手动编写。
     */
    @Provides
    fun provideBookDao(database: AppDatabase): BookDao = database.bookDao()

    /**
     * 从数据库实例获取 BookmarkDao（v6 书签）。
     *
     * 与 BookDao 同样由 Database 管理生命周期，不需要 @Singleton。
     */
    @Provides
    fun provideBookmarkDao(database: AppDatabase): BookmarkDao = database.bookmarkDao()

    /**
     * 提供 BookRepository 接口的实现。
     * 将 BookRepositoryImpl 绑定到 BookRepository 接口，
     * ViewModel 层只依赖接口，不直接依赖实现类（依赖倒置原则）。
     *
     * v6：构造参数新增 BookmarkDao，为书签功能提供数据通道。
     */
    @Provides
    @Singleton
    fun provideBookRepository(
        bookDao: BookDao,
        bookmarkDao: BookmarkDao,
        @ApplicationContext context: Context,
    ): BookRepository = BookRepositoryImpl(bookDao, bookmarkDao, context)
}
