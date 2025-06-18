/*******************************************************************************
 * Copyright (c) 2023, 2025 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package com.ibm.ws.http.netty.message;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.http.channel.internal.HttpChannelConfig;
import com.ibm.ws.http.channel.internal.HttpMessages;
import com.ibm.ws.http.channel.internal.HttpTrailersImpl;
import com.ibm.ws.http.channel.internal.inbound.HttpInboundServiceContextImpl;
import com.ibm.ws.http.dispatcher.internal.HttpDispatcher;
import com.ibm.wsspi.genericbnf.HeaderField;
import com.ibm.wsspi.genericbnf.HeaderKeys;
import com.ibm.wsspi.genericbnf.exception.UnsupportedProtocolVersionException;
import com.ibm.wsspi.http.HttpCookie;
import com.ibm.wsspi.http.channel.HttpResponseMessage;
import com.ibm.wsspi.http.channel.HttpServiceContext;
import com.ibm.wsspi.http.channel.HttpTrailers;
import com.ibm.wsspi.http.channel.inbound.HttpInboundServiceContext;
import com.ibm.wsspi.http.channel.values.ConnectionValues;
import com.ibm.wsspi.http.channel.values.ContentEncodingValues;
import com.ibm.wsspi.http.channel.values.ExpectValues;
import com.ibm.wsspi.http.channel.values.HttpHeaderKeys;
import com.ibm.wsspi.http.channel.values.StatusCodes;
import com.ibm.wsspi.http.channel.values.TransferEncodingValues;
import com.ibm.wsspi.http.channel.values.VersionValues;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.openliberty.http.netty.channel.utils.HeaderValidator;
import io.openliberty.http.netty.channel.utils.HeaderValidator.FieldType;
import io.openliberty.http.netty.cookie.CookieEncoder;

/**
 *
 */
public class NettyResponseMessage extends NettyBaseMessage implements HttpResponseMessage {

    /** RAS trace variable */
    private static final TraceComponent tc = Tr.register(NettyResponseMessage.class, HttpMessages.HTTP_TRACE_NAME, HttpMessages.HTTP_BUNDLE);

    private HttpResponse nettyResponse;
    private HttpHeaders headers;
    private HttpHeaders trailers;
    private NettyTrailers nettyTrailerWrapper;
    private HttpInboundServiceContext context;
    private HttpChannelConfig config;

    public NettyResponseMessage(HttpResponse response, HttpInboundServiceContext isc, HttpRequest request) {
        Objects.requireNonNull(isc);
        Objects.requireNonNull(response);

        this.context = isc;
        this.nettyResponse = response;
        this.headers = nettyResponse.headers();
        this.trailers = null;
        this.nettyTrailerWrapper = null;

        if (request.headers().contains(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text())) {
            String streamId = request.headers().get(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
            nettyResponse.headers().set(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), streamId);

        }

        if (isc instanceof HttpInboundServiceContextImpl) {
            incoming(((HttpInboundServiceContextImpl) isc).isInboundConnection());
            this.config = ((HttpInboundServiceContextImpl) isc).getHttpConfig();
        }

        super.init(response, context, config);
        setMessageType(MessageType.RESPONSE);

    }

    public void update(HttpResponse response) {
        this.nettyResponse = response;
        this.headers = response.headers();
        trailers = null;
        nettyTrailerWrapper = null;
    }

    private void ensureTrailers(){
        if (trailers == null){
            trailers = new DefaultHttpHeaders();
            nettyTrailerWrapper = new NettyTrailers(trailers);
        }
    }

    @Override
    public void clear() {
        super.clear();
        this.setStatusCode(HttpResponseStatus.OK.code());
        this.nettyResponse.setProtocolVersion(HttpVersion.HTTP_1_1);
        trailers = null;
        nettyTrailerWrapper = null;

    }

    @Override
    public void destroy() {
        super.destroy();

    }

    @Override
    public boolean isBodyExpected() {

        if (VersionValues.V10.equals(getVersionValue())) {
            return isBodyAllowed();
        }

        if (HttpMethod.HEAD.toString().equals(getServiceContext().getRequest().getMethod())) {
            return false;
        }

        boolean bodyExpected = super.isBodyExpected();

        if (!bodyExpected) {
            bodyExpected = containsHeader(HttpHeaderNames.CONTENT_ENCODING.array()) || containsHeader(HttpHeaderNames.CONTENT_RANGE.array());
        }

        return bodyExpected && isBodyAllowedForStatusCode();

    }

