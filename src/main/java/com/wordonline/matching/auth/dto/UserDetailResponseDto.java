package com.wordonline.matching.auth.dto;

import com.wordonline.matching.matching.dto.AccountMemberResponseDto;

public record UserDetailResponseDto(long id, String name, String email, String appearance) {

    public UserDetailResponseDto(long id, String name, String email) {
        this(id, name, email, null);
    }

    public UserDetailResponseDto(long id, AccountMemberResponseDto accountMemberResponseDto, String appearance) {
        this(id, accountMemberResponseDto.getName(), accountMemberResponseDto.getEmail(), appearance);
    }
}
