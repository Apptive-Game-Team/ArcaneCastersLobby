package com.wordonline.matching.auth.dto;

import com.wordonline.matching.auth.domain.User;

// mmr is the rating the profile screen shows. The client's GameUser has always carried an
// `mmr` int, so leaving it out of this response never failed - it left the profile showing
// the default 0, with nothing to say the value had not arrived.
public record UserResponseDto(Long id, Long selectedDeckId, Long mmr) {

    public UserResponseDto(User user) {
        this(user.getId(), user.getSelectedDeckId(), user.getMmr());
    }
}
