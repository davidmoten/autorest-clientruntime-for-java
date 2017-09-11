/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.rest.v2;

import com.google.common.reflect.TypeToken;
import com.microsoft.rest.RestClient;
import com.microsoft.rest.RestException;
import com.microsoft.rest.protocol.SerializerAdapter;
import com.microsoft.rest.v2.http.HttpClient;
import com.microsoft.rest.v2.http.HttpHeader;
import com.microsoft.rest.v2.http.HttpRequest;
import com.microsoft.rest.v2.http.HttpResponse;
import com.microsoft.rest.v2.http.OkHttpAdapter;
import com.microsoft.rest.v2.http.UrlBuilder;
import rx.Completable;
import rx.Single;
import rx.functions.Func1;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * This class can be used to create a proxy implementation for a provided Swagger generated
 * interface.
 */
public final class RestProxy implements InvocationHandler {
    private final HttpClient httpClient;
    private final SerializerAdapter<?> serializer;
    private final SwaggerInterfaceParser interfaceParser;

    private RestProxy(HttpClient httpClient, SerializerAdapter<?> serializer, SwaggerInterfaceParser interfaceParser) {
        this.httpClient = httpClient;
        this.serializer = serializer;
        this.interfaceParser = interfaceParser;
    }

    @Override
    public Object invoke(Object proxy, final Method method, Object[] args) throws IOException {
        final SwaggerMethodParser methodParser = interfaceParser.methodParser(method);

        final UrlBuilder urlBuilder = new UrlBuilder()
                .withScheme(methodParser.scheme(args))
                .withHost(methodParser.host(args))
                .withPath(methodParser.path(args));

        for (final EncodedParameter queryParameter : methodParser.encodedQueryParameters(args)) {
            urlBuilder.withQueryParameter(queryParameter.name(), queryParameter.encodedValue());
        }

        final String url = urlBuilder.toString();
        final HttpRequest request = new HttpRequest(methodParser.fullyQualifiedMethodName(), methodParser.httpMethod(), url);

        for (final HttpHeader header : methodParser.headers(args)) {
            request.withHeader(header.name(), header.value());
        }

        final Object bodyContentObject = methodParser.body(args);
        if (bodyContentObject != null) {
            final String mimeType = "application/json";
            final String bodyContentString = serializer.serialize(bodyContentObject);
            request.withBody(bodyContentString, mimeType);
        }

        Object result;
        final Type returnType = methodParser.returnType();
        final TypeToken returnTypeToken = TypeToken.of(returnType);
        if (returnTypeToken.isSubtypeOf(Completable.class)) {
            final Single<? extends HttpResponse> asyncResponse = httpClient.sendRequestAsync(request);
            result = Completable.fromSingle(asyncResponse);
        }
        else if (returnTypeToken.isSubtypeOf(Single.class)) {
            final Single<? extends HttpResponse> asyncResponse = httpClient.sendRequestAsync(request);
            result = asyncResponse.flatMap(new Func1<HttpResponse, Single<?>>() {
                @Override
                public Single<?> call(HttpResponse response) {
                    Single<?> asyncResult;
                    final Type singleReturnType = ((ParameterizedType) returnType).getActualTypeArguments()[0];
                    final TypeToken singleReturnTypeToken = TypeToken.of(singleReturnType);
                    if (methodParser.httpMethod().equalsIgnoreCase("HEAD")) {
                        asyncResult = Single.just(null);
                    } else if (singleReturnTypeToken.isSubtypeOf(InputStream.class)) {
                        asyncResult = response.bodyAsInputStreamAsync();
                    } else if (singleReturnTypeToken.isSubtypeOf(byte[].class)) {
                        asyncResult = response.bodyAsByteArrayAsync();
                    } else {
                        final Single<String> asyncResponseBodyString = response.bodyAsStringAsync();
                        asyncResult = asyncResponseBodyString.flatMap(new Func1<String, Single<Object>>() {
                            @Override
                            public Single<Object> call(String responseBodyString) {
                                try {
                                    return Single.just(serializer.deserialize(responseBodyString, singleReturnType));
                                } catch (IOException e) {
                                    return Single.error(e);
                                }
                            }
                        });
                    }
                    return asyncResult;
                }
            });
        }
        else {
            final HttpResponse response = httpClient.sendRequest(request);

            final int responseStatusCode = response.statusCode();
            if (!methodParser.isExpectedResponseStatusCode(responseStatusCode)) {
                final Class<? extends RestException> exceptionType = methodParser.exceptionType();
                String responseContent = null;
                try {
                    final Class<?> exceptionBodyType = methodParser.exceptionBodyType();
                    final Constructor<? extends RestException> exceptionConstructor = exceptionType.getConstructor(String.class, HttpResponse.class, exceptionBodyType);

                    try {
                        responseContent = response.bodyAsString();
                    } catch (IOException ignored) {
                    }

                    final Object exceptionBody = responseContent == null || responseContent.isEmpty() ? null : serializer.deserialize(responseContent, exceptionBodyType);

                    throw exceptionConstructor.newInstance("Status code " + responseStatusCode + ", " + responseContent, response, exceptionBody);
                } catch (IllegalAccessException | InstantiationException | InvocationTargetException | NoSuchMethodException e) {
                    String message = "Status code " + responseStatusCode + ", but an instance of " + exceptionType.getCanonicalName() + " cannot be created.";
                    if (responseContent != null && responseContent.isEmpty()) {
                        message += " Response content: \"" + responseContent + "\"";
                    }
                    throw new IOException(message, e);
                }
            }

            if (returnType.equals(Void.TYPE) || methodParser.httpMethod().equalsIgnoreCase("HEAD")) {
                result = null;
            } else if (returnTypeToken.isSubtypeOf(InputStream.class)) {
                result = response.bodyAsInputStream();
            } else if (returnTypeToken.isSubtypeOf(byte[].class)) {
                result = response.bodyAsByteArray();
            } else {
                final String responseBodyString = response.bodyAsString();
                result = serializer.deserialize(responseBodyString, returnType);
            }
        }

        return result;
    }

    /**
     * Create a proxy implementation of the provided Swagger interface.
     * @param swaggerInterface The Swagger interface to provide a proxy implementation for.
     * @param restClient The internal HTTP client that will be used to make REST calls.
     * @param <A> The type of the Swagger interface.
     * @return A proxy implementation of the provided Swagger interface.
     */
    @SuppressWarnings("unchecked")
    public static <A> A create(Class<A> swaggerInterface, RestClient restClient) {
        final HttpClient httpClient = new OkHttpAdapter(restClient.httpClient());
        final SerializerAdapter<?> serializer = restClient.serializerAdapter();
        return create(swaggerInterface, httpClient, serializer);
    }

    /**
     * Create a proxy implementation of the provided Swagger interface.
     * @param swaggerInterface The Swagger interface to provide a proxy implementation for.
     * @param httpClient The internal HTTP client that will be used to make REST calls.
     * @param serializer The serializer that will be used to convert POJOs to and from request and
     *                   response bodies.
     * @param <A> The type of the Swagger interface.
     * @return A proxy implementation of the provided Swagger interface.
     */
    @SuppressWarnings("unchecked")
    public static <A> A create(Class<A> swaggerInterface, HttpClient httpClient, SerializerAdapter<?> serializer) {
        final SwaggerInterfaceParser interfaceParser = new SwaggerInterfaceParser(swaggerInterface);
        final RestProxy restProxy = new RestProxy(httpClient, serializer, interfaceParser);
        return (A) Proxy.newProxyInstance(swaggerInterface.getClassLoader(), new Class[]{swaggerInterface}, restProxy);
    }
}