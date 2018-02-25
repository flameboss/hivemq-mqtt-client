package org.mqttbee.mqtt.codec.decoder.mqtt5;

import com.google.common.base.Utf8;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.ImmutableIntArray;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.mqttbee.annotations.NotNull;
import org.mqttbee.annotations.Nullable;
import org.mqttbee.api.mqtt.datatypes.MqttQoS;
import org.mqttbee.api.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode;
import org.mqttbee.api.mqtt.mqtt5.message.publish.Mqtt5PayloadFormatIndicator;
import org.mqttbee.api.mqtt.mqtt5.message.publish.TopicAliasUsage;
import org.mqttbee.mqtt.MqttClientConnectionDataImpl;
import org.mqttbee.mqtt.codec.decoder.MqttMessageDecoder;
import org.mqttbee.mqtt.codec.encoder.mqtt5.Mqtt5PublishEncoder;
import org.mqttbee.mqtt.datatypes.*;
import org.mqttbee.mqtt.message.publish.MqttPublishImpl;
import org.mqttbee.mqtt.message.publish.MqttPublishWrapper;
import org.mqttbee.mqtt5.netty.ChannelAttributes;
import org.mqttbee.util.ByteBufferUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.ByteBuffer;

import static org.mqttbee.mqtt.codec.decoder.MqttMessageDecoderUtil.disconnectRemainingLengthTooShort;
import static org.mqttbee.mqtt.codec.decoder.mqtt5.Mqtt5MessageDecoderUtil.*;
import static org.mqttbee.mqtt.message.publish.MqttPublishImpl.MESSAGE_EXPIRY_INTERVAL_INFINITY;
import static org.mqttbee.mqtt.message.publish.MqttPublishProperty.*;
import static org.mqttbee.mqtt.message.publish.MqttPublishWrapper.*;
import static org.mqttbee.mqtt5.handler.disconnect.MqttDisconnectUtil.disconnect;

/**
 * @author Silvio Giebl
 */
@Singleton
public class Mqtt5PublishDecoder implements MqttMessageDecoder {

    private static final int MIN_REMAINING_LENGTH = 3; // topic name (min 2) + property length (min 1)

    @Inject
    Mqtt5PublishDecoder() {
    }

