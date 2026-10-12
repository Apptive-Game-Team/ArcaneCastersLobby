package com.wordonline.matching.matching.dto

data class AccountMemberResponseDto @JvmOverloads constructor(
    val email: String,
    val name: String,
    val id: Long? = null,
    /** Guest account flag from the account server; absent (false) until that server sends it. */
    val guest: Boolean = false,
) {
    val displayName: String get() = name
}
