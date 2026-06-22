package moe.tachyon.shadowed.route

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import moe.tachyon.shadowed.contentNegotiationJson
import moe.tachyon.shadowed.dataClass.ChatId
import moe.tachyon.shadowed.dataClass.Message
import moe.tachyon.shadowed.dataClass.MessageType
import moe.tachyon.shadowed.dataClass.UserId
import moe.tachyon.shadowed.database.ChatMembers
import moe.tachyon.shadowed.database.Chats
import moe.tachyon.shadowed.database.Messages
import moe.tachyon.shadowed.logger.ShadowedLogger
import moe.tachyon.shadowed.utils.FileUtils
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val logger = ShadowedLogger.getLogger()

// 上传任务信息
@Serializable
data class UploadTaskInfo(
    val uploadId: String,
    val chatId: ChatId,
    val userId: UserId,
    val messageType: MessageType,
    val metadata: String,
    val totalChunks: Int,
    val totalSize: Long,
    val createdAt: Long
)

// 内存中的上传任务缓存
private val uploadTasks = ConcurrentHashMap<String, UploadTaskInfo>()

/**
 * Eviction threshold for [uploadTasks], in milliseconds. Initialised from
 * `upload.taskExpireHours` (default 24h) when [Route.fileRoute] is installed.
 */
private var uploadTaskExpireMs: Long = 24L * 60 * 60 * 1000

private val uploadCleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
private var uploadCleanupJob: Job? = null
private const val UPLOAD_CLEANUP_INTERVAL_MS = 60L * 60 * 1000 // 1h

/**
 * Removes upload tasks whose [UploadTaskInfo.createdAt] is older than the
 * configured `upload.taskExpireHours` threshold. Deletes both the in-memory
 * entry and the on-disk chunk directory. Safe to call from any thread.
 *
 * Exposed publicly so a Main.kt startup hook or operational tooling can
 * trigger an explicit sweep on demand; it is also invoked automatically on
 * the [UPLOAD_CLEANUP_INTERVAL_MS] cadence by [startUploadCleanup].
 */
fun cleanupExpiredUploads()
{
    val now = System.currentTimeMillis()
    val cutoff = now - uploadTaskExpireMs
    val staleIds = uploadTasks.entries.asSequence()
        .filter { it.value.createdAt < cutoff }
        .map { it.key }
        .toList()
    for (id in staleIds)
    {
        val info = uploadTasks.remove(id) ?: continue
        runCatching { FileUtils.getUploadDir(id).deleteRecursively() }
            .onFailure { logger.warning("Failed to delete upload dir for $id: ${it.message}") }
        logger.info("Evicted expired upload task $id (chat=${info.chatId}, user=${info.userId}, ageMs=${now - info.createdAt})")
    }
}

/**
 * Launches a background coroutine that periodically calls [cleanupExpiredUploads]
 * to evict abandoned chunked uploads. Idempotent: subsequent calls are no-ops
 * while a previous job is still active.
 *
 * This is started from inside [Route.fileRoute] rather than Main.kt to keep the
 * upload subsystem self-contained. If Main.kt gains a centralised startup-hook
 * registry later, this can move there.
 */
private fun startUploadCleanup(expireHours: Long)
{
    if (uploadCleanupJob?.isActive == true) return
    uploadTaskExpireMs = expireHours.coerceAtLeast(1L) * 60L * 60 * 1000
    uploadCleanupJob = uploadCleanupScope.launch {
        logger.info("Upload cleanup task started: expireHours=$expireHours, sweepIntervalMs=$UPLOAD_CLEANUP_INTERVAL_MS")
        while (isActive)
        {
            try
            {
                delay(UPLOAD_CLEANUP_INTERVAL_MS)
                cleanupExpiredUploads()
            }
            catch (e: CancellationException)
            {
                throw e
            }
            catch (e: Exception)
            {
                logger.severe("Upload cleanup error: ${e.message}", e)
            }
        }
    }
}

