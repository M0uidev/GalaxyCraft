package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.UseBook;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/**
 * A guest's block use: the host's shadow runs Minecraft's own use of the block (doors, chests, levers), as this
 * player. The click waits for the host's answer; if nothing took it, the guest places its block as before.
 * Client thread only.
 */
final class MpUse {
    private static final UseBook book = new UseBook();
    private static int tick;

    private MpUse() {}

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.UseDone.TYPE,
                (done, ctx) -> ctx.client().execute(() -> book.settle(done.nonce(), done.passed())));
    }

    /** Whether this game is a guest on the host's galaxy: block use goes to the host. */
    static boolean guest() {
        return MpClient.connected() && Minecraft.getInstance().getSingleplayerServer() == null;
    }

    /**
     * Sends the click; onPass runs when nothing used it (also if the host cannot take it). False if it could not
     * be sent (too many waiting): the caller then goes on as if nothing used it.
     */
    static boolean ask(int planet, int cell, int face, double hx, double hy, double hz, InteractionHand hand, Runnable onPass) {
        int n = book.add(onPass, () -> {}, tick);
        if (n < 0) return false;
        ClientPlayNetworking.send(new MpPayloads.Use(n, planet, cell, face, (float) hx, (float) hy, (float) hz,
                hand == InteractionHand.MAIN_HAND ? 0 : 1));
        return true;
    }

    /** A block was placed outside creative: the server's inventory loses one. */
    static void usedUp(InteractionHand hand) {
        ClientPlayNetworking.send(new MpPayloads.UsedUp(hand == InteractionHand.MAIN_HAND ? 0 : 1));
    }

    static void tick() {
        if (book.expire(++tick) > 0) dev.moui.galaxycraft.GalaxyCraft.LOG.warn("[GalaxyCraft mp] a block use got no answer from the host");
    }

    static void reset() {
        book.clear();
    }
}
