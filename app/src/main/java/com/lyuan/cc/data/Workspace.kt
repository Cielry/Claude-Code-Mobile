package com.lyuan.cc.data

/** 用户保存的自定义工作区。 */
data class Workspace(
    val id: String,
    val name: String,
    val uri: String,
    val path: String,
)