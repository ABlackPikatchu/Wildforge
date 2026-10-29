package com.wildforge.item;

import com.wildforge.content.ItemCatalog;
import com.wildforge.content.ItemDefinition;
import com.wildforge.content.ResourceId;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Stable keyed inventory format for player save v3. Entire read is validated by caller
 * before replacing the live inventory. The namespaced item ID is the save identity. */
public final class ItemStackCodec {
    private ItemStackCodec() { }
    public static void write(DataOutput out, ItemStack stack) throws IOException {
        if (stack == null || stack.isEmpty()) {
            out.writeUTF("");
            out.writeInt(0);
            return;
        }
        String name = stack.itemId().toString();
        if (name.length() > 256 || stack.getCount() < 1 || stack.getCount() > stack.maxStackSize())
            throw new IOException("Invalid item stack: " + name);
        out.writeUTF(name);
        out.writeInt(stack.getCount());
    }
    public static ItemStack read(DataInput in) throws IOException {
        String key = in.readUTF();
        int count = in.readInt();
        if (key.isEmpty()) {
            if (count != 0) throw new IOException("Empty slot with nonzero count");
            return null;
        }
        if (key.length() > 256) throw new IOException("Item key is too long");
        try {
            ItemDefinition definition = ItemCatalog.require(ResourceId.parse(key));
            if (count < 1 || count > definition.getMaxStackSize())
                throw new IOException("Invalid count for " + key + ": " + count);
            return new ItemStack(definition, count);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Unknown item in save: " + key, exception);
        }
    }
    public static ItemStack readLegacy(DataInput in) throws IOException {
        int numericId = in.readUnsignedByte();
        int count = in.readInt();
        if (numericId == 0) {
            if (count != 0) throw new IOException("Empty slot with nonzero count");
            return null;
        }
        ItemType type = ItemType.fromId(numericId);
        if (type == null) throw new IOException("Unknown legacy item ID: " + numericId);
        ItemDefinition item = ItemCatalog.legacy(type);
        if (count < 1 || count > item.getMaxStackSize())
            throw new IOException("Invalid legacy item stack");
        return new ItemStack(item, count);
    }
}
