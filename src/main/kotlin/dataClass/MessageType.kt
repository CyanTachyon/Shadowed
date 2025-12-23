package moe.tachyon.shadowed.dataClass

enum class MessageType
{
    TEXT,
    IMAGE,
    ;
    companion object
    {
        fun fromString(value: String): MessageType = when (value.uppercase())
        {
            "TEXT" -> TEXT
            "IMAGE" -> IMAGE
            else -> TEXT
        }
    }
}