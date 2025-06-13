// /*******************************************************************************
//  * Copyright (c) 2023, 2025 IBM Corporation and others.
//  * All rights reserved. This program and the accompanying materials
//  * are made available under the terms of the Eclipse Public License 2.0
//  * which accompanies this distribution, and is available at
//  * http://www.eclipse.org/legal/epl-2.0/
//  *
//  * SPDX-License-Identifier: EPL-2.0
//  *******************************************************************************/
// package com.ibm.ws.http.netty.pipeline.inbound;

// import java.net.InetSocketAddress;
// import java.nio.channels.Pipe;
// import java.util.Objects;
// import java.util.concurrent.ExecutorService;

// import com.ibm.websphere.ras.Tr;
// import com.ibm.websphere.ras.TraceComponent;
// import com.ibm.ws.ffdc.FFDCFilter;
// import com.ibm.ws.http.channel.internal.HttpChannelConfig;
// import com.ibm.ws.http.channel.internal.HttpMessages;
// import com.ibm.ws.http.dispatcher.internal.HttpDispatcher;
// import com.ibm.ws.http.dispatcher.internal.channel.HttpDispatcherLink;
// import com.ibm.ws.http.netty.NettyHttpConstants;
// import com.ibm.wsspi.bytebuffer.WsByteBuffer;
// import com.ibm.wsspi.bytebuffer.WsByteBufferUtils;
// import com.ibm.wsspi.http.channel.error.HttpError;
// import com.ibm.wsspi.http.channel.error.HttpErrorPageProvider;
// import com.ibm.wsspi.http.channel.error.HttpErrorPageService;
// import com.ibm.wsspi.http.channel.values.StatusCodes;

// import io.netty.buffer.Unpooled;
// import io.netty.channel.ChannelFutureListener;
// import io.netty.channel.ChannelHandler;
// import io.netty.channel.ChannelHandlerContext;
// import io.netty.channel.SimpleChannelInboundHandler;
// import io.netty.handler.codec.DecoderResult;
// import io.netty.handler.codec.http.DefaultFullHttpResponse;
// import io.netty.handler.codec.http.FullHttpRequest;
// import io.netty.handler.codec.http.FullHttpResponse;
// import io.netty.handler.codec.http.HttpResponseStatus;
// import io.netty.handler.codec.http.HttpUtil;
// import io.netty.handler.codec.http.HttpVersion;
// import io.netty.handler.codec.http.TooLongHttpHeaderException;
// import io.netty.handler.codec.http2.Http2Connection;
// import io.netty.handler.codec.http2.Http2Exception.StreamException;
// import io.netty.handler.codec.http2.Http2Error;
// import io.netty.handler.codec.http2.Http2Stream;
// import io.netty.handler.codec.http2.HttpConversionUtil;
// import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
// import io.netty.util.ReferenceCountUtil;
// import io.openliberty.http.netty.channel.utils.PipelineOffload;

// /**
//  *
//  */
// public class HttpDispatcherHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

//     private static final TraceComponent tc = Tr.register(HttpDispatcherHandler.class, HttpMessages.HTTP_TRACE_NAME, HttpMessages.HTTP_BUNDLE);

//     private static final DefaultFullHttpResponse BAD_REQUEST;
//     static{
//         BAD_REQUEST = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
//         HttpUtil.setKeepAlive(BAD_REQUEST, false);
//         HttpUtil.setContentLength(BAD_REQUEST, 0);
//     }

//     private final HttpChannelConfig config;

//     private static final String MAX_STREAMS_REFUSED_MESSAGE = "too many client-initiated streams have been refused; closing the connection";

//     public HttpDispatcherHandler(HttpChannelConfig config) {
//         super(false);
//         this.config = Objects.requireNonNull(config);
//     }

//     @Override
//     public void handlerAdded(ChannelHandlerContext context) {
//         context.channel().attr(NettyHttpConstants.NUMBER_OF_HTTP_REQUESTS).set(0);
//         context.channel().attr(NettyHttpConstants.STREAMS_REFUSED).set(0);
//     }

