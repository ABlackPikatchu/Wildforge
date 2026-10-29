package com.wildforge.world;

import com.wildforge.entity.DroppedItemEntity;
import com.wildforge.item.ItemType;

/** Regresses the fast-falling-item tunneling bug with a one-block floor. */
public final class DroppedItemPhysicsSmokeTest {
    private DroppedItemPhysicsSmokeTest() { }
    public static void main(String[] args) {
        TerrainMesh terrain=new TerrainMesh();
        int x=16,z=27;
        int top=(int)terrain.getHeightAt(x,z);
        int floor=Math.max(10,Math.min(TerrainMesh.WORLD_HEIGHT-16,top+8));
        if (!terrain.setBlock(x,floor,z,BlockType.STONE))
            throw new AssertionError("Could not prepare solid floor");
        DroppedItemEntity item=new DroppedItemEntity(ItemType.DIAMOND,1,x+.5f,floor+3f,z+.5f,terrain);
        item.setVelocity(0,-70f,0);
        item.update(.05f);
        if (item.getY() < floor+1.15f)
            throw new AssertionError("Item tunneled through a block at speed -70");
        System.out.println("Dropped-item swept collision passed");
    }
}
