package moe.tachyon.shadowed.dataClass

import kotlinx.serialization.Serializable

@Serializable
enum class ForumPostZone
{
    PUBLIC,
    PROTECT,
    PRIVATE;
}
