/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.rest.v2.http;

import com.microsoft.rest.protocol.SerializerAdapter;
import com.microsoft.rest.serializer.JacksonAdapter;
import rx.Single;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class MockHttpResponse extends HttpResponse {
    private final static SerializerAdapter<?> serializer = new JacksonAdapter();

    private final int statusCode;

    private final boolean hasBody;
    private byte[] byteArray;
    private String string;

    public MockHttpResponse() {
        this(200);
    }

    public MockHttpResponse(int statusCode) {
        this.statusCode = statusCode;
        hasBody = false;
    }

    public MockHttpResponse(int statusCode, byte[] byteArray) {
        this.statusCode = statusCode;
        hasBody = true;
        this.byteArray = byteArray;
    }

    public MockHttpResponse(int statusCode, String string) {
        this.statusCode = statusCode;
        hasBody = true;
        this.string = string;
    }

    public MockHttpResponse(int statusCode, Object serializable) {
        this.statusCode = statusCode;
        hasBody = true;
        try {
            this.string = serializer.serialize(serializable);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public Single<? extends InputStream> bodyAsInputStreamAsync() {
        return Single.just(new ByteArrayInputStream(byteArray));
    }

    @Override
    public Single<byte[]> bodyAsByteArrayAsync() {
        return Single.just(byteArray);
    }

    @Override
    public Single<String> bodyAsStringAsync() {
        return Single.just(string);
    }
}