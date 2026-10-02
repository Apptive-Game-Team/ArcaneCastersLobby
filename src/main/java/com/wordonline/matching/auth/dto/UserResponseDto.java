package com.wordonline.matching.auth.dto;

import com.wordonline.matching.auth.domain.User;

public record UserResponseDto(Long id, Long selectedDeckId, String appearance, int mmr) {

    public UserResponseDto(User user) {
        this(user.getId(), user.getSelectedDeckId(), user.getAppearance(),
                user.getMmr() == null ? 0 : Math.toIntExact(user.getMmr()));
    }
}
