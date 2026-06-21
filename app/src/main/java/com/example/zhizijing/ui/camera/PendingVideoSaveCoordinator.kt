package com.example.zhizijing.ui.camera

class PendingVideoSaveCoordinator {
    private var pendingTriggeredByRemote: Boolean? = null

    fun request(triggeredByRemote: Boolean): Boolean {
        if (pendingTriggeredByRemote != null) return false
        pendingTriggeredByRemote = triggeredByRemote
        return true
    }

    fun consumeTriggeredByRemote(): Boolean? {
        val value = pendingTriggeredByRemote ?: return null
        pendingTriggeredByRemote = null
        return value
    }

    fun isPending(): Boolean =
        pendingTriggeredByRemote != null
}
