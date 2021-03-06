package mcmultipart.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.UUID;

import mcmultipart.MCMultiPartMod;
import mcmultipart.multipart.IMultipart;
import mcmultipart.multipart.IMultipartContainer;
import mcmultipart.multipart.MultipartHelper;
import mcmultipart.multipart.MultipartRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class MessageMultipartChange implements IMessage, IMessageHandler<MessageMultipartChange, MessageMultipartChange> {

    private Type type;
    private UUID partID;
    private String partType;
    private IMultipart part;
    private BlockPos pos;
    private byte[] data;

    private MessageMultipartChange(Type type, UUID partID, String partType, IMultipart part, BlockPos pos) {

        this.type = type;
        this.partID = partID;
        this.partType = partType;
        this.part = part;
        this.pos = pos;
    }

    public MessageMultipartChange() {

    }

    @Override
    public void toBytes(ByteBuf buf) {

        buf.writeInt(type.ordinal());
        buf.writeLong(partID.getMostSignificantBits());
        buf.writeLong(partID.getLeastSignificantBits());
        ByteBufUtils.writeUTF8String(buf, part.getType());
        buf.writeInt(pos.getX()).writeInt(pos.getY()).writeInt(pos.getZ());

        if (type == Type.ADD || type == Type.UPDATE || type == Type.UPDATE_RERENDER) {
            ByteBuf dataBuf = Unpooled.buffer();
            part.writeUpdatePacket(new PacketBuffer(dataBuf));
            data = dataBuf.array();
            dataBuf.clear();
            buf.writeMedium(data.length);
            buf.writeBytes(data);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {

        type = Type.VALUES[buf.readInt()];

        long msb = buf.readLong();
        long lsb = buf.readLong();
        partID = new UUID(msb, lsb);

        partType = ByteBufUtils.readUTF8String(buf);
        pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());

        if (type == Type.ADD || type == Type.UPDATE || type == Type.UPDATE_RERENDER) {
            data = new byte[buf.readUnsignedMedium()];
            buf.readBytes(data, 0, data.length);
        }
    }

    @Override
    public MessageMultipartChange onMessage(MessageMultipartChange message, MessageContext ctx) {

        if (ctx.side == Side.CLIENT) schedulePacketHandling(message);
        return null;
    }

    @SideOnly(Side.CLIENT)
    private void schedulePacketHandling(final MessageMultipartChange message) {

        Minecraft.getMinecraft().addScheduledTask(new Runnable() {

            @Override
            public void run() {

                MessageMultipartChange.handlePacket(message);
            }
        });
    }

    private static void handlePacket(MessageMultipartChange message) {

        EntityPlayer player = MCMultiPartMod.proxy.getPlayer();
        if (player == null || player.worldObj == null || message.pos == null || message.type == null) return;

        if (message.type == Type.ADD) {
            message.part = MultipartRegistry.createPart(message.partType, new PacketBuffer(Unpooled.copiedBuffer(message.data)));
            MultipartHelper.addPart(player.worldObj, message.pos, message.part, message.partID);

            if (message.part.getModelPath() != null) player.worldObj.markBlockRangeForRenderUpdate(message.pos, message.pos);
            player.worldObj.checkLight(message.pos);
        } else if (message.type == Type.REMOVE) {
            IMultipartContainer container = MultipartHelper.getPartContainer(player.worldObj, message.pos);
            if (container != null) {
                message.part = container.getPartFromID(message.partID);
                if (message.part != null) container.removePart(message.part);

                if (message.part == null || message.part.getModelPath() != null)
                    player.worldObj.markBlockRangeForRenderUpdate(message.pos, message.pos);
                player.worldObj.checkLight(message.pos);
            }
        } else if (message.type == Type.UPDATE || message.type == Type.UPDATE_RERENDER) {
            IMultipartContainer container = MultipartHelper.getPartContainer(player.worldObj, message.pos);
            if (container == null) return;
            message.part = container.getPartFromID(message.partID);

            if (message.part != null) {
                message.part.readUpdatePacket(new PacketBuffer(Unpooled.copiedBuffer(message.data)));

                if (message.type == Type.UPDATE_RERENDER) player.worldObj.markBlockRangeForRenderUpdate(message.pos, message.pos);
            }
        }
    }

    public void send(World world) {

        MultipartNetworkHandler.sendToAllWatching(this, world, pos);
    }

    public static MessageMultipartChange newPacket(World world, BlockPos pos, IMultipart part, Type type) {

        IMultipartContainer container = MultipartHelper.getPartContainer(world, pos);
        if (container == null)
            throw new IllegalStateException("Attempted to " + type.name().toLowerCase() + " a multipart at an illegal position!");
        return new MessageMultipartChange(type, container.getPartID(part), part.getType(), part, pos);
    }

    public static enum Type {
        ADD,
        REMOVE,
        UPDATE,
        UPDATE_RERENDER;

        public static final Type[] VALUES = values();
    }

}
