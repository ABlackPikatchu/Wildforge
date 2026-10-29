package com.wildforge.content;

import com.wildforge.lang.LanguageManager;
import com.wildforge.item.ItemType;
import com.wildforge.render.BlockTextures;
import com.wildforge.world.BlockType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/** Run with ./gradlew.bat contentCheck. Uses no additional test dependencies. */
public final class ContentSmokeTest {
    private ContentSmokeTest() { }

    public static void main(String[] args) throws Exception {
        ContentLoader.Content content = ContentLoader.loadBuiltIn();
        ContentLoader.validateLegacyIds(content);
        LanguageManager language = LanguageManager.loadBuiltIn();
        for (BlockDefinition block : content.blocks().getAll()) {
            check(language.contains(block.getTranslationKey()),
                    "Missing block translation: " + block.getTranslationKey());
        }
        for (ItemDefinition item : content.items().getAll()) {
            check(language.contains(item.getTranslationKey()),
                    "Missing item translation: " + item.getTranslationKey());
        }
        LanguageManager.install(language);
        check("Stone Block".equals(ItemType.STONE_BLOCK.getDisplayName()),
                "Inventory display names must come from en_us.json");
        check("Stone".equals(content.blocks().get("stone").getDisplayName()),
                "Block display names must come from en_us.json");

        check(content.blocks().size() == 195, "Expected 195 registered block types");
        check(content.items().size() == 202, "Expected 202 registered item types");
        check(content.blocks().get("wildforge:stone").getNumericId() == 3,
                "Stone block save ID must remain 3");
        check(content.items().get("wildforge:coal").getNumericId() == 4,
                "Coal item save ID must remain 4");
        check(content.items().getByNumericId(7) == null,
                "Item save ID 7 must stay unused");
        check(content.blocks().getByNumericId(6).getBlockItem() == null,
                "Bedrock should not have a placeable item");
        for (BlockType log : new BlockType[]{BlockType.OAK_LOG,BlockType.PINE_LOG,
                BlockType.MAPLE_LOG,BlockType.WILLOW_LOG,BlockType.CYPRESS_LOG}) {
            BlockType x=log.orientedLog(0), z=log.orientedLog(2);
            check(x!=log && z!=log && x!=z && x.getDropItem()==log.getDropItem(),
                    "Horizontal log must retain original item drop: " + log);
            check(x.textureForAxis(0)==log.getTopTextureLayer() &&
                    x.textureForAxis(1)==log.getSideTextureLayer() &&
                    z.textureForAxis(2)==log.getTopTextureLayer(),
                    "Log bark/end-grain must follow axis: " + log);
        }
        check(!BlockType.WATER.isSolid() && !BlockType.LAVA.isSolid(),
                "Fluid blocks must be non-solid");
        check(content.blocks().get("wildforge:flowing_water") != null,
                "Flowing water must have a generated definition");
        check(content.blocks().get("stone").getId().equals(ResourceId.parse("wildforge:stone")),
                "Unqualified resource IDs should resolve to wildforge namespace");
        check(content.items().get("wildforge:stone_block").getPlacesBlock()
                .equals(ResourceId.parse("wildforge:stone")),
                "Stone item should place the stone block");
        check(content.blocks().get("wildforge:stone").getDrops().get(0).item()
                .equals(ResourceId.parse("wildforge:stone_block")),
                "Stone drop must point to legacy stone item");
        // Tag membership comes from generated JSON, including nested #tag references.
        TagRegistry tags = content.tags();
        // Keep these totals aligned with ContentDataGenerator's expanded catalogs.
        check(tags.blockTagCount() == 16 && tags.itemTagCount() == 11,
                "Expected 16 block tags and 11 item tags, found "
                        + tags.blockTagCount() + " block tags and "
                        + tags.itemTagCount() + " item tags");
        check(tags.containsBlock("wildforge:stone", "wildforge:stone"),
                "Stone must belong to the stone tag");
        check(tags.containsBlock("wildforge:stone", "wildforge:andesite"),
                "Andesite must belong to the stone tag");
        check(tags.containsBlock("wildforge:natural_stone", "wildforge:basalt"),
                "Nested natural_stone tag must include basalt");
        check(tags.containsBlock("wildforge:natural_stone", "wildforge:sandstone"),
                "Natural stone must include sandstone");
        check(tags.containsBlock("wildforge:logs", "wildforge:oak_log_x"),
                "Sideways oak logs must belong to logs tag");
        check(tags.containsBlock("wildforge:water", "wildforge:flowing_water"),
                "Flowing water must belong to water tag");
        check(tags.containsBlock("wildforge:ores", "wildforge:iron_ore"),
                "Iron ore must belong to ores tag");
        check(tags.containsItem("wildforge:tools", "wildforge:iron_pickaxe"),
                "Iron pickaxe must belong to tools tag");
        check(tags.containsItem("wildforge:decoration_blocks", "wildforge:cave_moss_block"),
                "Cave moss must belong to decoration blocks tag");
        check(!tags.containsBlock("wildforge:stone", "wildforge:dirt"),
                "Dirt must not match the stone tag");
        check(tags.containsItem("wildforge:building_blocks", "wildforge:stone_block"),
                "Nested item tag must include stone block");
        check(tags.containsItem("wildforge:building_blocks", "wildforge:dirt_block"),
                "Building blocks tag must include dirt block");
        check(!tags.containsItem("wildforge:stone_blocks", "wildforge:coal"),
                "Coal must not match the stone blocks tag");
        check(!tags.containsItem("wildforge:missing", "wildforge:coal"),
                "Missing tags must not accidentally match items");
        RegistryContentAdapter bridge = new RegistryContentAdapter(content);
        check(bridge.block(com.wildforge.world.BlockType.STONE).getNumericId() == 3,
                "Legacy blocks must resolve to matching JSON entries");
        check(bridge.item(ItemType.COAL).getId().equals(ResourceId.parse("wildforge:coal")),
                "Legacy items must resolve to matching JSON entries");
        check(bridge.blockType(ResourceId.parse("wildforge:stone"))
                == com.wildforge.world.BlockType.STONE,
                "JSON stone must map back to the existing world enum");
        check(bridge.itemType(ResourceId.parse("wildforge:stone_block")) == ItemType.STONE_BLOCK,
                "JSON stone item must map back to the existing inventory enum");
        // Content is not yet fully registry-driven: every enum texture layer
        // must exist and have the expected size before OpenGL tries to load it.
        for (BlockType type : BlockType.values()) {
            for (int layer : new int[] {type.getTopTextureLayer(),
                    type.getSideTextureLayer(), type.getBottomTextureLayer()}) {
                check(layer >= 0 && layer < BlockTextures.layerCount(),
                        "Missing texture layer for block " + type);
            }
        }
        for (int layer = 0; layer < BlockTextures.layerCount(); layer++) {
            String path = BlockTextures.resourcePathForLayer(layer);
            try (InputStream stream = BlockTextures.class.getResourceAsStream(path)) {
                check(stream != null, "Missing texture: " + path);
                BufferedImage image = ImageIO.read(stream);
                check(image != null, "Could not decode texture: " + path);
                // Keep this in sync with BlockTextures.loadTexture(): the atlas
                // accepts static 32x32 images and 32x64 two-frame strips.
                // Until the animated atlas is implemented, frame 0 is rendered.
                int size = BlockTextures.TEXTURE_SIZE;
                check(image.getWidth() == size
                        && (image.getHeight() == size || image.getHeight() == size * 2),
                        "Texture must be 32x32 or a 32x64 two-frame strip: " + path
                                + " (got " + image.getWidth() + "x"
                                + image.getHeight() + ")");
            }
        }
        TagValidationSmokeTest.run();
        BlockRegistry duplicateCheck = new BlockRegistry();
        duplicateCheck.register(content.blocks().get("wildforge:stone"));
        boolean duplicateRejected = false;
        try {
            duplicateCheck.register(content.blocks().get("wildforge:stone"));
        } catch (IllegalArgumentException expected) {
            duplicateRejected = true;
        }
        check(duplicateRejected, "Duplicate block registration must fail");
        System.out.println("Wildforge content check passed: "
                + content.blocks().size() + " blocks, " + content.items().size() + " items; "
                + language.size() + " translations, " + tags.blockTagCount()
                + " block tags, " + tags.itemTagCount() + " item tags; legacy IDs preserved.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
