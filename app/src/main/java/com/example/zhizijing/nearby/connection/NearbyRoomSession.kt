package com.example.zhizijing.nearby.connection

import android.content.Context

object NearbyRoomSession {
    private var manager: NearbyConnectionManager? = null

    fun manager(context: Context): NearbyConnectionManager {
        val existing = manager
        if (existing != null) return existing
        val created = NearbyConnectionManager(context.applicationContext)
        manager = created
        return created
    }

    fun stopIfCreated() {
        manager?.stop()
    }
}
