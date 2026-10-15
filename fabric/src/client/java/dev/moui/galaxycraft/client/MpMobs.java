package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.net.MobsCodec;
import dev.moui.galaxycraft.net.MpPayloads;
import dev.moui.galaxycraft.net.MpServer;
import dev.moui.galaxycraft.shadow.ShadowToads;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Entities of the host's shadow, for the guests. The host (every few ticks) turns the shadow's entities into a
 * {@link MobsCodec} body and sends it to each guest that holds the running planet; the guest builds plain Minecraft
 * entities from it (never added to a level: only for their renderers) and EntityClient draws them where the host
 * says they are. Client thread only.
 */
final class MpMobs {
    /** The host sends this often (client ticks). */
    private static final int EVERY = 4;
    /** A mob not heard of for this long (client ticks) is gone. */
    private static final int STALE = 40;

    /** A mob on the guest: the entity, and the frame it moves from and to between two snapshots. */
    static final class Remote {
        final int id;
        final Entity entity;
        float[] from, to;
        float yawFrom, yawTo, headFrom, headTo, pitch, speed;
        int hurt, death;
        long at; // nanoTime the latest snapshot arrived
        int heard;

        Remote(int id, Entity entity) {
            this.id = id;
            this.entity = entity;
        }
    }

    private static final boolean TRACE = System.getenv("GXC_MP_TRACE") != null;
    private static int planet = -1;
    private static final Map<Integer, Remote> remotes = new HashMap<>();
    private static int tick;

