package dev.chunkplan.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import dev.chunkplan.common.GuiStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * ChunkPlan 客户端侧入口（NeoForge 1.21.1，Dist.CLIENT）。
 * 注册打开 GUI 的按键（默认 K），并接收服务端状态回推刷新已打开的界面。
 * 本类仅在客户端加载：服务端代码不得引用（防 dedicated server NoClassDefFoundError）。
 */
public final class ChunkPlanClient {

    /** 打开探索额度界面（默认 K，可在原版按键设置里改） */
    public static final KeyMapping OPEN_GUI = new KeyMapping(
            "key.chunkplan.open_gui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.chunkplan");

    private ChunkPlanClient() {
    }

    /** MOD 总线（仅客户端）：注册按键映射 */
    @EventBusSubscriber(modid = ChunkPlanNeoForge.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_GUI);
        }
    }

    /** 游戏事件总线（仅客户端）：轮询按键并打开界面 */
    @EventBusSubscriber(modid = ChunkPlanNeoForge.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
    public static final class GameEvents {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            // 未连接服务器（主菜单/世界选择页）不打开界面：sendRequest 会因空连接崩溃
            if (OPEN_GUI.consumeClick() && Minecraft.getInstance().getConnection() != null) {
                Minecraft.getInstance().setScreen(new ChunkPlanGuiScreen());
            }
        }
    }

    /** 服务端状态回推（S2C handler 调用，仅客户端触发） */
    public static void onStatus(byte[] data) {
        GuiStatus status = GuiStatus.decode(data);
        if (Minecraft.getInstance().screen instanceof ChunkPlanGuiScreen s) {
            s.onStatus(status);
        }
    }

    /** 向服务端请求状态（打开界面时调用；界面打开期间断线再点刷新也会走此路径，
     *  未连接直接放弃——sendToServer 对空连接 requireNonNull 抛 NPE） */
    public static void sendRequest() {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        PacketDistributor.sendToServer(new ChunkPlanNetwork.GuiRequestPayload(GuiStatus.PROTOCOL_VERSION));
    }

    /** 向服务端发送 GUI 操作命令串（未连接直接放弃，防 NPE） */
    public static void sendCommand(String command) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        PacketDistributor.sendToServer(new ChunkPlanNetwork.GuiCommandPayload(command));
    }
}
