package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashSet;
import java.util.Set;

public class AutoCrystalMacro extends Module {

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> activateKey = sgGeneral.add(new IntSetting.Builder()
        .name("activate-key")
        .description("Key that activates crystalling. -1 = always active.")
        .defaultValue(1) // 1 = left mouse button (GLFW_MOUSE_BUTTON_LEFT)
        .min(-1)
        .max(400)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks to wait between placing crystals.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> breakDelay = sgGeneral.add(new IntSetting.Builder()
        .name("break-delay")
        .description("Ticks to wait between breaking crystals.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> stopOnKill = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-on-kill")
        .description("Pauses the macro for 5 seconds when a nearby player dies.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> placeObsidianIfMissing = sgGeneral.add(new BoolSetting.Builder()
        .name("place-obsidian-if-missing")
        .description("Places obsidian if the target block isn't obsidian or bedrock.")
        .defaultValue(true)
        .build()
    );

    // ── State ──────────────────────────────────────────────────────────────────

    private int  placeDelayCounter = 0;
    private int  breakDelayCounter = 0;
    private boolean paused         = false;
    private long    resumeTime     = 0;
    private final Set<Player> deadPlayers = new HashSet<>();

    public AutoCrystalMacro() {
        super(AddonTemplate.CATEGORY, "auto-crystal-macro",
            "Automatically places and breaks End Crystals for crystal PvP.");
    }

    @Override
    public void onActivate() {
        placeDelayCounter = 0;
        breakDelayCounter = 0;
        paused            = false;
        resumeTime        = 0;
        deadPlayers.clear();
    }

    @Override
    public void onDeactivate() {
        placeDelayCounter = 0;
        breakDelayCounter = 0;
        paused            = false;
        resumeTime        = 0;
        deadPlayers.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        // Tick counters every tick regardless
        if (placeDelayCounter > 0) placeDelayCounter--;
        if (breakDelayCounter > 0) breakDelayCounter--;

        // Resume after stop-on-kill pause
        if (paused && System.currentTimeMillis() >= resumeTime) {
            paused = false;
        }
        if (paused) return;

        if (!isKeyActive()) return;
        if (mc.player.isUsingItem()) return;

        // Must be holding an End Crystal in main hand
        if (mc.player.getMainHandItem().getItem() != Items.END_CRYSTAL) return;

        // Stop-on-kill check
        if (stopOnKill.get() && checkForDeadPlayers()) {
            paused     = true;
            resumeTime = System.currentTimeMillis() + 5000;
            return;
        }

        // Dispatch on crosshair target
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
            handleBlockInteraction(mc, blockHit);
        } else if (hit instanceof EntityHitResult entityHit) {
            handleEntityInteraction(mc, entityHit);
        }
    }

    // ── Block interaction ──────────────────────────────────────────────────────

    private void handleBlockInteraction(Minecraft mc, BlockHitResult blockHit) {
        if (placeDelayCounter > 0) return;

        BlockPos pos   = blockHit.getBlockPos();
        boolean isBase = mc.level.getBlockState(pos).getBlock() == Blocks.OBSIDIAN
                      || mc.level.getBlockState(pos).getBlock() == Blocks.BEDROCK;

        if (!isBase && placeObsidianIfMissing.get()) {
            FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
            FindItemResult crystal  = InvUtils.findInHotbar(Items.END_CRYSTAL);
            if (!obsidian.found() || !crystal.found()) return;

            InvUtils.swap(obsidian.slot(), false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, blockHit);
            mc.player.swing(InteractionHand.MAIN_HAND);
            placeDelayCounter = placeDelay.get();

            InvUtils.swap(crystal.slot(), false);
            return;
        }

        if (isBase && isValidCrystalPlacement(mc, pos)) {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, blockHit);
            placeDelayCounter = placeDelay.get();
        }
    }

    // ── Entity interaction ─────────────────────────────────────────────────────

    private void handleEntityInteraction(Minecraft mc, EntityHitResult entityHit) {
        if (breakDelayCounter > 0) return;
        if (!(entityHit.getEntity() instanceof EndCrystal crystal)) return;

        mc.gameMode.attack(mc.player, crystal);
        mc.player.swing(InteractionHand.MAIN_HAND);
        breakDelayCounter = breakDelay.get();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isKeyActive() {
        int key = activateKey.get();
        if (key == -1) return true;
        // Use GLFW's current context handle — works without needing Window internals
        long handle = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        if (key <= 2) {
            return org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, key)
                == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        return org.lwjgl.glfw.GLFW.glfwGetKey(handle, key)
            == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    private boolean isValidCrystalPlacement(Minecraft mc, BlockPos pos) {
        BlockPos above = pos.above();
        if (!mc.level.getBlockState(above).isAir()) return false;
        double x = above.getX(), y = above.getY(), z = above.getZ();
        return mc.level.getEntitiesOfClass(
            net.minecraft.world.entity.Entity.class,
            new AABB(x, y, z, x + 1.0, y + 2.0, z + 1.0)
        ).isEmpty();
    }

    private boolean checkForDeadPlayers() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            if ((player.isDeadOrDying() || player.getHealth() <= 0)
                    && !deadPlayers.contains(player)) {
                deadPlayers.add(player);
                return true;
            }
        }
        deadPlayers.removeIf(p -> !p.isDeadOrDying() && p.getHealth() > 0);
        return false;
    }
}