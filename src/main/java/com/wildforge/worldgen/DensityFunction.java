package com.wildforge.worldgen;

/** Immutable, world-coordinate density sampler. Positive values represent solid matter. */
@FunctionalInterface
public interface DensityFunction {
    double sample(int x, int y, int z);

    default DensityFunction add(DensityFunction other) {
        return (x,y,z) -> sample(x,y,z) + other.sample(x,y,z);
    }
    default DensityFunction multiply(DensityFunction other) {
        return (x,y,z) -> sample(x,y,z) * other.sample(x,y,z);
    }
    static DensityFunction constant(double value) { return (x,y,z) -> value; }
}
