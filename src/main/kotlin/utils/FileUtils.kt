package moe.tachyon.shadowed.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.dataDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object FileUtils
{
    val userAvatarDir = File(dataDir, "user_avatars").apply { mkdirs() }
    val chatFilesDir = File(dataDir, "chat_files").apply { mkdirs() }

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

    suspend fun saveChatFile(messageId: Long, bytes: ByteArray)
    {
        val chatFile = File(chatFilesDir, "$messageId.dat")
        withContext(Dispatchers.IO)
        {
            chatFile.writeBytes(bytes)
        }
    }

    suspend fun getChatFile(messageId: Long): ByteArray? = runCatching()
    {
        val chatFile = File(chatFilesDir, "$messageId.dat")
        if (!chatFile.exists()) return null
        return withContext(Dispatchers.IO)
        {
            chatFile.readBytes()
        }
    }.getOrNull()
}