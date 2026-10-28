package com.wildforge.worldgen;

/** Deterministic smooth value noise; no global RNG or mutable sampling state. */
public final class SeededNoise {
    private final long seed;

    public SeededNoise(long seed) {
        this.seed = seed;
    }

    private static long avalanche(long x) {
        x ^= x >>> 30;
        x *= 0xbf58476d1ce4e5b9L;
        x ^= x >>> 27;
        x *= 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    public long hash(int x, int y, int z, long salt) {
        return avalanche(seed ^ salt
                ^ avalanche((long) x * 0x9e3779b97f4a7c15L)
                ^ avalanche((long) y * 0xc2b2ae3d27d4eb4fL)
                ^ avalanche((long) z * 0x165667b19e3779f9L));
    }

    public double unit(int x, int y, int z, long salt) {
        return (hash(x, y, z, salt) >>> 11) * 0x1.0p-53;
    }

    private double signed(int x, int y, int z, long salt) {
        return unit(x, y, z, salt) * 2.0 - 1.0;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public double noise2(double x, double z, long salt) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        double tx = smooth(x - ix);
        double tz = smooth(z - iz);
        double a = mix(signed(ix, 0, iz, salt), signed(ix + 1, 0, iz, salt), tx);
        double b = mix(signed(ix, 0, iz + 1, salt), signed(ix + 1, 0, iz + 1, salt), tx);
        return mix(a, b, tz);
    }

    public double noise3(double x, double y, double z, long salt) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        double tx = smooth(x - ix), ty = smooth(y - iy), tz = smooth(z - iz);
        double a = mix(signed(ix, iy, iz, salt), signed(ix + 1, iy, iz, salt), tx);
        double b = mix(signed(ix, iy, iz + 1, salt), signed(ix + 1, iy, iz + 1, salt), tx);
        double c = mix(signed(ix, iy + 1, iz, salt), signed(ix + 1, iy + 1, iz, salt), tx);
        double d = mix(signed(ix, iy + 1, iz + 1, salt), signed(ix + 1, iy + 1, iz + 1, salt), tx);
        return mix(mix(a, b, tz), mix(c, d, tz), ty);
    }

    public double fbm2(double x, double z, int octaves, long salt) {
        double amp = 1.0, total = 0.0, sum = 0.0;
        for (int i = 0; i < octaves; i++) {
            sum += noise2(x, z, salt + i * 0x6a09e667f3bcc909L) * amp;
            total += amp;
            x *= 2.0; z *= 2.0; amp *= 0.5;
        }
        return sum / total;
    }
}