    private MpMobs() {}

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MpPayloads.Mobs.TYPE,
                (m, ctx) -> ctx.client().execute(() -> receive(m.data())));
    }

    static void reset() {
        remotes.clear();
        planet = -1;
    }

    // ---- host

    /** Host, every client tick: sends the guests that hold the running planet what stands in the shadow. */
    static void hostTick(int now, VoxelPlanet planet, int planetIndex, java.util.Collection<UUID> holders) {
        ShadowWorld.Entities es = ShadowWorld.entitiesOf(planet);
        if (es == null || holders.isEmpty() || now % EVERY != 0) return;
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        List<MobsCodec.Mob> mobs = new ArrayList<>();
        for (Entity e : es.list()) {
            if (e.isRemoved() || e instanceof Player || ShadowToads.idOf(e) != null) continue;
            double[] f = es.map().frame(e.getX(), e.getY(), e.getZ());
            if (f == null) continue;
            float[] frame = new float[12];
            for (int i = 0; i < 12; i++) frame[i] = (float) f[i];
            float yaw = e instanceof LivingEntity l ? l.yBodyRot : e.getYRot();
            float head = e instanceof LivingEntity l ? l.yHeadRot : yaw;
            float speed = e instanceof LivingEntity l ? l.walkAnimation.speed() : 0;
            byte[] data;
            try {
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
                for (SynchedEntityData.DataValue<?> v : e.getEntityData().getNonDefaultValues()) v.write(buf);
                buf.writeByte(255); // the end, as in Minecraft's own data packet
                data = new byte[buf.readableBytes()];
                buf.readBytes(data);
            } catch (RuntimeException ex) {
                continue;
            }
            if (data.length > MobsCodec.MAX_DATA) continue;
            mobs.add(new MobsCodec.Mob(e.getId(), BuiltInRegistries.ENTITY_TYPE.getId(e.getType()), frame, yaw, e.getXRot(), head, speed,
                    e instanceof LivingEntity h ? h.hurtTime : 0, e instanceof LivingEntity h ? h.deathTime : 0, data));
        }
        if (TRACE && now % 40 == 0) GalaxyCraft.LOG.info("[mp mobs] host: {} shadow entities, {} sent for planet {} to {} guests", es.list().size(), mobs.size(), planetIndex, holders.size());
        byte[] body = MobsCodec.encode(planetIndex, mobs);
        for (UUID g : holders) MpServer.mobs(g, body);
    }

    // ---- guest

    private static void receive(byte[] bytes) {
        MobsCodec.Body b = MobsCodec.decode(bytes);
        var mc = Minecraft.getInstance();
        if (b == null || mc.level == null || !MpUse.guest()) return;
        if (b.planet() != planet) {
            remotes.clear();
            planet = b.planet();
        }
        if (TRACE && tick % 40 == 0) GalaxyCraft.LOG.info("[mp mobs] guest: {} mobs for planet {}", b.mobs().size(), b.planet());
        long nowNs = System.nanoTime();
        for (MobsCodec.Mob m : b.mobs()) {
            Remote r = remotes.get(m.id());
            if (r == null) {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(m.type());
                Entity e;
                try {
                    e = type == null ? null : type.create(mc.level, EntitySpawnReason.LOAD);
                } catch (RuntimeException ex) {
                    e = null;
                }
                if (e == null) continue;
                e.setId(ID_BASE + m.id()); // never one of the client level's own ids
                r = new Remote(m.id(), e);
                r.from = m.frame();
                r.yawFrom = m.yaw();
                r.headFrom = m.headYaw();
                remotes.put(m.id(), r);
            } else {
                r.from = now(r);
                r.yawFrom = lerpYaw(r.yawFrom, r.yawTo, fraction(r));
                r.headFrom = lerpYaw(r.headFrom, r.headTo, fraction(r));
            }
            r.to = m.frame();
            r.yawTo = m.yaw();
            r.headTo = m.headYaw();
            r.pitch = m.pitch();
            r.speed = m.walkSpeed();
            r.hurt = m.hurt();
            r.death = m.death();
            r.at = nowNs;
            r.heard = tick;
            try {
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(m.data()), mc.level.registryAccess());
                List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
                for (int id; (id = buf.readUnsignedByte()) != 255; ) values.add(SynchedEntityData.DataValue.read(buf, id));
                r.entity.getEntityData().assignValues(values);
            } catch (RuntimeException ex) {
                GalaxyCraft.LOG.debug("[GalaxyCraft mp] bad entity data for {}: {}", r.entity.getType(), ex.toString());
            }
        }
    }

    static void tick() {
        tick++;
        for (var it = remotes.values().iterator(); it.hasNext(); ) {
            Remote r = it.next();
            if (tick - r.heard > STALE) {
                it.remove();
                continue;
            }
            if (r.entity instanceof LivingEntity l) {
                l.walkAnimation.update(r.speed, 0.4f, 1f);
                l.tickCount++;
                if (r.hurt > 0) r.hurt--; // as Minecraft counts it down between the host's reports
                l.hurtTime = r.hurt;
                l.deathTime = r.death;
            }
        }
    }

    /** Mobs have this id offset on the guest: the host's entity id is the guest entity's minus it. */
    static final int ID_BASE = 0x40000000;

    /** Guest: hit (kind 0) or use (kind 1) a mob of the host's shadow; look: the way the guest looks, in the planet's space. */
    static void hit(int planetIndex, Entity guestEntity, int kind, org.joml.Vector3d look) {
        ClientPlayNetworking.send(new MpPayloads.MobHit(planetIndex, guestEntity.getId() - ID_BASE, kind,
                (float) look.x, (float) look.y, (float) look.z));
    }

    /** Guest: the mobs of this planet (by catalog index), or none. */
    static java.util.Collection<Remote> of(int planetIndex) {
        return planetIndex == planet ? remotes.values() : List.of();
    }

    // ---- between snapshots (the host sends 5 a second; they glide over one)

    private static final double GLIDE_NS = 0.2e9;

    private static float fraction(Remote r) {
        return (float) Math.min(1, (System.nanoTime() - r.at) / GLIDE_NS);
    }

    static float[] now(Remote r) {
        float t = fraction(r);
        float[] out = new float[12];
        for (int i = 0; i < 12; i++) out[i] = r.from[i] + (r.to[i] - r.from[i]) * t;
        return out;
    }

    static float yaw(Remote r) {
        return lerpYaw(r.yawFrom, r.yawTo, fraction(r));
    }

    static float head(Remote r) {
        return lerpYaw(r.headFrom, r.headTo, fraction(r));
    }

    private static float lerpYaw(float a, float b, float t) {
        float d = ((b - a) % 360 + 540) % 360 - 180;
        return a + d * t;
    }
}
