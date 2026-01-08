/*
 * Copyright © 2026 Sorekill
 *
 * Licensed under the Apache License, Version 2.0.
 * See https://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.sorekillrtp.rtp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.Set;

public final class SafetyChecks {

    // Blocks that are dangerous to stand on
    private static final Set<Material> UNSAFE_FLOOR = EnumSet.of(
            Material.LAVA,
            Material.MAGMA_BLOCK,
            Material.CACTUS,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW,
            Material.WITHER_ROSE,
            Material.POINTED_DRIPSTONE
    );

    // Blocks you never want in/at the player body
    private static final Set<Material> UNSAFE_BODY = EnumSet.of(
            Material.LAVA,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.CACTUS,
            Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW,
            Material.WITHER_ROSE,
            Material.COBWEB,
            Material.POINTED_DRIPSTONE
    );

    // Hazards we don’t want adjacent to the player (lava/fire etc.)
    private static final Set<Material> UNSAFE_NEAR = EnumSet.of(
            Material.LAVA,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.CACTUS,
            Material.MAGMA_BLOCK,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE,
            Material.POINTED_DRIPSTONE
    );

    public static boolean isSafeStandingStrict(Location feet) {
        World w = feet.getWorld();
        if (w == null) return false;

        int x = feet.getBlockX();
        int y = feet.getBlockY();
        int z = feet.getBlockZ();

        // Must be within build bounds (head block must exist too)
        if (y <= w.getMinHeight() + 1) return false;
        if (y >= w.getMaxHeight() - 2) return false;

        Block feetBlock = w.getBlockAt(x, y, z);
        Block headBlock = w.getBlockAt(x, y + 1, z);
        Block floorBlock = w.getBlockAt(x, y - 1, z);

        Material feetMat = feetBlock.getType();
        Material headMat = headBlock.getType();
        Material floorMat = floorBlock.getType();

        // Floor: must be solid and not harmful
        if (!floorMat.isSolid()) return false;
        if (UNSAFE_FLOOR.contains(floorMat)) return false;

        // Body space: STRICT "air pocket" requirement
        if (!isAirLike(feetMat)) return false;
        if (!isAirLike(headMat)) return false;

        // No liquids in body space
        if (feetBlock.isLiquid() || headBlock.isLiquid()) return false;

        // Don’t stand in/inside bad body blocks (extra guard)
        if (UNSAFE_BODY.contains(feetMat) || UNSAFE_BODY.contains(headMat)) return false;

        // Nearby hazard check (3x3 around feet & head, and 3x3 around floor)
        // This prevents “standing next to lava/fire/cactus” even if the exact floor is safe.
        if (hasNearbyHazards(w, x, y, z)) return false;

        return true;
    }

    private static boolean isAirLike(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    private static boolean hasNearbyHazards(World w, int x, int y, int z) {
        // Check a tight cube around the player:
        // - feet layer (y)
        // - head layer (y+1)
        // - floor layer (y-1)
        for (int dy : new int[]{-1, 0, 1}) {
            int yy = y + dy;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material t = w.getBlockAt(x + dx, yy, z + dz).getType();
                    if (UNSAFE_NEAR.contains(t)) return true;
                }
            }
        }
        return false;
    }

    private SafetyChecks() {}
}
