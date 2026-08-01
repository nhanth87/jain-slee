/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.nio.ByteBuffer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jdiameter.api.Message;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.client.impl.parser.MessageParser;

/** Netty channel handler: parse raw bytes → JDiameter Message → sink; encode replies. */
final class DiameterNettyCodec extends SimpleChannelInboundHandler<ByteBuf> {
    private static final Logger LOG = LogManager.getLogger(DiameterNettyCodec.class);

    private final MessageParser parser;
    private final String peerId;
    private final DiameterTransportCallbacks callbacks;

    DiameterNettyCodec(MessageParser parser, String peerId, DiameterTransportCallbacks callbacks) {
        this.parser = parser;
        this.peerId = peerId;
        this.callbacks = callbacks;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        callbacks.onPeerConnected(peerId);
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        callbacks.onPeerDisconnected(peerId);
        ctx.fireChannelInactive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
        byte[] raw = new byte[buf.readableBytes()];
        buf.readBytes(raw);
        try {
            Message msg = parser.createMessage(raw);
            callbacks.onMessage(peerId, msg, answer -> writeAnswer(ctx, answer));
        } catch (Exception e) {
            LOG.warn("Diameter parse error peer={}", peerId, e);
        }
    }

    private void writeAnswer(ChannelHandlerContext ctx, Message answer) {
        try {
            if (!(answer instanceof IMessage imsg)) {
                LOG.warn("Cannot encode non-IMessage answer peer={}", peerId);
                return;
            }
            ByteBuffer encoded = parser.encodeMessage(imsg);
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            ctx.writeAndFlush(Unpooled.wrappedBuffer(bytes));
        } catch (Exception e) {
            LOG.warn("Diameter encode/write failed peer={}", peerId, e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.warn("Diameter transport error peer={}", peerId, cause);
        ctx.close();
    }
}
