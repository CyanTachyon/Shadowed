package moe.tachyon.shadowed.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.dataDir
import moe.tachyon.shadowed.logger.ShadowedLogger
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

object FileUtils
{
    private val logger = ShadowedLogger.getLogger()
    val userAvatarDir = File(dataDir, "user_avatars").apply { mkdirs() }
    val groupAvatarDir = File(dataDir, "group_avatars").apply { mkdirs() }
    val chatFilesDir = File(dataDir, "chat_files").apply { mkdirs() }
    val uploadChunksDir = File(dataDir, "upload_chunks").apply { mkdirs() }
    val forumFilesDir = File(dataDir, "forum_files").apply { mkdirs() }

    suspend fun getAvatar(user: UserId): BufferedImage? = runCatching()
    {
        val avatarFile = File(userAvatarDir, "$user.png")
        if (!avatarFile.exists()) return null
        return withContext(Dispatchers.IO)
        {
            ImageIO.read(avatarFile)
        }
    }.getOrNull()

    suspend fun setAvatar(user: UserId, image: BufferedImage)
    {
        val image1 = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        val g = image1.createGraphics()
        g.drawImage(image, 0, 0, 512, 512, null)
        g.dispose()
        val avatarFile = File(userAvatarDir, "$user.png")
        withContext(Dispatchers.IO)
        {
            ImageIO.write(image1, "png", avatarFile)
        }
    }

    suspend fun getGroupAvatar(chatId: ChatId): BufferedImage? = runCatching()
    {
        val avatarFile = File(groupAvatarDir, "$chatId.png")
        if (!avatarFile.exists()) return null
        return withContext(Dispatchers.IO)
        {
            ImageIO.read(avatarFile)
        }
    }.getOrNull()

    suspend fun setGroupAvatar(chatId: ChatId, image: BufferedImage)
    {
        val image1 = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        val g = image1.createGraphics()
        g.drawImage(image, 0, 0, 512, 512, null)
        g.dispose()
        val avatarFile = File(groupAvatarDir, "$chatId.png")
        withContext(Dispatchers.IO)
        {
            ImageIO.write(image1, "png", avatarFile)
        }
    }

    suspend fun deleteGroupAvatar(chatId: ChatId): Boolean = withContext(Dispatchers.IO)
    {
        val avatarFile = File(groupAvatarDir, "$chatId.png")
        if (!avatarFile.exists()) return@withContext false
        val deleted = avatarFile.delete()
        if (deleted)
        {
            logger.info("Deleted avatar for group $chatId")
        }
        return@withContext deleted
    }

