package com.jamieswhiteshirt.rtree3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Same as {@link RStarSplitter}, but calculates volumes and surface areas as doubles, so they can't overflow.
 * See {@link GomlRStarSelector} for why.
 * <p>
 * Added by Get Off My Lawn.
 */
public final class GomlRStarSplitter implements Splitter {
    private static final List<ToIntFunction<Box>> SORT_KEYS = List.of(Box::x1, Box::x2, Box::y1, Box::y2, Box::z1, Box::z2);

    @Override
    public <T> Groups<T> split(List<T> items, int minSize, Function<T, Box> boxMapper) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Can't split an empty list");
        }

        // Sort by each box edge, and use the one where splits have the smallest sum of surface areas
        List<Groups<T>> bestPairs = null;
        double bestMarginSum = 0;

        for (var key : SORT_KEYS) {
            var sorted = new ArrayList<>(items);
            sorted.sort(Comparator.comparingInt(item -> key.applyAsInt(boxMapper.apply(item))));
            var pairs = RStarSplitter.createPairs(minSize, sorted, boxMapper);

            double marginSum = 0;
            for (var pair : pairs) {
                marginSum += GomlRStarSelector.surfaceArea(pair.getGroup1().getBox()) + GomlRStarSelector.surfaceArea(pair.getGroup2().getBox());
            }

            if (bestPairs == null || marginSum < bestMarginSum) {
                bestPairs = pairs;
                bestMarginSum = marginSum;
            }
        }

        // Then pick the split with minimal overlap, then minimal volume
        Groups<T> best = null;
        double bestOverlap = 0;
        double bestVolume = 0;

        for (var pair : bestPairs) {
            var box1 = pair.getGroup1().getBox();
            var box2 = pair.getGroup2().getBox();
            double overlap = GomlRStarSelector.intersectionVolume(box1, box2);
            double volume = GomlRStarSelector.volume(box1) + GomlRStarSelector.volume(box2);

            if (best == null || overlap < bestOverlap || (overlap == bestOverlap && volume < bestVolume)) {
                best = pair;
                bestOverlap = overlap;
                bestVolume = volume;
            }
        }

        return best;
    }
}