//     @Override
//     protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
//         DecoderResult result = request.decoderResult();
//         if(!result.isFinished() || !result.isSuccess()){
//             if(TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()){
//                 Tr.debug(tc,"Bad request frame: "+ result.cause());
//             }
//             respondToBadRequest(context);
//             ReferenceCountUtil.release(request);
//             return;
//         }

//         FullHttpRequest req = request.retain();
//         PipelineOffload.run(context, () ->{
//             try{
//                 newRequest(context, req);
//             }catch(Throwable t){
//                 context.executor().execute(() -> handleException(context, t));
//             }finally{
//                 context.executor().execute(() -> ReferenceCountUtil.release(req));
//             }
//         });
//     }

//     private void respondToBadRequest(ChannelHandlerContext context) {
//         if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
//             Tr.debug(tc, "Sending 400 Bad Rrequest");
//         }
//         context.writeAndFlush(BAD_REQUEST.retainedDuplicate()).addListener(ChannelFutureListener.CLOSE);
//     }

//     private void handleException(ChannelHandlerContext context, Throwable t){
//         try{
//             exceptionCaught(context, t);
//         }catch(Exception e){
//             context.close();
//         }
//     }

    

//     @Override
//     public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception{
//         if(cause instanceof StreamException){
//             handleStreamException(context, (StreamException) cause);
//             return;
//         }
//         if(cause instanceof IllegalArgumentException || cause instanceof TooLongHttpHeaderException){
//             boolean skipFFDC = false;
//             if(context.channel().attr(NettyHttpConstants.THROW_FFDC).get() != null){
//                 context.channel().attr(NettyHttpConstants.THROW_FFDC).set(null);
//                 skipFFDC = true;
//             }
//             if(!skipFFDC && cause.getMessage() != null && cause.getMessage().contains("possibly HTTP/0.9")){
//                 skipFFDC = true;
//             }
//             if(skipFFDC){
//                 sendErrorMessage(context, StatusCodes.BAD_REQUEST.getHttpError());
//             } else{
//                 PipelineOffload.run(context, () ->{
//                     FFDCFilter.processException(cause, getClass().getName() + ".exceptionCaught", "1", context);
//                     context.executor().execute(() -> sendErrorMessage(context, StatusCodes.BAD_REQUEST.getHttpError()));
//                 });
//             }
//             return;
//         }
//         context.close();
//     }

//     private void handleStreamException(ChannelHandlerContext context, StreamException cause){
//         HttpToHttp2ConnectionHandler h2Handler = context.pipeline().get(HttpToHttp2ConnectionHandler.class);
//         if(h2Handler == null) return;
        
//         if(cause.getMessage() != null && cause.getMessage().startsWith("Maximum active streams violated for this endpoint")){
//             if(config.getH2MaxStreamsRefused() == 0) return;
//             int streamsRefused = context.channel().attr(NettyHttpConstants.STREAMS_REFUSED).get();
//             if(++streamsRefused >= config.getH2MaxStreamsRefused()){
//                 Http2Connection connection = h2Handler.connection();
//                 h2Handler.goAway(context, connection.remote().lastStreamCreated(), Http2Error.ENHANCE_YOUR_CALM.code(), 
//                         Unpooled.wrappedBuffer(MAX_STREAMS_REFUSED_MESSAGE.getBytes()), context.channel().newPromise());
//             } else{
//                 context.channel().attr(NettyHttpConstants.STREAMS_REFUSED).set(streamsRefused);
//             }
//             return;
//         }
//         Http2Stream stream = h2Handler.connection().stream(cause.streamId());
//         if(stream != null) stream.close();
//     }


//     private void sendErrorMessage(ChannelHandlerContext context, HttpError error) {
//         PipelineOffload.run(context, () -> {
//             FullHttpResponse response = BAD_REQUEST.retainedDuplicate();
//             loadErrorPage(context, response, error);
//             context.executor().execute(() ->context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE));
//         });
//     }

