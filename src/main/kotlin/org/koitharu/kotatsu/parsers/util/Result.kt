package org.koitharu.kotatsu.parsers.util

import kotlinx.coroutines.CancellationException
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

private const val CAUSE_CHAIN_LIMIT = 8

internal fun Throwable.isCloudFlareProtection(): Boolean {
	var error: Throwable? = this
	var depth = 0
	while (error != null && depth < CAUSE_CHAIN_LIMIT) {
		if (error.javaClass.simpleName.contains("CloudFlare", ignoreCase = true)) {
			return true
		}
		error = error.cause
		depth++
	}
	return false
}

internal fun Throwable.rethrowIfCritical(rethrowNotFound: Boolean = true) {
	if (this is CancellationException ||
		this is InterruptedException ||
		this is AuthRequiredException ||
		(rethrowNotFound && this is NotFoundException) ||
		isCloudFlareProtection()
	) {
		throw this
	}
}

@Suppress("WRONG_INVOCATION_KIND") // https://youtrack.jetbrains.com/issue/KT-70714
public inline fun <T, R> T.runCatchingCancellable(block: T.() -> R): Result<R> {
	contract {
		callsInPlace(block, InvocationKind.EXACTLY_ONCE)
	}
	return try {
		Result.success(block())
	} catch (e: InterruptedException) {
		throw e
	} catch (e: CancellationException) {
		throw e
	} catch (e: Throwable) {
		Result.failure(e)
	}
}

public inline fun <R, T : R> Result<T>.recoverCatchingCancellable(transform: (exception: Throwable) -> R): Result<R> {
	contract {
		callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
	}
	return when (val exception = exceptionOrNull()) {
		null -> this
		else -> runCatchingCancellable { transform(exception) }
	}
}

public inline fun <R : Any, T : R> Result<T>.recoverNotNull(transform: (exception: Throwable) -> R?): Result<R> {
	contract {
		callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
	}
	return when (val exception = exceptionOrNull()) {
		null -> this
		else -> transform(exception)?.let(Result.Companion::success) ?: this
	}
}
