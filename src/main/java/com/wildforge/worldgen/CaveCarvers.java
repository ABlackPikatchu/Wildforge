package com.wildforge.worldgen;

import com.wildforge.world.BlockType;
import com.wildforge.world.TerrainMesh;
import java.util.Arrays;
import java.util.Random;

/**
 * Seeded, chunk-order-independent geological tunnels. Noise caves are generated
 * separately; this pass connects some chambers without exposing entire roofs.
 * All positions are world coordinates, including branch and entrance anchors.
 */
public final class CaveCarvers {
    private static final int REGION = 112;
    private static final int CHUNK = 16;
    private static final int HEIGHT = TerrainMesh.WORLD_HEIGHT;
    private static final int MIN_ROOF = 13;
    private final long seed;

    private enum Shape { TUNNEL, KARST, LAVA_TUBE, FRACTURE }

    public CaveCarvers(long seed) { this.seed = seed; }

    public void carve(byte[] blocks, int chunkX, int chunkZ, WorldGenerator generator) {
        if (blocks.length != CHUNK * CHUNK * HEIGHT)
            throw new IllegalArgumentException("Carver expected one 16x16x" + HEIGHT + " column");
        int minX = chunkX * CHUNK, minZ = chunkZ * CHUNK;
        int[] surfaceCache = new int[CHUNK * CHUNK];
        Arrays.fill(surfaceCache, Integer.MIN_VALUE);
        // A route is at most 140 blocks from its regional anchor. Three-ring
        // sampling safely includes branches crossing this chunk's boundaries.
        int rx0 = Math.floorDiv(minX, REGION), rz0 = Math.floorDiv(minZ, REGION);
        for (int rx = rx0 - 2; rx <= rx0 + 2; rx++) {
            for (int rz = rz0 - 2; rz <= rz0 + 2; rz++) {
                Random random = new Random(mix(seed ^ ((long) rx * 0x9e3779b97f4a7c15L)
                        ^ ((long) rz * 0xc2b2ae3d27d4eb4fL)));
                if (random.nextDouble() > 0.46) continue;
                double x = rx * REGION + random.nextInt(REGION);
                double z = rz * REGION + random.nextInt(REGION);
                WorldGenerator.Surface source = generator.surface((int) x, (int) z);
                Geology geology = source.geology();
                Shape kind = switch (geology) {
                    case LIMESTONE -> random.nextDouble() < .72 ? Shape.KARST : Shape.TUNNEL;
                    case VOLCANIC, BASALT -> random.nextDouble() < .65 ? Shape.LAVA_TUBE : Shape.TUNNEL;
                    case GRANITE, SHALE -> random.nextDouble() < .23 ? Shape.FRACTURE : Shape.TUNNEL;
                    default -> Shape.TUNNEL;
                };
                // Surface fractures are uncommon. Ordinary routes keep a substantial
                // intact rock roof, even at steep hillsides and mountain slopes.
                boolean entrance = source.waterBody() == WorldGenerator.WaterBody.LAND
                        && source.surfaceY() > generator.settings().seaLevel() + 8
                        && random.nextDouble() < .055;
                double y = Math.max(generator.settings().minY() + 24,
                        source.surfaceY() - (kind == Shape.FRACTURE ? 34 : 28)
                                - random.nextInt(kind == Shape.LAVA_TUBE ? 68 : 58));
                y = Math.min(y, source.surfaceY() - MIN_ROOF - 5);
                double angle = random.nextDouble() * Math.PI * 2;
                double length = switch (kind) {
                    case KARST -> 48 + random.nextInt(54);
                    case LAVA_TUBE -> 54 + random.nextInt(60);
                    case FRACTURE -> 42 + random.nextInt(53);
                    default -> 35 + random.nextInt(58);
                };
                double curve = (random.nextDouble() - .5) * .65;
                double incline = (random.nextDouble() - .5)
                        * (kind == Shape.LAVA_TUBE ? .055 : .15);
                int steps = (int) (length / 3);
                for (int i = 0; i <= steps; i++) {
                    double t = i / (double) steps;
                    double heading = angle + curve * t;
                    double cx = x + Math.cos(heading) * t * length;
                    double cz = z + Math.sin(heading) * t * length;
                    double cy = y + incline * t * length + Math.sin(t * Math.PI * 2) * 1.4;
                    double radius = switch (kind) {
                        case KARST -> 2.2 + 1.5 * Math.sin(t * Math.PI);
                        case LAVA_TUBE -> 2.5 + .7 * Math.sin(t * Math.PI);
                        case FRACTURE -> 1.7 + .5 * Math.sin(t * Math.PI);
                        default -> 1.7 + 1.1 * Math.sin(t * Math.PI);
                    };
                    double verticalRadius = switch (kind) {
                        case FRACTURE -> 6.5 + radius * 1.35;
                        case LAVA_TUBE -> radius * .82;
                        case KARST -> radius * (1.05 + .20 * Math.sin(t * 9));
                        default -> radius * (0.85 + .20 * Math.sin(t * 7));
                    };
                    carveEllipsoid(blocks, minX, minZ, surfaceCache, generator,
                            cx, cy, cz, radius, verticalRadius, false);
                }
                // Short connected side tunnels originate on the main path, rather
                // than placing independent disconnected ellipsoids in nearby rock.
                if (kind != Shape.FRACTURE && random.nextDouble() < .52) {
                    double split = .34 + random.nextDouble() * .35;
                    double sx = x + Math.cos(angle + curve * split) * split * length;
                    double sz = z + Math.sin(angle + curve * split) * split * length;
                    double sy = y + incline * split * length + Math.sin(split * Math.PI * 2) * 1.4;
                    double branchAngle = angle + (random.nextBoolean() ? 1 : -1) * (0.65 + random.nextDouble() * .65);
                    double branchLength = 15 + random.nextInt(24);
                    for (int k = 0; k <= (int) branchLength / 3; k++) {
                        double d = k * 3;
                        carveEllipsoid(blocks, minX, minZ, surfaceCache, generator,
                                sx + Math.cos(branchAngle) * d,
                                sy + Math.sin(d / 12.0) * 1.5,
                                sz + Math.sin(branchAngle) * d,
                                1.8, 2.1, false);
                    }
                }
                // Rare deliberate daylight entrances join the START of a tunnel.
                // Never pierce a random cavern roof simply because a ravine passes.
                if (entrance) {
                    int top = source.surfaceY();
                    int segments = Math.max(8, (int) ((top - y) / 2.6));
                    for (int k = 0; k <= segments; k++) {
                        double t = k / (double) segments;
                        carveEllipsoid(blocks, minX, minZ, surfaceCache, generator,
                                x - Math.cos(angle) * (1 - t) * 8,
                                top + 1 - (top + 1 - y) * t,
                                z - Math.sin(angle) * (1 - t) * 8,
                                1.8 + .35 * t, 2.4, true);
                    }
                }
            }
        }
    }

