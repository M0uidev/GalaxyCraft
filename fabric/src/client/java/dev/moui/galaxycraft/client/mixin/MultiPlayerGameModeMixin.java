package dev.moui.galaxycraft.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A guest's Q on a planet the host runs: the host throws the item onto the planet (the server's own throw would land in the host's coordinates). */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$guestThrow(LocalPlayer player, boolean all, CallbackInfo ci) {
        // Crouching, the drop key throws the whole stack at once (as Ctrl does).
        if (!all && player.isShiftKeyDown()) {
            ((MultiPlayerGameMode) (Object) this).dropItem(player, true);
            ci.cancel();
            return;
        }
        if (dev.moui.galaxycraft.client.PlanetClient.guestThrow(player, all)) ci.cancel();
    }

    /** A guest dropping from an open inventory (a click outside it, Q or Ctrl+Q over a slot): thrown through the host like the drop key. */
    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void galaxycraft$guestInventoryDrop(int containerId, int slotNum, int buttonNum, ContainerInput input, Player player,
            CallbackInfo ci) {
        if (!(player instanceof LocalPlayer local) || containerId != player.containerMenu.containerId) return;
        if (input == ContainerInput.THROW || slotNum == net.minecraft.world.inventory.AbstractContainerMenu.SLOT_CLICKED_OUTSIDE)
            dev.moui.galaxycraft.GalaxyCraft.LOG.info("[mp trace] container click {} slot {} button {}", input, slotNum, buttonNum);
        boolean outside = input == ContainerInput.PICKUP && slotNum == net.minecraft.world.inventory.AbstractContainerMenu.SLOT_CLICKED_OUTSIDE
                && !player.containerMenu.getCarried().isEmpty();
        boolean key = input == ContainerInput.THROW && slotNum >= 0 && player.containerMenu.getSlot(slotNum).hasItem();
        if (!outside && !key) return;
        // Outside, the left button throws the whole stack on the cursor and the right one a single item; Q throws one, Ctrl+Q all.
        boolean all = outside ? buttonNum == 0 : buttonNum != 0;
        if (dev.moui.galaxycraft.client.PlanetClient.guestThrow(local, all, containerId, outside ? -1 : slotNum)) ci.cancel();
    }
}
