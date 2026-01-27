package org.nxy.smartcoppergolem.util

import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.phys.Vec3
import org.nxy.smartcoppergolem.config.ConfigAccessor

object MobUtil {
    fun canUpdatePath(mob: PathfinderMob): Boolean {
        return mob.onGround() || mob.isInLiquid || mob.isPassenger
    }

    fun getCenterPosition(mob: PathfinderMob): Vec3 {
        return mob.position().add(0.0, mob.bbHeight * 0.5, 0.0)
    }

    fun addMobEyeHeightToFeetPosY(mob: PathfinderMob, feetPos: Vec3): Vec3 {
        return feetPos.add(0.0, mob.eyeHeight.toDouble(), 0.0)
    }

    fun addMobHalfBbHeightToFeetPosY(mob: PathfinderMob, feetPos: Vec3): Vec3 {
        return feetPos.add(0.0, mob.bbHeight * 0.5, 0.0)
    }

    fun getHorizontalSearchDistance(mob: PathfinderMob): Int {
        return if (mob.isPassenger) 1 else ConfigAccessor.pathfindingHorizontalSearchDistance
    }

    fun getVerticalSearchDistance(mob: PathfinderMob): Int {
        return if (mob.isPassenger) 1 else ConfigAccessor.pathfindingVerticalSearchDistance
    }
}
