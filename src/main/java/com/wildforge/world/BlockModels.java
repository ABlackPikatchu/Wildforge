package com.wildforge.world;

import java.util.Objects;
import java.util.Properties;
import java.io.IOException;
import java.io.InputStream;

/**
 * Central face-texture and model metadata registry. Terrain meshing can use
 * the same definition as dropped cubes and future item/block previews.
 * Shape metadata is shared by world meshing, placement rules and inventory
 * previews; all non-cube shapes registered by Wildforge have a render path.
 */
public final class BlockModels {
    private BlockModels() {}

    public enum Shape { CUBE, FLUID, CROSS_PLANT, TALL_CROSS_PLANT, WALL_ATTACHMENT, CEILING_ATTACHMENT, SPIKE, CRYSTAL, CUSTOM }
    public enum Pass { OPAQUE, CUTOUT, TRANSLUCENT }
    public enum Face {
        WEST(0), EAST(0), DOWN(1), UP(1), NORTH(2), SOUTH(2);
        private final int axis;
        Face(int axis) { this.axis = axis; }
        public int axis() { return axis; }
    }

    public record Model(Shape shape, Pass pass, boolean fullCubeOcclusion) {}
    private static final Model SOLID_CUBE = new Model(Shape.CUBE, Pass.OPAQUE, true);
    private static final Model GLASS_CUBE = new Model(Shape.CUBE, Pass.TRANSLUCENT, false);
    private static final Model FLUID = new Model(Shape.FLUID, Pass.TRANSLUCENT, false);
    private static final Model AIR = new Model(Shape.CUSTOM, Pass.OPAQUE, false);

    private static final String RESOURCE = "/assets/wildforge/models/block_models.properties";
    private static final Model[] MODELS = loadModels();

    private static Model[] loadModels() {
        Model[] models = new Model[256];
        for (BlockType legacy : BlockType.values()) {
            BlockType block = BlockState.fromLegacy(legacy).block();
            Model defaultModel = block == BlockType.AIR ? AIR :
                    block.isFluid() ? FLUID : block == BlockType.GLASS ? GLASS_CUBE : SOLID_CUBE;
            models[Byte.toUnsignedInt(legacy.getId())] = defaultModel;
        }
        // Resource definitions override built-in cube/fluid defaults while
        // retaining the original byte IDs used in saved worlds.
        try (InputStream in = BlockModels.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return models;
            Properties properties = new Properties();
            properties.load(in);
            for (String name : properties.stringPropertyNames()) {
                BlockType block = BlockType.valueOf(name.strip());
                String[] fields = properties.getProperty(name).strip().split(",", -1);
                if (fields.length != 3) throw new IllegalStateException("Bad block model entry: " + name);
                Shape shape = Shape.valueOf(fields[0].strip());
                Pass pass = Pass.valueOf(fields[1].strip());
                String occlusionText = fields[2].strip();
                if (!occlusionText.equals("true") && !occlusionText.equals("false"))
                    throw new IllegalStateException("Bad block occlusion flag for " + name);
                if (shape != Shape.CUBE && shape != Shape.FLUID && shape != Shape.CUSTOM
                        && shape != Shape.CROSS_PLANT && shape != Shape.TALL_CROSS_PLANT
                        && shape != Shape.CEILING_ATTACHMENT
                        && shape != Shape.SPIKE && shape != Shape.CRYSTAL)
                    throw new IllegalStateException("Model shape is not implemented: " + name + " / " + shape);
                if (shape != Shape.CUBE && Boolean.parseBoolean(occlusionText))
                    throw new IllegalStateException("Non-cube model cannot occlude full faces: " + name);
                Model model = new Model(shape, pass, Boolean.parseBoolean(occlusionText));
                BlockState state = BlockState.fromLegacy(block);
                if (state.isLog() && state.block() != block)
                    throw new IllegalStateException("Configure the logical log base, not axis variant: " + name);
                for (BlockType legacy : BlockType.values())
                    if (BlockState.fromLegacy(legacy).block() == state.block())
                        models[Byte.toUnsignedInt(legacy.getId())] = model;
            }
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Invalid Wildforge block models resource", error);
        }
        return models;
    }

    public static Model modelFor(BlockState state) {
        Objects.requireNonNull(state, "state");
        return MODELS[Byte.toUnsignedInt(state.toLegacyId())];
    }

    public static int textureForAxis(BlockState state, int faceAxis) {
        return switch (faceAxis) {
            case 0 -> textureForFace(state, Face.EAST);
            case 1 -> textureForFace(state, Face.UP);
            case 2 -> textureForFace(state, Face.SOUTH);
            default -> throw new IllegalArgumentException("Invalid face axis: " + faceAxis);
        };
    }

    public static int textureForFace(BlockState state, Face face) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(face, "face");
        BlockType block = state.block();
        if (state.isLog())
            return state.axis() == BlockState.Axis.fromFaceAxis(face.axis())
                    ? block.getTopTextureLayer() : block.getSideTextureLayer();
        return switch (face) {
            case UP -> block.getTopTextureLayer();
            case DOWN -> block.getBottomTextureLayer();
            default -> block.getSideTextureLayer();
        };
    }
}
