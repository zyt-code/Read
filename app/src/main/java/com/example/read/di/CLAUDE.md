# com.example.read.di -- Hilt 依赖注入模块

## 包概述

Hilt 依赖注入模块，提供应用级的单例依赖。
定义依赖关系链：`AppDatabase -> BookDao -> BookRepository`。

## 文件列表

| 文件 | 职责 |
|------|------|
| `AppModule.kt` | Hilt Module，提供 Database（含 Migration）、DAO 和 Repository 的实例 |

## 关键类

### AppModule

`@Module @InstallIn(SingletonComponent::class)` 注解的 Hilt 模块。

**提供的依赖**：

| @Provides 方法 | 返回类型 | 作用域 | 说明 |
|----------------|----------|--------|------|
| `provideDatabase(context)` | `AppDatabase` | @Singleton | 创建或打开 "read.db" 数据库，注册 MIGRATION_1_2 |
| `provideBookDao(database)` | `BookDao` | 非单例 | 从 Database 获取 DAO 实例 |
| `provideBookRepository(bookDao, context)` | `BookRepository` | @Singleton | 绑定 BookRepositoryImpl 到接口 |

## 依赖关系图

```
ApplicationContext
    |
    v
AppDatabase (Singleton, "read.db")
    |
    v
BookDao (由 Database 实例提供)
    |
    v
BookRepositoryImpl (Singleton, 绑定到 BookRepository 接口)
```

## 依赖关系

- **依赖**：`data.local.AppDatabase`、`data.local.dao.BookDao`、`data.repository.BookRepositoryImpl`、`domain.repository.BookRepository`
- **被依赖**：所有 `@HiltViewModel` 类通过构造注入获取 `BookRepository` 实例

## 编码规范

- 使用 `@Singleton` 确保 Database 和 Repository 全局唯一
- BookDao 不标 `@Singleton`，由 Room Database 实例管理其生命周期
- `@ApplicationContext` 注入 Application 级 Context，避免 Activity 内存泄漏
- Repository 绑定使用接口类型（`BookRepository`），实现依赖倒置
- Module 使用 `object` 而非 `class`（无状态，纯工厂方法）
