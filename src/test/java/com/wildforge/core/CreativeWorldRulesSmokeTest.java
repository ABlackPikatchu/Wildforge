package com.wildforge.core;

import com.wildforge.item.ItemType;
import com.wildforge.ui.CreativeCatalog;
import java.nio.file.Files;

public final class CreativeWorldRulesSmokeTest {
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        var dir=Files.createTempDirectory("wf-creative-rules");
        try {
            SaveManager saves=new SaveManager(dir);
            check(!saves.cheatsEnabled(0), "older worlds do not grant cheats");
            saves.saveCheatsEnabled(0,true);
            check(saves.cheatsEnabled(0), "cheats true persisted");
            saves.saveCheatsEnabled(0,false);
            check(!saves.cheatsEnabled(0), "cheats false persisted");
            var all=CreativeCatalog.items("",0);
            check(all.size()==ItemType.values().length, "catalog includes every item");
            check(CreativeCatalog.items("stone",0).size()>0,"search finds blocks");
            check(CreativeCatalog.items("",1).stream().allMatch(i->i.getPlacedBlock()!=null),"blocks tab");
            check(CreativeCatalog.hit(52+109,111+109,668,360)>=0,"grid click targets");
        } finally {
            try (var files=Files.list(dir)) {
                for(var f:files.toList())Files.deleteIfExists(f);
            }
            Files.deleteIfExists(dir);
        }
        System.out.println("Creative catalog and per-world permissions passed");
    }
}
