package com.damia.movrand;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Set;

/** Job-facing navigation through the bundled Baritone executor. */
public final class Pathing {
    public enum Nav { PLANNING, WALKING, ARRIVED, NO_ROUTE }

    private final NativeNavigation nativeNav;
    public String status = "";
    public double lastCost;
    public int lastNodes;

    public Pathing(Config cfg) { nativeNav = new NativeNavigation(cfg); }

    public void reset() {
        nativeNav.reset();
        status = "";
    }

    public void invalidate(String why) {
        nativeNav.reset();
        status = why;
    }

    public boolean walking() { return nativeNav.walking(); }
    public BlockPos breakingBlock() { return nativeNav.breakingBlock(); }
    public int step() { return nativeNav.walking() ? nativeNav.step : 0; }
    public int length() { return nativeNav.walking() ? nativeNav.length : 0; }
    public PathFinder.Kind currentKind() { return nativeNav.currentKind(); }

    public Nav tick(PathMove.Ctx ctx, Bot.Steer steer, BlockPos goalPos,
                    PathFinder.Goal goal, Set<Block> mayBreak) {
        return tick(ctx, steer, goalPos, goal, mayBreak, true);
    }

    public Nav tick(PathMove.Ctx ctx, Bot.Steer steer, BlockPos goalPos,
                    PathFinder.Goal goal, Set<Block> mayBreak, boolean edits) {
        Nav result = nativeNav.tick(ctx, steer, goalPos, goal, mayBreak, edits);
        status = nativeNav.status;
        lastCost = nativeNav.cost;
        lastNodes = nativeNav.nodes;
        return result;
    }

    public static void main(String[] args) {
        // Old profiles may still contain the removed toggle. They must load normally.
        Config cfg = new com.google.gson.Gson().fromJson("{\"baritoneNavigation\":false}", Config.class);
        cfg.clampAll();
        Pathing nav = new Pathing(cfg);
        assert !nav.walking() && nav.step() == 0 && nav.length() == 0;
        assert nav.breakingBlock() == null && nav.currentKind() == PathFinder.Kind.START;
        nav.invalidate("obstructed");
        assert !nav.walking() && nav.status.equals("obstructed");
        nav.reset();
        assert !nav.walking() && nav.status.isEmpty();
        assert !new com.google.gson.Gson().toJson(cfg).contains("baritoneNavigation");
        System.out.println("Pathing self-check passed");
    }
}
