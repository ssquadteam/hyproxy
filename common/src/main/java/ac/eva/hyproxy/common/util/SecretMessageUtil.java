package ac.eva.hyproxy.common.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

@UtilityClass
public class SecretMessageUtil {
    private static final int CLOCK_SKEW_SECONDS = 60;

    public @Nullable BackendReferralMessage validateAndDecodeReferralData(ByteBuf referralData, UUID expectedProfileId, byte[] secret) {
        try {
            int initialReaderIndex = referralData.readerIndex();

            UUID profileId = ProtocolUtil.readUUID(referralData);
            String backendId = ProtocolUtil.readVarString(referralData, 64);
            long issuedAt = referralData.readLong();

            int endReaderIndex = referralData.readerIndex();
            int hmacLength = VarIntUtil.read(referralData);

            if (hmacLength < 0 || hmacLength > 128) {
                return null;
            }

            byte[] givenHmac = new byte[hmacLength];
            referralData.readBytes(givenHmac);

            byte[] dump = new byte[endReaderIndex - initialReaderIndex];
            referralData.getBytes(initialReaderIndex, dump);

            byte[] expectedHmac = hmacSHA256(dump, secret);

            if (!MessageDigest.isEqual(givenHmac, expectedHmac)) {
                return null;
            }

            long nowSeconds = Instant.now().getEpochSecond();
            if (nowSeconds >= issuedAt + CLOCK_SKEW_SECONDS) {
                return null;
            }

            if (!expectedProfileId.equals(profileId)) {
                return null;
            }

            return new BackendReferralMessage(
                    profileId,
                    backendId,
                    issuedAt
            );
        } catch (Exception ex) {
            return null;
        } finally {
            referralData.release();
        }
    }

    @SneakyThrows
    public byte[] generateReferralData(BackendReferralMessage message, byte[] secret) {
        ByteBuf payloadBuf = Unpooled.buffer();

        ProtocolUtil.writeUUID(payloadBuf, message.profileId());
        ProtocolUtil.writeVarString(payloadBuf, message.backendId());
        payloadBuf.writeLong(message.issuedAt());

        byte[] payload = new byte[payloadBuf.readableBytes()];
        payloadBuf.readBytes(payload);
        payloadBuf.release();

        ByteBuf buf = Unpooled.buffer();
        buf.writeBytes(payload);

        byte[] hmac = hmacSHA256(payload, secret);
        VarIntUtil.write(buf, hmac.length);
        buf.writeBytes(hmac);

        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();

        return data;
    }

    public @Nullable BackendPlayerInfoMessage validateAndDecodePlayerInfoReferral(ByteBuf referralData, UUID expectedProfileId, String expectedUsername, String expectedBackendId, byte[] secret) {
        try {
            int initialReaderIndex = referralData.readerIndex();

            UUID profileId = ProtocolUtil.readUUID(referralData);
            String userName = ProtocolUtil.readVarString(referralData, 64);
            String backendId = ProtocolUtil.readVarString(referralData, 64);
            String remoteAddress = ProtocolUtil.readVarString(referralData, 500);
            long issuedAt = referralData.readLong();

            int endReaderIndex = referralData.readerIndex();
            int hmacLength = VarIntUtil.read(referralData);

            if (hmacLength < 0 || hmacLength > 128) {
                return null;
            }

            byte[] givenHmac = new byte[hmacLength];
            referralData.readBytes(givenHmac);

            byte[] dump = new byte[endReaderIndex - initialReaderIndex];
            referralData.getBytes(initialReaderIndex, dump);

            byte[] expectedHmac = hmacSHA256(dump, secret);

            if (!MessageDigest.isEqual(givenHmac, expectedHmac)) {
                return null;
            }

            long nowSeconds = Instant.now().getEpochSecond();
            if (nowSeconds >= issuedAt + CLOCK_SKEW_SECONDS) {
                return null;
            }

            if (!expectedProfileId.equals(profileId)) {
                return null;
            }

            if (!expectedUsername.equals(userName)) {
                return null;
            }

            if (!expectedBackendId.equals(backendId)) {
                return null;
            }

            return new BackendPlayerInfoMessage(
                    profileId,
                    userName,
                    backendId,
                    remoteAddress,
                    issuedAt
            );
        } catch (Exception ex) {
            return null;
        } finally {
            referralData.release();
        }
    }

    @SneakyThrows
    public byte[] generatePlayerInfoReferral(BackendPlayerInfoMessage message, byte[] secret) {
        ByteBuf payloadBuf = Unpooled.buffer();

        ProtocolUtil.writeUUID(payloadBuf, message.profileId());
        ProtocolUtil.writeVarString(payloadBuf, message.username());
        ProtocolUtil.writeVarString(payloadBuf, message.backendId());
        ProtocolUtil.writeVarString(payloadBuf, message.remoteAddress());
        payloadBuf.writeLong(message.issuedAt());

        byte[] payload = new byte[payloadBuf.readableBytes()];
        payloadBuf.readBytes(payload);
        payloadBuf.release();

        ByteBuf buf = Unpooled.buffer();
        buf.writeBytes(payload);

        byte[] hmac = hmacSHA256(payload, secret);
        VarIntUtil.write(buf, hmac.length);
        buf.writeBytes(hmac);

        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();

        return data;
    }

    private byte[] hmacSHA256(byte[] data, byte[] key) throws NoSuchAlgorithmException, InvalidKeyException {
        SecretKeySpec spec = new SecretKeySpec(key, "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(spec);
        return mac.doFinal(data);
    }

    public record BackendReferralMessage(
            UUID profileId,
            String backendId,
            long issuedAt
    ) {}

    public record BackendPlayerInfoMessage(
            UUID profileId,
            String username,
            String backendId,
            String remoteAddress,
            long issuedAt
    ) {}
}
