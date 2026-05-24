package com.example.read.util

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EpubParser 安全防护单元测试。
 *
 * 测试范围：
 * 1. ZIP Slip 路径穿越攻击防护：验证 extractZip 方法能检测并拒绝恶意路径
 * 2. ZIP 条目大小限制：验证超大条目（>100MB）被正确拒绝
 * 3. 正常 ZIP 条目的兼容性：验证合法路径不受安全检查影响
 *
 * 安全背景：
 * - ZIP Slip 是一种常见的路径穿越攻击，通过在 ZIP 条目名中使用 "../../" 等相对路径，
 *   使解压后的文件落在目标目录之外，可能覆盖系统关键文件。
 * - ZIP 炸弹通过声明极大的解压大小来耗尽存储空间。
 *
 * 测试策略：
 * - unpack() 方法在解析 container.xml 之前先调用 extractZip()，
 *   因此路径穿越检查会在 XML 解析之前触发 SecurityException。
 * - 使用内存中的 ZIP 字节数组，无需真实文件系统。
 * - 大小限制测试通过手动构造 ZIP 二进制数据实现，避免 ZipOutputStream
 *   在 data descriptor 模式下将 size 写为 0 的问题。
 */
class EpubParserSecurityTest {

    // ==================== ZIP Slip 路径穿越防护测试 ====================

