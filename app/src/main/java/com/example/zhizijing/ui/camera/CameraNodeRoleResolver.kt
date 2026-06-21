package com.example.zhizijing.ui.camera

import com.example.zhizijing.domain.model.DeviceRole

object CameraNodeRoleResolver {
    fun captureRole(localRole: DeviceRole): DeviceRole =
        when (localRole) {
            DeviceRole.FRONT_CAMERA,
            DeviceRole.SIDE_CAMERA,
            DeviceRole.BACKUP_CAMERA -> localRole
            DeviceRole.HOST,
            DeviceRole.UNKNOWN -> DeviceRole.FRONT_CAMERA
        }
}
