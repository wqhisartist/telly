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
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class AutoDoubleHand extends Module {

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgCrystal = this.settings.createGroup("Crystal");
    private final SettingGroup sgAnchor  = this.settings.createGroup("Anchor");

    // ── Crystal settings ───────────────────────────────────────────────────────

    private final Setting<Boolean> onCrystal = sgCrystal.add(new BoolSetting.Builder()
        .name("on-crystal")
        .description("Switch to totem when at the same Y level or above a nearby End Crystal.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> crystalRange = sgCrystal.add(new DoubleSetting.Builder()
        .name("crystal-range")
        .description("Horizontal range in blocks to check for End Crystals.")
        .defaultValue(5.0)
        .min(1.0)
        .sliderMax(20.0)
        .visible(onCrystal::get)
        .build()
    );

    // ── Anchor settings ────────────────────────────────────────────────────────

    private final Setting<Boolean> onAnchor = sgAnchor.add(new BoolSetting.Builder()
        .name("on-anchor")
        .description("Switch to totem when within range of a charged Respawn Anchor.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> anchorRange = sgAnchor.add(new DoubleSetting.Builder()
        .name("anchor-range")
        .description("Horizontal range in blocks to check for charged Respawn Anchors.")
        .defaultValue(5.0)
        .min(1.0)
        .sliderMax(20.0)
        .visible(onAnchor::get)
        .build()
    );

    // ── General ────────────────────────────────────────────────────────────────

    private final Setting<Boolean> requireSurvival = sgGeneral.add(new BoolSetting.Builder()
        .name("require-survival")
        .description("Only activate in Survival or Adventure mode.")
        .defaultValue(true)
        .build()
    );

    // ── State ──────────────────────────────────────────────────────────────────

    // The slot we were on before the module switched to totem
    private int originalSlot = -1;
    // Whether we are currently holding totem due to this module
    private boolean holding = false;

    public AutoDoubleHand() {
        super(AddonTemplate.CATEGORY, "auto-double-hand",
            "Automatically switches to a totem of undying when near an End Crystal or charged Respawn Anchor.");
    }

    @Override
    public void onDeactivate() {
        // Restore original slot on disable if we were holding totem
        if (holding && originalSlot != -1) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) InvUtils.swap(originalSlot, false);
        }
        holding      = false;
        originalSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.screen != null) return;

        if (requireSurvival.get()) {
            GameType gm = mc.gameMode.getPlayerMode();
            if (gm != GameType.SURVIVAL && gm != GameType.ADVENTURE) return;
        }

        boolean shouldHoldTotem = shouldHoldTotem(mc);

        if (shouldHoldTotem && !holding) {
            // If there is already a totem in the offhand, the player is protected
            // regardless of what is in the main hand — but we still swap the main
            // hand to a totem so both hands have one (double-hand).
            // Only skip if main hand is already a totem.
            if (mc.player.getMainHandItem().getItem() == Items.TOTEM_OF_UNDYING) {
                // Main hand already has totem — track without swapping
                holding      = true;
                originalSlot = mc.player.getInventory().getSelectedSlot();
                return;
            }

            // Search only hotbar slots 0-8 for a totem.
            // We explicitly skip the offhand so that even if the offhand already
            // has a totem, we find a second one in the hotbar to double-hand with.
            FindItemResult totem = InvUtils.findInHotbar(
                s -> s.getItem() == Items.TOTEM_OF_UNDYING
            );
            if (!totem.found()) return; // No totem in hotbar slots 0-8

            originalSlot = mc.player.getInventory().getSelectedSlot();
            InvUtils.swap(totem.slot(), false);
            holding = true;

        } else if (!shouldHoldTotem && holding) {
            // Danger has passed — restore original slot
            if (originalSlot != -1) {
                InvUtils.swap(originalSlot, false);
            }
            holding      = false;
            originalSlot = -1;
        }
    }

    // ── Logic ──────────────────────────────────────────────────────────────────

    private boolean shouldHoldTotem(Minecraft mc) {
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        // ── Crystal check ──────────────────────────────────────────────────────
        if (onCrystal.get()) {
            double r = crystalRange.get();
            // Search for End Crystal entities in a flat cylinder:
            // horizontal radius = r, vertical = unbounded downward from player Y
            // (crystal must be at same Y or below player — i.e. player Y >= crystal Y)
            AABB searchBox = new AABB(px - r, -64, pz - r, px + r, py + 256, pz + r);
            List<EndCrystal> crystals = mc.level.getEntitiesOfClass(EndCrystal.class, searchBox);

            for (EndCrystal crystal : crystals) {
                double dx = crystal.getX() - px;
                double dz = crystal.getZ() - pz;
                double horizDist = Math.sqrt(dx * dx + dz * dz);

                // Horizontal range check + player must be at same Y or above crystal
                if (horizDist <= r && py >= crystal.getY()) {
                    return true;
                }
            }
        }

        // ── Anchor check ───────────────────────────────────────────────────────
        if (onAnchor.get()) {
            double r = anchorRange.get();
            // Scan block positions in a flat cylinder around the player
            int ri   = (int) Math.ceil(r);
            int minX = (int) Math.floor(px - r);
            int maxX = (int) Math.floor(px + r);
            int minY = (int) Math.floor(py) - 2; // check a couple blocks below feet
            int maxY = (int) Math.floor(py) + 2;
            int minZ = (int) Math.floor(pz - r);
            int maxZ = (int) Math.floor(pz + r);

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        double dx = (x + 0.5) - px;
                        double dz = (z + 0.5) - pz;
                        double horizDist = Math.sqrt(dx * dx + dz * dz);
                        if (horizDist > r) continue;

                        BlockPos pos   = new BlockPos(x, y, z);
                        var      state = mc.level.getBlockState(pos);

                        if (state.is(Blocks.RESPAWN_ANCHOR)
                                && state.getValue(RespawnAnchorBlock.CHARGE) > 0) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }
}