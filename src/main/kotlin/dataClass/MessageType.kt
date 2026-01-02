package moe.tachyon.shadowed.dataClass

enum class MessageType
{
    TEXT,
    IMAGE,
    VIDEO,
    FILE,
    ;
    companion object
    {
        fun fromString(value: String): MessageType = when (value.uppercase())
        {
            "TEXT" -> TEXT
            "IMAGE" -> IMAGE
            "VIDEO" -> VIDEO
            "FILE" -> FILE
            else -> TEXT
        }
    }
}