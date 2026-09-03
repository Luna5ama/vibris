package dev.vibris.mod.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.luna5ama.vibris.capture.CaptureManager;
import dev.vibris.mod.IrisVibrisLifecycle;
import dev.vibris.mod.VibrisClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

public final class VibrisFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("vibris", "keybinds"));
		KeyMapping flameGraph = KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.vibris.toggle_flame_graph", GLFW.GLFW_KEY_F10, category));
		VibrisClient.flameGraph().setToggleKey(flameGraph);
		VibrisClient.flameGraph().setPauseKey(KeyBindingHelper.registerKeyBinding(
			new KeyMapping("key.vibris.pause_flame_graph", GLFW.GLFW_KEY_F9, category)));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("vibris")
				.then(ClientCommandManager.literal("preset")
					.then(ClientCommandManager.literal("save")
						.then(ClientCommandManager.argument("id", StringArgumentType.word())
							.executes(ctx -> savePreset(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))));
			dispatcher.register(ClientCommandManager.literal("capture")
				.then(ClientCommandManager.argument("pass", StringArgumentType.word())
					.executes(ctx -> queueCapture(ctx.getSource(), StringArgumentType.getString(ctx, "pass"), false))));
			dispatcher.register(ClientCommandManager.literal("capturemulti")
				.then(ClientCommandManager.argument("type", StringArgumentType.word())
					.executes(ctx -> queueCapture(ctx.getSource(), StringArgumentType.getString(ctx, "type"), true))));
		});
	}

	private static int savePreset(FabricClientCommandSource source, String id) {
		try {
			String preset = IrisVibrisLifecycle.savePreset(id);
			source.sendFeedback(Component.literal("Saved Vibris preset: " + preset));
			return 1;
		} catch (Exception exception) {
			source.sendError(Component.literal("Failed to save Vibris preset: " + exception.getMessage()));
			return 0;
		}
	}

	private static int queueCapture(FabricClientCommandSource source, String target, boolean multi) {
		Path path = CaptureManager.defaultOutputPath(target);
		if (multi) VibrisClient.captureManager().prepareMultiCapture(path, target);
		else VibrisClient.captureManager().prepareSingleCapture(path, target);
		source.sendFeedback(Component.literal("Queued vibris capture: " + path));
		return 1;
	}
}
