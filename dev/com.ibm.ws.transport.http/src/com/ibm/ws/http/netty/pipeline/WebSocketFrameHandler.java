/*******************************************************************************
 * Copyright (c) 2023 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package com.ibm.ws.http.netty.pipeline;

import com.ibm.ws.http.netty.MSP;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

/**
 *
 */
public class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        // Handle Text Frames
        if (frame instanceof TextWebSocketFrame) {
            TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
            MSP.log("Received Text: " + textFrame.text());
            ctx.channel().writeAndFlush(new TextWebSocketFrame("Message received: " + textFrame.text()));
        }
        // Handle Binary Frames
        else if (frame instanceof BinaryWebSocketFrame) {
            BinaryWebSocketFrame binaryFrame = (BinaryWebSocketFrame) frame;
            MSP.log("Received Binary data");
            // Handle the binary data here
        }
        // Handle Ping Frames
        else if (frame instanceof PingWebSocketFrame) {
            System.out.println("Received Ping Frame");
            ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        }
        // Handle Pong Frames
        else if (frame instanceof PongWebSocketFrame) {
            MSP.log("Received Pong Frame");
            //TODO: handle pong?
        }
        // Handle Close Frames
        else if (frame instanceof CloseWebSocketFrame) {
            MSP.log("Received Close Frame");
            ctx.close();
        } else {
            MSP.log("Unsupported frame type: " + frame.getClass().getName());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