//     private void loadErrorPage(ChannelHandlerContext context, FullHttpResponse response, HttpError error) {
//         response.setStatus(HttpResponseStatus.valueOf(error.getErrorCode()));
//         WsByteBuffer[] body = error.getErrorBody();
//         if(body != null){
//             if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
//                 Tr.debug(tc, "HttpError returned body of length=" + body.length);
//             }
//             response.replace(Unpooled.wrappedBuffer(WsByteBufferUtils.asByteArray(body)));
//             HttpUtil.setContentLength(response, body.length);
//             return;
//         }
//         HttpErrorPageService eps = (HttpErrorPageService) HttpDispatcher.getFramework().lookupService(HttpErrorPageService.class);
//         if (eps == null){
//             if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
//                 Tr.debug(tc, "No HttpErrorPageService configured");
//                 return;
//             }
//         }

//         InetSocketAddress local = (InetSocketAddress) context.channel().localAddress();
//         if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
//             Tr.debug(tc, "Querying service for port=" + local.getPort());
//         }
//         HttpErrorPageProvider provider = eps.access(local.getPort());
//         if (provider == null) { return; }
//         try{
//             if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
//                 Tr.debug(tc, "Querying provider for host=" + local.getAddress().getHostName());
//             }
//             body = provider.accessPage(local.getAddress().getHostName(), local.getPort(), null, null);
//         } catch (Throwable t){
//             if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
//                 Tr.debug(tc, "Exception while calling provider" + t);
//             }
//         }
//         if (body != null){
//             if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
//                 Tr.debug(tc, "Received body of length=" + body.length);
//             }
//             response.replace(Unpooled.wrappedBuffer(WsByteBufferUtils.asByteArray(body)));
//             HttpUtil.setContentLength(response, body.length);
//         }
//     }

//     public void newRequest(ChannelHandlerContext context, FullHttpRequest request) {
//         String proto = request.headers().contains(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text())
//                 ? "HTTP2" : (request.protocolVersion().equals(HttpVersion.HTTP_1_0) ? "HTTP10" : "http");

//         context.channel().attr(NettyHttpConstants.PROTOCOL).set(proto);
//         context.channel().attr(NettyHttpConstants.CONTENT_LENGTH).set(null);

//         int numberOfRequests = context.channel().attr(NettyHttpConstants.NUMBER_OF_HTTP_REQUESTS).get();
//         context.channel().attr(NettyHttpConstants.NUMBER_OF_HTTP_REQUESTS).set(numberOfRequests + 1);

//         HttpDispatcherLink link = new HttpDispatcherLink();
//         link.init(context, request, config);
//         link.ready();
//     }
// }

/*******************************************************************************
 * Copyright (c) 2023, 2025 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package com.ibm.ws.http.netty.pipeline.inbound;

import java.net.InetSocketAddress;
import java.util.Objects;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.ffdc.FFDCFilter;
import com.ibm.ws.http.channel.internal.HttpChannelConfig;
import com.ibm.ws.http.channel.internal.HttpMessages;
import com.ibm.ws.http.dispatcher.internal.HttpDispatcher;
import com.ibm.ws.http.dispatcher.internal.channel.HttpDispatcherLink;
import com.ibm.ws.http.netty.NettyHttpConstants;
import com.ibm.wsspi.bytebuffer.WsByteBuffer;
import com.ibm.wsspi.bytebuffer.WsByteBufferUtils;
import com.ibm.wsspi.http.channel.error.HttpError;
import com.ibm.wsspi.http.channel.error.HttpErrorPageProvider;
import com.ibm.wsspi.http.channel.error.HttpErrorPageService;
import com.ibm.wsspi.http.channel.values.StatusCodes;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Exception.StreamException;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.util.ReferenceCountUtil;

/**
 *
 */
