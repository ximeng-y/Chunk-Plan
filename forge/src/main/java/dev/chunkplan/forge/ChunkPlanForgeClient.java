package dev.chunkplan.forge;

import com.mojang.blaze3d.platform.InputConstants;
import dev.chunkplan.common.GuiStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * ChunkPlan 客户端侧入口（Forge 1.20.1，Dist.CLIENT）。
 * 注册打开 GUI 的按键（默认 K），并接收服务端状态回推刷新已打开的界面。
 * 本类仅在客户端加载：服务端代码不得引用（防 dedicated server NoClassDefFoundError）。
 */
public final class ChunkPlanForgeClient {

    /** 打开探索额度界面（默认 K，可在原版按键设置里改） */
    public static final KeyMapping OPEN_GUI = new KeyMapping(
            "key.chunkplan.open_gui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.chunkplan");

    private ChunkPlanForgeClient() {
    }

    /** MOD 总线（仅客户端）：注册按键映射 */
    @Mod.EventBusSubscriber(modid = ChunkPlanForge.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_GUI);
        }
    }

    /** FORGE 总线（仅客户端）：轮询按键并打开界面 */
    @Mod.EventBusSubscriber(modid = ChunkPlanForge.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class GameEvents {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            if (OPEN_GUI.consumeClick()) {
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

    /** 向服务端请求状态（打开界面时调用） */
    public static void sendRequest() {
        ChunkPlanNetwork.CHANNEL.sendToServer(new ChunkPlanNetwork.GuiRequestPayload(GuiStatus.PROTOCOL_VERSION));
    }

    /** 向服务端发送 GUI 操作命令串 */
    public static void sendCommand(String command) {
        ChunkPlanNetwork.CHANNEL.sendToServer(new ChunkPlanNetwork.GuiCommandPayload(command));
    }
}
