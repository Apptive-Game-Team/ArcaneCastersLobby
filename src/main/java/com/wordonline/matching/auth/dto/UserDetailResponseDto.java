package com.wordonline.matching.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wordonline.matching.matching.dto.AccountMemberResponseDto;

public record UserDetailResponseDto(long id, String name, String email, String appearance,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long matchMmr) {

    public UserDetailResponseDto(long id, String name, String email) {
        this(id, name, email, null, null);
    }

    public UserDetailResponseDto(long id, String name, String email, String appearance) {
        this(id, name, email, appearance, null);
    }

    public UserDetailResponseDto(long id, AccountMemberResponseDto account, String appearance) {
        this(id, account, appearance, null);
    }

    public UserDetailResponseDto(long id, AccountMemberResponseDto account, String appearance, Long matchMmr) {
        this(id, account.getName(), account.getEmail(), appearance, matchMmr);
    }
}
