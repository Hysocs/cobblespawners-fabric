package com.cobblespawners.utils;

import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import java.util.EnumSet;
import kotlin.math.cos;
import kotlin.math.sin;
import kotlin.math.sqrt;

class WanderBackToSpawnerGoal(
    private val entity: MobEntity,
    private val spawnerCenter: Vec3d,
    private val speed: Double,
    private val settings: WanderingSettings,
    private val tickDelay: Int = 10
) : Goal() {

    private val allowedRadius: Double = settings.wanderDistance.toDouble()
    private val allowedRadiusSquared = allowedRadius * allowedRadius
    private var targetPos: Vec3d? = null
    private var ticksSinceCheck = entity.random.nextInt(tickDelay)

    init {
        controls = EnumSet.of(Control.MOVE)
    }

    override fun canStart(): Boolean {
        if (!settings.enabled) return false

        if (--ticksSinceCheck > 0) return false
        ticksSinceCheck = tickDelay

        // Check if the entity is outside the allowed radius
        return entity.pos.squaredDistanceTo(spawnerCenter) > allowedRadiusSquared
    }

    override fun start() {
        // Always stop current navigation before starting a new one
        entity.navigation.stop()

        // Determine the target position
        targetPos = if (settings.wanderType.equals("RADIUS", ignoreCase = true)) {
            // This is the optimized function call
            findRandomTargetInRadius() ?: spawnerCenter // Fallback to center if no point is found
        } else {
            spawnerCenter
        }

        // --- OPTIMIZATION: Pathfinding is now only called ONCE here ---
        if (targetPos != null) {
            val path = entity.navigation.findPathTo(targetPos!!.x, targetPos!!.y, targetPos!!.z, 0)
            if (path != null) {
                entity.navigation.startMovingAlong(path, speed)
            }
        }
    }

    /**
     * OPTIMIZED: Finds a suitable random target position without performing expensive pathfinding.
     * It now focuses on finding a valid block on the ground within the radius.
     */
    private fun findRandomTargetInRadius(): Vec3d? {
        for (i in 0..9) { // Try up to 10 times to find a valid spot
            val randomAngle = entity.random.nextDouble() * 2.0 * Math.PI
            val randomDist = sqrt(entity.random.nextDouble()) * allowedRadius

            val offsetX = cos(randomAngle) * randomDist
            val offsetZ = sin(randomAngle) * randomDist

            val potentialX = spawnerCenter.x + offsetX
            val potentialZ = spawnerCenter.z + offsetZ

            // Find the highest solid block at the random coordinates
            val targetY = entity.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, potentialX.toInt(), potentialZ.toInt())
            val potentialTargetPos = BlockPos(potentialX.toInt(), targetY, potentialZ.toInt())

            // Check if this position is within the allowed radius
            if (Vec3d.ofCenter(potentialTargetPos).squaredDistanceTo(spawnerCenter) <= allowedRadiusSquared) {
                // --- CRITICAL CHANGE: We no longer call findPathTo() here. ---
                // We just return the valid position. The 'start' method will handle pathfinding.
                return Vec3d.ofBottomCenter(potentialTargetPos)
            }
        }
        // Return null if no suitable point was found after 10 attempts
        return null
    }

    override fun shouldContinue(): Boolean {
        // Stop if the goal is disabled or the entity's navigation is idle (reached destination or stuck)
        return settings.enabled && !entity.navigation.isIdle
    }

    override fun stop() {
        // Clear the target position and stop navigation to prevent conflicts
        targetPos = null
        entity.navigation.stop()
    }
}