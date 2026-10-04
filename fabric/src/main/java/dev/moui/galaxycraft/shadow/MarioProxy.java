package dev.moui.galaxycraft.shadow;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

/**
 * Mario in the shadow: a player standing where he stands on the planet, so mobs see, chase and
 * attack him there, arrows and explosions hit him, and his blows land from where he is. Whatever
 * hurts this stand-in hurts the real player instead (it never loses health itself).
 */
final class MarioProxy extends FakePlayer {
    static final GameProfile PROFILE = new GameProfile(UUID.fromString("6d617269-6f00-4000-8000-67616c617879"), "Mario");

    private UUID real;

    MarioProxy(ServerLevel level) {
        super(level, PROFILE);
    }

    void follow(UUID player) {
        real = player;
    }

    ServerPlayer real() {
        return real == null ? null : server().getPlayerList().getPlayer(real);
    }

    private net.minecraft.server.MinecraftServer server() {
        return ((ServerLevel) level()).getServer();
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        ServerPlayer p = real();
        return p == null || p.isInvulnerableTo((ServerLevel) p.level(), source);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        ServerPlayer p = real();
        if (p == null || !p.isAlive()) return false;
        DamageSource there = source.sourcePositionRaw() != null && source.getEntity() == null
                ? new DamageSource(source.typeHolder(), p.position())
                : new DamageSource(source.typeHolder(), source.getDirectEntity(), source.getEntity());
        return p.hurtServer((ServerLevel) p.level(), there, amount);
    }
}
