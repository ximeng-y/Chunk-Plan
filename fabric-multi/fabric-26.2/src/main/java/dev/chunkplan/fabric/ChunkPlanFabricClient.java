package dev.chunkplan.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.chunkplan.common.GuiStatus;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * ChunkPlan 客户端侧入口（Fabric 26.x，client entrypoint）。
 * 注册打开 GUI 的按键（默认 K），并接收服务端状态回推刷新已打开的界面。
 * 本类仅在客户端加载（client entrypoint），服务端不引用。
 */
public final class ChunkPlanFabricClient implements ClientModInitializer {

    /** 打开探索额度界面（默认 K，可在原版按键设置里改） */
    private static KeyMapping openGui;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("chunkplan", "keybinds"));
        openGui = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.chunkplan.open_gui", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));

        ClientPlayNetworking.registerGlobalReceiver(ChunkPlanNetwork.GuiStatusPayload.TYPE,
                (payload, context) -> onStatus(payload.status()));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 未连接服务器（主菜单/世界选择页）不打开界面：sendRequest 会抛 IllegalStateException
            if (openGui.consumeClick() && Minecraft.getInstance().getConnection() != null) {
                Minecraft.getInstance().setScreenAndShow(new ChunkPlanGuiScreen());
            }
        });
    }

    /** 服务端状态回推（S2C handler 调用，仅客户端触发） */
    public static void onStatus(byte[] data) {
        GuiStatus status = GuiStatus.decode(data);
        if (Minecraft.getInstance().gui.screen() instanceof ChunkPlanGuiScreen s) {
            s.onStatus(status);
        }
    }

    /** 向服务端请求状态（打开界面时调用；界面打开期间断线再点刷新也会走此路径，
     *  未连接直接放弃——ClientPlayNetworking.send 对空连接抛 IllegalStateException） */
    public static void sendRequest() {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(new ChunkPlanNetwork.GuiRequestPayload(GuiStatus.PROTOCOL_VERSION));
    }

    /** 向服务端发送 GUI 操作命令串（未连接直接放弃，防 IllegalStateException） */
    public static void sendCommand(String command) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(new ChunkPlanNetwork.GuiCommandPayload(command));
    }
}