fun Route.fileRoute()
{
    // Launch the abandoned-upload eviction loop using the configured threshold.
    val expireHours = environment.config.propertyOrNull("upload.taskExpireHours")?.getString()?.toLongOrNull() ?: 24L
    startUploadCleanup(expireHours)

    // 获取文件大小限制
    fun getMaxSize(messageType: MessageType): Long
    {
        return when (messageType)
        {
            MessageType.IMAGE -> environment.config.property("maxImageSize").getString().toLong()
            MessageType.VIDEO -> environment.config.propertyOrNull("maxVideoSize")?.getString()?.toLong() ?: (1024L * 1024 * 1024)
            MessageType.FILE -> environment.config.propertyOrNull("maxFileSize")?.getString()?.toLong() ?: (1024L * 1024 * 1024)
            else -> environment.config.property("maxImageSize").getString().toLong()
        }
    }

    // 获取分片大小
    fun getChunkSize(): Long
    {
        return environment.config.propertyOrNull("upload.chunkSize")?.getString()?.toLong() ?: (5L * 1024 * 1024)
    }

    post("/send_file")
    {
        val chat = call.request.header("X-Chat-Id")?.toIntOrNull()?.let(::ChatId)
        val messageType = call.request.header("X-Message-Type")?.let(MessageType::fromString)
        val metadata = call.request.header("X-Message-Metadata") ?: ""
        val bodySize = call.request.header(HttpHeaders.ContentLength)?.toIntOrNull() ?: return@post call.respondApiError("Content-Length required", HttpStatusCode.LengthRequired)
        if (messageType != null && bodySize > getMaxSize(messageType))
        {
            call.respondApiError("File size exceeds limit", HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val rawStream = call.receiveStream()
        if (chat == null || messageType == null)
            return@post call.respondApiError("Missing chat or message type", HttpStatusCode.BadRequest)
        val userAuth = call.authenticateSession() ?: return@post call.respondApiError("Authentication required", HttpStatusCode.Unauthorized)
        if (!getKoin().get<ChatMembers>().isMember(chat, userAuth.id))
            return@post call.respondApiError("Not a chat member", HttpStatusCode.Forbidden)

        // Server-side content sanity check for media types — defends against
        // malicious uploads that lie via X-Message-Type / Content-Type.
        val saveStream: InputStream = if (messageType == MessageType.IMAGE || messageType == MessageType.VIDEO)
        {
            val header = ByteArray(32)
            var totalRead = 0
            while (totalRead < header.size)
            {
                val n = rawStream.read(header, totalRead, header.size - totalRead)
                if (n <= 0) break
                totalRead += n
            }
            val headerBytes = if (totalRead > 0) header.copyOf(totalRead) else ByteArray(0)
            val prefix = if (messageType == MessageType.IMAGE) "image/" else "video/"
            if (!FileUtils.matchesAnySignature(headerBytes, setOf(prefix)))
                return@post call.respondApiError("File content does not match declared type", HttpStatusCode.BadRequest)
            SequenceInputStream(ByteArrayInputStream(headerBytes), rawStream)
        }
        else
        {
            rawStream
        }

        val messages = getKoin().get<Messages>()
        val burnTime = getKoin().get<Chats>().getChat(chat)?.burnTime
        val messageId = messages.addChatMessage(
            content = metadata,
            type = messageType,
            chatId = chat,
            senderId = userAuth.id,
            burnTime = burnTime,
            replyTo = null,
        )
        getKoin().get<Chats>().updateTime(chat)
        getKoin().get<ChatMembers>().incrementUnread(chat, userAuth.id)
        getKoin().get<ChatMembers>().resetUnread(chat, userAuth.id)
        FileUtils.saveChatFile(messageId, saveStream)
        call.respondApi(
            buildJsonObject()
            {
                put("messageId", messageId)
            }
        )
        distributeMessage(
            Message(
                id = messageId,
                content = metadata,
                type = messageType,
                chatId = chat,
                senderId = userAuth.id,
                senderName = userAuth.username,
                time = Clock.System.now().toEpochMilliseconds(),
                readAt = null,
                senderIsDonor = userAuth.isDonor,
                replyTo = null,
                burn = null,
            ),
            silent = false
        )
    }

    // === 分片上传 API ===

    // 初始化上传任务
    post("/upload/init")
    {
        @Serializable
        data class InitRequest(
            val chatId: ChatId,
            val messageType: String,
            val metadata: String,
            val totalChunks: Int,
            val totalSize: Long
        )

        val userAuth = call.authenticateSession() ?: return@post call.respondApiError("Authentication required", HttpStatusCode.Unauthorized)

        val request = call.receive<InitRequest>()
        val messageType = MessageType.fromString(request.messageType)

        // 验证聊天权限
        if (!getKoin().get<ChatMembers>().isMember(request.chatId, userAuth.id))
            return@post call.respondApiError("Not a chat member", HttpStatusCode.Forbidden)

        // 验证文件大小
        if (request.totalSize > getMaxSize(messageType))
            return@post call.respondApiError("File size exceeds limit", HttpStatusCode.PayloadTooLarge)

        // 创建上传任务
        val uploadId = UUID.randomUUID().toString()
        val taskInfo = UploadTaskInfo(
            uploadId = uploadId,
            chatId = request.chatId,
            userId = userAuth.id,
            messageType = messageType,
            metadata = request.metadata,
            totalChunks = request.totalChunks,
            totalSize = request.totalSize,
            createdAt = Clock.System.now().toEpochMilliseconds()
        )
        uploadTasks[uploadId] = taskInfo

        // 创建分片目录
        FileUtils.getUploadDir(uploadId)

        call.respondApi(
            buildJsonObject()
            {
                put("uploadId", uploadId)
                put("chunkSize", getChunkSize())
            }
        )
    }

    // 上传分片
    post("/upload/{uploadId}/chunk/{chunkIndex}")
    {
        val uploadId = call.pathParameters["uploadId"] ?: return@post call.respondApiError("Invalid upload id", HttpStatusCode.BadRequest)
        val chunkIndex = call.pathParameters["chunkIndex"]?.toIntOrNull() ?: return@post call.respondApiError("Invalid chunk index", HttpStatusCode.BadRequest)
        val userAuth = call.authenticateSession() ?: return@post call.respondApiError("Authentication required", HttpStatusCode.Unauthorized)

        val taskInfo = uploadTasks[uploadId] ?: return@post call.respondApiError("Upload task not found", HttpStatusCode.NotFound)
        if (taskInfo.userId != userAuth.id)
            return@post call.respondApiError("Not authorized", HttpStatusCode.Forbidden)

        if (chunkIndex < 0 || chunkIndex >= taskInfo.totalChunks)
            return@post call.respondApiError("Invalid chunk index", HttpStatusCode.BadRequest)

        // 保存分片
        val chunkData = call.receiveStream()
        FileUtils.saveChunk(uploadId, chunkIndex, chunkData)

        val uploadedChunks = FileUtils.getUploadedChunks(uploadId)
        call.respondApi(
            buildJsonObject()
            {
                put("chunkIndex", chunkIndex)
                put("uploadedCount", uploadedChunks.size)
            }
        )
    }

    // 查询上传状态
    get("/upload/{uploadId}/status")
    {
        val uploadId = call.pathParameters["uploadId"] ?: return@get call.respondApiError("Invalid upload id", HttpStatusCode.BadRequest)
        val userAuth = call.authenticateSession() ?: return@get call.respondApiError("Authentication required", HttpStatusCode.Unauthorized)

        val taskInfo = uploadTasks[uploadId] ?: return@get call.respondApiError("Upload task not found", HttpStatusCode.NotFound)
        if (taskInfo.userId != userAuth.id)
            return@get call.respondApiError("Not authorized", HttpStatusCode.Forbidden)

        val uploadedChunks = FileUtils.getUploadedChunks(uploadId)
        call.respondApi(
            buildJsonObject()
            {
                put("uploadId", uploadId)
                put("totalChunks", taskInfo.totalChunks)
                put("uploadedChunks", contentNegotiationJson.encodeToJsonElement(uploadedChunks))
                put("isComplete", uploadedChunks.size == taskInfo.totalChunks)
            }
        )
    }

    // 完成上传
    post("/upload/{uploadId}/complete")
    {
        val uploadId = call.pathParameters["uploadId"] ?: return@post call.respondApiError("Invalid upload id", HttpStatusCode.BadRequest)
        val userAuth = call.authenticateSession() ?: return@post call.respondApiError("Authentication required", HttpStatusCode.Unauthorized)

        val taskInfo = uploadTasks[uploadId] ?: return@post call.respondApiError("Upload task not found", HttpStatusCode.NotFound)
        if (taskInfo.userId != userAuth.id)
            return@post call.respondApiError("Not authorized", HttpStatusCode.Forbidden)

        // 检查是否所有分片都已上传
        val uploadedChunks = FileUtils.getUploadedChunks(uploadId)
        if (uploadedChunks.size != taskInfo.totalChunks)
            return@post call.respondApiError("Not all chunks uploaded: ${uploadedChunks.size}/${taskInfo.totalChunks}", HttpStatusCode.BadRequest)

        val messages = getKoin().get<Messages>()

        // 创建消息记录
        val messageId = messages.addChatMessage(
            content = taskInfo.metadata,
            type = taskInfo.messageType,
            chatId = taskInfo.chatId,
            senderId = userAuth.id,
            burnTime = getKoin().get<Chats>().getChat(taskInfo.chatId)?.burnTime,
            replyTo = null,
        )

        // 合并分片
        val mergeSuccess = FileUtils.mergeChunks(uploadId, messageId, taskInfo.totalChunks)
        if (!mergeSuccess)
        {
            // 合并失败，删除消息记录
            messages.deleteMessage(messageId)
            return@post call.respondApiError("Failed to merge chunks", HttpStatusCode.InternalServerError)
        }

        // 更新聊天时间和未读计数
        getKoin().get<Chats>().updateTime(taskInfo.chatId)
        getKoin().get<ChatMembers>().incrementUnread(taskInfo.chatId, userAuth.id)
        getKoin().get<ChatMembers>().resetUnread(taskInfo.chatId, userAuth.id)

        // 移除上传任务
        uploadTasks.remove(uploadId)

        call.respondApi(
            buildJsonObject()
            {
                put("messageId", messageId)
            }
        )

        // 推送消息
        distributeMessage(
            Message(
                id = messageId,
                content = taskInfo.metadata,
                type = taskInfo.messageType,
                chatId = taskInfo.chatId,
                senderId = userAuth.id,
                senderName = userAuth.username,
                time = Clock.System.now().toEpochMilliseconds(),
                readAt = null,
                replyTo = null,
                burn = null,
                senderIsDonor = userAuth.isDonor,
            ),
            silent = false
        )
    }

    // 取消上传
    delete("/upload/{uploadId}")
    {
        val uploadId = call.pathParameters["uploadId"] ?: return@delete call.respondApiError("Invalid upload id", HttpStatusCode.BadRequest)
        val userAuth = call.authenticateSession() ?: return@delete call.respondApiError("Authentication required", HttpStatusCode.Unauthorized)

        val taskInfo = uploadTasks[uploadId]
        if (taskInfo != null && taskInfo.userId != userAuth.id)
            return@delete call.respondApiError("Not authorized", HttpStatusCode.Forbidden)

        // 删除分片目录
        FileUtils.getUploadDir(uploadId).deleteRecursively()
        uploadTasks.remove(uploadId)

        call.respondApi(Unit)
    }

    get("/file/{messageId}")
    {
        val messageId = call.pathParameters["messageId"]?.toLongOrNull() ?: return@get call.respondApiError("Invalid message id", HttpStatusCode.BadRequest)
        // Session-based auth (was: plaintext-password replay via X-Auth-Token, C-2)
        val userAuth = call.authenticateSession()
            ?: return@get call.respondApiError("Authentication required", HttpStatusCode.Unauthorized)
        val message = getKoin().get<Messages>().getMessage(messageId) ?: return@get call.respondApiError("Message not found", HttpStatusCode.NotFound)
        if (!getKoin().get<ChatMembers>().isMember(message.chatId, userAuth.id))
            return@get call.respondApiError("Not a chat member", HttpStatusCode.Forbidden)
        val fileBytes = FileUtils.getChatFile(messageId) ?: return@get call.respondApiError("File not found", HttpStatusCode.NotFound)
        val bytes = fileBytes.readBytes()
        call.response.header(HttpHeaders.CacheControl, "max-age=${30*24*60*60}") // 30 days
        call.respondBytes(bytes, ContentType.Text.Plain)
    }
}

internal suspend fun distributeMessage(message: Message, silent: Boolean)
{
    val members = getKoin().get<ChatMembers>().getMemberIds(message.chatId)
    members.forEach()
    { uid ->
        SessionManager.forEachSession(uid)
        { s ->
            s.sendUnreadCount(uid, message.chatId)
            val pushData = buildJsonObject()
            {
                put("packet", "receive_message")
                put("message", contentNegotiationJson.encodeToJsonElement(message))
                put("silent", silent)
            }
            logger.warning("sending message to $uid")
            {
                s.send(contentNegotiationJson.encodeToString(pushData))
            }
        }
    }
}
