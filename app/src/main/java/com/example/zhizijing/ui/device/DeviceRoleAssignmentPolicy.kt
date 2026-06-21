package com.example.zhizijing.ui.device

import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.nearby.connection.NearbyEndpoint

data class DeviceRoleAssignment(
    val endpointId: String,
    val deviceName: String,
    val role: DeviceRole,
)

// 机位冲突与候选选择
object DeviceRoleAssignmentPolicy {
    fun selfAssignmentError(
        endpoints: List<NearbyEndpoint>,
        targetRole: DeviceRole,
    ): String? {
        val conflict = endpoints.firstOrNull { endpoint ->
            endpoint.isOnline &&
                endpoint.role == targetRole &&
                targetRole in PRIMARY_CAMERA_ROLES
        } ?: return null
        return "另一台手机 ${conflict.deviceName} 已经是${targetRole.displayText()}，本机不能重复选择该机位。"
    }

    fun selectEndpointForRole(
        endpoints: List<NearbyEndpoint>,
        targetRole: DeviceRole,
    ): NearbyEndpoint? {
        val online = endpoints.filter { it.isOnline }
        if (online.isEmpty()) return null
        online.firstOrNull { it.role == targetRole }?.let { return it }
        online.firstOrNull { it.role == DeviceRole.UNKNOWN || it.role == DeviceRole.BACKUP_CAMERA }?.let {
            return it
        }
        return online.firstOrNull { endpoint ->
            when (targetRole) {
                DeviceRole.FRONT_CAMERA -> endpoint.role != DeviceRole.SIDE_CAMERA
                DeviceRole.SIDE_CAMERA -> endpoint.role != DeviceRole.FRONT_CAMERA
                else -> true
            }
        } ?: online.first()
    }

    fun autoAssignPrimaryRoles(endpoints: List<NearbyEndpoint>): List<DeviceRoleAssignment> {
        val online = endpoints.filter { it.isOnline }
        if (online.isEmpty()) return emptyList()

        val assignments = mutableListOf<DeviceRoleAssignment>()
        val usedEndpointIds = mutableSetOf<String>()

        val front = online.firstOrNull { it.role == DeviceRole.FRONT_CAMERA } ?: online.first()
        assignments += front.toAssignment(DeviceRole.FRONT_CAMERA)
        usedEndpointIds += front.endpointId

        val side = online.firstOrNull {
            it.role == DeviceRole.SIDE_CAMERA && it.endpointId !in usedEndpointIds
        } ?: online.firstOrNull {
            it.endpointId !in usedEndpointIds &&
                (it.role == DeviceRole.UNKNOWN || it.role == DeviceRole.BACKUP_CAMERA)
        } ?: online.firstOrNull {
            it.endpointId !in usedEndpointIds
        }
        if (side != null) {
            assignments += side.toAssignment(DeviceRole.SIDE_CAMERA)
        }

        return assignments
    }

    fun assignmentSummary(assignments: List<DeviceRoleAssignment>): String {
        if (assignments.isEmpty()) return "当前没有在线设备可分配"
        return assignments.joinToString(separator = "；") { assignment ->
            "${assignment.deviceName} -> ${assignment.role.displayText()}"
        }
    }

    private fun DeviceRole.displayText(): String =
        when (this) {
            DeviceRole.HOST -> "主控端"
            DeviceRole.FRONT_CAMERA -> "正面机位"
            DeviceRole.SIDE_CAMERA -> "侧面机位"
            DeviceRole.BACKUP_CAMERA -> "备用机位"
            DeviceRole.UNKNOWN -> "未分配"
        }

    private val PRIMARY_CAMERA_ROLES = setOf(DeviceRole.FRONT_CAMERA, DeviceRole.SIDE_CAMERA)

    private fun NearbyEndpoint.toAssignment(role: DeviceRole): DeviceRoleAssignment =
        DeviceRoleAssignment(
            endpointId = endpointId,
            deviceName = deviceName,
            role = role,
        )
}
