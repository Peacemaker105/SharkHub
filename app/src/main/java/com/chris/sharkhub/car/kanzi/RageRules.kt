package com.chris.sharkhub.car.kanzi

/**
 * The two hand-written inputs the Rage Mode placement needs (kzb_place.py's `--overrides` and
 * `--orphans` files). They are our inferences about BYD's scene, not BYD's data: the wheel instances
 * take their rest pose from the `_Space` helpers with the artist's 43° steering snapshot zeroed, and
 * the three driveline meshes no node references are authored Z-up with the nose at −Y (the older
 * export the Model3D nodes' +90° X rotations were made for), so Rx(−90°) puts them in the body frame.
 */
object RageRules {
    private const val WHEEL_PREFIX = "/RootNode/Vehicle/VehicleRoot/Vehicle/"

    val POSE_OVERRIDES: Map<String, PoseOverride> = mapOf(
        WHEEL_PREFIX + "Wheel_SF_FR" to PoseOverride(translation = doubleArrayOf(-0.975, 0.43, 1.822),
            why = "Space pose = #AnimRoot/Wheel_FR.VehicleNodePosHelper_Space; its RotateHelper_Space is (0, 43, 0) deg, the artist's steering snapshot (FL has 0) - rest pose uses no steer"),
        WHEEL_PREFIX + "Wheel_SF_LF" to PoseOverride(translation = doubleArrayOf(0.975, 0.43, 1.822),
            why = "Space pose = #AnimRoot/Wheel_FL.VehicleNodePosHelper_Space; the binding takes its rotation from Wheel_FR's helper (43 deg snapshot) - rest pose uses no steer"),
    )

    private val RX_MINUS_90 = doubleArrayOf(0.70710678, -0.70710678, 0.0, 0.0)

    val ORPHANS: Map<String, OrphanRule> = mapOf(
        "ElectricalMachinery" to OrphanRule(quaternion = RX_MINUS_90, material = "EF", textures = mapOf("Texture" to "EnergyFlowPipeline_T"),
            why = "front e-motor: no node references this mesh; it is authored Z-up with the nose at -Y (the older export the Model3D nodes' +90 deg X rotations were made for), so Rx(-90 deg) puts it in the body frame - inferred, not read from the scene"),
        "ElectricalMachinery_R" to OrphanRule(quaternion = RX_MINUS_90, material = "EF", textures = mapOf("Texture" to "EnergyFlowPipeline_T"),
            why = "rear e-motor: same inference as ElectricalMachinery"),
        "battery" to OrphanRule(quaternion = RX_MINUS_90, material = "EF", textures = mapOf("Texture" to "Cell&Tank_T"),
            why = "traction battery pack: same inference as ElectricalMachinery (lands under the floor between the axles)"),
        "Other_Ext_Body_CarPaint_Shell1" to OrphanRule(material = "Energy_Body_T",
            why = "small rear-left body piece, authored in the body frame (Y-up); no node references it - identity"),
        "Wheel_FL_Logo" to OrphanRule(translation = doubleArrayOf(0.975, 0.43, 1.822), why = "older wheel set (FL/FR/RL/RR names, UVW uvs), not instanced; placed at the Space-pose hub for reference"),
        "Wheel_FL_brake_disc" to OrphanRule(translation = doubleArrayOf(0.975, 0.43, 1.822), why = "older wheel set, not instanced; at the FL hub"),
        "Wheel_FR_Logo" to OrphanRule(translation = doubleArrayOf(-0.975, 0.43, 1.822), why = "older wheel set, not instanced; at the FR hub"),
        "Wheel_FR_brake_disc" to OrphanRule(translation = doubleArrayOf(-0.975, 0.43, 1.822), why = "older wheel set, not instanced; at the FR hub"),
        "Wheel_FR_Calipers" to OrphanRule(translation = doubleArrayOf(-0.975, 0.43, 1.822), why = "older wheel set, not instanced; at the FR hub"),
        "Wheel_RL_Logo" to OrphanRule(translation = doubleArrayOf(0.975, 0.43, -1.7), why = "older wheel set, not instanced; at the RL hub"),
        "Wheel_RL_brake_disc" to OrphanRule(translation = doubleArrayOf(0.975, 0.43, -1.7), why = "older wheel set, not instanced; at the RL hub"),
        "Wheel_RR_brake_disc" to OrphanRule(translation = doubleArrayOf(-0.975, 0.43, -1.7), why = "older wheel set, not instanced; at the RR hub"),
        "Wheel_RR_Calipers" to OrphanRule(translation = doubleArrayOf(-0.975, 0.43, -1.7), why = "older wheel set, not instanced; at the RR hub"),
    )

    /** The x-ray driveline: what the render page's `chassis` layer shows (kzb2glb.py's `--select` list). */
    val DRIVE_PARTS = listOf("Suspension", "Shock absorbers", "Engine", "ElectricalMachinery", "ElectricalMachinery_R", "battery", "cell", "Fuel tank", "EnergyFlowPipeline")

    const val ROOT = "/Prefabs/RootNode"
    const val RELATIVE_TO = "/RootNode/Vehicle/VehicleRoot"
    const val POSE = "Space"

    /** Rage corner → (hub pivot mesh, tyre mesh) names used to measure the placed car. */
    val WHEELS = mapOf("FL" to ("Wheel_LF_Hub" to "Wheel_LF_Tires"), "FR" to ("Wheel_RF_Hub" to "Wheel_RF_Tires"), "RL" to ("Wheel_LR_Hub" to "Wheel_LR_Tires"), "RR" to ("Wheel_RR_Hub" to "Wheel_RR_Tires"))
    /** PA corner → tyre mesh (the hub is its bounding-box centre). */
    val PA_WHEELS = mapOf("FL" to "wheel_01_LF", "FR" to "wheel_01_RF", "RL" to "wheel_01_LR", "RR" to "wheel_01_RR")
}
