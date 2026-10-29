package com.wildforge.core.save;

import com.wildforge.entity.DroppedItemEntity;
import com.wildforge.item.ItemType;
import com.wildforge.content.ItemCatalog;
import com.wildforge.content.ItemDefinition;
import com.wildforge.content.ResourceId;
import com.wildforge.world.TerrainMesh;
import com.wildforge.world.WorldItemManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Versioned disk representation of active dropped stacks. No random spawn impulses on restore. */
public final class DroppedItemStorage {
    private static final int MAGIC = 0x57464954; // WFIT
    private static final int VERSION = 2;
    private static final int MAX_ITEMS = 100_000;
    public record Stack(ResourceId id, int count, float x, float y, float z,
                        float vx, float vy, float vz, float age) { }
    private DroppedItemStorage() { }

    public static void write(Path path, WorldItemManager items) throws IOException {
        List<DroppedItemEntity> current = items == null ? List.of() : items.getItems();
        if (current.size() > MAX_ITEMS) throw new IOException("Too many dropped item entities");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(current.size());
            for (DroppedItemEntity entity : current) {
                out.writeUTF(entity.itemId().toString());
                out.writeInt(entity.getCount());
                out.writeFloat(entity.getX());out.writeFloat(entity.getY());out.writeFloat(entity.getZ());
                out.writeFloat(entity.getVelocityX());out.writeFloat(entity.getVelocityY());out.writeFloat(entity.getVelocityZ());
                out.writeFloat(entity.getAge());
            }
        }
    }

    public static void read(Path path, WorldItemManager items, TerrainMesh terrain) throws IOException {
        List<Stack> restored=readStacks(path);
        // Validate the whole file before replacing in-memory entities.
        items.clear();
        for (Stack stack : restored) items.restore(stack, terrain);
    }

    /** Decode and validate without mutating a live world, useful for migration and tests. */
    public static List<Stack> readStacks(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return List.of();
        List<Stack> restored = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC) throw new IOException("Unrecognized dropped-item magic");
            int version=in.readInt();
            if(version!=1 && version!=VERSION) throw new IOException("Unrecognized dropped-item version: "+version);
            int count = in.readInt();
            if (count < 0 || count > MAX_ITEMS) throw new IOException("Invalid dropped-item count");
            for (int i=0; i<count; i++) {
                ResourceId id;
                if(version==1) {
                    ItemType legacy=ItemType.fromId(in.readInt());
                    if(legacy==null)throw new IOException("Unknown legacy drop at index "+i);
                    id=ItemCatalog.legacyId(legacy);
                }else{
                    try { id=ResourceId.parse(in.readUTF()); }
                    catch(RuntimeException ex){ throw new IOException("Invalid dropped-item identity at index "+i,ex); }
                }
                int amount=in.readInt();
                float x=in.readFloat(),y=in.readFloat(),z=in.readFloat();
                float vx=in.readFloat(),vy=in.readFloat(),vz=in.readFloat(),age=in.readFloat();
                ItemDefinition type=ItemCatalog.byId(id);
                if (type==null || amount<1 || amount>type.getMaxStackSize() ||
                    !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) ||
                    !Float.isFinite(vx) || !Float.isFinite(vy) || !Float.isFinite(vz) ||
                    !Float.isFinite(age) || age<0 || Math.abs(x)>30_000_000 ||
                    Math.abs(z)>30_000_000 || y<0 || y>TerrainMesh.WORLD_HEIGHT)
                    throw new IOException("Invalid dropped-item data at index " + i);
                restored.add(new Stack(id,amount,x,y,z,vx,vy,vz,age));
            }
            if (in.read()!=-1) throw new IOException("Trailing dropped-item data");
        }
        return List.copyOf(restored);
    }
}
