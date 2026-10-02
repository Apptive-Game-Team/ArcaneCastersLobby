package com.wordonline.matching.auth.dto;

import com.wordonline.matching.auth.domain.User;

public record UserResponseDto(Long id, Long selectedDeckId, String appearance) {

    public UserResponseDto(User user) {
        this(user.getId(), user.getSelectedDeckId(), user.getAppearance());
    }
}
