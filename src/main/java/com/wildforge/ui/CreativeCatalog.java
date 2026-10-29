package com.wildforge.ui;

import com.wildforge.item.ItemType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Complete legacy-compatible item catalog. Grouping never changes item numeric IDs. */
public final class CreativeCatalog {
    public static final int COLS=9,ROWS=4,PAGE_SIZE=COLS*ROWS,CATEGORY_COUNT=6;
    public static final String[] TABS={"ALL","TERRAIN","WOOD","PLANTS","MATERIAL","TOOLS"};
    private CreativeCatalog() {}

    public static int category(ItemType item) {
        if(item==null)return 0;
        String n=item.name();
        if(isTool(n))return 5;
        if(n.contains("LOG")||n.contains("PLANK")||n.contains("WOOD")||n.contains("LEAVES")||n.contains("CRAFTING_TABLE"))return 2;
        if(n.contains("FLOWER")||n.contains("TUFT")||n.contains("CRYSTAL_CLUSTER")||n.contains("STALACTITE")||n.contains("STALAGMITE")||n.contains("ROOTS")||n.contains("SPORECAP")||n.contains("REED")||n.contains("SMALL_GRASS")||n.contains("FERN")||n.contains("SHRUB")||n.contains("MUSHROOM")||n.contains("BUSH")||n.contains("CACTUS")||n.contains("MOSS")||n.contains("LITTER")||n.contains("ROOT_MAT"))return 3;
        if(item.getPlacedBlock()==null)return 4;
        return 1;
    }
    public static boolean isTool(String name) {
        return name.endsWith("_AXE")||name.endsWith("_HOE")||name.endsWith("_SWORD")||
            name.endsWith("_SHOVEL")||name.endsWith("_PICKAXE");
    }
    public static List<ItemType> items(String search,int category) {
        String q=search==null?"":search.strip().toLowerCase(Locale.ROOT).replace(' ','_');
        List<ItemType> result=new ArrayList<>();
        for(ItemType item:ItemType.values()) {
            if(item.getId()<=0)continue;
            if(!item.name().toLowerCase(Locale.ROOT).contains(q) &&
                !item.getDisplayName().toLowerCase(Locale.ROOT).replace(' ','_').contains(q))continue;
            if(category>0 && category(item)!=category)continue;
            result.add(item);
        }
        result.sort(Comparator.comparingInt(CreativeCatalog::category)
            .thenComparing(ItemType::getDisplayName).thenComparingInt(ItemType::getId));
        return List.copyOf(result);
    }
    /** -2 previous, -3 next, -10..-15 tabs, -100..-108 hotbar, 0..35 catalog. */
    public static int hit(float x,float y,int w,int h) {
        float left=(w-568f)/2f,top=(h-456f)/2f;
        if(y>=top+43 && y<top+73) {
            for(int k=0;k<CATEGORY_COUNT;k++)
                if(x>=left+16+k*90 && x<left+16+k*90+87)return -10-k;
        }
        if(y>=top+320 && y<top+356) {
            if(x>=left+14 && x<left+145)return -2;
            if(x>=left+427 && x<left+557)return -3;
        }
        float hx=left+52,hy=top+377;
        if(y>=hy && y<hy+48 && x>=hx) {
            int col=(int)((x-hx)/52f);
            if(col>=0&&col<9&&x<hx+col*52+48)return -100-col;
        }
        float gx=left+52,gy=top+111;
        if(x<gx||y<gy)return -1;
        int col=(int)((x-gx)/52f),row=(int)((y-gy)/51f);
        if(row<0||row>=ROWS||col<0||col>=COLS)return -1;
        if(x>=gx+col*52+48||y>=gy+row*51+48)return -1;
        return row*COLS+col;
    }
}
