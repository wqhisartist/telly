package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Random;

public class WeaponSwap extends Module {

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgDelays  = this.settings.createGroup("Delays");

    public enum WeaponType {
        Sword,
        Axe,
        Mace
    }

    // ── Settings ───────────────────────────────────────────────────────────────

    private final Setting<WeaponType> targetWeapon = sgGeneral.add(new EnumSetting.Builder<WeaponType>()
        .name("target-weapon")
        .description("The weapon type to auto-swap to when aiming at a player.")
        .defaultValue(WeaponType.Sword)
        .build()
    );

    private final Setting<Integer> delayMinMs = sgDelays.add(new IntSetting.Builder()
        .name("delay-min-ms")
        .description("Minimum random reaction delay in milliseconds before swapping.")
        .defaultValue(10)
        .min(0)
        .sliderMax(300)
        .build()
    );

    private final Setting<Integer> delayMaxMs = sgDelays.add(new IntSetting.Builder()
        .name("delay-max-ms")
        .description("Maximum random reaction delay in milliseconds before swapping.")
        .defaultValue(50)
        .min(0)
        .sliderMax(300)
        .build()
    );

    private final Random rng = new Random();
    private long swapExecutionTimestamp = -1;
    private int queuedSlot = -1;

    public WeaponSwap() {
        super(AddonTemplate.CATEGORY, "weapon-swap", 
            "Automatically switches to your preferred weapon when aiming at a player.");
    }

    @Override
    public void onDeactivate() {
        resetQueue();
    }

    private void resetQueue() {
        swapExecutionTimestamp = -1;
        queuedSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;

        // 1. Process Pending Scheduled Swap
        if (swapExecutionTimestamp > 0) {
            if (System.currentTimeMillis() >= swapExecutionTimestamp) {
                if (queuedSlot != -1) {
                    InvUtils.swap(queuedSlot, false);
                }
                resetQueue();
            }
            return;
        }

        // 2. Crosshair Targeting Evaluation
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) return;
        
        EntityHitResult entityHit = (EntityHitResult) hit;
        if (!(entityHit.getEntity() instanceof Player)) return;

        // 3. Verify Current Hand State (Using tags & direct matching like your working module)
        var currentStack = mc.player.getMainHandItem();
        boolean alreadyHolding = switch (targetWeapon.get()) {
            case Sword -> currentStack.is(ItemTags.SWORDS);
            case Axe   -> currentStack.is(ItemTags.AXES);
            case Mace  -> currentStack.is(Items.MACE);
        };
        
        if (alreadyHolding) return;

        // 4. Locate the Target Weapon in the Hotbar
        FindItemResult weapon = InvUtils.findInHotbar(itemStack -> switch (targetWeapon.get()) {
            case Sword -> itemStack.is(ItemTags.SWORDS);
            case Axe   -> itemStack.is(ItemTags.AXES);
            case Mace  -> itemStack.is(Items.MACE);
        });

        // 5. Schedule the Swap
        if (weapon.found()) {
            queuedSlot = weapon.slot();
            swapExecutionTimestamp = System.currentTimeMillis() + getRandomDelayMs();
        }
    }

    private int getRandomDelayMs() {
        int lo = Math.min(delayMinMs.get(), delayMaxMs.get());
        int hi = Math.max(delayMinMs.get(), delayMaxMs.get());
        return lo == hi ? lo : lo + rng.nextInt(hi - lo + 1);
    }
}