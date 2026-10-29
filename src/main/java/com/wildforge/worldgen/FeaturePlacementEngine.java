package com.wildforge.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft-inspired placement modifiers: biome-specific density, patch/clustering,
 * altitude limit, slope and hydrology filters, separated from feature geometry.
 * Species weights continue to come from the individual generated biome profiles.
 */
public final class FeaturePlacementEngine {
    public record Rules(double treePatchScale,double treePatchAmplitude,double treeForestBoost,
                        double plantPatchScale,double wetPlantMultiplier,double dryPlantMultiplier,
                        double bankChance,int frostspireTreeLineAboveSea,int treeMinSpacing) {
        public Rules {
            if(treePatchScale<=0||plantPatchScale<=0||treePatchAmplitude<0||treeForestBoost<=0||
                wetPlantMultiplier<=0||dryPlantMultiplier<=0||bankChance<0||bankChance>1 ||
                frostspireTreeLineAboveSea<20||treeMinSpacing<1||treeMinSpacing>6)throw new IllegalArgumentException("Invalid feature placement rules");
        }
    }
    public static final String RESOURCE="data/wildforge/worldgen/feature_placement.json";
    public static Rules defaults(){return new Rules(96,.45,1.24,75,1.65,1.15,.62,94,3);}
    private final Rules rules;
    public FeaturePlacementEngine(Rules rules){this.rules=rules;}
    public static FeaturePlacementEngine loadBuiltIn() throws IOException {
        try(InputStream in=FeaturePlacementEngine.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if(in==null)return new FeaturePlacementEngine(defaults());
            JsonObject j=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!"wildforge-placed-features-v1".equals(j.get("schema").getAsString()))throw new IOException("Unknown feature schema");
            return new FeaturePlacementEngine(new Rules(j.get("tree_patch_scale").getAsDouble(),
                j.get("tree_patch_amplitude").getAsDouble(),j.get("forest_boost").getAsDouble(),
                j.get("plant_patch_scale").getAsDouble(),j.get("wet_plant_multiplier").getAsDouble(),
                j.get("dry_plant_multiplier").getAsDouble(),j.get("bank_plant_chance").getAsDouble(),
                j.get("frostspire_treeline_above_sea").getAsInt(),
                j.get("tree_min_spacing").getAsInt()));
        }catch(RuntimeException ex){throw new IOException("Invalid placed feature data",ex);}
    }
    public static FeaturePlacementEngine loadBuiltInUnchecked(){
        try{return loadBuiltIn();}catch(IOException e){throw new IllegalStateException(e);}
    }
    private static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}
    public boolean validTree(BiomeProfile profile,WorldGenerator.Surface surface,int slope,int seaLevel){
        if(surface.waterBody()!=WorldGenerator.WaterBody.LAND||slope>profile.maxTreeSlope())return false;
        return surface.biome()!=Biome.FROSTSPIRE_HIGHLANDS||
            (slope<=2&&surface.surfaceY()<=seaLevel+rules.frostspireTreeLineAboveSea());
    }
    public boolean validPlant(BiomeProfile profile,WorldGenerator.Surface surface,int slope){
        return surface.waterBody()==WorldGenerator.WaterBody.LAND&&slope<=profile.maxPlantSlope();
    }
    public double treeChance(BiomeProfile profile,double global,double local,double cluster){
        double forest=profile.treeDensity()>.35?rules.treeForestBoost():1.0;
        return clamp(profile.treeDensity()*global*local*forest*(.88+rules.treePatchAmplitude()*cluster),0,.91);
    }
    public double plantChance(BiomeProfile profile,double global,double local,double rain){
        return clamp(profile.plantDensity()*global*local*(rain>.4?rules.wetPlantMultiplier():rules.dryPlantMultiplier()),0,.88);
    }
    public double treePatchScale(){return rules.treePatchScale();}
    public double plantPatchScale(){return rules.plantPatchScale();}
    public double bankChance(){return rules.bankChance();}
    public int treeMinSpacing(){return rules.treeMinSpacing();}
}
