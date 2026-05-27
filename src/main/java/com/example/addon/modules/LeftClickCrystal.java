package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class LeftClickCrystal extends Module {

    private final SettingGroup sgGeneral      = this.settings.getDefaultGroup();
    private final SettingGroup sgPlacement    = this.settings.createGroup("Placement");
    private final SettingGroup sgCrystalSwitch = this.settings.createGroup("Crystal Switch");

    // ── General ────────────────────────────────────────────────────────────────

    private final Setting<Boolean> triggerObsidian = sgGeneral.add(new BoolSetting.Builder()
        .name("obsidian")
        .description("Place crystal by left-clicking obsidian.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> triggerBedrock = sgGeneral.add(new BoolSetting.Builder()
        .name("bedrock")
        .description("Place crystal by left-clicking bedrock.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> requireSurvival = sgGeneral.add(new BoolSetting.Builder()
        .name("require-survival")
        .description("Only activate in Survival or Adventure mode.")
        .defaultValue(true)
        .build()
    );

    // ── Placement ──────────────────────────────────────────────────────────────

    private final Setting<Boolean> onlyTopFace = sgPlacement.add(new BoolSetting.Builder()
        .name("only-top-face")
        .description("Only place when punching the top face of the block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgPlacement.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Swap back to your original slot after placing the crystal.")
        .defaultValue(true)
        .build()
    );

    // ── Crystal Switch ─────────────────────────────────────────────────────────
    // When enabled, if you are holding one of the listed items and left-click
    // obsidian/bedrock, the module will automatically switch to a crystal in your
    // hotbar before placing (instead of requiring you to already hold the crystal).

    private final Setting<Boolean> crystalSwitch = sgCrystalSwitch.add(new BoolSetting.Builder()
        .name("crystal-switch")
        .description("Automatically switch to an End Crystal when left-clicking obsidian/bedrock while holding a listed item.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> csCrystal = sgCrystalSwitch.add(new BoolSetting.Builder()
        .name("crystal")
        .description("Switch to crystal when already holding a crystal (useful for swap-back combos).")
        .defaultValue(true)
        .visible(crystalSwitch::get)
        .build()
    );

    private final Setting<Boolean> csSword = sgCrystalSwitch.add(new BoolSetting.Builder()
        .name("sword")
        .description("Switch to crystal when holding a sword.")
        .defaultValue(true)
        .visible(crystalSwitch::get)
        .build()
    );

    private final Setting<Boolean> csObsidian = sgCrystalSwitch.add(new BoolSetting.Builder()
        .name("obsidian")
        .description("Switch to crystal when holding obsidian.")
        .defaultValue(true)
        .visible(crystalSwitch::get)
        .build()
    );

    private final Setting<Boolean> csTotem = sgCrystalSwitch.add(new BoolSetting.Builder()
        .name("totem")
        .description("Switch to crystal when holding a Totem of Undying.")
        .defaultValue(true)
        .visible(crystalSwitch::get)
        .build()
    );

    private final Setting<Boolean> csGlowstone = sgCrystalSwitch.add(new BoolSetting.Builder()
        .name("glowstone")
        .description("Switch to crystal when holding glowstone.")
        .defaultValue(true)
        .visible(crystalSwitch::get)
        .build()
    );

    private final Setting<Boolean> csAnchor = sgCrystalSwitch.add(new BoolSetting.Builder()
        .name("respawn-anchor")
        .description("Switch to crystal when holding a Respawn Anchor.")
        .defaultValue(true)
        .visible(crystalSwitch::get)
        .build()
    );

    // ──────────────────────────────────────────────────────────────────────────

    public LeftClickCrystal() {
        super(AddonTemplate.CATEGORY, "left-click-crystal",
            "Left-click obsidian or bedrock while holding an End Crystal to place it. " +
            "If a crystal already exists on the block, breaks it instead.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onStartBreaking(StartBreakingBlockEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        // Game mode guard
        if (requireSurvival.get()) {
            GameType gm = mc.gameMode.getPlayerMode();
            if (gm != GameType.SURVIVAL && gm != GameType.ADVENTURE) return;
        }

        // Face direction guard
        if (onlyTopFace.get() && event.direction != Direction.UP) return;

        BlockPos hitPos = event.blockPos;
        var hitBlock = mc.level.getBlockState(hitPos).getBlock();

        // Must be an enabled trigger block
        boolean isObsidian = hitBlock == Blocks.OBSIDIAN && triggerObsidian.get();
        boolean isBedrock  = hitBlock == Blocks.BEDROCK  && triggerBedrock.get();
        if (!isObsidian && !isBedrock) return;

        ItemStack held = mc.player.getMainHandItem();

        // ── Determine whether we should act, and which slot to use ─────────────
        // Case A: already holding a crystal — always triggers.
        // Case B: crystal-switch is on and held item is a listed trigger — find a
        //         crystal in the hotbar and switch to it before placing.
        int crystalSlot;

        if (held.getItem() == Items.END_CRYSTAL) {
            // Already holding the crystal — use current slot directly, no swap needed.
            crystalSlot = mc.player.getInventory().getSelectedSlot();
        } else if (crystalSwitch.get() && isCrystalSwitchTrigger(held)) {
            // Crystal switch — find a crystal elsewhere in the hotbar.
            FindItemResult found = InvUtils.findInHotbar(Items.END_CRYSTAL);
            if (!found.found()) return;
            crystalSlot = found.slot();
        } else {
            // Held item doesn't qualify under any active setting.
            return;
        }

        // ── Crystal already on this block? Let the punch through to break it ───
        BlockPos abovePos = hitPos.above();
        AABB searchBox = new AABB(abovePos).inflate(0.5);
        List<EndCrystal> crystals = mc.level.getEntitiesOfClass(EndCrystal.class, searchBox);
        if (!crystals.isEmpty()) {
            // Don't cancel — normal swing will attack the crystal entity.
            return;
        }

        // ── No crystal here — place one ────────────────────────────────────────
        if (!mc.level.getBlockState(abovePos).isAir()) return;

        int originalSlot = mc.player.getInventory().getSelectedSlot();
        boolean needsSwap = crystalSlot != originalSlot;

        // Swap via InvUtils — keeps sequence numbers valid (avoids BadPacketsA/G).
        if (needsSwap) {
            InvUtils.swap(crystalSlot, false);
        }

        // Place via mc.gameMode.useItemOn() — auto-increments sequence number,
        // avoiding Grim BadPacketsH ("expected N, id=0").
        BlockHitResult hitResult = new BlockHitResult(
            Vec3.atCenterOf(hitPos).add(0, 0.5, 0),
            Direction.UP,
            hitPos,
            false
        );
        InteractionResult result = mc.gameMode.useItemOn(
            mc.player,
            InteractionHand.MAIN_HAND,
            hitResult
        );

        // Swap back
        if (swapBack.get() && needsSwap) {
            InvUtils.swap(originalSlot, false);
        }

        // Cancel the punch only if placement was accepted
        if (result != InteractionResult.PASS && result != InteractionResult.FAIL) {
            event.cancel();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Returns true if the held item qualifies as a crystal-switch trigger. */
    private boolean isCrystalSwitchTrigger(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();

        if (csCrystal.get()    && item == Items.END_CRYSTAL)      return true;
        if (csSword.get()      && stack.is(ItemTags.SWORDS))      return true;
        if (csObsidian.get()   && item == Items.OBSIDIAN)         return true;
        if (csTotem.get()      && item == Items.TOTEM_OF_UNDYING) return true;
        if (csGlowstone.get()  && item == Items.GLOWSTONE)        return true;
        if (csAnchor.get()     && item == Items.RESPAWN_ANCHOR)   return true;

        return false;
    }
}