public class HttpDispatcherHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final TraceComponent tc = Tr.register(HttpDispatcherHandler.class, HttpMessages.HTTP_TRACE_NAME, HttpMessages.HTTP_BUNDLE);

    HttpChannelConfig config;
    private ChannelHandlerContext context;
    private final DefaultFullHttpResponse errorResponse;
    private static final String MAX_STREAMS_REFUSED_MESSAGE = "too many client-initiated streams have been refused; closing the connection";

    // private HttpDispatcherLink link;

    public HttpDispatcherHandler(HttpChannelConfig config) {
        super(false);
        Objects.requireNonNull(config);
        this.config = config;
        errorResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        // Store the context for later use
        context = ctx;
        context.channel().attr(NettyHttpConstants.NUMBER_OF_HTTP_REQUESTS).set(0);
        context.channel().attr(NettyHttpConstants.STREAMS_REFUSED).set(0);
    }

    // Method to allow direct invocation
    // TODO check if this can be cleaned up and removed
    public void processMessageDirectly(FullHttpRequest request) throws Exception {
        channelRead0(context, request);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
        // TODO Need to see if we need to check decoder result from request to ensure data is properly parsed as expected
        if (request.decoderResult().isFinished() && request.decoderResult().isSuccess()) {

            FullHttpRequest msg = request;
            HttpDispatcher.getExecutorService().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        newRequest(context, msg);
                    } catch (Throwable t) {
                        try {
                            exceptionCaught(context, t);
                        } catch (Exception e) {
                            context.close();
                        }
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }
            });
        } else {
            if (request.decoderResult().cause() != null) {
                request.decoderResult().cause().printStackTrace();
            }
        }

    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        if (cause instanceof StreamException) {
            StreamException c = (StreamException) cause;
            HttpToHttp2ConnectionHandler handler = context.pipeline().get(HttpToHttp2ConnectionHandler.class);
            Http2Connection connection = handler.connection();

            if (cause.getMessage().startsWith("Maximum active streams violated for this endpoint")) {
                // This is for the overlay to control the amount of streams we are willing to refuse on a channel
                if (config.getH2MaxStreamsRefused() == 0) {
                    // Check disabled, just send a reset stream out and don't worry about the rest
                    // Reset already handled by codec so just return here
                    return;
                }
                // Increment max streams refused and continue
                int streamsRefused = context.channel().attr(NettyHttpConstants.STREAMS_REFUSED).get();
                if (++streamsRefused >= config.getH2MaxStreamsRefused()) {
                    // Streams refused exceeded the number of configured allowed streams so closing connection
                    // Send go away with enhance your calm
                    handler.goAway(context, connection.remote().lastStreamCreated(), Http2Error.ENHANCE_YOUR_CALM.code(),
                                   Unpooled.wrappedBuffer(MAX_STREAMS_REFUSED_MESSAGE.getBytes()), context.channel().newPromise());
                } else {
                    // Increment streams refused attribute and let reset happen by codec
                    context.channel().attr(NettyHttpConstants.STREAMS_REFUSED).set(streamsRefused);
                    return;
                }
            } else {
                Http2Stream stream = connection.stream(c.streamId());
                if (Objects.nonNull(stream)) {
                    stream.close();
                }
                return;
            }
        } else if (cause instanceof IllegalArgumentException) {
            //Legacy doesnt throw ffdc on processNewInformation
            if (context.channel().attr(NettyHttpConstants.THROW_FFDC).get() != null) {
                context.channel().attr(NettyHttpConstants.THROW_FFDC).set(null);
            } else if (!cause.getMessage().contains("possibly HTTP/0.9")) {
                FFDCFilter.processException(cause, HttpDispatcherHandler.class.getName() + ".exceptionCaught(ChannelHandlerContext, Throwable)", "1", context);
            }

            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(tc, "exceptionCaught encountered an IllegalArgumentException : " + cause);
            }
            sendErrorMessage(cause);
            return;
        } else if (cause instanceof TooLongHttpHeaderException) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(tc, "exceptionCaught encountered an TooLongHttpHeaderException : " + cause);
            }
            sendErrorMessage(cause);
            return;
        }
        context.close();
    }

    private void sendErrorMessage(Throwable cause) {
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(tc, "Sending a 400 for throwable [" + cause + "]");
        }
        // TODO Need a way to check if headers were already sent or not before sending an entire response
        loadErrorPage(StatusCodes.BAD_REQUEST.getHttpError());
        HttpUtil.setKeepAlive(errorResponse, false);
        this.context.writeAndFlush(errorResponse);
    }

    private void loadErrorPage(HttpError error) {
        errorResponse.setStatus(HttpResponseStatus.valueOf(error.getErrorCode()));
        WsByteBuffer[] body = error.getErrorBody();
        if (null != body) {
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(tc, "HttpError returned body of length=" + body.length);
            }
            errorResponse.replace(Unpooled.wrappedBuffer(WsByteBufferUtils.asByteArray(body)));
            if (HttpUtil.isTransferEncodingChunked(errorResponse))
                HttpUtil.setTransferEncodingChunked(errorResponse, false);
            HttpUtil.setContentLength(errorResponse, body.length);
            return;
        }
        if (HttpUtil.isTransferEncodingChunked(errorResponse))
            HttpUtil.setTransferEncodingChunked(errorResponse, false);
        HttpUtil.setContentLength(errorResponse, 0);
        HttpErrorPageService eps = (HttpErrorPageService) HttpDispatcher.getFramework().lookupService(HttpErrorPageService.class);
        if (null == eps) {
            return;
        }

        InetSocketAddress local = (InetSocketAddress) context.channel().localAddress();
        InetSocketAddress remote = (InetSocketAddress) context.channel().remoteAddress();
        // found the error page service, load the pieces we need and then
        // query for any configured body
        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(tc, "Querying service for port=" + local.getPort());
        }
        HttpErrorPageProvider provider = eps.access(local.getPort());
        if (null != provider) {
            String host = local.getAddress().getHostName();
            if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                Tr.debug(tc, "Querying provider for host=" + host);
            }
            try {
                body = provider.accessPage(host, local.getPort(), null, null);
            } catch (Throwable t) {
                //                FFDCFilter.processException(t, getClass().getName() + ".loadErrorBody", "1");
                if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                    Tr.debug(tc, "Exception while calling into provider, t=" + t);
                }
            }
            if (null != body) {
                if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
                    Tr.debug(tc, "Received body of length=" + body.length);
                }
                errorResponse.replace(Unpooled.wrappedBuffer(WsByteBufferUtils.asByteArray(body)));
                if (HttpUtil.isTransferEncodingChunked(errorResponse))
                    HttpUtil.setTransferEncodingChunked(errorResponse, false);
                HttpUtil.setContentLength(errorResponse, body.length);
            }
        }
        return;
    }

    public void newRequest(ChannelHandlerContext context, FullHttpRequest request) {

        if (request.headers().contains(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text())) {
            context.channel().attr(NettyHttpConstants.PROTOCOL).set("HTTP2");
        } else {
            if (request.protocolVersion().equals(HttpVersion.HTTP_1_0)) {
                context.channel().attr(NettyHttpConstants.PROTOCOL).set("HTTP10");
            } else
                context.channel().attr(NettyHttpConstants.PROTOCOL).set("http");
        }
        HttpDispatcherLink link = new HttpDispatcherLink();
        if (context.channel().hasAttr(NettyHttpConstants.CONTENT_LENGTH)) {
            context.channel().attr(NettyHttpConstants.CONTENT_LENGTH).set(null);

        }
        int numberOfRequests = context.channel().attr(NettyHttpConstants.NUMBER_OF_HTTP_REQUESTS).get();
        context.channel().attr(NettyHttpConstants.NUMBER_OF_HTTP_REQUESTS).set(numberOfRequests + 1);
        link.init(context, request, config);
        link.ready();
    }
}