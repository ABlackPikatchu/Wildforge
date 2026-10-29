package com.wildforge.world;

import java.util.BitSet;

/** Headless async lifecycle regression for section meshing and LOD jobs. */
public final class ChunkMeshSchedulerSmokeTest {
    public static void main(String[] args) throws Exception {
        ChunkSource chunks=new ChunkSource();
        try(ChunkMeshScheduler scheduler=new ChunkMeshScheduler()){
            ChunkColumn chunk=new ChunkColumn(3,-2);
            long key=ChunkCoordinates.pack(3,-2);
            chunks.install(chunk);
            BitSet sections=new BitSet(TerrainMesh.SECTION_COUNT);sections.set(0);sections.set(31);
            scheduler.markDirty(key,sections,chunk);
            check(scheduler.pendingMeshSectionCount()==2,"tracks specific sections");
            scheduler.scheduleSections(chunks,3,-2,(c,s)->()->{
                float[][] result=new float[TerrainMesh.SECTION_COUNT*2][];
                for(int i=s.nextSetBit(0);i>=0;i=s.nextSetBit(i+1)){
                    result[i]=new float[]{i};result[i+TerrainMesh.SECTION_COUNT]=new float[]{i+100};
                }
                return result;
            });
            boolean ready=false;
            for(int attempt=0;attempt<100&&!ready;attempt++){
                ready=scheduler.installSections(chunks,1)>0;
                if(!ready)Thread.sleep(5);
            }
            check(ready,"worker result installed");
            check(chunk.render.terrain(0).length==1&&chunk.render.terrain(31).length==1,"selected sections updated");
            check(chunk.render.terrain(15).length==0,"untouched sections retained");
            scheduler.requestDistant(key,chunk,()->new ChunkMeshScheduler.LodMeshes(new float[]{12f},new float[]{13f}));
            boolean lod=false;
            for(int attempt=0;attempt<100&&!lod;attempt++){
                lod=scheduler.installDistant(chunks)>0;
                if(!lod)Thread.sleep(5);
            }
            check(lod&&chunk.render.hasDistant(),"distant worker installed");
            scheduler.markDirty(key,sections,chunk);
            check(!chunk.render.hasDistant(),"edited column invalidates far mesh");
            // Cancel an in-flight job after a second edit. Its old mesh must
            // never replace the new snapshot even if the old worker finishes.
            java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
            scheduler.scheduleSections(chunks,3,-2,(c,selected)->()->{
                started.countDown();
                try{release.await();}catch(InterruptedException ignored){}
                float[][] built=new float[TerrainMesh.SECTION_COUNT*2][];
                for(int i=selected.nextSetBit(0);i>=0;i=selected.nextSetBit(i+1)){
                    built[i]=new float[]{-1f};built[i+TerrainMesh.SECTION_COUNT]=new float[]{-1f};
                }
                return built;
            });
            check(started.await(2,java.util.concurrent.TimeUnit.SECONDS),"in-flight job started");
            scheduler.markDirty(key,sections,chunk);
            release.countDown();
            scheduler.scheduleSections(chunks,3,-2,(c,selected)->()->{
                float[][] built=new float[TerrainMesh.SECTION_COUNT*2][];
                for(int i=selected.nextSetBit(0);i>=0;i=selected.nextSetBit(i+1)){
                    built[i]=new float[]{42f};built[i+TerrainMesh.SECTION_COUNT]=new float[]{43f};
                }
                return built;
            });
            boolean fresh=false;
            for(int attempt=0;attempt<100&&!fresh;attempt++){
                scheduler.installSections(chunks,1);
                fresh=chunk.render.terrain(0).length>0&&chunk.render.terrain(0)[0]==42f;
                if(!fresh)Thread.sleep(5);
            }
            check(fresh,"canceled old mesh cannot overwrite newer edits");
            scheduler.evict(key);
            check(scheduler.pendingMeshCount()==0&&scheduler.pendingLodCount()==0,"eviction cancels jobs");
            scheduler.reset();
            check(scheduler.pendingMeshSectionCount()==0,"reset clears stale work");
        }finally{chunks.close();}
        System.out.println("Mesh scheduler: sections, LOD, invalidation, eviction and reset passed");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
