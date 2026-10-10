package com.wordonline.matching.preview

import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/data/magic-previews")
class PreviewController(private val service: PreviewService) {
    @GetMapping
    suspend fun catalog(): ResponseEntity<PreviewCatalog> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore()).header("Retry-After", "5").body(service.catalog())

    @GetMapping("/{name}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun recording(@PathVariable name: String, @RequestParam serverId: Long,
                          @RequestParam revision: String, @RequestParam hash: String): ResponseEntity<ByteArray> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(hash)
            .body(service.recording(serverId, name, revision, hash))
}
