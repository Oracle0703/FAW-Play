package com.shilapi.xcertplay.hud

/** One lane instruction selected by the phone, preserving its lane order and signed angles. */
data class ClusterLaneGuidance(val index: Int, val lanes: List<ClusterLane>)

/** Angles are degrees; the separately highlighted angle is not part of [angles]. */
data class ClusterLane(
    val index: Int,
    val status: ClusterLaneStatus,
    val angles: List<Int>,
    val highlightedAngle: Int?,
)

enum class ClusterLaneStatus { NOT_GOOD, GOOD, PREFERRED }
