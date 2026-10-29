package com.wildforge.world;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small end-to-end checks for fluid block edits, persistence and backward compatibility. */
public final class FluidPersistenceSmokeTest {
    private FluidPersistenceSmokeTest() {}

    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("wildforge-fluid-check-");
        try {
            int x=16,z=27;
            TerrainMesh terrain=new TerrainMesh();
            int y=Math.min(TerrainMesh.WORLD_HEIGHT-4,Math.max(35,(int)terrain.getHeightAt(x,z)+14));
            check(terrain.setBlock(x,y,z,BlockType.FLOWING_WATER),"Place flowing water");
            check(terrain.getFluidLevelAt(x,y,z)==1,"Default flow strength");
            check(terrain.setFluidLevelAt(x,y,z,6),"Set flow strength");
            check(terrain.getFluidLevelAt(x,y,z)==6,"Flow level must update");
            check(!terrain.setFluidLevelAt(x,y,z,8),"Reject invalid level");
            check(terrain.setBlock(x+1,y,z,BlockType.WATER),"Place source");
            check(terrain.getFluidLevelAt(x+1,y,z)==7,"Sources are full");
            check(terrain.setBlock(x+2,y,z,BlockType.FLOWING_LAVA),"Place flowing lava");
            check(terrain.setFluidLevelAt(x+2,y,z,3),"Set lava strength");
            // Replacing a flow must erase its persisted level.
            check(terrain.setBlock(x+3,y,z,BlockType.FLOWING_WATER),"Place removable flow");
            check(terrain.setFluidLevelAt(x+3,y,z,5),"Adjust removable flow");
            check(terrain.setBlock(x+3,y,z,BlockType.STONE),"Replace flow with rock");
            check(terrain.setBlock(x+3,y,z,BlockType.FLOWING_WATER),"Restore flow");
            check(terrain.getFluidLevelAt(x+3,y,z)==1,"Replaced flow reset to level 1");

            Path save=dir.resolve("world-v6.wfs");terrain.saveTo(save);
            TerrainMesh loaded=new TerrainMesh();loaded.loadFrom(save);
            check(loaded.getFluidLevelAt(x,y,z)==6,"Water level survives reload");
            check(loaded.getFluidLevelAt(x+1,y,z)==7,"Source level survives reload");
            check(loaded.getFluidLevelAt(x+2,y,z)==3,"Lava level survives reload");
            check(loaded.getFluidLevelAt(x+3,y,z)==1,"Cleared metadata doesn't survive");

            // No implicit reinterpretation of old generator/save formats.
            byte[] raw=Files.readAllBytes(save);
            byte[] oldFormat=raw.clone();
            java.nio.ByteBuffer.wrap(oldFormat).putInt(4,3);
            Path old=dir.resolve("world-v3.wfs");Files.write(old,oldFormat);
            try {loaded.loadFrom(old);throw new AssertionError("Old save format should fail");}
            catch(IOException expected) {check(loaded.getFluidLevelAt(x,y,z)==6,"Rejected old save changed world");}

            // Future incompatible generator revisions fail before replacing live data.
            byte[] mismatch=raw.clone();
            java.nio.ByteBuffer.wrap(mismatch).putInt(20,9999);
            Path badRevision=dir.resolve("future-generator.wfs");Files.write(badRevision,mismatch);
            try {loaded.loadFrom(badRevision);throw new AssertionError("Incompatible generator must fail");}
            catch(IOException expected) {check(loaded.getFluidLevelAt(x,y,z)==6,"Rejected save changed live world");}

            // Corrupt fluid metadata must fail without replacing the current world.
            byte[] corrupt=Files.readAllBytes(save);corrupt[corrupt.length-1]=0;
            Path bad=dir.resolve("corrupt-v6.wfs");Files.write(bad,corrupt);
            try {loaded.loadFrom(bad);throw new AssertionError("Invalid levels must be rejected");}
            catch(IOException expected) {check(loaded.getFluidLevelAt(x,y,z)==6,"Failed load must not alter live world");}
            System.out.println("Fluid persistence checks passed: v6 levels, old-format rejection, edits, corruption");
        } finally {
            try(var paths=Files.walk(dir)) {
                for(Path f:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(f);
            }
        }
    }
    private static void check(boolean condition,String description) {
        if(!condition)throw new AssertionError(description);
    }
}
