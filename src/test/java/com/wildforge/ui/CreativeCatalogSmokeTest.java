package com.wildforge.ui;

import com.wildforge.item.ItemType;
import com.wildforge.core.DeveloperCommands;
import java.util.HashSet;

/** Tests catalog coverage, category exclusivity, hotbar geometry and command completion. */
public final class CreativeCatalogSmokeTest {
    public static void main(String[] args) {
        var all=CreativeCatalog.items("",0);
        require(all.size()==ItemType.values().length,"Creative catalog omitted registered items");
        var ids=new HashSet<Integer>();
        for(var item:all)require(ids.add(item.getId()),"Duplicate item numeric id");
        int total=0;
        for(int c=1;c<CreativeCatalog.CATEGORY_COUNT;c++) total+=CreativeCatalog.items("",c).size();
        require(total==all.size(),"Category tab omitted or duplicated an item");
        require(CreativeCatalog.items("diamond sword",0).contains(ItemType.DIAMOND_SWORD),"Search by display name");
        require(CreativeCatalog.items("gold_ingot",0).contains(ItemType.GOLD_INGOT),"Search by ID");
        require(CreativeCatalog.hit(52,377,568,456)==-100,"Hotbar first slot hitbox");
        require(CreativeCatalog.hit(468,377,568,456)==-108,"Hotbar last slot hitbox");
        require(DeveloperCommands.suggestions("/gamemode c",true).contains("/gamemode creative"),"Gamemode completion");
        require(!DeveloperCommands.suggestions("/g",false).contains("/gamemode"),"Cheat permission completion");
        require(DeveloperCommands.suggestions("/",false).contains("/help"),"Slash completion");
        System.out.println("Creative catalog and command completion OK: "+all.size()+" items");
    }
    private static void require(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
}
