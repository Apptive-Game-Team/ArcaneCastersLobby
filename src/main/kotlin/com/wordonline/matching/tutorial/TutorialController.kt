package com.wordonline.matching.tutorial

import com.wordonline.matching.auth.service.UserId
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/tutorial/battles")
@PreAuthorize("isAuthenticated()")
class TutorialController(private val service: TutorialService) {
    @PostMapping
    suspend fun create(@UserId ownerId: Long?, @RequestBody request: CreateTutorial): TutorialReady =
        service.create(ownerId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED), request)
}