    private static void carveEllipsoid(byte[] blocks, int minX, int minZ, int[] surfaceCache,
                                      WorldGenerator gen, double cx, double cy, double cz,
                                      double radius, double radiusY, boolean entrance) {
        int x0 = Math.max(minX, (int) Math.floor(cx - radius));
        int x1 = Math.min(minX + 15, (int) Math.ceil(cx + radius));
        int z0 = Math.max(minZ, (int) Math.floor(cz - radius));
        int z1 = Math.min(minZ + 15, (int) Math.ceil(cz + radius));
        if (x0 > x1 || z0 > z1) return;
        int y0 = Math.max(5, (int) Math.floor(cy - radiusY));
        int y1 = Math.min(HEIGHT - 2, (int) Math.ceil(cy + radiusY));
        for (int wx = x0; wx <= x1; wx++) for (int wz = z0; wz <= z1; wz++) {
            double dx = (wx + .5 - cx) / radius, dz = (wz + .5 - cz) / radius;
            double h = dx * dx + dz * dz;
            if (h >= 1) continue;
            int column = (wx - minX) * CHUNK + (wz - minZ);
            int surface = surfaceCache[column];
            if (surface == Integer.MIN_VALUE)
                surfaceCache[column] = surface = gen.surface(wx, wz).surfaceY();
            int ceiling = entrance ? surface + 1 : surface - MIN_ROOF;
            for (int wy = y0; wy <= Math.min(y1, ceiling); wy++) {
                double dy = (wy + .5 - cy) / radiusY;
                if (h + dy * dy >= 1) continue;
                int idx = column * HEIGHT + wy;
                if (blocks[idx] != BlockType.BEDROCK.getId() && blocks[idx] != BlockType.AIR.getId())
                    blocks[idx] = BlockType.AIR.getId();
            }
        }
    }

    private static long mix(long n) {
        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
        n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
        return n ^ (n >>> 31);
    }
}
