package com.ibm.ws.http.netty.pipeline.inbound;

import java.util.concurrent.atomic.AtomicBoolean;

import com.ibm.ws.http.netty.NettyHttpConstants;
import com.ibm.ws.http.netty.message.BodyQueue;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;

public final class ReadFlowHandler extends ChannelDuplexHandler{

    public static final AttributeKey<FlowState> FLOW_KEY = AttributeKey.valueOf("http.flow.state");

    public ReadFlowHandler(){}

    public static final class FlowState {
        public volatile boolean requestConsumed; // set when request body is fully read or proven empty
        public volatile boolean responseInFlight; // set true at first commit; false on final write future
        public volatile boolean keepAliveAllowed; // writer decides based on response & policy
        public volatile boolean closedOrUpgraded; // shutdown/upgrade says “never resume”
        public volatile boolean headRequest; // request is HEAD
    }

    private static FlowState state(ChannelHandlerContext context){
        FlowState state = context.channel().attr(FLOW_KEY).get();
        if(state == null){
            state = new FlowState();
            context.channel().attr(FLOW_KEY).set(state);
        }
        return state;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {

        if (!Boolean.TRUE.equals(context.channel().attr(NettyHttpConstants.UPGRADED).get())
            && context.channel().config().isAutoRead()) {
            context.channel().config().setAutoRead(false);
        }

        FlowState state = state(context);
        state.requestConsumed     = false;
        state.responseInFlight    = false;
        state.keepAliveAllowed    = true;
        state.closedOrUpgraded    = false;
        state.headRequest = false;
        super.channelActive(context);

        ReadFlowHandler.clearReadPending(context);
        context.read();
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {

        
        FlowState state = state(context);
        if(message instanceof HttpRequest){
            HttpRequest request = (HttpRequest) message;
            state.headRequest = request.method() == HttpMethod.HEAD;
            state.requestConsumed = state.headRequest || !isBodyExpected(request);
            super.channelRead(context, message);
            return;
        }

        if(message instanceof LastHttpContent){
            state.requestConsumed = true;
            super.channelRead(context, message);
            return;
        }

        super.channelRead(context, message);
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        FlowState state = state(context);

        if (message instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) message;
            int code = response.status().code();
            boolean informational = (code >= 100 && code < 200 && code != 101);
            state.keepAliveAllowed = HttpUtil.isKeepAlive(response);

            if (!informational) {
                state.responseInFlight = true; 
                if (message instanceof FullHttpResponse) {
                    promise.addListener((ChannelFutureListener) f -> state.responseInFlight = false);
                }         
            }
        }
        
        if (message instanceof LastHttpContent) {
            // When the final chunk is flushed, we may allow one more read
            promise.addListener((ChannelFutureListener) f -> {
                state.responseInFlight = false;
            });
        }

        super.write(context, message, promise);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent || evt instanceof ChannelInputShutdownReadComplete) {
            FlowState state = state(context);
            state.closedOrUpgraded = true;
            state.keepAliveAllowed = false;
            if (!state.responseInFlight) {
                return;
            }
        }
        super.userEventTriggered(context, evt);
    }

    public static FlowState getFlowState(Channel channel) {
        return channel.attr(FLOW_KEY).get();
    }

    public static void markRequestConsumed(ChannelHandlerContext context) {
        FlowState state = state(context);
        state.requestConsumed = true;
    }

    public static void setClosedOrUpgraded(ChannelHandlerContext context) {
        FlowState state = state(context);
        state.closedOrUpgraded = true;
        state.keepAliveAllowed = false;
    }

    private static boolean isBodyExpected(HttpRequest request){
        if (request.method() == HttpMethod.HEAD) return false;
        if (HttpUtil.isTransferEncodingChunked(request)) return true;
        return HttpUtil.getContentLength(request, -1) > 0;
    }

    public static void requestReadIfNeeded(ChannelHandlerContext context, BodyQueue queue) {
        if (context == null || queue == null) return;
        if (context.channel().config().isAutoRead()) return;
        if (!queue.wantsInput()) return;

        AtomicBoolean pending = context.channel().attr(NettyHttpConstants.READ_PENDING).get();
        if (pending == null) {
            pending = new AtomicBoolean(false);
            context.channel().attr(NettyHttpConstants.READ_PENDING).set(pending);
        }
        if (!pending.compareAndSet(false, true)) {
            return; // already requested a read; don’t spam
        }

        if (context.executor().inEventLoop()) {
            context.channel().read();
        } else {
            context.executor().execute(() -> context.channel().read());
        }
    }

    public static void clearReadPending(ChannelHandlerContext context) {
        if (context == null) return;
        AtomicBoolean pending = context.channel().attr(NettyHttpConstants.READ_PENDING).get();
        if (pending != null) pending.set(false);
    }
}
