// Re-exports from core:common so existing imports in :core:network continue to work.
// All signaling message types now live in com.childhelper.core.common.signaling
// to be shareable with the JVM backend server.
package com.childhelper.core.network.signaling

typealias SignalingMessage = com.childhelper.core.common.signaling.SignalingMessage
typealias SdpMessage = com.childhelper.core.common.signaling.SdpMessage
typealias SdpType = com.childhelper.core.common.signaling.SdpType
typealias IceMessage = com.childhelper.core.common.signaling.IceMessage
typealias HangUpMessage = com.childhelper.core.common.signaling.HangUpMessage
typealias HangUpReason = com.childhelper.core.common.signaling.HangUpReason
typealias PingMessage = com.childhelper.core.common.signaling.PingMessage
typealias PongMessage = com.childhelper.core.common.signaling.PongMessage
