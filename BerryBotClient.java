package com.example.berrybot.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * BerryBot v2 - bot trade dan lang dieu khien bang MENU (BotScreen) + config.json
 * Phim: J luu diem | U xoa diem cuoi | L xoa het | K bat/tat bot | M mo menu cai dat
 * Menu cung mo duoc bang nut "BerryBot" o goc tren-trai cua menu Pause (ESC) - dung duoc tren dien thoai.
 */
public class BerryBotClient implements ClientModInitializer {

    // ===== HANG SO KY THUAT (khong can doi) =====
    static final double ARRIVE_DIST = 0.8;
    static final float MAX_TURN = 14.0f;
    static final int STUCK_CHECK_TICKS = 40;
    static final double STUCK_MIN_MOVE = 0.25;
    static final int STUCK_LIMIT = 6;

    static final double REACH = 2.9;
    static final int AIM_TIMEOUT = 60;
    static final int MENU_TIMEOUT = 60;
    static final int MENU_RETRIES = 3;
    static final int TRADE_DELAY_MIN = 3;
    static final int TRADE_DELAY_RAND = 3;
    static final int NO_GAIN_LIMIT = 4;

    static final int RETRY_MIN_TICKS = 200;  // 10 giay
    static final int RETRY_RAND_TICKS = 100; // + 0..5 giay
    static final int EAT_BELOW = 14;
    static final int EAT_UNTIL = 19;

    enum State { OFF, WALK, AIM, WAIT_MENU, TRADING, SELL_CMD, SELL_WAIT, SELL_FILL, SELL_VERIFY, ORDER, EAT }
    enum Stage { NONE, MAIN, LIST, EDIT, COLLECT, SELL, OTHER }

    static State state = State.OFF;
    static final List<double[]> waypoints = new ArrayList<>();
    static int targetIdx = 0;
    static long gameTick = 0;

    static int stuckTicks = 0;
    static int stuckCount = 0;
    static double lastX, lastZ;

    // trade
    static final Set<UUID> done = new HashSet<>();
    static Villager target = null;
    static int phaseTicks = 0;
    static int openTries = 0;
    static int alignedTicks = 0;
    static int tradeStep = 0;
    static int wait = 0;
    static int resultFail = 0;
    static int noGain = 0;
    static boolean pendingCheck = false;
    static Item gainItem = Items.AIR;
    static int gainBefore = 0;

    // sell / order
    static int cmdTries = 0;
    static int stepWait = 0;
    static int limitTicks = RETRY_MIN_TICKS;
    static int sellRounds = 0;
    static int sellFail = 0;
    static int lastSellCount = -1;
    static int sellCount = 0;
    static boolean skipOrderAfterSell = false;
    static long freeSellCooldown = 0;
    static int orderStep = 0;
    static int orderRetries = 0;
    static int collectFail = 0;
    static int collectCount = 0;
    static int collectWait = 0;
    static int lastOrderInv = -1;
    static boolean sendCmdLater = false;

    // an
    static int eatTicks = 0;
    static int eatCooldown = 0;

    static final Random RNG = new Random();

    static KeyMapping keyAdd, keyToggle, keyClear, keyUndo, keyMenu;

    static BotConfig cfg() {
        return BotConfig.cur;
    }

