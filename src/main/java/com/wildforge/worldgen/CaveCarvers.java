package com.wildforge.worldgen;

import com.wildforge.world.BlockType;
import java.util.Random;

/** Deterministic, world-space tunnel and ravine carvers. Noise caves remain independent.
 * Each regional feature is sampled across neighboring regions, so chunk order never matters.
 * Carvers change only existing solid voxels and do not touch bedrock.
 */
public final class CaveCarvers {
    private static final int REGION = 96;
    private static final int CHUNK = 16;
    private static final int HEIGHT = com.wildforge.world.TerrainMesh.WORLD_HEIGHT;
    private final long seed;

    public CaveCarvers(long seed) { this.seed = seed; }

    public void carve(byte[] blocks, int chunkX, int chunkZ, WorldGenerator generator) {
        int minX=chunkX*CHUNK, minZ=chunkZ*CHUNK;
        // Trails travel at most ~90 blocks from their region origin; scan surrounding regions.
        int r0x=Math.floorDiv(minX,REGION), r0z=Math.floorDiv(minZ,REGION);
        for(int rx=r0x-2;rx<=r0x+2;rx++)for(int rz=r0z-2;rz<=r0z+2;rz++) {
            Random random=new Random(mix(seed ^ ((long)rx*0x9e3779b97f4a7c15L)
                    ^ ((long)rz*0xc2b2ae3d27d4eb4fL)));
            if(random.nextDouble()>0.65) continue;
            double x=rx*REGION+random.nextInt(REGION);
            double z=rz*REGION+random.nextInt(REGION);
            WorldGenerator.Surface source=generator.surface((int)x,(int)z);
            int top=source.surfaceY();
            Geology geology=source.geology();
            // Limestone dissolution passages and old volcanic tubes follow their host geology.
            boolean karst=geology==Geology.LIMESTONE && random.nextDouble()<0.50;
            boolean volcanic=(geology==Geology.VOLCANIC || geology==Geology.BASALT)
                    && random.nextDouble()<0.48;
            boolean fault=(geology==Geology.GRANITE || geology==Geology.SHALE)
                    && random.nextDouble()<0.20;
            boolean ravine=fault || (!karst && !volcanic && random.nextDouble()<0.17);
            // Rare geology-appropriate entrances descend from daylight into the tunnel
            // network. They are deterministic per region and safe across chunk borders.
            // Keep entrances relatively sparse to avoid swiss-cheese surface terrain.
            if (random.nextDouble()<0.12 && source.waterBody()==WorldGenerator.WaterBody.LAND
                    && top>generator.settings().seaLevel()+3) {
                double descend=17+random.nextInt(19);
                for(int e=0;e<12;e++) {
                    double f=e/11.0;
                    double ex=x+Math.cos(e*0.39+rx)*f*17;
                    double ez=z+Math.sin(e*0.39+rz)*f*17;
                    double ey=top+1-descend*f;
                    carveEllipsoid(blocks,minX,minZ,ex,ey,ez,2.1+f*0.9,2.7,generator,true);
                }
            }
            // Most caves are deep, occasional ravines cut through the surface.
            double y=ravine?Math.max(17,top-8-random.nextInt(24)):
                    Math.max(12,top-(karst?25:volcanic?40:18)-random.nextInt(karst?55:volcanic?75:45));
            double angle=random.nextDouble()*Math.PI*2.0;
            double length=ravine?40+random.nextInt(52):karst?55+random.nextInt(75):volcanic?60+random.nextInt(90):32+random.nextInt(60);
            double turn=(random.nextDouble()-0.5)*0.85;
            double vertical=(random.nextDouble()-0.5)*(volcanic?0.025:0.10);
            int count=(int)(length/3.0);
            for(int i=0;i<count;i++) {
                double t=i/(double)Math.max(1,count-1);
                double cx=x+Math.cos(angle+turn*t)*i*3.0;
                double cz=z+Math.sin(angle+turn*t)*i*3.0;
                double cy=y+vertical*i*3.0+Math.sin(t*Math.PI*2.0)*2.0;
                double radius=(ravine?2.4:volcanic?2.8:karst?2.2:2.0)
                        +(ravine?0.4:volcanic?0.5:karst?2.0:1.3)*Math.sin(t*Math.PI);
                double heightRadius=ravine?Math.max(7.0,radius*4.0):
                        volcanic?radius*0.85:karst?radius*(1.0+0.4*Math.sin(t*8.0)):
                        radius*(0.8+0.35*Math.sin(t*7.0));
                carveEllipsoid(blocks,minX,minZ,cx,cy,cz,radius,heightRadius,generator,ravine);
            }
        }
    }

    private static void carveEllipsoid(byte[] blocks,int minX,int minZ,double cx,double cy,double cz,
                                      double radius,double ry,WorldGenerator generator,boolean ravine) {
        int x0=Math.max(minX,(int)Math.floor(cx-radius));
        int x1=Math.min(minX+15,(int)Math.ceil(cx+radius));
        int z0=Math.max(minZ,(int)Math.floor(cz-radius));
        int z1=Math.min(minZ+15,(int)Math.ceil(cz+radius));
        if(x0>x1||z0>z1)return;
        int y0=Math.max(5,(int)Math.floor(cy-ry));
        int y1=Math.min(HEIGHT-2,(int)Math.ceil(cy+ry));
        for(int wx=x0;wx<=x1;wx++)for(int wz=z0;wz<=z1;wz++) {
            double dx=(wx+0.5-cx)/radius,dz=(wz+0.5-cz)/radius;
            double horizontal=dx*dx+dz*dz;
            if(horizontal>=1)continue;
            int surface=generator.surface(wx,wz).surfaceY();
            for(int wy=y0;wy<=y1;wy++) {
                if(!ravine && wy>surface-5)continue;
                double dy=(wy+0.5-cy)/ry;
                if(horizontal+dy*dy>=1)continue;
                int idx=((wx-minX)*CHUNK+(wz-minZ))*HEIGHT+wy;
                if(blocks[idx]!=BlockType.BEDROCK.getId())blocks[idx]=BlockType.AIR.getId();
            }
        }
    }

    private static long mix(long n) {
        n=(n^(n>>>30))*0xbf58476d1ce4e5b9L;
        n=(n^(n>>>27))*0x94d049bb133111ebL;
        return n^(n>>>31);
    }
}
