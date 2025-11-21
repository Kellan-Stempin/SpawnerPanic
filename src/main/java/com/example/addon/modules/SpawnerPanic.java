package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.systems.friends.Friends;

import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

import static com.example.addon.SpawnerPanicAddon.SPAWNER_CATEGORY;

public class SpawnerPanic extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Do not panice when only Meteor friends are nearby.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugAlwaysPanic = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-always-panic")
        .description("Always run panic logic, even if no players are nearby (for testing).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> playerDetectRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("player-detect-radius")
        .description("Radius to detect other players for panic.")
        .defaultValue(100.0)
        .min(8.0)
        .sliderMax(256.0)
        .build()
    );

    private final Setting<Integer> spawnerScanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("spawner-scan-radius")
        .description("Radius around you to scan for spawners.")
        .defaultValue(8)
        .min(3)
        .sliderMax(32)
        .build()
    );

    private final Setting<Boolean> requireSilkTouch = sgGeneral.add(new BoolSetting.Builder()
        .name("require-silk-touch")
        .description("Only break spawners if holding a Silk Touch pickaxe.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoSneak = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-sneak-breaking")
        .description("Hold sneak while breaking spawners (for stacked spawners).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useExistingEchest = sgGeneral.add(new BoolSetting.Builder()
        .name("use-existing-echest")
        .description("Use an existing ender chest nearby if one is within reach.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoPlaceEchest = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-place-echest")
        .description("Place an ender chest from your hotbar if no nearby echest exists.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ensurePickup = sgGeneral.add(new BoolSetting.Builder()
        .name("ensure-pickup")
        .description("Only move on after all spawner drops are picked up and inventory has space.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> pickupWaitTicks = sgGeneral.add(new IntSetting.Builder()
        .name("pickup-wait-ticks")
        .description("How many ticks to wait watching for dropped spawners before moving to the next.")
        .defaultValue(20)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> spawnersPerTrigger = sgGeneral.add(new IntSetting.Builder()
        .name("spawners-per-trigger")
        .description("How many spawners to collect (since activation / last stash) before auto-stashing in an echest. Set to 0 to disable.")
        .defaultValue(500)
        .min(0)
        .sliderMax(2048)
        .build()
    );

    private final Set<BlockPos> cachedSpawners = new HashSet<>();
    private BlockPos currentTarget = null;
    private int pickupWaitCounter = 0;

    private boolean warnedNoSilk = false;
    private boolean warnedInvFull = false;
    private boolean warnedNoEchest = false;

    private boolean forcingSneak = false;
    private boolean movingToTarget = false;

    private int baseSpawnerCount = 0;

    private static final double BREAK_RANGE = 4.5;

    public SpawnerPanic() {
        super(SPAWNER_CATEGORY, "spawner-panic",
            "Panic-breaks stacked spawners with Silk Touch & stashes them into echests.");
    }

    @Override
    public void onActivate() {
        cachedSpawners.clear();
        currentTarget = null;
        pickupWaitCounter = 0;

        warnedNoSilk = false;
        warnedInvFull = false;
        warnedNoEchest = false;
        forcingSneak = false;
        movingToTarget = false;

        baseSpawnerCount = getSpawnerCountInInventory();
    }

    @Override
    public void onDeactivate() {
        cachedSpawners.clear();
        currentTarget = null;
        pickupWaitCounter = 0;

        releaseSneakIfForced();
        stopMovingToTarget();
    }

    //tick loop
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        boolean panic = debugAlwaysPanic.get() || isOtherPlayerClose(playerDetectRadius.get());

        if (!panic) {
            passiveScanSpawners();
            releaseSneakIfForced();
            stopMovingToTarget();
            return;
        }

        if (requireSilkTouch.get() && !isHoldingSilkTouchPick()) {
            if (!warnedNoSilk) {
                sendChat("[SpawnerPanic] Not holding Silk Touch pickaxe, not breaking spawners.");
                warnedNoSilk = true;
            }
            stopMovingToTarget();
            releaseSneakIfForced();
            return;
        } else {
            warnedNoSilk = false;
        }

        if (ensurePickup.get()) {
            if (isInventoryFull()) {
                if (!warnedInvFull) {
                    sendChat("[SpawnerPanic] Inventory full, cannot safely pick up spawners.");
                    warnedInvFull = true;
                }
                stopMovingToTarget();
                releaseSneakIfForced();
                return;
            } else {
                warnedInvFull = false;
            }

            if (pickupWaitCounter > 0) {
                if (anySpawnerItemsOnGround()) {
                    pickupWaitCounter--;
                    return;
                } else {
                    pickupWaitCounter = 0;
                }
            }
        }

        if (shouldTriggerStackStash()) {
            releaseSneakIfForced();
            stopMovingToTarget();
            runStashLogic();
            return;
        }

        updateCurrentTarget();

        if (currentTarget != null) {
            if (!isInBreakRange(currentTarget)) {
                moveTowards(currentTarget);
                return;
            } else {
                stopMovingToTarget();
            }

            breakStackedSpawner(currentTarget);

            if (ensurePickup.get()) {
                pickupWaitCounter = pickupWaitTicks.get();
            }

            return;
        }

        releaseSneakIfForced();
        stopMovingToTarget();
        runStashLogic();
    }

    //helpers
    private void releaseSneakIfForced() {
        if (forcingSneak && mc.options != null && mc.options.sneakKey != null) {
            mc.options.sneakKey.setPressed(false);
            forcingSneak = false;
        }
    }

    private boolean isOtherPlayerClose(double radius) {
        double r2 = radius * radius;

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (p.isSpectator()) continue;

            if (ignoreFriends.get() && Friends.get().isFriend(p)) continue;

            if (p.squaredDistanceTo(mc.player) <= r2) {
                return true;
            }
        }
        return false;
    }

    private void passiveScanSpawners() {
        if (mc.player == null) return;
        scanSpawnersAroundPlayer();
    }

    private void scanSpawnersAroundPlayer() {
        BlockPos center = mc.player.getBlockPos();
        int r = spawnerScanRadius.get();

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = center.add(x, y, z);
                    if (isSpawner(pos)) {
                        cachedSpawners.add(pos.toImmutable());
                    }
                }
            }
        }
    }

    private boolean isSpawner(BlockPos pos) {
        return mc.world.getBlockState(pos).getBlock() == Blocks.SPAWNER;
    }

    private void updateCurrentTarget() {
        if (currentTarget != null && !isSpawner(currentTarget)) {
            cachedSpawners.remove(currentTarget);
            currentTarget = null;
        }

        if (cachedSpawners.isEmpty()) {
            scanSpawnersAroundPlayer();
        } else {
            cachedSpawners.removeIf(pos -> !isSpawner(pos));
        }

        if (!cachedSpawners.isEmpty()) {
            BlockPos playerPos = mc.player.getBlockPos();
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;

            for (BlockPos pos : cachedSpawners) {
                if (!isSpawner(pos)) continue;
                double d = pos.getSquaredDistance(playerPos);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos;
                }
            }

            currentTarget = best;
        } else {
            currentTarget = null;
        }
    }

    private boolean isHoldingSilkTouchPick() {
        ItemStack stack = mc.player.getMainHandStack();
        if (stack.isEmpty()) return false;

        if (!stack.isIn(ItemTags.PICKAXES)) return false;

        String enchString = stack.getEnchantments().toString().toLowerCase();
        return enchString.contains("silk") && enchString.contains("touch");
    }

    private boolean isInventoryFull() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private boolean hasSpawnerInInventory() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == Items.SPAWNER) return true;
        }
        return false;
    }

    private int getSpawnerCountInInventory() {
        if (mc.player == null) return 0;

        int count = 0;
        var inv = mc.player.getInventory();

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == Items.SPAWNER) {
                count += stack.getCount();
            }
        }

        return count;
    }

    private boolean shouldTriggerStackStash() {
        int threshold = spawnersPerTrigger.get();
        if (threshold <= 0) return false; // disabled

        int current = getSpawnerCountInInventory();
        return current - baseSpawnerCount >= threshold;
    }

    private boolean anySpawnerItemsOnGround() {
        Box box = mc.player.getBoundingBox().expand(4.0);

        for (ItemEntity item : mc.world.getEntitiesByClass(ItemEntity.class, box, e -> true)) {
            if (item.getStack().getItem() == Items.SPAWNER) {
                return true;
            }
        }
        return false;
    }

    private boolean isInBreakRange(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        return eye.squaredDistanceTo(target) <= BREAK_RANGE * BREAK_RANGE;
    }

    private void moveTowards(BlockPos pos) {
        if (mc.player == null || mc.options == null) return;

        Vec3d eye = mc.player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);

        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        double hDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, hDist));

        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);

        if (mc.options.forwardKey != null) {
            mc.options.forwardKey.setPressed(true);
            movingToTarget = true;
        }
    }

    private void stopMovingToTarget() {
        if (movingToTarget && mc.options != null && mc.options.forwardKey != null) {
            mc.options.forwardKey.setPressed(false);
        }
        movingToTarget = false;
    }

    private void breakStackedSpawner(BlockPos pos) {
        if (autoSneak.get() && mc.options != null && mc.options.sneakKey != null) {
            mc.options.sneakKey.setPressed(true);
            forcingSneak = true;
        }

        BlockUtils.breakBlock(pos, true);

        if (!isSpawner(pos)) {
            cachedSpawners.remove(pos);
            currentTarget = null;
        }
    }

    private boolean ensureAndOpenEchest() {
        if (mc.player == null || mc.world == null) return false;

        BlockPos echestPos = null;

        if (useExistingEchest.get()) {
            echestPos = findNearbyEchest(5);
        }

        if (echestPos == null && autoPlaceEchest.get()) {
            if (placeEnderChestNearPlayer()) {
                echestPos = findNearbyEchest(3);
            }
        }

        if (echestPos == null) return false;

        openEchest(echestPos);
        return true;
    }

    private BlockPos findNearbyEchest(int radius) {
        BlockPos center = mc.player.getBlockPos();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.add(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
                        return pos.toImmutable();
                    }
                }
            }
        }
        return null;
    }

    private void openEchest(BlockPos pos) {
        if (mc.currentScreen instanceof HandledScreen<?>) return;

        Vec3d hitPos = new Vec3d(
            pos.getX() + 0.5,
            pos.getY() + 0.5,
            pos.getZ() + 0.5
        );

        BlockHitResult hit = new BlockHitResult(
            hitPos,
            Direction.UP,
            pos,
            false
        );

        if (mc.interactionManager != null && mc.player != null) {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private boolean placeEnderChestNearPlayer() {
        //find exhest in hotbar
        FindItemResult echest = InvUtils.findInHotbar(Items.ENDER_CHEST);
        if (!echest.found()) {
            sendChat("[SpawnerPanic] No ender chest in hotbar.");
            return false;
        }

        BlockPos playerPos = mc.player.getBlockPos();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos base = playerPos.add(x, -1, z);
                BlockPos placePos = base.up();

                if (mc.world.getBlockState(base).isAir()) continue;
                if (!mc.world.getBlockState(placePos).isAir()) continue;

                boolean success = BlockUtils.place(
                    placePos,
                    echest,
                    true,
                    0,
                    true
                );

                if (success) {
                    sendChat("[SpawnerPanic] Placed ender chest at " + placePos.toShortString());
                    return true;
                }
            }
        }

        sendChat("[SpawnerPanic] Failed to place echest — no valid position.");
        return false;
    }

    private void stashSpawnersInOpenContainer() {
        if (!(mc.currentScreen instanceof HandledScreen)) return;

        HandledScreen<?> handled = (HandledScreen<?>) mc.currentScreen;
        var handler = handled.getScreenHandler();
        var slots = handler.slots;

        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);

            if (slot.inventory != mc.player.getInventory()) continue;

            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            if (stack.getItem() != Items.SPAWNER) continue;

            mc.interactionManager.clickSlot(
                handler.syncId,
                i,
                0,
                SlotActionType.QUICK_MOVE,
                mc.player
            );
        }
    }

    private void runStashLogic() {

        if (!hasSpawnerInInventory()) {
            if (mc.currentScreen instanceof HandledScreen) {
                mc.player.closeHandledScreen();
            }
            return;
        }

        if ((useExistingEchest.get() || autoPlaceEchest.get()) && !ensureAndOpenEchest()) {
            if (!warnedNoEchest) {
                sendChat("[SpawnerPanic] Could not find/place an ender chest nearby to stash spawners.");
                warnedNoEchest = true;
            }
            return;
        } else {
            warnedNoEchest = false;
        }

        stashSpawnersInOpenContainer();

        if (!hasSpawnerInInventory() && mc.currentScreen instanceof HandledScreen) {
            mc.player.closeHandledScreen();
        }

        baseSpawnerCount = getSpawnerCountInInventory();
    }

    private void sendChat(String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal(msg), false);
        }
    }
}
