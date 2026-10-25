package com.villagerdetails.rule.blockSitting;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * 「坐在楼梯 / 半砖上」的<b>座面计算</b>（位置全由方块自己的碰撞箱决定，没有任何偏移配置）。
 *
 * <p>两条规则：
 * <ol>
 *     <li>能坐的方块只有 {@code minecraft:stairs} 和 {@code minecraft:slabs} 两个标签里的
 *         —— 用标签而不是判断 {@code StairsBlock}/{@code SlabBlock} 类，模组加的同名方块也能坐；</li>
 *     <li><b>高度 = 「平的那一面」的顶面</b>，所以楼梯和半砖一样高（下半砖 / 下半楼梯都坐 y+0.5）：
 *         楼梯的 L 形碰撞箱里，「占满整个方块的那块」就是平的那一级，坐上去是坐在下面那一级台阶上，
 *         而不是悬在上面那一级的边缘。上半砖 / 上下颠倒的楼梯自然变成坐 y+1.0。</li>
 *     <li><b>楼梯再往「矮的那边」挪 2 像素</b>：人坐在方块中心时，坐着模型的背会正好贴着上面
 *         那一级的立面，往空的那侧挪一点点（{@link #LOW_SIDE_SHIFT}，1/8 格 = 2 个像素）就不贴了。</li>
 * </ol>
 *
 * <p>玩家自己相对座面低 0.6 格是原版 {@code positionRider} 用玩家的 vehicle 挂点算出来的
 * （坐姿模型正好贴住座面），这里不掺和。
 */
public final class BlockSittingSeat {

    private BlockSittingSeat() {
    }

    /**
     * 楼梯往「矮的那边」挪的距离：2 个像素 = 2/16 格。嫌多/嫌少改这一个数就行。
     */
    private static final double LOW_SIDE_SHIFT = 2.0D / 16.0D;

    /**
     * 玩家碰撞箱的半宽（玩家 0.6 × 1.8）
     */
    private static final double HALF_WIDTH = 0.3D;

    /**
     * 座面上方要求留空的最高点：坐姿玩家的头在座面上方 1.2 格左右，这里按「方块上方一整格再留一格」算
     */
    private static final double HEADROOM_TOP = 2.2D;

    /**
     * 这个方块能不能坐：<b>只有「下半砖」和「下半楼梯」</b>。
     *
     * <ul>
     *     <li>半砖：{@code SLAB_TYPE == BOTTOM}（上半砖 {@code TOP}、双半砖 {@code DOUBLE} 都不行 ——
     *         双半砖其实就是完整方块，没有可坐的矮台）；</li>
     *     <li>楼梯：{@code HALF == BOTTOM}（上下颠倒放的那种「上半楼梯」是天花板造型，不能坐）；</li>
     *     <li>方块得真的带这两个属性，不带的（某些模组变体）一律不许坐，宁可坐不上也不要把人摆歪。</li>
     * </ul>
     *
     * <p>判定用的是<b>标签 + 属性</b>，所以模组加的下半砖 / 下半楼梯也能坐。
     */
    public static boolean isSittingBlock(BlockState state) {
        if (state.is(BlockTags.SLABS)) {
            return state.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.BOTTOM;
        }
        if (state.is(BlockTags.STAIRS)) {
            return state.hasProperty(BlockStateProperties.HALF)
                    && state.getValue(BlockStateProperties.HALF) == Half.BOTTOM;
        }
        return false;
    }

    /**
     * 算出这个方块上的座位点（座位实体的坐标）；坐不了返回 {@code null}。
     *
     * <p>返回值的 {@code y} 就是座面高度，{@code x}/{@code z} 是座面中心（楼梯会往矮的那边偏 2 像素）。
     */
    public static Vec3 seatPosition(BlockGetter level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            // 个别模组楼梯只给视觉形状、碰撞形状是空的；这种也让它能坐
            shape = state.getShape(level, pos);
        }
        if (shape.isEmpty()) {
            return null;
        }
        List<AABB> boxes = shape.toAabbs();
        if (boxes.isEmpty()) {
            return null;
        }

        boolean stairs = state.is(BlockTags.STAIRS);
        // 楼梯取「占满整个方块」的那块（平的那一级），半砖就取最高那块
        AABB base = stairs ? flattest(boxes) : highest(boxes);
        if (base == null || base.maxY <= 0.0D) {
            return null;
        }

        double x = pos.getX() + (base.minX + base.maxX) / 2.0D;
        double y = pos.getY() + base.maxY;
        double z = pos.getZ() + (base.minZ + base.maxZ) / 2.0D;

        if (stairs) {
            Vec3 offset = lowSideOffset(base, boxes);
            x += offset.x;
            z += offset.z;
        }
        return new Vec3(x, y, z);
    }

    /**
     * 座位上方有没有空间（别把人塞进压在上面的方块里）。
     *
     * <p>只看<b>方块上方</b>那一格起：楼梯 / 半砖自己的碰撞箱就在座位下面，不能再算进去
     * （跪坐时人的身体是贴着座面、并往矮的那侧探出来的，方块本体那部分不算「堵」）。
     */
    public static boolean hasHeadroom(Level level, BlockPos pos) {
        AABB box = new AABB(
                pos.getX() + 0.5D - HALF_WIDTH, pos.getY() + 1.0D, pos.getZ() + 0.5D - HALF_WIDTH,
                pos.getX() + 0.5D + HALF_WIDTH, pos.getY() + HEADROOM_TOP, pos.getZ() + 0.5D + HALF_WIDTH);
        return level.noCollision(box);
    }

    /**
     * 最高的那一块（半砖用）
     */
    private static AABB highest(List<AABB> boxes) {
        AABB best = null;
        for (AABB box : boxes) {
            if (best == null || box.maxY > best.maxY) {
                best = box;
            }
        }
        return best;
    }

    /**
     * 平摊面积最大的那一块（楼梯用：L 形碰撞箱里占满整个方块的那块才是「平的那一级」）
     */
    private static AABB flattest(List<AABB> boxes) {
        AABB best = null;
        double bestArea = -1.0D;
        for (AABB box : boxes) {
            double area = (box.maxX - box.minX) * (box.maxZ - box.minZ);
            if (area > bestArea) {
                bestArea = area;
                best = box;
            }
        }
        return best;
    }

    /**
     * 从「台阶那部分」指向「平的那部分」的水平方向，取单位向量乘 {@link #LOW_SIDE_SHIFT}。
     *
     * <p>L 形楼梯的台阶在高的那侧，所以这个方向就是「矮的那边」。
     */
    private static Vec3 lowSideOffset(AABB base, List<AABB> boxes) {
        double baseX = (base.minX + base.maxX) / 2.0D;
        double baseZ = (base.minZ + base.maxZ) / 2.0D;
        double stepX = 0.0D;
        double stepZ = 0.0D;
        int stepCount = 0;
        for (AABB box : boxes) {
            if (box == base) continue;
            stepX += (box.minX + box.maxX) / 2.0D;
            stepZ += (box.minZ + box.maxZ) / 2.0D;
            stepCount++;
        }
        if (stepCount == 0) {
            return Vec3.ZERO;
        }
        double dx = baseX - stepX / stepCount;
        double dz = baseZ - stepZ / stepCount;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return Vec3.ZERO;
        }
        return new Vec3(dx / length * LOW_SIDE_SHIFT, 0.0D, dz / length * LOW_SIDE_SHIFT);
    }
}
