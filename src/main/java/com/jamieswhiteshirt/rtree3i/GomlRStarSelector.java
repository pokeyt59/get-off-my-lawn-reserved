package com.jamieswhiteshirt.rtree3i;

import java.util.List;

/**
 * Same as {@link RStarSelector}, but calculates volumes as doubles.
 * <p>
 * rtree-3i-lite calculates them as ints, which overflow for boxes above ~2.1 billion blocks
 * (like nodes covering claims spread thousands of blocks apart). The tree then picks nodes based on wrapped around values,
 * so lookups end up testing a large part of all claims instead of just a few.
 * <p>
 * Added by Get Off My Lawn. It's in this package, as {@link Node} is package-private.
 */
public final class GomlRStarSelector implements Selector {
    @Override
    public <K, V> Node<K, V> select(Box box, List<Node<K, V>> nodes) {
        // Same order as RStarSelector: minimal overlap (only for leaves), then minimal volume increase, then minimal volume
        boolean leafNodes = nodes.get(0).isLeaf();

        Node<K, V> best = null;
        double bestOverlap = 0;
        double bestIncrease = 0;
        double bestVolume = 0;

        for (var node : nodes) {
            var nodeBox = node.getBox();
            var expanded = nodeBox.add(box);

            double overlap = 0;
            if (leafNodes) {
                for (var other : nodes) {
                    overlap += intersectionVolume(expanded, other.getBox());
                }
            }

            double volume = volume(expanded);
            double increase = volume - volume(nodeBox);

            if (best == null || overlap < bestOverlap
                    || (overlap == bestOverlap && (increase < bestIncrease || (increase == bestIncrease && volume < bestVolume)))) {
                best = node;
                bestOverlap = overlap;
                bestIncrease = increase;
                bestVolume = volume;
            }
        }

        return best;
    }

    static double volume(Box box) {
        return (double) (box.x2() - box.x1()) * (box.y2() - box.y1()) * (box.z2() - box.z1());
    }

    static double intersectionVolume(Box a, Box b) {
        if (!a.intersectsClosed(b)) {
            return 0;
        }

        return (double) (Math.min(a.x2(), b.x2()) - Math.max(a.x1(), b.x1()))
                * (Math.min(a.y2(), b.y2()) - Math.max(a.y1(), b.y1()))
                * (Math.min(a.z2(), b.z2()) - Math.max(a.z1(), b.z1()));
    }

    static double surfaceArea(Box box) {
        double dx = box.x2() - box.x1();
        double dy = box.y2() - box.y1();
        double dz = box.z2() - box.z1();
        return 2 * (dx * dy + dy * dz + dx * dz);
    }
}
