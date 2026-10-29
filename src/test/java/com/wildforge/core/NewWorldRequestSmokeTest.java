package com.wildforge.core;

import com.wildforge.player.GameMode;
public final class NewWorldRequestSmokeTest {
    public static void main(String[] args){
        var a=NewWorldRequest.fromDraft("1234","  Test Land  ",GameMode.CREATIVE,true,()->12L);
        if(a.seed()!=1234||!a.name().equals("Test Land")||!a.cheats())throw new AssertionError("Numeric seed/name");
        if(NewWorldRequest.fromDraft("horse","",GameMode.SURVIVAL,false,()->12L).seed()!=NewWorldRequest.textSeed("horse"))
            throw new AssertionError("Stable text seed");
        if(NewWorldRequest.fromDraft(" ","",GameMode.SURVIVAL,false,()->12L).seed()!=12L)
            throw new AssertionError("Random seed injection");
        System.out.println("Phase 4 world-creation request passed");
    }
}
