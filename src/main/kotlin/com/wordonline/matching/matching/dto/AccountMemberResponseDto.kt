package com.wordonline.matching.matching.dto

data class AccountMemberResponseDto @JvmOverloads constructor(
    val email: String,
    val name: String,
    val id: Long? = null,
) {
    val displayName: String get() = name
}
