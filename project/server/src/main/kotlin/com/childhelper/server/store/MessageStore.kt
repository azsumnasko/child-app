package com.childhelper.server.store

import com.childhelper.core.common.signaling.SignalingMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class MessageStore {
    private val pendingMessages = ConcurrentHashMap<String, ConcurrentLinkedQueue<SignalingMessage>>()

    fun enqueue(toDeviceId: String, message: SignalingMessage) {
        pendingMessages.getOrPut(toDeviceId) { ConcurrentLinkedQueue() }.add(message)
    }

    fun dequeueAll(deviceId: String): List<SignalingMessage> {
        val queue = pendingMessages[deviceId] ?: return emptyList()
        val messages = mutableListOf<SignalingMessage>()
        while (true) {
            val msg = queue.poll() ?: break
            messages.add(msg)
        }
        pendingMessages.remove(deviceId)
        return messages
    }

    fun peekAll(deviceId: String): List<SignalingMessage> {
        return pendingMessages[deviceId]?.toList() ?: emptyList()
    }
}
