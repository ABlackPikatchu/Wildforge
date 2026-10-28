package com.wildforge.worldgen;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Offline vertical density/cave section, without launching OpenGL. */
public final class WorldGenCrossSection {
    private WorldGenCrossSection() {}

    public static void main(String[] args) throws IOException {
        Path folder = Path.of(args.length > 0 ? args[0] : "build/worldgen-preview");
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261028L;
        int z = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int startX = args.length > 3 ? Integer.parseInt(args[3]) : -512;
        int width = 1024, minY = 0, maxY = 255;
        WorldGenerator gen = WorldGenRegistry.loadBuiltIn().generator(seed);
        BufferedImage picture = new BufferedImage(width, maxY-minY+1, BufferedImage.TYPE_INT_RGB);
        for (int px=0;px<width;px++) {
            int x=startX+px;
            WorldGenerator.Surface s=gen.surface(x,z);
            int top=s.surfaceY(), slope=gen.slopeAt(x,z);
            for (int y=minY;y<=maxY;y++) {
                boolean solid=gen.isSolidTerrain(x,y,z,top,slope);
                boolean cave=solid && gen.isCave(x,y,z,top);
                Color color;
                if (cave) color=new Color(25,26,38);
                else if (solid && y > top-3) color = new Color(116,151,101);
                else if (solid) color = new Color(106,106,114);
                else if (y <= gen.settings().seaLevel()) color = new Color(55,110,160);
                else color = new Color(137,192,226);
                picture.setRGB(px,maxY-y,color.getRGB());
            }
        }
        Files.createDirectories(folder);
        Path output=folder.resolve("section_seed_"+seed+"_z_"+z+".png");
        ImageIO.write(picture,"png",output.toFile());
        System.out.println("Wrote " + output);
    }
}
