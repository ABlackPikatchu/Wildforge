package com.wildforge.item;

import com.wildforge.content.*;
import com.wildforge.inventory.Inventory;
import com.wildforge.player.MiningRules;
import java.io.*;

/** Headless Phase 8A regression: stable item IDs, inventory accounting, codec and tool data. */
public final class Phase8AItemMigrationSmokeTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static ItemStack roundTrip(ItemStack item) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ItemStackCodec.write(new DataOutputStream(bytes), item);
        return ItemStackCodec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }
    public static void main(String[] args) throws Exception {
        ItemRegistry registry = new ItemRegistry();
        // Every built-in item is represented by a registry definition, even before content load.
        for (ItemType type : ItemType.values()) {
            ItemDefinition legacy = ItemCatalog.legacy(type);
            if (type == ItemType.STONE_PICKAXE) {
                legacy = new ItemDefinition(legacy.getId(), legacy.getNumericId(),
                        legacy.getMaxStackSize(), legacy.getPlacesBlock(), legacy.getTexture(),
                        legacy.getModel(), new ItemDefinition.ToolComponent("pickaxe", 2, 9));
            }
            registry.register(legacy);
        }
        ItemDefinition.ToolComponent diamond = new ItemDefinition.ToolComponent("pickaxe",4,8);
        ItemDefinition custom = new ItemDefinition(ResourceId.parse("demo:gear"), 60000, 16,
                null, ResourceId.parse("demo:gear"), null);
        registry.register(custom);
        ItemDefinition tool = new ItemDefinition(ResourceId.parse("demo:hammer"), 60001, 1,
                null, ResourceId.parse("demo:hammer"), null, diamond);
        registry.register(tool);
        ItemCatalog.install(registry);

        check(ItemCatalog.require(custom.getId()) == custom, "registry lookup");
        check(ItemCatalog.legacy(ItemType.STONE_BLOCK).getId().equals(ResourceId.parse("wildforge:stone_block")), "legacy key");
        check(new ItemStack(custom,32).getCount()==16, "custom max stack");
        check(new ItemStack(custom,1).getItemType() == null, "data-only identity must not alias enums");
        ItemStack result = roundTrip(new ItemStack(custom,16));
        check(result.itemId().equals(custom.getId()) && result.getCount()==16, "keyed save round-trip");
        check(roundTrip(null)==null, "empty-slot round-trip");

        Inventory inv = new Inventory();
        check(inv.addItem(custom, 39), "multi-slot insertion");
        check(inv.getItemCount(custom)==39, "total count");
        check(inv.removeItem(custom, 17), "removal");
        check(inv.getItemCount(custom)==22, "remaining count");
        check(inv.addItem(ItemType.STONE_BLOCK,70), "legacy adapter insertion");
        check(inv.getItemCount(ItemType.STONE_BLOCK)==70, "legacy count");
        check(inv.getItemCount(custom)==22, "different items do not merge");
        check(!inv.removeItem(custom,200), "all or nothing removal");
        check(inv.getItemCount(custom)==22, "failed removal did not mutate");
        inv.clickSlot(0, false);
        check(inv.getCursorStack()!=null, "cursor pickup");
        inv.returnCursorStackToInventory();
        check(inv.getCursorStack()==null, "cursor return");
        check(inv.getItemCount(custom)==22, "cursor conservation");
        check(tool.getTool().tier()==4 && tool.getTool().speed()==8, "component properties");
        check(MiningRules.tool(ItemType.STONE_PICKAXE).speed()==9,
                "Mining must use registry speed, not enum-name defaults");

        ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        DataOutputStream old = new DataOutputStream(legacy);
        old.writeByte(ItemType.STONE_BLOCK.getId()); old.writeInt(13);
        ItemStack converted = ItemStackCodec.readLegacy(new DataInputStream(new ByteArrayInputStream(legacy.toByteArray())));
        check(converted.itemId().equals(ResourceId.parse("wildforge:stone_block")) && converted.getCount()==13, "legacy numeric migration");
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        DataOutputStream invalid = new DataOutputStream(bad);
        invalid.writeUTF("demo:missing"); invalid.writeInt(1);
        try { ItemStackCodec.read(new DataInputStream(new ByteArrayInputStream(bad.toByteArray())));
            throw new AssertionError("Unknown saved items must fail, not disappear");
        } catch (IOException expected) { }
        bad.reset(); invalid = new DataOutputStream(bad);
        invalid.writeUTF("demo:gear"); invalid.writeInt(17);
        try { ItemStackCodec.read(new DataInputStream(new ByteArrayInputStream(bad.toByteArray())));
            throw new AssertionError("Overstacked save must fail");
        } catch (IOException expected) { }
        System.out.println("Phase 8A items passed: stable keys, 16-stack items, high numeric IDs, inventory, v3/legacy saves");
    }
}
