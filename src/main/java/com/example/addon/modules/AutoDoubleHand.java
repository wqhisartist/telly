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

    // Whether this module has already swapped to totem this danger window
    private boolean swapped = false;

    public AutoDoubleHand() {
        super(AddonTemplate.CATEGORY, "auto-double-hand",
            "Automatically switches main hand to a totem of undying when near an End Crystal or charged Respawn Anchor. Never switches back automatically.");
    }

    @Override
    public void onDeactivate() {
        swapped = false;
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

        boolean inDanger = shouldHoldTotem(mc);

        if (inDanger) {
            if (swapped) return;

            // If we are already holding a totem in our main hand, we are successfully double-handing
            if (mc.player.getMainHandItem().getItem() == Items.TOTEM_OF_UNDYING) {
                swapped = true;
                return;
            }

            // Scan the entire inventory for a totem, but explicitly ignore the one currently sitting in the offhand
            FindItemResult totem = InvUtils.find(s -> s.getItem() == Items.TOTEM_OF_UNDYING && s != mc.player.getOffhandItem());
            if (!totem.found()) return;

            if (totem.isHotbar()) {
                // If a backup totem is already on the hotbar, simply switch to its slot
                if (mc.player.getInventory().getSelectedSlot() != totem.slot()) {
                    InvUtils.swap(totem.slot(), false);
                }
            } else {
                // If the backup totem is in the main inventory, pull it straight into our active hotbar slot
                InvUtils.move().from(totem.slot()).toHotbar(mc.player.getInventory().getSelectedSlot());
            }
            
            swapped = true;

        } else {
            // Out of danger — reset the flag so the next time danger begins
            // we swap again. We deliberately do NOT swap back to any previous
            // slot — the player keeps whatever they are holding.
            swapped = false;
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
            AABB searchBox = new AABB(px - r, -64, pz - r, px + r, py + 256, pz + r);
            List<EndCrystal> crystals = mc.level.getEntitiesOfClass(EndCrystal.class, searchBox);

            for (EndCrystal crystal : crystals) {
                double dx = crystal.getX() - px;
                double dz = crystal.getZ() - pz;
                double horizDist = Math.sqrt(dx * dx + dz * dz);
                if (horizDist <= r && py >= crystal.getY()) {
                    return true;
                }
            }
        }

        // ── Anchor check ───────────────────────────────────────────────────────
        if (onAnchor.get()) {
            double r = anchorRange.get();
            int minX = (int) Math.floor(px - r);
            int maxX = (int) Math.floor(px + r);
            int minY = (int) Math.floor(py) - 2;
            int maxY = (int) Math.floor(py) + 2;
            int minZ = (int) Math.floor(pz - r);
            int maxZ = (int) Math.floor(pz + r);

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        double dx = (x + 0.5) - px;
                        double dz = (z + 0.5) - pz;
                        if (Math.sqrt(dx * dx + dz * dz) > r) continue;

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