    suspend fun saveChatFile(messageId: Long, bytes: InputStream)
    {
        val chatFile = File(chatFilesDir, "$messageId.dat")
        withContext(Dispatchers.IO)
        {
            bytes.use { input ->
                chatFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun getChatFile(messageId: Long): File? = runCatching()
    {
        val chatFile = File(chatFilesDir, "$messageId.dat")
        if (!chatFile.exists()) return null
        return chatFile
    }.getOrNull()

    // 获取上传任务的分片目录
    fun getUploadDir(uploadId: String): File = File(uploadChunksDir, uploadId).apply { mkdirs() }

    // 保存分片
    suspend fun saveChunk(uploadId: String, chunkIndex: Int, bytes: InputStream)
    {
        val chunkFile = File(getUploadDir(uploadId), "chunk_$chunkIndex")
        withContext(Dispatchers.IO)
        {
            bytes.use()
            { input ->
                chunkFile.outputStream().use()
                { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    // 获取已上传的分片索引列表
    suspend fun getUploadedChunks(uploadId: String): List<Int> = withContext(Dispatchers.IO)
    {
        val dir = getUploadDir(uploadId)
        if (!dir.exists()) return@withContext emptyList()
        dir.listFiles()
            ?.filter { it.name.startsWith("chunk_") }
            ?.mapNotNull { it.name.removePrefix("chunk_").toIntOrNull() }
            ?.sorted()
            ?: emptyList()
    }

    // 合并分片到最终文件
    suspend fun mergeChunks(uploadId: String, messageId: Long, totalChunks: Int): Boolean = withContext(Dispatchers.IO)
    {
        val uploadDir = getUploadDir(uploadId)
        val chatFile = File(chatFilesDir, "$messageId.dat")

        // 检查所有分片是否存在
        for (i in 0 until totalChunks)
        {
            val chunkFile = File(uploadDir, "chunk_$i")
            if (!chunkFile.exists()) return@withContext false
        }

        // 合并分片
        chatFile.outputStream().use()
        { output ->
            for (i in 0 until totalChunks)
            {
                val chunkFile = File(uploadDir, "chunk_$i")
                chunkFile.inputStream().use()
                { input ->
                    input.copyTo(output)
                }
            }
        }

        // 清理分片目录
        uploadDir.deleteRecursively()
        true
    }

    /**
     * Delete a message's associated file if it exists
     * Used for IMAGE, VIDEO, and FILE message types
     */
    suspend fun deleteChatFile(messageId: Long): Boolean = withContext(Dispatchers.IO)
    {
        val chatFile = File(chatFilesDir, "$messageId.dat")
        if (!chatFile.exists()) return@withContext false
        val deleted = chatFile.delete()
        if (deleted)
        {
            logger.info("Deleted file for message $messageId")
        }
        return@withContext deleted
    }

    // ==================== Forum Files ====================

    suspend fun saveForumFile(attachmentId: Long, bytes: InputStream)
    {
        val file = File(forumFilesDir, "$attachmentId.dat")
        withContext(Dispatchers.IO)
        {
            forumFilesDir.mkdirs()
            bytes.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun getForumFile(attachmentId: Long): File?
    {
        val file = File(forumFilesDir, "$attachmentId.dat")
        return if (file.exists()) file else null
    }

    suspend fun deleteForumFile(attachmentId: Long): Boolean = withContext(Dispatchers.IO)
    {
        val file = File(forumFilesDir, "$attachmentId.dat")
        if (!file.exists()) return@withContext false
        file.delete()
    }

    // ==================== Magic-byte validation ====================

    /**
     * Known file signatures for the MIME types accepted by the forum upload and
     * chat send_file endpoints. Most signatures appear at offset 0; special-cased
     * types (mp4, webp, wav) are handled in [validateMagicBytes].
     *
     * `image/avif` is intentionally mapped to an empty signature list: its ISO
     * BMFF ftyp-box signature collides with `video/mp4` unless the 4-byte major
     * brand at offset 8 is also inspected, and AVIF has several valid brands
     * (`avif`, `avis`, `mif1`). The empty-list sentinel lets the type pass
     * through [matchesAnySignature] (which would otherwise reject AVIF uploads
     * because it iterates this map's keys).
     */
    private val MAGIC_BYTES: Map<String, List<ByteArray>> = mapOf(
        "image/png"  to listOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        "image/jpeg" to listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
        "image/gif"  to listOf(
            byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61), // GIF87a
            byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)  // GIF89a
        ),
        "image/webp" to listOf(byteArrayOf(0x52, 0x49, 0x46, 0x46)), // RIFF....WEBP — brand checked separately
        "image/avif" to emptyList(), // No usable magic byte pattern; see KDoc above
        "video/mp4"  to listOf(byteArrayOf(0x66, 0x74, 0x79, 0x70)), // "ftyp" at offset 4
        "video/webm" to listOf(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())), // EBML
        "audio/mpeg" to listOf(
            byteArrayOf(0x49, 0x44, 0x33),              // ID3v2 tag
            byteArrayOf(0xFF.toByte(), 0xFB.toByte())   // MPEG-1 Layer 3 frame sync (no CRC)
        ),
        "audio/ogg"  to listOf(byteArrayOf(0x4F, 0x67, 0x67, 0x53)), // OggS
        "audio/wav"  to listOf(byteArrayOf(0x52, 0x49, 0x46, 0x46)), // RIFF....WAVE — brand checked separately
        "application/pdf" to listOf(byteArrayOf(0x25, 0x50, 0x44, 0x46)) // %PDF
    )

    /**
     * Verifies that the leading bytes of [data] match one of the known
     * signatures for [declaredType]. Used as a server-side defense against
     * clients that lie via the `Content-Type` header.
     *
     * Returns `true` for unknown MIME types or types whose signatures are
     * declared as `emptyList()` (currently `image/avif`) — the caller has
     * already enforced a MIME allowlist, so unknown-but-allowed types simply
     * skip the magic-byte layer (conservative policy: never break a legitimate
     * upload because of an incomplete signature table).
     */
    fun validateMagicBytes(data: ByteArray, declaredType: String): Boolean
    {
        val normalized = declaredType.trim().lowercase()
        val signatures = MAGIC_BYTES[normalized] ?: return true
        if (signatures.isEmpty()) return true
        if (data.isEmpty()) return false

        return signatures.any { sig ->
            when (normalized)
            {
                "video/mp4" -> data.size >= 8 &&
                    data.copyOfRange(4, 8).contentEquals(sig)
                "image/webp" -> data.size >= 12 &&
                    data.copyOfRange(0, 4).contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46)) && // RIFF
                    data.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50))    // WEBP
                "audio/wav" -> data.size >= 12 &&
                    data.copyOfRange(0, 4).contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46)) && // RIFF
                    data.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x41, 0x56, 0x45))    // WAVE
                else -> data.size >= sig.size &&
                    data.copyOfRange(0, sig.size).contentEquals(sig)
            }
        }
    }

    /**
     * For chat file uploads where only the broad [MessageType] category is known
     * (IMAGE / VIDEO), not the exact MIME subtype. Returns `true` if [data]
     * matches any signature whose MIME type starts with one of [mimePrefixes]
     * (e.g. `image/`, `video/`). Types mapped to an empty signature list
     * (e.g. `image/avif`) automatically pass.
     */
    fun matchesAnySignature(data: ByteArray, mimePrefixes: Set<String>): Boolean
    {
        if (data.isEmpty()) return false
        val candidates = MAGIC_BYTES.keys.filter { mime -> mimePrefixes.any { p -> mime.startsWith(p) } }
        if (candidates.isEmpty()) return true
        return candidates.any { mime -> validateMagicBytes(data, mime) }
    }
}