    @Override
    public boolean isBodyAllowed() {
        if (super.isBodyAllowed()) {

            if (HttpMethod.HEAD.toString().equals(getServiceContext().getRequest().getMethod())) {
                return false;
            }
            return isBodyAllowedForStatusCode();
        }
        return false;
    }

    @Override
    public HttpTrailers getTrailers() {
        ensureTrailers();
        return nettyTrailerWrapper;
    }

    public HttpHeaders getNettyTrailers() {
        ensureTrailers();
        return trailers;
    }


    @Override
    public HttpTrailersImpl createTrailers() {
        ensureTrailers();
        return null;
    }

    @Override
    public int getStatusCodeAsInt() {
        return this.nettyResponse.status().code();
    }

    @Override
    public StatusCodes getStatusCode() {
        return StatusCodes.getByOrdinal(getStatusCodeAsInt());
    }

    @Override
    public void setStatusCode(int code) {
        this.nettyResponse.setStatus(HttpResponseStatus.valueOf(code));

    }

    @Override
    public void setStatusCode(StatusCodes code) {
        setStatusCode(code.getIntCode());

    }

    @Override
    public String getReasonPhrase() {
        return nettyResponse.status().reasonPhrase();
    }

    @Override
    public byte[] getReasonPhraseBytes() {
        return nettyResponse.status().reasonPhrase().getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public void setReasonPhrase(String reason) {
        if (reason != null) {
            nettyResponse.setStatus(new HttpResponseStatus(getStatusCodeAsInt(), reason));
        }
    }

    @Override
    public void setReasonPhrase(byte[] reason) {
        setReasonPhrase(new String(reason, StandardCharsets.US_ASCII));
    }

    @Override
    public HttpResponseMessage duplicate() {
        throw new UnsupportedOperationException("duplicate() not supported");
    }

    /**
     * @return
     */
    @Override
    public HttpServiceContext getServiceContext() {
        return this.context;
    }

    protected void processCookie(HttpCookie cookie, HeaderKeys header) {
        String result = null;
        if (Objects.nonNull(cookie) && Objects.nonNull(header)) {
            String userAgent = getServiceContext().getRequest().getHeader(HttpHeaderKeys.HDR_USER_AGENT).asString();
            result = CookieEncoder.encodeCookie(cookie, header, config, userAgent);

            if (Objects.nonNull(result)) {
                if (config.doNotAllowDuplicateSetCookies() && header.equals(HttpHeaderKeys.HDR_SET_COOKIE)) {
                    if (this.headers.contains(HttpHeaderKeys.HDR_SET_COOKIE.getName())) {
                        headers.set(header.getName(), result);
                    }
                } else {
                    headers.add(header.getName(), result);
                }
            }
        }

    }

    @Override
    public long getBytesWritten() {
        return this.getServiceContext().getNumBytesWritten();
    }

    private boolean isBodyAllowedForStatusCode() {
        //ixx (except 101), 204 and 304 responses must not include a message body
        int statusCode = getStatusCodeAsInt();
        if ((statusCode >= 100 && statusCode < 200) || statusCode == 204 || statusCode == 304) {
            return false;
        }
        return true;
    }

    /**
     * Read an instance of this object from the input stream.
     *
     * @param input
     * @throws IOException
     * @throws ClassNotFoundException
     */
    @Override
    public void readExternal(ObjectInput input) throws IOException, ClassNotFoundException {

        if (TraceComponent.isAnyTracingEnabled() && tc.isDebugEnabled()) {
            Tr.debug(tc, "De-serializing into: " + this);
        }
        super.readExternal(input);
        if (SERIALIZATION_V2 == deserializationVersion) {
            setStatusCode(input.readShort());
        } else {
            setStatusCode(input.readInt());
        }
        setReasonPhrase(readByteArray(input));
    }

    /**
     * Write this object instance to the output stream.
     *
     * @param output
     * @throws IOException
     */
    @Override
    public void writeExternal(ObjectOutput output) throws IOException {
        if (TraceComponent.isAnyTracingEnabled() && tc.isEventEnabled()) {
            Tr.event(tc, "Serializing: " + this);
        }
        super.writeExternal(output);
        output.writeShort(getStatusCodeAsInt());
        writeByteArray(output, this.getReasonPhraseBytes());
    }

    public HttpResponse getResponse() {
        return nettyResponse;
    }

}
