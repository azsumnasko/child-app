package com.childhelper.core.common.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Represents a privacy-safe operation result that explicitly models failure.
 *
 * Unlike Kotlin's [Result], this sealed class does not use exceptions for control
 * flow and provides strongly-typed error information suitable for UI presentation.
 *
 * @param T The type of the successful result value.
 */
sealed class SafeResult<out T> {

    /**
     * Successful operation with a computed value.
     *
     * @property data The result value.
     */
    data class Success<T>(val data: T) : SafeResult<T>()

    /**
     * Failed operation with a structured error description.
     *
     * @property error User-presentable error message (already localized when possible).
     * @property code Optional machine-readable error code for telemetry.
     */
    data class Failure(
        val error: String,
        val code: ErrorCode = ErrorCode.UNKNOWN
    ) : SafeResult<Nothing>()

    /**
     * Returns `true` if this result is [Success].
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Returns `true` if this result is [Failure].
     */
    val isFailure: Boolean get() = this is Failure

    /**
     * Returns the encapsulated value if [Success], or `null` if [Failure].
     */
    fun getOrNull(): T? = (this as? Success)?.data

    /**
     * Returns the encapsulated value if [Success], or [default] if [Failure].
     */
    fun getOrDefault(default: @UnsafeVariance T): T = (this as? Success)?.data ?: default

    /**
     * Returns the encapsulated value if [Success], or throws the exception
     * produced by [onFailure] if [Failure].
     */
    fun getOrThrow(onFailure: (Failure) -> Throwable = { Exception(it.error) }): T =
        when (this) {
            is Success -> data
            is Failure -> throw onFailure(this)
        }

    /**
     * Maps a [Success] value through [transform], or passes [Failure] through unchanged.
     */
    inline fun <R> map(transform: (T) -> R): SafeResult<R> =
        when (this) {
            is Success -> Success(transform(data))
            is Failure -> this
        }

    /**
     * Maps a [Failure] through [transform], or passes [Success] through unchanged.
     */
    inline fun mapFailure(transform: (Failure) -> Failure): SafeResult<T> =
        when (this) {
            is Success -> this
            is Failure -> transform(this)
        }

    /**
     * Flat-maps a [Success] value through [transform], or passes [Failure] through unchanged.
     */
    inline fun <R> flatMap(transform: (T) -> SafeResult<R>): SafeResult<R> =
        when (this) {
            is Success -> transform(data)
            is Failure -> this
        }

    /**
     * Performs [action] only if this result is [Success].
     */
    inline fun onSuccess(action: (T) -> Unit): SafeResult<T> =
        apply { if (this is Success) action(data) }

    /**
     * Performs [action] only if this result is [Failure].
     */
    inline fun onFailure(action: (Failure) -> Unit): SafeResult<T> =
        apply { if (this is Failure) action(this) }
}

/**
 * Machine-readable error codes for categorizing failures across the app.
 */
enum class ErrorCode {
    /** Unknown or uncategorized error. */
    UNKNOWN,

    /** Keystore operation failed (key not found, corruption, etc.). */
    KEYSTORE_ERROR,

    /** Encryption or decryption operation failed. */
    ENCRYPTION_ERROR,

    /** Network request failed (timeout, no connectivity, etc.). */
    NETWORK_ERROR,

    /** Server returned a non-2xx response. */
    SERVER_ERROR,

    /** Pairing code invalid, expired, or session not found. */
    PAIRING_ERROR,

    /** WebRTC signaling or peer connection failure. */
    CALL_ERROR,

    /** ML model inference failed or model not loaded. */
    DETECTION_ERROR,

    /** Required permission not granted. */
    PERMISSION_DENIED,

    /** Invalid argument or malformed data. */
    INVALID_ARGUMENT
}

/**
 * Wraps a suspending block in a [SafeResult], catching all exceptions except
 * [CancellationException] (which is rethrown to respect coroutine cancellation).
 *
 * Usage:
 * ```
 * val result = safeCall { repository.fetchData() }
 * result.onSuccess { data -> ... }.onFailure { error -> ... }
 * ```
 *
 * @param errorCode The [ErrorCode] to use if an exception is caught.
 * @param block The suspending operation to execute.
 * @return [SafeResult.Success] with the block's return value, or [SafeResult.Failure].
 */
inline fun <T> safeCall(
    errorCode: ErrorCode = ErrorCode.UNKNOWN,
    block: () -> T
): SafeResult<T> =
    try {
        SafeResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SafeResult.Failure(
            error = e.message ?: "An unexpected error occurred",
            code = errorCode
        )
    }

/**
 * Wraps a suspending block in a [SafeResult], catching all exceptions except
 * [CancellationException].
 */
suspend inline fun <T> safeCallAsync(
    errorCode: ErrorCode = ErrorCode.UNKNOWN,
    crossinline block: suspend () -> T
): SafeResult<T> =
    try {
        SafeResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SafeResult.Failure(
            error = e.message ?: "An unexpected error occurred",
            code = errorCode
        )
    }