    /**
     * 验证包含 "../../evil.txt" 路径穿越的 ZIP 条目被拒绝。
     *
     * 测试场景：ZIP 中包含一个名为 "../../evil.txt" 的条目，
     * 该条目解压后会落在目标目录的上两级目录中。
     * 预期：unpack() 抛出 SecurityException，消息中包含 "ZIP Slip" 关键字。
     */
    @Test
    fun unpack_zipSlipParentTraversal_throwsSecurityException() {
        // 准备：创建包含路径穿越条目的 ZIP
        val maliciousZip = createZipWithEntries(
            "../../evil.txt" to "malicious content".toByteArray()
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_security_test_${System.nanoTime()}")
        try {
            // 执行 & 验证：应抛出 SecurityException
            val exception = assertThrows<SecurityException> {
                parser.unpack(maliciousZip.inputStream(), tempDir)
            }
            assertTrue(
                "异常消息应包含 ZIP Slip 关键字",
                exception.message!!.contains("ZIP Slip") || exception.message!!.contains("路径越界")
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 验证包含 "subdir/../../evil.txt" 嵌套路径穿越的 ZIP 条目被拒绝。
     *
     * 测试场景：ZIP 中包含一个名为 "subdir/../../evil.txt" 的条目，
     * 虽然第一级是合法的 "subdir/"，但 "../.." 仍然使路径穿越到目标目录之外。
     * 预期：unpack() 抛出 SecurityException。
     */
    @Test
    fun unpack_zipSlipNestedTraversal_throwsSecurityException() {
        // 准备：创建包含嵌套路径穿越条目的 ZIP
        val maliciousZip = createZipWithEntries(
            "subdir/../../evil.txt" to "malicious content".toByteArray()
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_security_test_${System.nanoTime()}")
        try {
            // 执行 & 验证：应抛出 SecurityException
            val exception = assertThrows<SecurityException> {
                parser.unpack(maliciousZip.inputStream(), tempDir)
            }
            assertTrue(
                "异常消息应包含路径越界关键字",
                exception.message!!.contains("ZIP Slip") || exception.message!!.contains("路径越界")
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 验证包含绝对路径穿越的 ZIP 条目被拒绝（仅 Unix/macOS 系统）。
     *
     * 测试场景：ZIP 中包含一个名为 "/etc/passwd" 的条目（绝对路径），
     * 在 Unix/macOS 上该路径解压后会落在根目录下。
     * 在 Windows 上 "/etc/passwd" 被 File(parent, child) 视为相对路径，
     * 不会穿越目标目录，因此此测试在 Windows 上跳过。
     * 预期：unpack() 抛出 SecurityException（仅 Unix/macOS）。
     */
    @Test
    fun unpack_zipSlipAbsolutePath_throwsSecurityException() {
        // Windows 将 "/etc/passwd" 视为相对路径（File(parent, child) 会拼接），
        // 不会穿越目标目录边界，因此跳过此测试
        Assume.assumeFalse(
            "Windows 将 /path 视为相对路径，此测试仅适用于 Unix/macOS",
            (System.getProperty("os.name") ?: "").lowercase().contains("windows")
        )

        // 准备：创建包含绝对路径条目的 ZIP
        val maliciousZip = createZipWithEntries(
            "/etc/passwd" to "root:x:0:0:root:/root:/bin/bash".toByteArray()
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_security_test_${System.nanoTime()}")
        try {
            // 执行 & 验证：应抛出 SecurityException
            assertThrows<SecurityException> {
                parser.unpack(maliciousZip.inputStream(), tempDir)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== 正常路径兼容性测试 ====================

    /**
     * 验证正常的 ZIP 条目（无路径穿越）能正常解压，不触发安全检查。
     *
     * 测试场景：ZIP 中包含合法路径的条目（如 "META-INF/container.xml"），
     * 虽然缺少完整的 EPUB 结构会导致后续解析失败，但 extractZip 阶段不应抛出 SecurityException。
     * 预期：不抛出 SecurityException（可能抛出 IllegalArgumentException，因为缺少 container.xml）。
     */
    @Test
    fun unpack_normalZipEntries_doesNotThrowSecurityException() {
        // 准备：创建包含正常路径的 ZIP（模拟 EPUB 结构但不完整）
        val normalZip = createZipWithEntries(
            "META-INF/container.xml" to "<container/>".toByteArray(),
            "OEBPS/content.opf" to "<package/>".toByteArray(),
            "OEBPS/chapter1.xhtml" to "<html><body>内容</body></html>".toByteArray()
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_security_test_${System.nanoTime()}")
        try {
            // 执行：调用 unpack，不应抛出 SecurityException
            // 由于 container.xml 内容不完整，可能会抛出 IllegalArgumentException，这是预期的
            try {
                parser.unpack(normalZip.inputStream(), tempDir)
            } catch (e: IllegalArgumentException) {
                // 预期异常：container.xml 缺少 rootfile 元素
                // 重要的是没有抛出 SecurityException
            } catch (e: SecurityException) {
                // 不应抛出 SecurityException
                throw AssertionError("正常路径不应触发 SecurityException", e)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 验证包含深层嵌套目录的正常路径能正常解压。
     *
     * 测试场景：ZIP 中包含多层嵌套目录的合法路径（如 "a/b/c/file.txt"），
     * 这种路径虽然包含多级目录，但不会穿越目标目录边界。
     * 预期：不抛出 SecurityException。
     */
    @Test
    fun unpack_deepNestedNormalPath_doesNotThrowSecurityException() {
        // 准备：创建包含深层嵌套路径的 ZIP
        val normalZip = createZipWithEntries(
            "a/b/c/d/file.txt" to "deep nested content".toByteArray()
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_security_test_${System.nanoTime()}")
        try {
            // 执行：不应抛出 SecurityException
            try {
                parser.unpack(normalZip.inputStream(), tempDir)
            } catch (e: IllegalArgumentException) {
                // 预期异常：缺少 EPUB 结构
            } catch (e: SecurityException) {
                throw AssertionError("深层嵌套的正常路径不应触发 SecurityException", e)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== ZIP 条目大小限制测试 ====================

    /**
     * 验证超过 100MB 大小限制的 ZIP 条目被拒绝。
     *
     * 测试场景：手动构造 ZIP 二进制数据，使条目的声明未压缩大小为 200MB。
     * 由于 ZipOutputStream 在 data descriptor 模式下会将 local file header 中的 size 写为 0，
     * 无法通过标准 API 创建声明大尺寸的条目，因此直接构造 ZIP 的 local file header 二进制数据。
     *
     * 手动构造的 ZIP 结构：
     * - Local File Header（30 字节 + 文件名）：uncompressed size 字段设为 200MB
     * - 文件数据（实际内容很小，仅 12 字节）
     * - 不包含 Central Directory（不需要，因为 size 检查在此之前触发）
     *
     * 预期：unpack() 抛出 IllegalArgumentException，消息中包含条目过大的关键字。
     */
    @Test
    fun unpack_oversizedZipEntry_throwsIllegalArgumentException() {
        // 准备：手动构造 ZIP 二进制数据，声明 size 为 200MB
        val fakeOversizedZip = createZipWithFakeOversizedSize(
            entryName = "META-INF/container.xml",
            content = "<container/>".toByteArray(),
            declaredUncompressedSize = 200 * 1024 * 1024 // 200MB
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_security_test_${System.nanoTime()}")
        try {
            // 执行 & 验证：应抛出 IllegalArgumentException
            val exception = assertThrows<IllegalArgumentException> {
                parser.unpack(fakeOversizedZip.inputStream(), tempDir)
            }
            assertTrue(
                "异常消息应包含条目过大关键字，实际消息: ${exception.message}",
                exception.message!!.contains("过大") || exception.message!!.contains("恶意文件")
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 验证恰好在 100MB 限制内的 ZIP 条目不被拒绝。
     *
     * 测试场景：手动构造 ZIP 二进制数据，声明 size 恰好为 100MB。
     * extractZip 中的检查是 `entrySize > MAX_ZIP_ENTRY_SIZE`（严格大于），
     * 因此 100MB 不应触发拒绝。
     * 预期：不抛出 IllegalArgumentException（可能因缺少 EPUB 结构而抛出其他异常）。
     */
    @Test
    fun unpack_entryAtSizeLimit_doesNotThrowSizeException() {
        // 准备：手动构造 ZIP 二进制数据，声明 size 恰好为 100MB
        val boundaryZip = createZipWithFakeOversizedSize(
            entryName = "content.txt",
            content = "boundary test".toByteArray(),
            declaredUncompressedSize = 100 * 1024 * 1024 // 100MB，恰好等于限制
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_security_test_${System.nanoTime()}")
        try {
            // 执行：不应抛出关于大小限制的 IllegalArgumentException（100MB 不大于 100MB）
            try {
                parser.unpack(boundaryZip.inputStream(), tempDir)
            } catch (e: IllegalArgumentException) {
                // 可能因为缺少 EPUB 结构而抛出，但不应是因为大小限制
                if (e.message!!.contains("过大") || e.message!!.contains("恶意文件")) {
                    throw AssertionError("恰好 100MB 的条目不应触发大小限制，实际消息: ${e.message}", e)
                }
                // 其他 IllegalArgumentException（如缺少 container.xml）是预期的
            } catch (e: SecurityException) {
                // 不应触发安全异常
                throw AssertionError("100MB 条目不应触发安全异常", e)
            } catch (e: java.util.zip.ZipException) {
                // 预期：手动构造的 ZIP 数据只有 13 字节实际内容，但 header 声明 100MB，
                // 数据读取阶段会因数据截断而抛出 ZipException。
                // 重要的是大小检查（extractZip 中的 entrySize > MAX_ZIP_ENTRY_SIZE）没有触发。
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建包含指定条目的 ZIP 字节数组。
     * 用于测试 ZIP Slip 防护和正常路径兼容性。
     *
     * @param entries 条目名到内容字节数组的映射
     * @return ZIP 文件的字节数组
     */
    private fun createZipWithEntries(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    /**
     * 手动构造包含假大尺寸条目的 ZIP 二进制数据。
     *
     * 由于 ZipOutputStream 在 data descriptor 模式下将 local file header 中的
     * uncompressed size 写为 0（因为 compressedSize 和 crc 未预先设置），
     * 无法通过标准 API 创建声明大尺寸的条目。
     * 此方法直接构造 ZIP 的 local file header 二进制数据，精确控制 size 字段。
     *
     * ZIP Local File Header 结构（小端字节序）：
     * - Offset 0: 签名 (0x04034b50, 4 bytes)
     * - Offset 4: 解压所需最低版本 (2 bytes)
     * - Offset 6: 通用标志位 (2 bytes, 设为 0 表示无 data descriptor)
     * - Offset 8: 压缩方法 (2 bytes, 0=STORED)
     * - Offset 10: 最后修改时间 (2 bytes)
     * - Offset 12: 最后修改日期 (2 bytes)
     * - Offset 14: CRC-32 (4 bytes)
     * - Offset 18: 压缩后大小 (4 bytes)
     * - Offset 22: 未压缩大小 (4 bytes) <-- 此字段设为声明的大值
     * - Offset 26: 文件名长度 (2 bytes)
     * - Offset 28: 扩展字段长度 (2 bytes, 设为 0)
     * - 之后: 文件名 + 文件数据
     *
     * @param entryName 条目文件名
     * @param content 实际文件内容（可以很小）
     * @param declaredUncompressedSize 声明的未压缩大小（如 200MB）
     * @return ZIP 文件的字节数组
     */
    private fun createZipWithFakeOversizedSize(
        entryName: String,
        content: ByteArray,
        declaredUncompressedSize: Int,
    ): ByteArray {
        val nameBytes = entryName.toByteArray()
        val headerSize = 30 + nameBytes.size + content.size
        val buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)

        // Local File Header
        buffer.putInt(0x04034b50)           // 签名: PK\x03\x04
        buffer.putShort(20)                  // 版本: 2.0
        buffer.putShort(0)                   // 标志位: 0（无 data descriptor）
        buffer.putShort(0)                   // 压缩方法: STORED
        buffer.putShort(0)                   // 修改时间
        buffer.putShort(0)                   // 修改日期
        buffer.putInt(0)                     // CRC-32（填 0，不需要实际校验）
        buffer.putInt(content.size)          // 压缩大小: 实际内容大小
        buffer.putInt(declaredUncompressedSize) // 未压缩大小: 声明的大值（200MB）
        buffer.putShort(nameBytes.size.toShort()) // 文件名长度
        buffer.putShort(0)                   // 扩展字段长度
        buffer.put(nameBytes)                // 文件名
        buffer.put(content)                  // 文件数据

        return buffer.array()
    }

    /**
     * 断言指定类型的异常被抛出。
     * 替代 JUnit 4 的 @Test(expected=...) 注解，支持更精确的异常验证。
     *
     * @param T 期望的异常类型
     * @param block 要执行的代码块
     * @return 捕获到的异常实例，供调用方进一步验证消息内容
     */
    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
            throw AssertionError("期望抛出 ${T::class.simpleName}，但没有异常被抛出")
        } catch (e: Throwable) {
            if (e is T) return e
            if (e is AssertionError) throw e
            throw AssertionError("期望抛出 ${T::class.simpleName}，但实际抛出了 ${e::class.simpleName}: ${e.message}", e)
        }
    }
}
