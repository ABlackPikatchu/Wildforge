package com.wildforge.player;

/** Interpolation must not alter authoritative player camera coordinates. */
public final class CameraInterpolationSmokeTest {
    public static void main(String[] args) {
        Camera physics=new Camera(),render=new Camera();
        physics.setPosition(1f,2f,3f);
        physics.beginPhysicsStep();
        physics.setPosition(5f,6f,7f);
        physics.rotate(7f,-3f);
        physics.copyInterpolatedTo(render,.25f);
        check(Math.abs(render.getPosition().x-2f)<.0001,"one quarter of the way on X");
        check(Math.abs(render.getPosition().y-3f)<.0001,"one quarter of the way on Y");
        check(Math.abs(physics.getPosition().x-5f)<.0001,"authoritative camera remains unchanged");
        check(Math.abs(render.getYaw()-physics.getYaw())<.0001,"rotation uses latest mouse orientation");
        physics.snapInterpolation();
        physics.setPosition(30f,40f,50f);
        physics.copyInterpolatedTo(render,0f);
        check(Math.abs(render.getPosition().z-50f)<.0001,"teleport snaps without lerping");
        boolean rejected=false;
        try {physics.copyInterpolatedTo(physics,.3f);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"never write render coordinates into authoritative camera");
        System.out.println("Camera interpolation: physics isolation, alpha and teleport snap passed");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
