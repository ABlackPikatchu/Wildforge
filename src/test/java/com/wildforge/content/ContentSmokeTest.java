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

        check(content.blocks().size() == 107, "Expected 107 registered block types");
        check(content.items().size() == 147, "Expected 147 registered item types");
        check(content.blocks().get("wildforge:stone").getNumericId() == 3,
                "Stone block save ID must remain 3");
        check(content.items().get("wildforge:coal").getNumericId() == 4,
                "Coal item save ID must remain 4");
        check(content.items().getByNumericId(7) == null,
                "Item save ID 7 must stay unused");
        check(content.blocks().getByNumericId(6).getBlockItem() == null,
                "Bedrock should not have a placeable item");
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
        check(tags.blockTagCount() == 2 && tags.itemTagCount() == 2,
                "Expected two block tags and two item tags");
        check(tags.containsBlock("wildforge:stone", "wildforge:stone"),
                "Stone must belong to the stone tag");
        check(tags.containsBlock("wildforge:stone", "wildforge:andesite"),
                "Andesite must belong to the stone tag");
        check(tags.containsBlock("wildforge:natural_stone", "wildforge:basalt"),
                "Nested natural_stone tag must include basalt");
        check(tags.containsBlock("wildforge:natural_stone", "wildforge:sandstone"),
                "Natural stone must include sandstone");
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
        LegacyContentBridge bridge = new LegacyContentBridge(content);
        check(bridge.block(com.wildforge.world.BlockType.STONE).getNumericId() == 3,
                "Legacy blocks must resolve to matching JSON entries");
        check(bridge.item(ItemType.COAL).getId().equals(ResourceId.parse("wildforge:coal")),
                "Legacy items must resolve to matching JSON entries");
        check(bridge.legacyBlock(ResourceId.parse("wildforge:stone"))
                == com.wildforge.world.BlockType.STONE,
                "JSON stone must map back to the existing world enum");
        check(bridge.legacyItem(ResourceId.parse("wildforge:stone_block")) == ItemType.STONE_BLOCK,
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
                check(image != null && image.getWidth() == BlockTextures.TEXTURE_SIZE
                        && image.getHeight() == BlockTextures.TEXTURE_SIZE,
                        "Texture must be 32x32: " + path);
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
