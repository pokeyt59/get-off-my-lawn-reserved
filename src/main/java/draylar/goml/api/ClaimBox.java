package draylar.goml.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

public record ClaimBox(com.jamieswhiteshirt.rtree3i.Box rtree3iBox, AABB minecraftBox, BlockPos origin, int radius, int radiusY, boolean noShift) {
    public static final ClaimBox EMPTY = new ClaimBox(BlockPos.ZERO, 0, 0, true);

    public ClaimBox(BlockPos origin, int radius, int radiusY) {
        this(
                origin,
                radius,
                radiusY,
                false
        );
    }

    public ClaimBox(BlockPos origin, int radius, int radiusY, boolean noShift) {
        this(noShift ? createBoxNoShift(origin, radius, radiusY) : createBox(origin, radius, radiusY), origin, radius, radiusY, noShift);
    }

    private ClaimBox(com.jamieswhiteshirt.rtree3i.Box box, BlockPos origin, int radius, int radiusY, boolean noShift) {
        // Exactly the protected blocks, so players next to the claim don't count as inside it (augments, force field, maps)
        this(box, new AABB(box.x1(), box.y1(), box.z1(), box.x2(), box.y2(), box.z2()), origin, radius, radiusY, noShift);
    }

    private static com.jamieswhiteshirt.rtree3i.Box createBox(BlockPos origin, int radius, int radiusY) {
        BlockPos lower = origin.offset(-radius, -radiusY, -radius);
        BlockPos upper = origin.offset(radius + 1, radiusY + 1, radius + 1);
        return com.jamieswhiteshirt.rtree3i.Box.create(lower.getX(), lower.getY(), lower.getZ(), upper.getX(), upper.getY(), upper.getZ());
    }

    private static com.jamieswhiteshirt.rtree3i.Box createBoxNoShift(BlockPos origin, int radius, int radiusY) {
        BlockPos lower = origin.offset(-radius, -radiusY, -radius);
        BlockPos upper = origin.offset(radius, radiusY, radius);
        return com.jamieswhiteshirt.rtree3i.Box.create(lower.getX(), lower.getY(), lower.getZ(), upper.getX(), upper.getY(), upper.getZ());
    }

    public com.jamieswhiteshirt.rtree3i.Box toBox() {
        return this.rtree3iBox;
    }

    public BlockPos getOrigin() {
        return this.origin;
    }

    public int getRadius() {
        return this.radius;
    }

    public int getX() {
        return this.radius;
    }

    public int getY() {
        return this.radiusY;
    }

    public int getZ() {
        return this.radius;
    }

    public static ClaimBox readData(ValueInput view, int i) {
        BlockPos originPos = BlockPos.of(view.getLongOr("OriginPos", 0));
        var radius = view.getIntOr("Radius", 0);
        var height = view.getIntOr("Height", radius);
        if (radius > 0 && height > 0) {
            return new ClaimBox(originPos, radius, height, view.getBooleanOr("NoShift", false));
        }
        return EMPTY;
    }

    public void writeData(ValueOutput view) {
        view.putLong("OriginPos", this.getOrigin().asLong());
        view.putInt("Radius", this.getRadius());
        view.putInt("Height", this.getY());
        view.putBoolean("NoShift", this.noShift());
    }
}
