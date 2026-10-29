package com.wildforge.world;

import com.wildforge.worldgen.WorldGenerator;

/**
 * Deterministic, chunk-independent voxel foliage layout. The same world-space
 * tree candidate generates the same leaves in every neighboring chunk.
 * Leaves are actual saved block cells, not an unsaved draw-only canopy.
 */
public final class TreeFoliage {
    @FunctionalInterface public interface LeafSink { void add(int x, int y, int z, BlockType leaf); }
    private TreeFoliage() {}

    public static BlockType leafFor(WorldGenerator.TreeType species) {
        return switch (species) {
            case OAK -> BlockType.OAK_LEAVES;
            case MAPLE -> BlockType.MAPLE_LEAVES;
            case PINE -> BlockType.PINE_LEAVES;
            case CYPRESS -> BlockType.CYPRESS_LEAVES;
            case WILLOW -> BlockType.WILLOW_LEAVES;
            case BIRCH -> BlockType.BIRCH_LEAVES;
            case SPRUCE -> BlockType.SPRUCE_LEAVES;
            case ACACIA -> BlockType.ACACIA_LEAVES;
            case ALDER -> BlockType.ALDER_LEAVES;
            case MANGROVE -> BlockType.MANGROVE_LEAVES;
            case PALM -> BlockType.PALM_LEAVES;
        };
    }

    public static void generate(WorldGenerator.Tree tree, int groundY, LeafSink sink) {
        BlockType foliage=leafFor(tree.type());
        int tip=groundY+1+tree.trunkHeight();
        if(tree.type()==WorldGenerator.TreeType.PALM) {
            // Radial fronds, with a dropped tip; no giant cube canopy.
            for(int arm=0;arm<8;arm++) {
                double a=arm*Math.PI/4.0+((tree.x()^tree.z())&3)*.13;
                int reach=4+((tree.x()+tree.z()+arm)&1);
                for(int d=0;d<=reach;d++) {
                    int x=tree.x()+(int)Math.round(Math.cos(a)*d);
                    int z=tree.z()+(int)Math.round(Math.sin(a)*d);
                    int y=tip+(d<=2?1:d<=3?0:-1-(d-4));
                    sink.add(x,y,z,foliage);
                    if(d>=2 && d<reach && (arm%2==0))sink.add(x,y,z+(arm<4?1:-1),foliage);
                }
            }
            return;
        }
        if(tree.type()==WorldGenerator.TreeType.PINE || tree.type()==WorldGenerator.TreeType.SPRUCE
                || tree.type()==WorldGenerator.TreeType.CYPRESS) {
            int depth=Math.min(tree.trunkHeight()-2,tree.type()==WorldGenerator.TreeType.CYPRESS?16:14);
            int widest=tree.trunkHeight()>=19?4:3;
            for(int layer=0;layer<=depth;layer++) {
                double taper=(layer+1.5)/(depth+2.0);
                double radius=Math.max(1.05,widest*taper*(layer%4==3?.84:1.0));
                int r=(int)Math.ceil(radius);
                int y=tip-layer;
                for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++) {
                    double dist=Math.sqrt(dx*dx+dz*dz);
                    if(dist>radius+.08)continue;
                    if(dist>radius*.67 && unitHash(tree.x()+dx,y,tree.z()+dz,tree.x(),tree.z())>.81)continue;
                    sink.add(tree.x()+dx,y,tree.z()+dz,foliage);
                }
            }
            return;
        }
        int count=switch(tree.type()) { case OAK,BIRCH -> 4; case MAPLE,WILLOW,ALDER -> 6;
            case ACACIA,MANGROVE -> 5; default -> 4; };
        for(int b=-1;b<count;b++) {
            int cx=tree.x(),cy=tip-1,cz=tree.z();
            if(b>=0) {
                int[] o=branchTip(tree,b,count);
                cx+=o[0];cy+=o[1]+1;cz+=o[2];
            }
            int radius=switch(tree.type()) {
                case WILLOW,ACACIA,MANGROVE -> b<0?4:3;
                case BIRCH -> b<0?3:2;
                default -> b<0?3:2+(tree.trunkHeight()>13?1:0);
            };
            for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)
                for(int dy=-radius;dy<=radius;dy++) {
                    double flat=tree.type()==WorldGenerator.TreeType.ACACIA?2.9:1.0;
                    double shape=(dx*dx+dz*dz)/(double)(radius*radius)
                        +dy*dy*flat/(double)Math.max(1,(radius-1)*(radius-1));
                    if(shape>1.20)continue;
                    int x=cx+dx,y=cy+dy,z=cz+dz;
                    double fill=switch(tree.type()) {
                        case MAPLE,MANGROVE,ALDER -> .82;
                        case ACACIA -> .58; case BIRCH -> .68; default -> .76;
                    };
                    if(shape>.48 && unitHash(x,y,z,tree.x(),tree.z())>fill)continue;
                    sink.add(x,y,z,foliage);
                }
        }
        if(tree.type()==WorldGenerator.TreeType.WILLOW)for(int b=0;b<count;b++) {
            int[] p=branchTip(tree,b,count);
            for(int d=0;d<3+b%3;d++)sink.add(tree.x()+p[0],tip+p[1]-2-d,tree.z()+p[2],foliage);
        }
    }

    private static int[] branchTip(WorldGenerator.Tree t,int b,int count) {
        long v=((long)t.x()*0x9E3779B97F4A7C15L)^((long)t.z()*0xC2B2AE3D27D4EB4FL)
                ^((long)b*0x165667B19E3779F9L);
        v^=v>>>30;v*=0xbf58476d1ce4e5b9L;v^=v>>>27;
        double a=2*Math.PI*(b+.23+((v>>>12)&255)/1024.0)/count;
        int length=switch(t.type()) {case OAK,BIRCH,MAPLE,ALDER -> 3+(int)((v>>>24)&3);
            default -> 5+(int)((v>>>24)&3);};
        int rise=switch(t.type()) {case OAK,BIRCH,MAPLE -> -2+b%3;
            case WILLOW,ALDER -> -2+b%2; case ACACIA -> -1+b%2;
            default -> -1+b%3;};
        return new int[]{(int)Math.round(length*Math.cos(a)),rise,(int)Math.round(length*Math.sin(a))};
    }
    private static double unitHash(int x,int y,int z,int tx,int tz) {
        long h=((long)x*0x9E3779B97F4A7C15L)^((long)y*0xC2B2AE3D27D4EB4FL)
                ^((long)z*0x165667B19E3779F9L)^((long)tx*0xD6E8FEB86659FD93L)
                ^((long)tz*0x94d049bb133111ebL);
        h^=h>>>33;h*=0xff51afd7ed558ccdL;h^=h>>>33;
        return (h>>>11)*0x1.0p-53;
    }
}
