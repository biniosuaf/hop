/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rabbitmq.http.client;

import com.rabbitmq.http.client.domain.OutboundMessage;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 *
 */
class Utils {

    private static final Charset CHARSET_UTF8 = Charset.forName("UTF-8");

    static Map<String, Object> bodyForPublish(String routingKey, OutboundMessage outboundMessage) {
        if (routingKey == null) {
            throw new IllegalArgumentException("routing key cannot be null");
        }
        if (outboundMessage == null) {
            throw new IllegalArgumentException("message cannot be null");
        }
        if (outboundMessage.getPayload() == null) {
            throw new IllegalArgumentException("message payload cannot be null");
        }
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("routing_key", routingKey);
        body.put("properties", outboundMessage.getProperties() == null ? Collections.EMPTY_MAP : outboundMessage.getProperties());
        body.put("payload", outboundMessage.getPayload());
        body.put("payload_encoding", outboundMessage.getPayloadEncoding());
        return body;
    }

    static Map<String, Object> bodyForGet(int count, GetAckMode ackMode, GetEncoding encoding, int truncate) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be greater than 0");
        }
        if (ackMode == null) {
            throw new IllegalArgumentException("acknowledgment mode cannot be null");
        }
        if (encoding == null) {
            throw new IllegalArgumentException("encoding cannot be null");
        }
        boolean requeue;
        if (ackMode.ackMode.equals("ack_requeue_true") || ackMode.ackMode.equals("reject_requeue_false")) {
            requeue = true;
        } else {
            requeue = false;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("count", count);
        body.put("requeue", requeue);
        body.put("ackmode", ackMode.ackMode);
        body.put("encoding", encoding.encoding);
        if (truncate >= 0) {
            body.put("truncate", truncate);
        }
        return body;
    }

    /* from https://github.com/apache/httpcomponents-client/commit/b58e7d46d75e1d3c42f5fd6db9bd45f32a49c639#diff-a74b24f025e68ec11e4550b42e9f807d */

    static String encodePath(String content, Charset charset) {
        final StringBuilder buf = new StringBuilder();
        final ByteBuffer bb = charset.encode(content);
        while (bb.hasRemaining()) {
            final int b = bb.get() & 0xff;
            if (PATHSAFE.get(b)) {
                buf.append((char) b);
            } else {
                buf.append("%");
                final char hex1 = Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, RADIX));
                final char hex2 = Character.toUpperCase(Character.forDigit(b & 0xF, RADIX));
                buf.append(hex1);
                buf.append(hex2);
            }
        }
        return buf.toString();
    }

    static String encode(String content) {
        return encodePath(content, CHARSET_UTF8);
    }

    private static final int RADIX = 16;

    /**
     * Unreserved characters, i.e. alphanumeric, plus: {@code _ - ! . ~ ' ( ) *}
     * <p>
     *  This list is the same as the {@code unreserved} list in
     *  <a href="https://www.ietf.org/rfc/rfc2396.txt">RFC 2396</a>
     */
    private static final BitSet UNRESERVED   = new BitSet(256);
    /**
     * Punctuation characters: , ; : $ & + =
     * <p>
     * These are the additional characters allowed by userinfo.
     */
    private static final BitSet PUNCT        = new BitSet(256);
    /** Characters which are safe to use in userinfo,
     * i.e. {@link #UNRESERVED} plus {@link #PUNCT}uation */
    private static final BitSet USERINFO     = new BitSet(256);
    /** Characters which are safe to use in a path,
     * i.e. {@link #UNRESERVED} plus {@link #PUNCT}uation plus / @ */
    private static final BitSet PATHSAFE     = new BitSet(256);
    /** Characters which are safe to use in a query or a fragment,
     * i.e. {@link #RESERVED} plus {@link #UNRESERVED} */
    private static final BitSet URIC     = new BitSet(256);

    /**
     * Reserved characters, i.e. {@code ;/?:@&=+$,[]}
     * <p>
     *  This list is the same as the {@code reserved} list in
     *  <a href="https://www.ietf.org/rfc/rfc2396.txt">RFC 2396</a>
     *  as augmented by
     *  <a href="https://www.ietf.org/rfc/rfc2732.txt">RFC 2732</a>
     */
    private static final BitSet RESERVED     = new BitSet(256);


    /**
     * Safe characters for x-www-form-urlencoded data, as per java.net.URLEncoder and browser behaviour,
     * i.e. alphanumeric plus {@code "-", "_", ".", "*"}
     */
    private static final BitSet URLENCODER   = new BitSet(256);

    static {
        // unreserved chars
        // alpha characters
        for (int i = 'a'; i <= 'z'; i++) {
            UNRESERVED.set(i);
        }
        for (int i = 'A'; i <= 'Z'; i++) {
            UNRESERVED.set(i);
        }
        // numeric characters
        for (int i = '0'; i <= '9'; i++) {
            UNRESERVED.set(i);
        }
        UNRESERVED.set('_'); // these are the charactes of the "mark" list
        UNRESERVED.set('-');
        UNRESERVED.set('.');
        UNRESERVED.set('*');
        URLENCODER.or(UNRESERVED); // skip remaining unreserved characters
        UNRESERVED.set('!');
        UNRESERVED.set('~');
        UNRESERVED.set('\'');
        UNRESERVED.set('(');
        UNRESERVED.set(')');
        // punct chars
        PUNCT.set(',');
        PUNCT.set(';');
        PUNCT.set(':');
        PUNCT.set('$');
        PUNCT.set('&');
        PUNCT.set('+');
        PUNCT.set('=');
        // Safe for userinfo
        USERINFO.or(UNRESERVED);
        USERINFO.or(PUNCT);

        // URL path safe
        PATHSAFE.or(UNRESERVED);
        // here we want to encode the segment separator, because we encode segment by segment
        // PATHSAFE.set('/'); // segment separator
        PATHSAFE.set(';'); // param separator
        PATHSAFE.set(':'); // rest as per list in 2396, i.e. : @ & = + $ ,
        PATHSAFE.set('@');
        PATHSAFE.set('&');
        PATHSAFE.set('=');
        PATHSAFE.set('+');
        PATHSAFE.set('$');
        PATHSAFE.set(',');

        RESERVED.set(';');
        RESERVED.set('/');
        RESERVED.set('?');
        RESERVED.set(':');
        RESERVED.set('@');
        RESERVED.set('&');
        RESERVED.set('=');
        RESERVED.set('+');
        RESERVED.set('$');
        RESERVED.set(',');
        RESERVED.set('['); // added by RFC 2732
        RESERVED.set(']'); // added by RFC 2732

        URIC.or(RESERVED);
        URIC.or(UNRESERVED);
    }

    /* end of from https://github.com/apache/httpcomponents-client/commit/b58e7d46d75e1d3c42f5fd6db9bd45f32a49c639#diff-a74b24f025e68ec11e4550b42e9f807d */
}
