package org.nxy.clevercoppergolem.utils

import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.phys.Vec3

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
}