package draylar.goml.test;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.impl.networking.UntrackedPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A fake player that keeps every packet the server sends to it, so tests can check messages, effects and particles.
 * Unlike {@link FakePlayer#get}, every instance is new, so no state leaks between tests.
 */
public class RecordingPlayer extends FakePlayer {
    public final List<Packet<?>> packets = new CopyOnWriteArrayList<>();

    public RecordingPlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
        this.connection = new Listener(this);
    }

    public List<ClientboundSystemChatPacket> chat() {
        return this.packets.stream()
                .filter(packet -> packet instanceof ClientboundSystemChatPacket)
                .map(packet -> (ClientboundSystemChatPacket) packet)
                .toList();
    }

    public List<Component> messages(boolean overlay) {
        return this.chat().stream().filter(packet -> packet.overlay() == overlay).map(ClientboundSystemChatPacket::content).toList();
    }

    public long count(Class<?> packetType) {
        return this.packets.stream().filter(packetType::isInstance).count();
    }

    // Untracked like Fabric's own fake player listener, so no networking events fire for it
    private static final class Listener extends ServerGamePacketListenerImpl implements UntrackedPacketListener {
        private Listener(RecordingPlayer player) {
            super(player.level().getServer(), new Connection(PacketFlow.CLIENTBOUND) {}, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }

        @Override
        public void send(Packet<?> packet, @Nullable ChannelFutureListener listener) {
            if (this.player instanceof RecordingPlayer recorder) {
                recorder.packets.add(packet);
            }
        }
    }
}
