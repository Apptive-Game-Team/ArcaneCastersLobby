package com.wordonline.matching.playground

import com.wordonline.matching.auth.service.UserId
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/dev/playgrounds")
@ConditionalOnProperty(name = ["playground.enabled"], havingValue = "true", matchIfMissing = true)
@PreAuthorize("hasAnyAuthority('SUPER_ADMIN', 'WORDONLINE_ADMIN')")
class PlaygroundController(private val service: PlaygroundService) {
    @PostMapping
    suspend fun create(@UserId userId: Long?): PlaygroundInfo =
        service.create(userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED))
}
