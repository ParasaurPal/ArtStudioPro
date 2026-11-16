package com.artstudio.pro.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LayerGroup(
    val id:String=UUID.randomUUID().toString(),
    var name:String,
    var isVisible:Boolean=true,
    var isExpanded:Boolean=true
)
