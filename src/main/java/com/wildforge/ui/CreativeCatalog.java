package com.wildforge.ui;

import com.wildforge.item.ItemType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure catalog and hit-testing shared by creative inventory UI and input handling. */
public final class CreativeCatalog {
    public static final int COLS=9,ROWS=4,PAGE_SIZE=COLS*ROWS;
    private CreativeCatalog() {}
    public static List<ItemType> items(String search,int category) {
        String q=search==null?"":search.strip().toLowerCase(Locale.ROOT);
        List<ItemType> found=new ArrayList<>();
        for(ItemType item:ItemType.values()) {
            if(item.getId()<=0)continue;
            if(!item.getDisplayName().toLowerCase(Locale.ROOT).contains(q) &&
                !item.name().toLowerCase(Locale.ROOT).contains(q))continue;
            boolean placeable=item.getPlacedBlock()!=null;
            String name=item.name();
            boolean tools=name.contains("AXE")||name.contains("PICKAXE")||
                name.contains("HOE")||name.contains("SWORD")||name.contains("SHOVEL");
            if(category==1 && !placeable)continue;
            if(category==2 && (placeable||tools))continue;
            if(category==3 && !tools)continue;
            found.add(item);
        }
        return List.copyOf(found);
    }
    /** Return grid cell 0..35, -2 previous, -3 next, -10..-13 category, -1 outside. */
    public static int hit(float x,float y,int w,int h) {
        float left=(w-568f)/2f, top=(h-360f)/2f;
        if(y>=top+43 && y<top+73) {
            for(int k=0;k<4;k++) if(x>=left+16+k*134 && x<left+16+(k+1)*134)return -10-k;
        }
        if(y>=top+320 && y<top+357) {
            if(x>=left+14 && x<left+145)return -2;
            if(x>=left+427 && x<left+557)return -3;
        }
        float gx=left+52,gy=top+111;
        if(x<gx||y<gy)return -1;
        int col=(int)((x-gx)/52f),row=(int)((y-gy)/51f);
        if(row<0||row>=ROWS||col<0||col>=COLS)return -1;
        if(x>=gx+col*52+48||y>=gy+row*51+48)return -1;
        return row*COLS+col;
    }
}
