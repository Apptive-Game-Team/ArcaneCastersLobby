package com.wordonline.matching.friend.service

import com.wordonline.matching.friend.domain.FriendEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class FriendEventService {

    private val userEventFlows = ConcurrentHashMap<Long, MutableSharedFlow<FriendEvent>>()

    fun publish(userId: Long, event: FriendEvent) {
        val flow = userEventFlows[userId] ?: return
        flow.tryEmit(event)
    }

    fun events(userId: Long): Flow<ServerSentEvent<FriendEvent>> {
        val userFlow = userEventFlows.computeIfAbsent(userId) {
            MutableSharedFlow(extraBufferCapacity = 64)
        }

        val eventStream = userFlow.map { event ->
            ServerSentEvent.builder(event)
                .event("friend-event")
                .build()
        }

        return merge(heartbeats(), eventStream)
    }

    private fun heartbeats(): Flow<ServerSentEvent<FriendEvent>> = flow {
        val heartbeat = ServerSentEvent.builder<FriendEvent>().comment("keep-alive").build()
        while (true) {
            emit(heartbeat)
            delay(15_000)
        }
    }
}