    static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("berrybot");
    }

    @Override
    public void onInitializeClient() {
        log("BerryBot v2 DA NAP (menu + config)");
        BotConfig.load();

        keyAdd = reg("key.berrybot.add_waypoint", GLFW.GLFW_KEY_J);
        keyToggle = reg("key.berrybot.toggle", GLFW.GLFW_KEY_K);
        keyClear = reg("key.berrybot.clear", GLFW.GLFW_KEY_L);
        keyUndo = reg("key.berrybot.undo", GLFW.GLFW_KEY_U);
        keyMenu = reg("key.berrybot.menu", GLFW.GLFW_KEY_M);

        loadWaypoints();
        ClientTickEvents.END_CLIENT_TICK.register(BerryBotClient::tick);
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> renderHud(graphics));

        // Nut "BerryBot" trong menu Pause (ESC) - dung duoc tren man hinh cam ung
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof PauseScreen) {
                Screens.getButtons(screen).add(
                        Button.builder(Component.literal("BerryBot"), b -> client.setScreen(new BotScreen(screen)))
                                .bounds(6, 6, 80, 20).build());
            }
        });
    }

    static KeyMapping reg(String name, int key) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyMapping(name, InputConstants.Type.KEYSYM, key, "key.categories.berrybot"));
    }

    // ===== HUD TRANG THAI (goc tren-phai) =====
    static String statusText() {
        return switch (state) {
            case OFF -> "[OFF]";
            case WALK -> "[ON] dang di";
            case AIM, WAIT_MENU -> "[ON] mo menu dan lang";
            case TRADING -> "[ON] dang trade";
            case SELL_CMD, SELL_WAIT, SELL_FILL, SELL_VERIFY -> "[ON] dang ban";
            case ORDER -> "[ON] lay hang /order";
            case EAT -> "[ON] dang an";
        };
    }

    static void renderHud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!cfg().hud || mc.player == null || mc.options.hideGui) return;
        String s = "BerryBot " + statusText();
        int w = mc.font.width(s);
        int x = g.guiWidth() - w - 10;
        g.fill(x - 4, 2, x + w + 4, 15, 0xB0000000);
        g.drawString(mc.font, s, x, 5, state == State.OFF ? 0xFFFF7777 : 0xFF77FF77, true);
    }

    // ===== VONG LAP CHINH =====
    static void tick(Minecraft mc) {
        gameTick++;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            if (state != State.OFF) state = State.OFF;
            return;
        }

        if (mc.screen == null) {
            while (keyAdd.consumeClick()) addWaypoint(p);
            while (keyUndo.consumeClick()) removeLastWaypoint(p);
            while (keyClear.consumeClick()) clearWaypoints(p);
            while (keyMenu.consumeClick()) mc.setScreen(new BotScreen(null));
            while (keyToggle.consumeClick()) toggleBot(mc, p);
        }

        if (state == State.OFF) return;
        if (checkStranger(mc, p)) return;

        switch (state) {
            case WALK -> walk(mc, p);
            case AIM -> aim(mc, p);
            case WAIT_MENU -> waitMenu(mc, p);
            case TRADING -> trading(mc, p);
            case SELL_CMD -> sellCmd(mc, p);
            case SELL_WAIT -> sellWait(mc, p);
            case SELL_FILL -> sellFill(mc, p);
            case SELL_VERIFY -> sellVerify(mc, p);
            case ORDER -> orderNav(mc, p);
            case EAT -> eat(mc, p);
            default -> { }
        }
    }

    static void toggleBot(Minecraft mc, LocalPlayer p) {
        if (state == State.OFF) start(mc, p); else stop(mc, p, "Đã tắt bot.");
    }

    // ===== DI BO =====
    static void walk(Minecraft mc, LocalPlayer p) {
        BotConfig c = cfg();
        if (waypoints.size() < 2) {
            stop(mc, p, "Cần ít nhất 2 waypoint (phím J hoặc nút trong menu).");
            return;
        }

        // Tui day -> di ban (neu bat)
        if (c.autoSell && c.sellWhenFreeBelow > 0 && gameTick % 20 == 0 && gameTick > freeSellCooldown
                && freeInvSlots(p) < c.sellWhenFreeBelow && invCountSet(p, sellSet()) > 0) {
            freeSellCooldown = gameTick + 1200;
            msg(p, "Túi sắp đầy, đi bán.");
            startSell(mc, p, true);
            return;
        }

        // Doi -> an
        if (c.autoEat) {
            if (eatCooldown > 0) {
                eatCooldown--;
            } else if (p.getFoodData().getFoodLevel() <= EAT_BELOW) {
                int hb = foodHotbarSlot(p);
                if (hb >= 0) {
                    releaseKeys(mc);
                    p.getInventory().selected = hb;
                    eatTicks = 0;
                    state = State.EAT;
                    msg(p, "Đói, đứng lại ăn.");
                    return;
                } else {
                    eatCooldown = 600;
                }
            }
        }

        Villager v = findVillager(mc, p);
        if (v != null) {
            releaseKeys(mc);
            target = v;
            phaseTicks = 0;
            openTries = 0;
            alignedTicks = 0;
            state = State.AIM;
            return;
        }

        if (targetIdx >= waypoints.size()) targetIdx = 0;
        double[] t = waypoints.get(targetIdx);

        double dx = t[0] - p.getX();
        double dz = t[2] - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist < ARRIVE_DIST) {
            int next = (targetIdx + 1) % waypoints.size();
            if (next == 0) {
                done.clear();
                log("Het 1 vong, xoa danh sach da trade.");
            }
            targetIdx = next;
            return;
        }

        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float diff = Mth.wrapDegrees(wantYaw - p.getYRot());
        float step = Mth.clamp(diff, -MAX_TURN, MAX_TURN);
        p.setYRot(p.getYRot() + step + (RNG.nextFloat() - 0.5f) * 0.4f);
        p.setXRot(Mth.lerp(0.2f, p.getXRot(), 8.0f));

        boolean aligned = Math.abs(diff) < 35.0f;
        mc.options.keyUp.setDown(aligned);
        mc.options.keyJump.setDown(aligned && p.horizontalCollision && p.onGround());

        stuckTicks++;
        if (stuckTicks >= STUCK_CHECK_TICKS) {
            double moved = Math.hypot(p.getX() - lastX, p.getZ() - lastZ);
            if (moved < STUCK_MIN_MOVE) stuckCount++; else stuckCount = 0;
            lastX = p.getX();
            lastZ = p.getZ();
            stuckTicks = 0;
            if (stuckCount >= STUCK_LIMIT) {
                stop(mc, p, "Bot bị kẹt, đã dừng. Kiểm tra gần waypoint J" + (targetIdx + 1) + ".");
            }
        }
    }

    static Villager findVillager(Minecraft mc, LocalPlayer p) {
        Vec3 eye = p.getEyePosition();
        List<Villager> list = mc.level.getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(REACH + 1.5));
        Villager best = null;
        double bestD = Double.MAX_VALUE;
        for (Villager v : list) {
            if (!v.isAlive() || v.isBaby()) continue;
            if (done.contains(v.getUUID())) continue;
            double d = v.getBoundingBox().distanceToSqr(eye);
            if (d > REACH * REACH) continue;
            if (!p.hasLineOfSight(v)) continue;
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    // ===== AN =====
    static int foodHotbarSlot(LocalPlayer p) {
        Item food = BotConfig.item(cfg().foodItem);
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(food)) return i;
        }
        return -1;
    }

    static void eat(Minecraft mc, LocalPlayer p) {
        eatTicks++;
        p.setXRot(p.getXRot() + Mth.clamp(-80f - p.getXRot(), -MAX_TURN, MAX_TURN));
        mc.options.keyUse.setDown(true);

        Item food = BotConfig.item(cfg().foodItem);
        boolean hasFood = p.getInventory().getItem(p.getInventory().selected).is(food);
        if (p.getFoodData().getFoodLevel() >= EAT_UNTIL || eatTicks > 400 || !hasFood) {
            mc.options.keyUse.setDown(false);
            eatCooldown = 100;
            resumeWalk(p);
        }
    }

    // ===== NGAM DAN LANG (tu raycast) =====
    static Vec3 rayHitOnTarget(Minecraft mc, LocalPlayer p, Villager v) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(REACH + 0.5));
        Optional<Vec3> hit = v.getBoundingBox().clip(eye, end);
        if (hit.isEmpty()) return null;
        BlockHitResult br = mc.level.clip(new ClipContext(eye, hit.get(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (br.getType() != HitResult.Type.MISS) return null;
        return hit.get();
    }

    static void aim(Minecraft mc, LocalPlayer p) {
        if (target == null || !target.isAlive()) {
            resumeWalk(p);
            return;
        }
        phaseTicks++;

        Vec3 eye = p.getEyePosition();
        Vec3 tp = target.getEyePosition();
        double dx = tp.x - eye.x;
        double dy = tp.y - eye.y;
        double dz = tp.z - eye.z;

        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float dYaw = Mth.wrapDegrees(wantYaw - p.getYRot());
        float dPitch = wantPitch - p.getXRot();

        p.setYRot(p.getYRot() + Mth.clamp(dYaw, -MAX_TURN, MAX_TURN));
        p.setXRot(p.getXRot() + Mth.clamp(dPitch, -MAX_TURN, MAX_TURN));

        Vec3 hit = rayHitOnTarget(mc, p, target);
        boolean aligned = Math.abs(dYaw) < 1.5f && Math.abs(dPitch) < 1.5f;
        if (aligned && hit != null) alignedTicks++; else alignedTicks = 0;

        if (alignedTicks >= 4 && hit != null && mc.gameMode != null) {
            InteractionResult r = mc.gameMode.interactAt(p, target, new EntityHitResult(target, hit), InteractionHand.MAIN_HAND);
            if (!r.consumesAction()) {
                r = mc.gameMode.interact(p, target, InteractionHand.MAIN_HAND);
            }
            log("Bam dan lang -> " + r + " | screen=" + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName()));
            alignedTicks = 0;
            phaseTicks = 0;
            state = State.WAIT_MENU;
            return;
        }
        if (phaseTicks > AIM_TIMEOUT) {
            log("Khong ngam trung dan lang, bo qua.");
            skipVillager(p);
        }
    }

    static void waitMenu(Minecraft mc, LocalPlayer p) {
        phaseTicks++;
        if (p.containerMenu instanceof MerchantMenu m && !m.getOffers().isEmpty()) {
            tradeStep = 0;
            wait = TRADE_DELAY_MIN + RNG.nextInt(TRADE_DELAY_RAND);
            resultFail = 0;
            noGain = 0;
            pendingCheck = false;
            state = State.TRADING;
            return;
        }
        if (phaseTicks > MENU_TIMEOUT) {
            openTries++;
            if (openTries >= MENU_RETRIES) {
                log("Menu trade khong mo, bo qua dan lang.");
                skipVillager(p);
            } else {
                phaseTicks = 0;
                alignedTicks = 0;
                state = State.AIM;
            }
        }
    }

    // ===== TRADE THEO QUY TAC (RULES) =====
    static void trading(Minecraft mc, LocalPlayer p) {
        if (!(p.containerMenu instanceof MerchantMenu m)) {
            log("Menu bi dong bat ngo, bo qua dan lang.");
            skipVillager(p);
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        BotConfig c = cfg();

        if (tradeStep == 0) {
            // xac nhan lan trade truoc co nhan duoc hang khong
            if (pendingCheck) {
                pendingCheck = false;
                if (countItem(m, gainItem, 3) > gainBefore) {
                    noGain = 0;
                } else {
                    noGain++;
                    if (noGain >= NO_GAIN_LIMIT) {
                        p.closeContainer();
                        stop(mc, p, "Trade xong nhưng túi không nhận được hàng (đầy túi hoặc lag). Đã dừng.");
                        return;
                    }
                }
            }

            MerchantOffers offers = m.getOffers();
            int idx = -1;
            Item chosenGet = Items.AIR;
            String restockItemName = null;

            for (int i = 0; i < offers.size() && idx < 0; i++) {
                MerchantOffer o = offers.get(i);
                if (o.isOutOfStock()) continue;
                if (!o.getCostB().isEmpty()) continue;
                ItemStack a = o.getCostA();
                for (BotConfig.Rule r : c.rules) {
                    if (!r.enabled) continue;
                    Item give = BotConfig.item(r.give);
                    Item get = BotConfig.item(r.get);
                    if (give == Items.AIR || get == Items.AIR) continue;
                    if (!a.is(give) || !o.getResult().is(get)) continue;
                    if (r.maxPrice > 0 && a.getCount() > r.maxPrice) continue;
                    if (countItem(m, give, -1) < a.getCount()) {
                        if (r.restock) restockItemName = BotConfig.itemName(r.give);
                        continue;
                    }
                    idx = i;
                    chosenGet = get;
                    break;
                }
            }

            if (idx < 0) {
                if (restockItemName != null) {
                    p.closeContainer();
                    beginRestock(mc, p, "Hết " + restockItemName + " để trade.");
                } else {
                    skipVillager(p);
                }
                return;
            }

            if (freeSlotsMenu(m) < 1) {
                p.closeContainer();
                if (c.autoSell) {
                    msg(p, "Túi đầy, đi bán bớt.");
                    startSell(mc, p, true);
                } else {
                    stop(mc, p, "Túi đầy và /sell đang tắt. Đã dừng.");
                }
                return;
            }

            gainItem = chosenGet;
            gainBefore = countItem(m, chosenGet, 3);
            m.setSelectionHint(idx);
            m.tryMoveItems(idx);
            if (mc.getConnection() != null) mc.getConnection().send(new ServerboundSelectTradePacket(idx));
            tradeStep = 1;
            wait = TRADE_DELAY_MIN + RNG.nextInt(TRADE_DELAY_RAND);
            return;
        }

        // tradeStep == 1: lay ket qua
        if (m.getSlot(2).hasItem()) {
            gainBefore = countItem(m, gainItem, 3);
            if (mc.gameMode != null) {
                mc.gameMode.handleInventoryMouseClick(m.containerId, 2, 0, ClickType.QUICK_MOVE, p);
            }
            pendingCheck = true;
            resultFail = 0;
        } else {
            resultFail++;
            if (resultFail >= 3) {
                log("O ket qua trong, coi nhu het hang.");
                skipVillager(p);
                return;
            }
        }
        tradeStep = 0;
        wait = TRADE_DELAY_MIN + RNG.nextInt(TRADE_DELAY_RAND);
    }

    static int countItem(MerchantMenu m, Item item, int fromSlot) {
        int n = 0;
        for (int i = 0; i < m.slots.size(); i++) {
            if (i == 2) continue;
            if (fromSlot >= 0 && i < fromSlot) continue;
            ItemStack s = m.getSlot(i).getItem();
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    static int freeSlotsMenu(MerchantMenu m) {
        int n = 0;
        for (int i = 3; i < m.slots.size(); i++) {
            if (m.getSlot(i).getItem().isEmpty()) n++;
        }
        return n;
    }

    static void skipVillager(LocalPlayer p) {
        if (target != null) done.add(target.getUUID());
        if (p.containerMenu instanceof MerchantMenu) p.closeContainer();
        resumeWalk(p);
    }

    static void resumeWalk(LocalPlayer p) {
        target = null;
        stuckTicks = 0;
        stuckCount = 0;
        lastX = p.getX();
        lastZ = p.getZ();
        state = State.WALK;
    }

    // =====================================================
    // ===== HET HANG: /SELL + /ORDER =====
    // =====================================================
    static void beginRestock(Minecraft mc, LocalPlayer p, String why) {
        BotConfig c = cfg();
        msg(p, why);
        if (!c.autoSell && !c.autoOrder) {
            stop(mc, p, "Hết hàng nhưng cả /sell và /order đều đang tắt. Bật trong menu BerryBot.");
            return;
        }
        if (c.autoSell) startSell(mc, p, false); else startOrder(mc, p);
    }

    static Set<Item> sellSet() {
        BotConfig c = cfg();
        Set<Item> s = new HashSet<>();
        for (BotConfig.Rule r : c.rules) {
            if (!r.enabled) continue;
            s.add(BotConfig.item(r.give));
            s.add(BotConfig.item(r.get));
        }
        s.add(BotConfig.item(c.orderItem));
        if (!c.sellEmerald) s.remove(Items.EMERALD);
        s.remove(Items.AIR);
        return s;
    }

    static int invCountSet(LocalPlayer p, Set<Item> set) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && set.contains(s.getItem())) n += s.getCount();
        }
        return n;
    }

    static int freeInvSlots(LocalPlayer p) {
        int n = 0;
        for (int i = 9; i <= 44; i++) {
            if (p.inventoryMenu.getSlot(i).getItem().isEmpty()) n++;
        }
        return n;
    }

    static void startSell(Minecraft mc, LocalPlayer p, boolean skipOrder) {
        releaseKeys(mc);
        target = null;
        skipOrderAfterSell = skipOrder;
        cmdTries = 0;
        sellRounds = 0;
        wait = 20 + RNG.nextInt(20);
        state = State.SELL_CMD;
    }

    static void sellCmd(Minecraft mc, LocalPlayer p) {
        if (wait > 0) { wait--; return; }
        sendCmd(mc, "sell");
        stepWait = 0;
        limitTicks = RETRY_MIN_TICKS + RNG.nextInt(RETRY_RAND_TICKS);
        state = State.SELL_WAIT;
        msg(p, "Gõ /sell");
    }

    // Menu /sell khong mo -> cho 10-15 giay roi thu lai, khong tu tat mod
    static void sellWait(Minecraft mc, LocalPlayer p) {
        if (wait > 0) { wait--; return; }
        Stage st = stageOf(chestTitle(mc, p));
        if (st == Stage.SELL) {
            sellFail = 0;
            lastSellCount = -1;
            wait = 8 + RNG.nextInt(8);
            state = State.SELL_FILL;
            return;
        }
        stepWait++;
        if (stepWait > limitTicks) {
            cmdTries++;
            if (st != Stage.NONE) p.closeContainer();
            msg(p, "Menu /sell chưa mở, chờ rồi thử lại (lần " + cmdTries + ").");
            sendCmd(mc, "sell");
            stepWait = 0;
            limitTicks = RETRY_MIN_TICKS + RNG.nextInt(RETRY_RAND_TICKS);
        }
    }

    static void sellFill(Minecraft mc, LocalPlayer p) {
        if (wait > 0) { wait--; return; }
        if (stageOf(chestTitle(mc, p)) != Stage.SELL) {
            wait = 60;
            state = State.SELL_VERIFY;
            return;
        }
        ChestMenu cm = (ChestMenu) p.containerMenu;
        int chestSlots = cm.getRowCount() * 9;
        Set<Item> set = sellSet();

        int cnt = 0;
        int src = -1;
        for (int i = chestSlots; i < cm.slots.size(); i++) {
            ItemStack s = cm.getSlot(i).getItem();
            if (!s.isEmpty() && set.contains(s.getItem())) {
                cnt += s.getCount();
                if (src < 0) src = i;
            }
        }

        if (lastSellCount >= 0) {
            if (cnt >= lastSellCount) sellFail++; else sellFail = 0;
            if (sellFail >= 4) {
                p.closeContainer();
                stop(mc, p, "Shift-click vào menu /sell không có tác dụng. Đã dừng.");
                return;
            }
        }
        if (src < 0) {
            p.closeContainer();
            wait = 60;
            state = State.SELL_VERIFY;
            return;
        }
        click(mc, p, cm, src, ClickType.QUICK_MOVE);
        lastSellCount = cnt;
        wait = 3 + RNG.nextInt(3);
    }

    static void sellVerify(Minecraft mc, LocalPlayer p) {
        if (wait > 0) { wait--; return; }
        int left = invCountSet(p, sellSet());
        if (left > 0) {
            sellRounds++;
            if (sellRounds > 3) {
                stop(mc, p, "Còn " + left + " món chưa bán được sau 3 lần. Đã dừng.");
                return;
            }
            msg(p, "Còn " + left + " món chưa bán, bán lại.");
            cmdTries = 0;
            wait = 10 + RNG.nextInt(10);
            state = State.SELL_CMD;
            return;
        }
        sellCount++;
        msg(p, "Đã bán xong (lần #" + sellCount + ").");
        if (skipOrderAfterSell) {
            resumeWalk(p);
        } else if (cfg().autoOrder) {
            startOrder(mc, p);
        } else {
            stop(mc, p, "Đã bán xong. /order đang tắt nên bot dừng.");
        }
    }

    // ===== /ORDER =====
    static void startOrder(Minecraft mc, LocalPlayer p) {
        releaseKeys(mc);
        orderStep = 0;
        orderRetries = 0;
        stepWait = 0;
        limitTicks = RETRY_MIN_TICKS + RNG.nextInt(RETRY_RAND_TICKS);
        collectFail = 0;
        collectCount = 0;
        collectWait = 0;
        lastOrderInv = -1;
        wait = 15 + RNG.nextInt(20);
        state = State.ORDER;
        sendCmdLater = true;
    }

    static void orderEmpty(Minecraft mc, LocalPlayer p, String why) {
        if (cfg().quitWhenOrderEmpty) {
            quitGame(mc, p, why + " -> thoát game.");
        } else {
            stop(mc, p, why + " Bot dừng.");
        }
    }

    static void orderNav(Minecraft mc, LocalPlayer p) {
        if (wait > 0) { wait--; return; }
        if (sendCmdLater) {
            sendCmdLater = false;
            sendCmd(mc, "order");
            msg(p, "Gõ /order");
            stepWait = 0;
            return;
        }

        Item oi = BotConfig.item(cfg().orderItem);
        Stage st = stageOf(chestTitle(mc, p));
        Stage need = switch (orderStep) {
            case 0 -> Stage.MAIN;
            case 1 -> Stage.LIST;
            case 2 -> Stage.EDIT;
            default -> Stage.COLLECT;
        };

        if (st == need) {
            ChestMenu cm = (ChestMenu) p.containerMenu;
            int rows = cm.getRowCount();
            boolean clicked = false;

            switch (orderStep) {
                case 0 -> clicked = clickNamedChest(mc, p, cm, rows, "your orders");
                case 1 -> clicked = clickItem(mc, p, cm, rows, oi);
                case 2 -> clicked = clickNamedChest(mc, p, cm, rows, "collect");
                default -> {
                    collect(mc, p, cm, rows, oi);
                    return;
                }
            }

            if (clicked) {
                orderStep++;
                stepWait = 0;
                limitTicks = RETRY_MIN_TICKS + RNG.nextInt(RETRY_RAND_TICKS);
                wait = 8 + RNG.nextInt(8);
            } else {
                stepWait++;
                if (stepWait > 100) {
                    p.closeContainer();
                    if (orderStep == 1) {
                        orderEmpty(mc, p, "Không có đơn " + BotConfig.itemName(cfg().orderItem) + " trong Your Orders.");
                    } else {
                        orderRetries++;
                        msg(p, "Chưa thấy nút cần bấm, chờ rồi mở lại /order (lần " + orderRetries + ").");
                        orderStep = 0;
                        stepWait = 0;
                        wait = RETRY_MIN_TICKS + RNG.nextInt(RETRY_RAND_TICKS);
                        sendCmdLater = true;
                    }
                }
            }
            return;
        }

        // Cho menu: thu lai moi 10-15 giay, KHONG tu tat mod
        stepWait++;
        if (stepWait > limitTicks) {
            orderRetries++;
            stepWait = 0;
            limitTicks = RETRY_MIN_TICKS + RNG.nextInt(RETRY_RAND_TICKS);

            Stage prev = switch (orderStep) {
                case 1 -> Stage.MAIN;
                case 2 -> Stage.LIST;
                case 3 -> Stage.EDIT;
                default -> Stage.NONE;
            };
            if (st == Stage.NONE) {
                msg(p, "Chưa thấy menu, thử lại /order (lần " + orderRetries + ").");
                orderStep = 0;
                sendCmd(mc, "order");
            } else if (st == prev) {
                msg(p, "Bấm chưa ăn, bấm lại đúng nút (lần " + orderRetries + ").");
                orderStep--;
            } else {
                msg(p, "Menu lạ, đóng và mở lại /order (lần " + orderRetries + ").");
                p.closeContainer();
                orderStep = 0;
                wait = 20 + RNG.nextInt(20);
                sendCmdLater = true;
            }
        }
    }

    static void collect(Minecraft mc, LocalPlayer p, ChestMenu cm, int rows, Item oi) {
        int chestSlots = rows * 9;

        int free = 0;
        int invCnt = 0;
        for (int i = chestSlots; i < cm.slots.size(); i++) {
            ItemStack s = cm.getSlot(i).getItem();
            if (s.isEmpty()) free++;
            else if (s.is(oi)) invCnt += s.getCount();
        }

        if (free <= cfg().keepFree) {
            p.closeContainer();
            msg(p, "Đã lấy đủ hàng, chừa " + free + " ô trống. Đi tiếp.");
            resumeWalk(p);
            return;
        }

        if (lastOrderInv >= 0) {
            if (invCnt <= lastOrderInv) collectFail++; else collectFail = 0;
            if (collectFail >= 4) {
                p.closeContainer();
                stop(mc, p, "Shift-click trong Collect Items không lấy được hàng. Đã dừng.");
                return;
            }
        }

        int src = -1;
        for (int i = 0; i < chestSlots; i++) {
            if (cm.getSlot(i).getItem().is(oi)) {
                src = i;
                break;
            }
        }
        if (src < 0) {
            if (collectCount == 0) {
                collectWait++;
                if (collectWait < 80) return;
                p.closeContainer();
                orderEmpty(mc, p, "Đơn " + BotConfig.itemName(cfg().orderItem) + " đã hết hàng (Collect Items trống).");
            } else {
                p.closeContainer();
                msg(p, "Đơn hết hàng, lấy được những gì còn lại. Đi tiếp.");
                resumeWalk(p);
            }
            return;
        }

        click(mc, p, cm, src, ClickType.QUICK_MOVE);
        collectCount++;
        lastOrderInv = invCnt;
        wait = 4 + RNG.nextInt(3);
    }

    // ===== TIEN ICH MENU =====
    static String chestTitle(Minecraft mc, LocalPlayer p) {
        if (!(p.containerMenu instanceof ChestMenu)) return null;
        if (mc.screen instanceof AbstractContainerScreen<?> s) return s.getTitle().getString();
        return null;
    }

    static Stage stageOf(String title) {
        if (title == null) return Stage.NONE;
        String t = title.toLowerCase(Locale.ROOT).trim();
        if (t.contains("collect items")) return Stage.COLLECT;
        if (t.contains("edit order")) return Stage.EDIT;
        if (t.contains("your orders")) return Stage.LIST;
        if (t.startsWith("orders")) return Stage.MAIN;
        if (t.contains("sell")) return Stage.SELL;
        return Stage.OTHER;
    }

    static void click(Minecraft mc, LocalPlayer p, ChestMenu cm, int slot, ClickType type) {
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryMouseClick(cm.containerId, slot, 0, type, p);
        }
    }

    static boolean clickNamedChest(Minecraft mc, LocalPlayer p, ChestMenu cm, int rows, String key) {
        for (int i = 0; i < rows * 9; i++) {
            ItemStack s = cm.getSlot(i).getItem();
            if (s.is(Items.CHEST) && s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(key)) {
                click(mc, p, cm, i, ClickType.PICKUP);
                return true;
            }
        }
        return false;
    }

    static boolean clickItem(Minecraft mc, LocalPlayer p, ChestMenu cm, int rows, Item item) {
        for (int i = 0; i < rows * 9; i++) {
            if (cm.getSlot(i).getItem().is(item)) {
                click(mc, p, cm, i, ClickType.PICKUP);
                return true;
            }
        }
        return false;
    }

    static void sendCmd(Minecraft mc, String cmd) {
        if (mc.getConnection() != null) mc.getConnection().sendCommand(cmd);
    }

    // ===== FAILSAFE =====
    static boolean checkStranger(Minecraft mc, LocalPlayer p) {
        BotConfig c = cfg();
        if (!c.quitOnPlayer) return false;
        for (AbstractClientPlayer other : mc.level.players()) {
            if (other == p) continue;
            if (p.distanceTo(other) > c.quitRadius) continue;
            quitGame(mc, p, "Phát hiện người chơi: " + other.getGameProfile().getName() + " -> thoát game.");
            return true;
        }
        return false;
    }

    static void quitGame(Minecraft mc, LocalPlayer p, String why) {
        log(why);
        releaseKeys(mc);
        state = State.OFF;
        mc.level.disconnect();
    }

    // ===== DIEU KHIEN =====
    static void start(Minecraft mc, LocalPlayer p) {
        if (waypoints.size() < 2) {
            msg(p, "Chưa đủ waypoint. Đứng tại từng điểm và bấm J (hoặc nút trong menu), cần ít nhất 2.");
            return;
        }
        mc.options.pauseOnLostFocus = false;

        double best = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            double[] w = waypoints.get(i);
            double d = Math.hypot(w[0] - p.getX(), w[2] - p.getZ());
            if (d < best) { best = d; targetIdx = i; }
        }
        done.clear();
        target = null;
        eatCooldown = 0;
        stuckTicks = 0;
        stuckCount = 0;
        lastX = p.getX();
        lastZ = p.getZ();
        state = State.WALK;
        msg(p, "Bot BẬT. " + waypoints.size() + " waypoint, bắt đầu từ J" + (targetIdx + 1) + ".");
    }

    static void stop(Minecraft mc, LocalPlayer p, String why) {
        releaseKeys(mc);
        state = State.OFF;
        target = null;
        if (mc.screen instanceof AbstractContainerScreen<?>) p.closeContainer();
        msg(p, why);
        log(why);
    }

    static void releaseKeys(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyUse.setDown(false);
    }

    // ===== WAYPOINT =====
    static void addWaypoint(LocalPlayer p) {
        waypoints.add(new double[]{p.getX(), p.getY(), p.getZ()});
        saveWaypoints();
        msg(p, "Đã lưu J" + waypoints.size());
    }

    static void removeLastWaypoint(LocalPlayer p) {
        if (waypoints.isEmpty()) {
            msg(p, "Không còn waypoint nào để xóa.");
            return;
        }
        waypoints.remove(waypoints.size() - 1);
        saveWaypoints();
        msg(p, "Đã xóa điểm cuối. Còn " + waypoints.size() + " waypoint.");
    }

    static void clearWaypoints(LocalPlayer p) {
        waypoints.clear();
        saveWaypoints();
        msg(p, "Đã xóa toàn bộ waypoint.");
    }

    static void saveWaypoints() {
        try {
            Files.createDirectories(dir());
            List<String> lines = new ArrayList<>();
            for (double[] w : waypoints) lines.add(w[0] + " " + w[1] + " " + w[2]);
            Files.write(dir().resolve("waypoints.txt"), lines);
        } catch (IOException e) {
            log("Lỗi lưu waypoint: " + e.getMessage());
        }
    }

    static void loadWaypoints() {
        waypoints.clear();
        Path f = dir().resolve("waypoints.txt");
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f)) {
                String[] s = line.trim().split("\\s+");
                if (s.length == 3) {
                    waypoints.add(new double[]{
                            Double.parseDouble(s[0]), Double.parseDouble(s[1]), Double.parseDouble(s[2])});
                }
            }
        } catch (IOException | NumberFormatException e) {
            log("Lỗi đọc waypoint: " + e.getMessage());
        }
    }

    static void msg(LocalPlayer p, String s) {
        p.displayClientMessage(Component.literal("[BerryBot] " + s), false);
    }

    static void log(String s) {
        System.out.println("[BerryBot] " + s);
    }

    // =====================================================
    // ===== CAI DAT (config.json) =====
    // =====================================================
    static class BotConfig {

    /** 1 quy tac trade: dua "give" cho dan lang de nhan "get". */
    public static class Rule {
        public String name = "Rule";
        public boolean enabled = false;
        public String give = "minecraft:emerald";
        public String get = "minecraft:cooked_chicken";
        public int maxPrice = 0;      // so "give" toi da moi lan trade (0 = khong gioi han)
        public boolean restock = false; // het "give" thi di /sell + /order lay hang

        public Rule() { }

        public Rule(String name, boolean enabled, String give, String get, int maxPrice, boolean restock) {
            this.name = name;
            this.enabled = enabled;
            this.give = give;
            this.get = get;
            this.maxPrice = maxPrice;
            this.restock = restock;
        }
    }

    // ===== CAI DAT CHUNG =====
    public boolean autoSell = true;
    public boolean autoOrder = true;
    public boolean quitWhenOrderEmpty = true;
    public boolean autoEat = true;
    public String foodItem = "minecraft:sweet_berries";
    public boolean quitOnPlayer = true;
    public int quitRadius = 30;
    public boolean sellEmerald = true;
    public String orderItem = "minecraft:sweet_berries";
    public int keepFree = 2;
    public int sellWhenFreeBelow = 0;
    public boolean hud = true;
    public List<Rule> rules = new ArrayList<>();

    // ===== TIEN ICH =====
    static BotConfig cur = new BotConfig();
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Danh sach item de bam doi trong menu. Muon them item khac: sua config.json (ghi id day du). */
    static final String[] PRESETS = {
            "minecraft:sweet_berries", "minecraft:emerald", "minecraft:emerald_block",
            "minecraft:cooked_chicken", "minecraft:cooked_porkchop", "minecraft:cooked_beef",
            "minecraft:cooked_mutton", "minecraft:cooked_rabbit", "minecraft:cooked_salmon", "minecraft:cooked_cod",
            "minecraft:chicken", "minecraft:porkchop", "minecraft:beef", "minecraft:mutton", "minecraft:rabbit",
            "minecraft:bread", "minecraft:baked_potato", "minecraft:carrot", "minecraft:potato",
            "minecraft:wheat", "minecraft:beetroot", "minecraft:apple", "minecraft:melon_slice",
            "minecraft:coal", "minecraft:dried_kelp_block"
    };

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("berrybot").resolve("config.json");
    }

    static void load() {
        Path f = file();
        BotConfig c = null;
        if (Files.exists(f)) {
            try {
                c = GSON.fromJson(Files.readString(f), BotConfig.class);
            } catch (Exception e) {
                System.out.println("[BerryBot] Loi doc config.json, dung mac dinh: " + e.getMessage());
            }
        }
        if (c == null) c = new BotConfig();
        c.ensureRules();
        cur = c;
        save();
    }

    static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(cur));
        } catch (IOException e) {
            System.out.println("[BerryBot] Loi ghi config.json: " + e.getMessage());
        }
    }

    void ensureRules() {
        if (rules == null) rules = new ArrayList<>();
        while (rules.size() < 3) {
            switch (rules.size()) {
                case 0 -> rules.add(new Rule("Ban qua mong lay emerald", true,
                        "minecraft:sweet_berries", "minecraft:emerald", 0, true));
                case 1 -> rules.add(new Rule("Mua ga chin bang emerald", false,
                        "minecraft:emerald", "minecraft:cooked_chicken", 2, false));
                default -> rules.add(new Rule("Mua heo chin bang emerald", false,
                        "minecraft:emerald", "minecraft:cooked_porkchop", 2, false));
            }
        }
    }

    // ===== ITEM =====
    static Item item(String id) {
        if (id == null) return Items.AIR;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Items.AIR;
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
    }

    static String itemName(String id) {
        return item(id).getDescription().getString();
    }

    /** Chuyen sang item ke tiep trong PRESETS (quay vong). */
    static String nextItem(String id) {
        int idx = -1;
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].equals(id)) { idx = i; break; }
        }
        return PRESETS[(idx + 1) % PRESETS.length];
    }

    static int nextInt(int v, int[] steps) {
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == v) return steps[(i + 1) % steps.length];
        }
        return steps[0];
    }
}

    // =====================================================
    // ===== MENU CAI DAT =====
    // =====================================================
    static class BotScreen extends Screen {

    private final Screen parent;
    private int page = 0;

    static final int[] GIA_STEPS = {0, 1, 2, 3, 4, 5, 8, 10};
    static final int[] FREE_STEPS = {0, 2, 4, 6, 8, 12, 16};
    static final int[] KEEP_STEPS = {1, 2, 3, 4, 6};
    static final int[] RADIUS_STEPS = {10, 20, 30, 50, 80};

    public BotScreen(Screen parent) {
        super(Component.literal("BerryBot - Cài đặt"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // khong tam dung game de bot van chay khi mo menu (singleplayer)
    }

    @Override
    public void onClose() {
        BotConfig.save();
        this.minecraft.setScreen(parent);
    }

    @Override
    protected void init() {
        if (page == 0) buildGeneral(); else buildTrade();

        int by = this.height - 26;
        this.addRenderableWidget(Button.builder(
                Component.literal(page == 0 ? "Trang 2: Trade >" : "< Trang 1: Chung"),
                b -> { page = 1 - page; rebuildWidgets(); })
                .bounds(this.width / 2 - 154, by, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Xong"), b -> onClose())
                .bounds(this.width / 2 + 4, by, 150, 20).build());
    }

    // ===== TIEN ICH VE NUT =====
    private int xOf(int col) {
        return col == 0 ? this.width / 2 - 154 : this.width / 2 + 4;
    }

    private void add(int col, int row, int span, Component label, Runnable action) {
        int w = span == 2 ? 308 : 150;
        int y = 34 + row * 22;
        this.addRenderableWidget(Button.builder(label, b -> {
            action.run();
            BotConfig.save();
            rebuildWidgets();
        }).bounds(xOf(col), y, w, 20).build());
    }

    private static Component onoff(String name, boolean v) {
        return Component.literal(name + ": ")
                .append(Component.literal(v ? "BẬT" : "TẮT").withStyle(v ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static Component plain(String s) {
        return Component.literal(s);
    }

    // ===== TRANG 1: CHUNG =====
    private void buildGeneral() {
        BotConfig c = BotConfig.cur;
        Minecraft mc = Minecraft.getInstance();
        boolean running = BerryBotClient.state != BerryBotClient.State.OFF;

        add(0, 0, 2, Component.literal(running ? "BOT ĐANG CHẠY - bấm để TẮT" : "BOT ĐANG TẮT - bấm để BẬT")
                .withStyle(running ? ChatFormatting.GREEN : ChatFormatting.RED), () -> {
            LocalPlayer p = mc.player;
            if (p != null) BerryBotClient.toggleBot(mc, p);
        });

        add(0, 1, 1, plain("Lưu waypoint (" + BerryBotClient.waypoints.size() + ")"), () -> {
            if (mc.player != null) BerryBotClient.addWaypoint(mc.player);
        });
        add(1, 1, 1, plain("Xóa điểm cuối"), () -> {
            if (mc.player != null) BerryBotClient.removeLastWaypoint(mc.player);
        });
        add(0, 2, 1, plain("Xóa hết waypoint"), () -> {
            if (mc.player != null) BerryBotClient.clearWaypoints(mc.player);
        });
        add(1, 2, 1, onoff("Tự /sell", c.autoSell), () -> c.autoSell = !c.autoSell);

        add(0, 3, 1, onoff("Tự /order lấy hàng", c.autoOrder), () -> c.autoOrder = !c.autoOrder);
        add(1, 3, 1, onoff("Order hết = thoát game", c.quitWhenOrderEmpty), () -> c.quitWhenOrderEmpty = !c.quitWhenOrderEmpty);

        add(0, 4, 1, onoff("Tự ăn", c.autoEat), () -> c.autoEat = !c.autoEat);
        add(1, 4, 1, onoff("Thoát khi có người", c.quitOnPlayer), () -> c.quitOnPlayer = !c.quitOnPlayer);

        add(0, 5, 1, onoff("Bán cả emerald", c.sellEmerald), () -> c.sellEmerald = !c.sellEmerald);
        add(1, 5, 1, onoff("Hiện trạng thái", c.hud), () -> c.hud = !c.hud);

        add(0, 6, 1, plain("Bán khi túi < " + c.sellWhenFreeBelow + " ô (0=tắt)"),
                () -> c.sellWhenFreeBelow = BotConfig.nextInt(c.sellWhenFreeBelow, FREE_STEPS));
        add(1, 6, 1, plain("Chừa trống: " + c.keepFree + " ô"),
                () -> c.keepFree = BotConfig.nextInt(c.keepFree, KEEP_STEPS));

        add(0, 7, 1, plain("Bán kính người lạ: " + c.quitRadius),
                () -> c.quitRadius = BotConfig.nextInt(c.quitRadius, RADIUS_STEPS));
    }

    // ===== TRANG 2: TRADE =====
    private void buildTrade() {
        BotConfig c = BotConfig.cur;
        for (int i = 0; i < c.rules.size() && i < 3; i++) {
            BotConfig.Rule r = c.rules.get(i);
            int row = i * 2;
            add(0, row, 1, onoff(r.name.length() > 18 ? r.name.substring(0, 18) : r.name, r.enabled),
                    () -> r.enabled = !r.enabled);
            add(1, row, 1, plain("Giá tối đa: " + (r.maxPrice == 0 ? "∞" : String.valueOf(r.maxPrice))),
                    () -> r.maxPrice = BotConfig.nextInt(r.maxPrice, GIA_STEPS));
            add(0, row + 1, 1, plain("Cho: " + BotConfig.itemName(r.give)), () -> r.give = BotConfig.nextItem(r.give));
            add(1, row + 1, 1, plain("Nhận: " + BotConfig.itemName(r.get)), () -> r.get = BotConfig.nextItem(r.get));
        }
        add(0, 6, 1, plain("Order lấy: " + BotConfig.itemName(c.orderItem)), () -> c.orderItem = BotConfig.nextItem(c.orderItem));
        add(1, 6, 1, plain("Món ăn: " + BotConfig.itemName(c.foodItem)), () -> c.foodItem = BotConfig.nextItem(c.foodItem));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        String sub = "Trang " + (page + 1) + "/2  |  Waypoint: " + BerryBotClient.waypoints.size()
                + "  |  " + (BerryBotClient.state == BerryBotClient.State.OFF ? "Bot TẮT" : "Bot BẬT");
        g.drawCenteredString(this.font, sub, this.width / 2, 20, 0xFFAAAAAA);
        if (page == 1) {
            g.drawCenteredString(this.font, "Bấm nút Cho/Nhận/Order/Món ăn để đổi sang item kế tiếp",
                    this.width / 2, this.height - 40, 0xFF888888);
        }
    }
}
}
