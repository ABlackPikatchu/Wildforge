package com.wildforge.worldgen;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;

/**
 * Offline worldgen visualization, not a replacement for the current test world.
 * Command: gradlew.bat worldgenPreview
 */
public final class WorldGenPreview {
    private WorldGenPreview() { }

    public static void main(String[] args) throws IOException {
        Path destination = args.length > 0 ? Path.of(args[0]) : Path.of("build/worldgen-preview");
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261028L;
        Files.createDirectories(destination);
        WorldGenerator generator = new WorldGenerator(seed, WorldGenConfigLoader.loadBuiltIn());
        int width = 512;
        int height = 512;
        int step = 4; // Each preview pixel samples 4 world blocks.
        BufferedImage biomeImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage heightImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        EnumMap<Biome, Integer> counts = new EnumMap<>(Biome.class);
        int yMin = generator.settings().maxY();
        int yMax = generator.settings().minY();
        for (int pz = 0; pz < height; pz++) {
            for (int px = 0; px < width; px++) {
                int x = (px - width / 2) * step;
                int z = (pz - height / 2) * step;
                WorldGenerator.Surface surface = generator.surface(x, z);
                int y = surface.surfaceY();
                yMin = Math.min(yMin, y);
                yMax = Math.max(yMax, y);
                counts.merge(surface.biome(), 1, Integer::sum);
                Color biome = biomeColor(surface.biome());
                // Small altitude variation gives contours without obscuring biome identity.
                double shade = Math.max(0.70, Math.min(1.14, 0.9 + (y - generator.settings().seaLevel()) / 320.0));
                biomeImage.setRGB(px, pz, shade(biome, shade).getRGB());
                int grayscale = (int) Math.round(255.0 * (y - generator.settings().minY())
                        / (generator.settings().maxY() - generator.settings().minY()));
                grayscale = Math.max(0, Math.min(255, grayscale));
                heightImage.setRGB(px, pz, new Color(grayscale, grayscale, grayscale).getRGB());
            }
        }
        Path biomePath = destination.resolve("biomes_seed_" + seed + ".png");
        Path heightPath = destination.resolve("height_seed_" + seed + ".png");
        ImageIO.write(biomeImage, "png", biomePath.toFile());
        ImageIO.write(heightImage, "png", heightPath.toFile());
        System.out.println("Worldgen preview for seed " + seed);
        try {
            System.out.println("Suggested safe spawn: " + SpawnFinder.find(generator, 768));
        } catch (IllegalStateException noSpawn) {
            System.out.println("No safe spawn found near world origin: " + noSpawn.getMessage());
        }
        System.out.println("Height range in preview: " + yMin + " to " + yMax);
        counts.forEach((biome, count) -> System.out.printf("  %-14s %5.1f%%%n",
                biome.name(), count * 100.0 / (width * height)));
        System.out.println("Wrote " + biomePath + " and " + heightPath);
    }

    private static Color shade(Color c, double amount) {
        return new Color(Math.min(255, (int) (c.getRed() * amount)),
                Math.min(255, (int) (c.getGreen() * amount)),
                Math.min(255, (int) (c.getBlue() * amount)));
    }

    private static Color biomeColor(Biome b) {
        return switch (b) {
            case DEEP_OCEAN -> new Color(30, 66, 124);
            case OCEAN -> new Color(55, 119, 176);
            case BEACH -> new Color(210, 195, 137);
            case PLAINS -> new Color(153, 188, 102);
            case FOREST -> new Color(68, 137, 71);
            case MAPLE_FOREST -> new Color(100, 154, 83);
            case TAIGA -> new Color(79, 120, 102);
            case DESERT -> new Color(213, 187, 104);
            case WETLAND -> new Color(96, 148, 110);
            case HIGHLANDS -> new Color(124, 151, 93);
            case MOUNTAINS -> new Color(128, 130, 135);
            case SNOWY_PEAKS -> new Color(225, 234, 239);
        };
    }
}
