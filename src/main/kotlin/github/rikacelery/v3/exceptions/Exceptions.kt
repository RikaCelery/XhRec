package github.rikacelery.v3.exceptions

class RenameException(val newName: String) : Exception("Room renamed to $newName")
class DeletedException : Exception("Room deleted")

/**
 * The platform answered 404 for a broadcast that is neither renamed nor deleted: the model is
 * simply not there.
 *
 * A domain answer, not a transport failure. Retrying it cannot help — the same URL on another host
 * answers the same 404 — but it used to be reported as a retryable error, so adding a typo'd model
 * burned nine requests and eight seconds of backoff before the route returned its 500.
 */
class ModelNotFoundException(reason: String) : Exception("request api failed: $reason")
