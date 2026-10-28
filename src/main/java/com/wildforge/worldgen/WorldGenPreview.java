package com.wildforge.worldgen;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;

/** Offline preview using the unified generated worldgen registry. */
public final class WorldGenPreview {
    private WorldGenPreview() { }

    public static void main(String[] args) throws IOException {
        Path destination = args.length > 0 ? Path.of(args[0]) : Path.of("build/worldgen-preview");
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261028L;
        Files.createDirectories(destination);
        WorldGenRegistry worldgen = WorldGenRegistry.loadBuiltIn();
        WorldGenerator generator = worldgen.generator(seed);
        int width = 512;
        int height = 512;
        int step = 4;
        BufferedImage biomes = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage elevations = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage rocks = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage continents = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage erosion = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage ridges = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage relief = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        EnumMap<Biome, Integer> counts = new EnumMap<>(Biome.class);
        EnumMap<Geology, Integer> geologicalCounts = new EnumMap<>(Geology.class);
        int waterSamples = 0;
        int minY = generator.settings().maxY();
        int maxY = generator.settings().minY();
        for (int pz = 0; pz < height; pz++) {
            for (int px = 0; px < width; px++) {
                int x = (px - width / 2) * step;
                int z = (pz - height / 2) * step;
                WorldGenerator.Surface surface = generator.surface(x, z);
                WorldGenerator.Geography g = generator.geographyAt(x,z);
                continents.setRGB(px,pz,gradient(g.continentalness()).getRGB());
                erosion.setRGB(px,pz,gradient(g.erosion()).getRGB());
                ridges.setRGB(px,pz,gradient(g.ridges()*2.0-1.0).getRGB());
                relief.setRGB(px,pz,reliefColor(g).getRGB());
                int y = surface.surfaceY();
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                counts.merge(surface.biome(), 1, Integer::sum);
                geologicalCounts.merge(surface.geology(), 1, Integer::sum);

                Color color = new Color(generator.biomes().get(surface.biome()).mapColor());
                if (surface.waterBody() == WorldGenerator.WaterBody.OCEAN) {
                    waterSamples++;
                    color = surface.waterDepth() > 18
                            ? new Color(32, 70, 128) : new Color(58, 118, 168);
                } else if (surface.waterBody() == WorldGenerator.WaterBody.RIVER) {
                    color = new Color(75, 138, 170);
                }
                double shade = Math.max(.72, Math.min(1.13,
                        .91 + (y - generator.settings().seaLevel()) / 350.0));
                biomes.setRGB(px, pz, shade(color, shade).getRGB());
                int gray = (int)Math.round(255.0 * (y - generator.settings().minY())
                        / (generator.settings().maxY() - generator.settings().minY()));
                gray = Math.max(0, Math.min(255, gray));
                elevations.setRGB(px, pz, new Color(gray, gray, gray).getRGB());
                rocks.setRGB(px, pz, geologyColor(surface.geology()).getRGB());
            }
        }
        Path biomePath = destination.resolve("biomes_seed_" + seed + ".png");
        Path heightPath = destination.resolve("height_seed_" + seed + ".png");
        Path rockPath = destination.resolve("geology_seed_" + seed + ".png");
        ImageIO.write(biomes, "png", biomePath.toFile());
        ImageIO.write(elevations, "png", heightPath.toFile());
        ImageIO.write(rocks, "png", rockPath.toFile());
        ImageIO.write(continents, "png", destination.resolve("continentalness_seed_"+seed+".png").toFile());
        ImageIO.write(erosion, "png", destination.resolve("erosion_seed_"+seed+".png").toFile());
        ImageIO.write(ridges, "png", destination.resolve("ridges_seed_"+seed+".png").toFile());
        ImageIO.write(relief, "png", destination.resolve("landforms_seed_"+seed+".png").toFile());
        System.out.println("Worldgen preview for seed " + seed);
        try {
            System.out.println("Suggested safe spawn: " + SpawnFinder.find(generator, 768));
        } catch (IllegalStateException noSpawn) {
            System.out.println("No safe spawn near origin: " + noSpawn.getMessage());
        }
        System.out.println("Sampled elevation range: " + minY + ".." + maxY);
        System.out.printf("Ocean water coverage: %.1f%%%n", waterSamples * 100.0 / (width * height));
        counts.forEach((biome, count) -> System.out.printf("  %-29s %5.1f%%%n",
                biome.name(), count * 100.0 / (width * height)));
        System.out.println("Geology distribution:");
        geologicalCounts.forEach((rock, count) -> System.out.printf("  %-14s %5.1f%%%n",
                rock.name(), count * 100.0 / (width * height)));
        System.out.println("Wrote " + biomePath + ", " + heightPath + ", " + rockPath);
    }

    private static Color gradient(double v) {
        int gray = Math.max(0, Math.min(255, (int)Math.round(127.5+127.5*v)));
        return new Color(gray,gray,gray);
    }
    private static Color reliefColor(WorldGenerator.Geography g) {
        if (g.continentalness() < -0.23) return new Color(37,79,141);
        if (g.continentalness() < -0.12) return new Color(96,163,184);
        double rugged = 1.0 - Math.max(0,Math.min(1,(g.erosion()+0.35)/0.83));
        if (g.upland() > 0.26 && rugged > 0.5) return new Color(160,158,153);
        if (g.upland() > 0.05) return new Color(165,148,109);
        if (rugged > 0.65) return new Color(106,137,84);
        return new Color(103,166,90);
    }

    private static Color shade(Color c, double amount) {
        return new Color(Math.min(255, (int)(c.getRed() * amount)),
                Math.min(255, (int)(c.getGreen() * amount)),
                Math.min(255, (int)(c.getBlue() * amount)));
    }

    private static Color geologyColor(Geology geology) {
        return switch (geology) {
            case GRANITE -> new Color(128, 116, 123);
            case LIMESTONE -> new Color(214, 208, 173);
            case SANDSTONE -> new Color(204, 154, 91);
            case SHALE -> new Color(96, 107, 123);
            case BASALT -> new Color(64, 70, 79);
            case VOLCANIC -> new Color(141, 75, 59);
        };
    }
}
