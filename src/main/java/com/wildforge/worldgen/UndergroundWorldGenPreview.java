package com.wildforge.worldgen;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;

/** Offline cave biome cross-sectional maps; the live test world is unaffected. */
public final class UndergroundWorldGenPreview {
    private static final Color[] COLORS = {
            new Color(117, 83, 54), new Color(70, 121, 108),
            new Color(72, 122, 176), new Color(202, 191, 145),
            new Color(77, 73, 68), new Color(226, 200, 144),
            new Color(137, 190, 213), new Color(151, 72, 65),
            new Color(137, 91, 204), new Color(85, 168, 112),
            new Color(214, 82, 41), new Color(55, 77, 130)
    };

    private UndergroundWorldGenPreview() { }

    public static void main(String[] args) throws IOException {
        Path output = args.length > 0 ? Path.of(args[0]) : Path.of("build/worldgen-preview");
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261028L;
        Files.createDirectories(output);
        WorldGenRegistry registry = WorldGenRegistry.loadBuiltIn();
        WorldGenerator world = registry.generator(seed);
        int size = 320;
        int step = 6;
        for (double fraction : new double[]{.18,.45,.72}) {
            BufferedImage map = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            EnumMap<UndergroundBiome, Integer> counts = new EnumMap<>(UndergroundBiome.class);
            int caveCells = 0;
            for (int pz = 0; pz < size; pz++) {
                for (int px = 0; px < size; px++) {
                    int x = (px - size / 2) * step;
                    int z = (pz - size / 2) * step;
                    WorldGenerator.Surface surface = world.surface(x, z);
                    int y = surface.surfaceY() - (int)Math.round(fraction
                            * (surface.surfaceY() - registry.terrain().minY()));
                    boolean carved = world.isCave(x, y, z, surface.surfaceY());
                    UndergroundBiome cave = carved ? world.caveBiomeAt(x, y, z) : null;
                    if (carved) caveCells++;
                    // Solid rock = charcoal; unclassified cave air = light neutral gray.
                    Color color = cave == null
                            ? (carved ? new Color(143, 148, 151) : new Color(27, 31, 38))
                            : COLORS[cave.ordinal()];
                    map.setRGB(px, pz, color.getRGB());
                    if (cave != null) counts.merge(cave, 1, Integer::sum);
                }
            }
            String layer = String.valueOf((int)Math.round(fraction * 100));
            Path png = output.resolve("underground_depth_" + layer + "_seed_" + seed + ".png");
            ImageIO.write(map, "png", png.toFile());
            System.out.println("Cave map: " + png + " (depth fraction " + fraction
                    + ", cave-air pixels " + caveCells + ")");
            counts.forEach((biome,count) -> System.out.println("  " + biome + ": " + count));
        }
    }
}
