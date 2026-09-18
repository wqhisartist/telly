package com.example.tellybridge;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class TellyBridgeAddon extends MeteorAddon {
    public static final Category TELLY_CATEGORY = new Category("Telly");

    @Override
    public void onInitialize() {
        Modules.get().add(new TellyBridgeModule());
    }

    @Override
    public String getPackage() {
        return "com.example.tellybridge";
    }

    public static class TellyBridgeModule extends Module {
        private final SettingGroup sgGeneral = settings.getDefaultGroup();
        private final SettingGroup sgMovement = settings.createGroup("移动设置");

        // 通用设置
        private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
                .name("放置距离")
                .description("方块放置最大距离")
                .defaultValue(4.2)
                .min(1.0)
                .max(6.0)
                .sliderRange(1,6)
                .build());

        private final Setting<Boolean> sneakOnly = sgGeneral.add(new BoolSetting.Builder()
                .name("仅潜行激活")
                .description("按住Shift才会搭路")
                .defaultValue(true)
                .build());

        private final Setting<Boolean> autoSelectBlock = sgGeneral.add(new BoolSetting.Builder()
                .name("自动切换方块")
                .description("背包自动寻找方块放到主手")
                .defaultValue(true)
                .build());

        private final Setting<Integer> tickDelay = sgGeneral.add(new IntSetting.Builder()
                .name("放置延迟(tick)")
                .description("多少游戏刻放置一次，防止高速刷屏")
                .defaultValue(2)
                .min(1)
                .max(10)
                .build());

        // WSD移动设置
        private final Setting<Boolean> wsdMode = sgMovement.add(new BoolSetting.Builder()
                .name("WSD模式")
                .description("原版Telly经典WSD侧移搭路逻辑")
                .defaultValue(true)
                .build());

        private final Setting<Double> moveSpeed = sgMovement.add(new DoubleSetting.Builder()
                .name("移动速度")
                .description("WSD侧移速度")
                .defaultValue(0.27)
                .min(0.1)
                .max(0.4)
                .build());

        private int tickCounter = 0;

        public TellyBridgeModule() {
            super(TELLY_CATEGORY, "TellyBridge-Full", "完整版Telly搭路（仅单人学习）");
        }

        @Override
        public void onActivate() {
            tickCounter = 0;
        }

        @Override
        public void onTick() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.world == null || !mc.player.isAlive()) return;
            PlayerEntity player = mc.player;

            if (sneakOnly.get() && !player.isSneaking()) return;

            tickCounter++;
            if (tickCounter < tickDelay.get()) return;
            tickCounter = 0;

            // 自动选中背包内方块
            if (autoSelectBlock.get()) {
                FindItemResult blockItem = InvUtils.findInHotbar(item -> item instanceof BlockItem);
                if (blockItem.found()) InvUtils.swap(blockItem.slot(), false);
            }

            if (!(player.getMainHandStack().getItem() instanceof BlockItem)) return;

            Vec3d eyePos = player.getEyePos();
            BlockPos targetPlacePos = BlockPos.ofFloored(eyePos.add(0, -1.2, 0));

            // 校验：目标位置必须是空气
            if (!mc.world.isAir(targetPlacePos)) return;

            // WSD 侧移逻辑
            if (wsdMode.get()) {
                Vec3d forward = player.getRotationVector();
                player.addVelocity(forward.x * moveSpeed.get(), 0, forward.z * moveSpeed.get());
                player.velocityModified = true;
            }

            BlockHitResult hitResult = new BlockHitResult(
                    eyePos.add(0, -1, 0),
                    Direction.UP,
                    targetPlacePos,
                    false
            );

            // 执行方块放置
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
        }
    }
}