    @Override
    @Nullable
    public MqttPublishWrapper decode(
            final int flags, @NotNull final ByteBuf in,
            @NotNull final MqttClientConnectionDataImpl clientConnectionData) {

        final Channel channel = clientConnectionData.getChannel();

        final boolean dup = (flags & 0b1000) != 0;
        final boolean retain = (flags & 0b0001) != 0;

        final MqttQoS qos = MqttQoS.fromCode((flags & 0b0110) >> 1);
        if (qos == null) {
            disconnect(channel, Mqtt5DisconnectReasonCode.MALFORMED_PACKET, "wrong QoS");
            return null;
        }
        if ((qos == MqttQoS.AT_MOST_ONCE) && dup) {
            disconnect(channel, Mqtt5DisconnectReasonCode.PROTOCOL_ERROR, "DUP flag must be 0 if QoS is 0");
            return null;
        }

        if (in.readableBytes() < MIN_REMAINING_LENGTH) {
            disconnectRemainingLengthTooShort(channel);
            return null;
        }

        final byte[] topicBinary = MqttBinaryData.decode(in);
        if (topicBinary == null) {
            disconnect(channel, Mqtt5DisconnectReasonCode.TOPIC_NAME_INVALID, "malformed topic");
            return null;
        }
        MqttTopicImpl topic = null;
        if (topicBinary.length != 0) {
            topic = MqttTopicImpl.from(topicBinary);
            if (topic == null) {
                disconnect(channel, Mqtt5DisconnectReasonCode.TOPIC_NAME_INVALID, "malformed topic");
                return null;
            }
        }

        int packetIdentifier = NO_PACKET_IDENTIFIER_QOS_0;
        if (qos != MqttQoS.AT_MOST_ONCE) {
            if (in.readableBytes() < 2) {
                disconnectRemainingLengthTooShort(channel);
                return null;
            }
            packetIdentifier = in.readUnsignedShort();
        }

        final int propertyLength = MqttVariableByteInteger.decode(in);
        if (propertyLength < 0) {
            disconnectMalformedPropertyLength(channel);
            return null;
        }
        if (in.readableBytes() < propertyLength) {
            disconnectRemainingLengthTooShort(channel);
            return null;
        }

        long messageExpiryInterval = MESSAGE_EXPIRY_INTERVAL_INFINITY;
        Mqtt5PayloadFormatIndicator payloadFormatIndicator = null;
        MqttUTF8StringImpl contentType = null;
        MqttTopicImpl responseTopic = null;
        ByteBuffer correlationData = null;
        ImmutableList.Builder<MqttUserPropertyImpl> userPropertiesBuilder = null;
        int topicAlias = DEFAULT_NO_TOPIC_ALIAS;
        TopicAliasUsage topicAliasUsage = TopicAliasUsage.HAS_NOT;
        ImmutableIntArray.Builder subscriptionIdentifiersBuilder = null;

        final int propertiesStartIndex = in.readerIndex();
        int readPropertyLength;
        while ((readPropertyLength = in.readerIndex() - propertiesStartIndex) < propertyLength) {

            final int propertyIdentifier = MqttVariableByteInteger.decode(in);
            if (propertyIdentifier < 0) {
                disconnectMalformedPropertyIdentifier(channel);
                return null;
            }

            switch (propertyIdentifier) {
                case MESSAGE_EXPIRY_INTERVAL:
                    if (!checkIntOnlyOnce(messageExpiryInterval, MESSAGE_EXPIRY_INTERVAL_INFINITY,
                            "message expiry interval", channel, in)) {
                        return null;
                    }
                    messageExpiryInterval = in.readUnsignedInt();
                    break;

                case PAYLOAD_FORMAT_INDICATOR:
                    if (!checkByteOnlyOnce(payloadFormatIndicator != null, "payload format indicator", channel, in)) {
                        return null;
                    }
                    payloadFormatIndicator = Mqtt5PayloadFormatIndicator.fromCode(in.readUnsignedByte());
                    if (payloadFormatIndicator == null) {
                        disconnect(
                                channel, Mqtt5DisconnectReasonCode.MALFORMED_PACKET, " wrong payload format indicator");
                        return null;
                    }
                    break;

                case CONTENT_TYPE:
                    contentType = decodeUTF8StringOnlyOnce(contentType, "content type", channel, in);
                    if (contentType == null) {
                        return null;
                    }
                    break;

                case RESPONSE_TOPIC:
                    if (responseTopic != null) {
                        disconnectOnlyOnce(channel, "response topic");
                        return null;
                    }
                    responseTopic = MqttTopicImpl.from(in);
                    if (responseTopic == null) {
                        disconnect(channel, Mqtt5DisconnectReasonCode.TOPIC_NAME_INVALID, "malformed response topic");
                        return null;
                    }
                    break;

                case CORRELATION_DATA:
                    correlationData = decodeBinaryDataOnlyOnce(correlationData, "correlation data", channel, in,
                            ChannelAttributes.useDirectBufferForCorrelationData(channel));
                    if (correlationData == null) {
                        return null;
                    }
                    break;

                case USER_PROPERTY:
                    userPropertiesBuilder = decodeUserProperty(userPropertiesBuilder, channel, in);
                    if (userPropertiesBuilder == null) {
                        return null;
                    }
                    break;

                case TOPIC_ALIAS:
                    if (!checkShortOnlyOnce(topicAlias, DEFAULT_NO_TOPIC_ALIAS, "topic alias", channel, in)) {
                        return null;
                    }
                    topicAlias = in.readUnsignedShort();
                    if (topicAlias == 0) {
                        disconnect(channel, Mqtt5DisconnectReasonCode.TOPIC_ALIAS_INVALID, "topic alias must not be 0");
                        return null;
                    }
                    topicAliasUsage = TopicAliasUsage.HAS;
                    break;

                case SUBSCRIPTION_IDENTIFIER:
                    if (subscriptionIdentifiersBuilder == null) {
                        subscriptionIdentifiersBuilder = ImmutableIntArray.builder();
                    }
                    final int subscriptionIdentifier = MqttVariableByteInteger.decode(in);
                    if (subscriptionIdentifier < 0) {
                        disconnect(channel, Mqtt5DisconnectReasonCode.MALFORMED_PACKET,
                                "malformed subscription identifier");
                        return null;
                    }
                    if (subscriptionIdentifier == 0) {
                        disconnect(channel, Mqtt5DisconnectReasonCode.PROTOCOL_ERROR,
                                "subscription identifier must not be 0");
                        return null;
                    }
                    subscriptionIdentifiersBuilder.add(subscriptionIdentifier);
                    break;

                default:
                    disconnectWrongProperty(channel, "PUBLISH");
                    return null;
            }
        }

        if (readPropertyLength != propertyLength) {
            disconnectMalformedPropertyLength(channel);
            return null;
        }

        boolean isNewTopicAlias = false;
        if (topicAlias != DEFAULT_NO_TOPIC_ALIAS) {
            final MqttTopicImpl[] topicAliasMapping = clientConnectionData.getTopicAliasMapping();
            if ((topicAliasMapping == null) || (topicAlias > topicAliasMapping.length)) {
                disconnect(channel, Mqtt5DisconnectReasonCode.TOPIC_ALIAS_INVALID,
                        "topic alias must not exceed topic alias maximum");
                return null;
            }
            if (topic == null) {
                topic = topicAliasMapping[topicAlias - 1];
                if (topic == null) {
                    disconnect(channel, Mqtt5DisconnectReasonCode.TOPIC_ALIAS_INVALID, "topic alias has no mapping");
                    return null;
                }
            } else {
                topicAliasMapping[topicAlias - 1] = topic;
                isNewTopicAlias = true;
            }
        } else if (topic == null) {
            disconnect(channel, Mqtt5DisconnectReasonCode.TOPIC_ALIAS_INVALID,
                    "topic alias must be present if topic name is zero length");
            return null;
        }

        final int payloadLength = in.readableBytes();
        ByteBuffer payload = null;
        if (payloadLength > 0) {
            payload = ByteBufferUtil.allocate(payloadLength, ChannelAttributes.useDirectBufferForPayload(channel));
            in.readBytes(payload);
            payload.position(0);

            if (payloadFormatIndicator == Mqtt5PayloadFormatIndicator.UTF_8) {
                if (ChannelAttributes.validatePayloadFormat(channel)) {
                    if (!Utf8.isWellFormed(ByteBufferUtil.getBytes(payload))) {
                        disconnect(channel, Mqtt5DisconnectReasonCode.PAYLOAD_FORMAT_INVALID,
                                "payload is not valid UTF-8");
                        return null;
                    }
                }
            }
        }

        final MqttUserPropertiesImpl userProperties = MqttUserPropertiesImpl.build(userPropertiesBuilder);

        final MqttPublishImpl publish =
                new MqttPublishImpl(topic, payload, qos, retain, messageExpiryInterval, payloadFormatIndicator,
                        contentType, responseTopic, correlationData, topicAliasUsage, userProperties,
                        Mqtt5PublishEncoder.PROVIDER);

        final ImmutableIntArray subscriptionIdentifiers =
                (subscriptionIdentifiersBuilder == null) ? DEFAULT_NO_SUBSCRIPTION_IDENTIFIERS :
                        subscriptionIdentifiersBuilder.build();

        return publish.wrap(packetIdentifier, dup, topicAlias, isNewTopicAlias, subscriptionIdentifiers);
    }

}