/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.azure.v2;

import com.microsoft.rest.v2.RestProxy;
import com.microsoft.rest.v2.SwaggerMethodParser;
import com.microsoft.rest.v2.http.HttpRequest;
import com.microsoft.rest.v2.http.HttpResponse;
import rx.Single;
import rx.functions.Func1;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * A PollStrategy type that uses the Location header value to check the status of a long running
 * operation.
 */
public final class LocationPollStrategy extends PollStrategy {
    private String locationUrl;
    private boolean done;

    /**
     * The name of the header that indicates that a long running operation will use the Location
     * strategy.
     */
    public static final String HEADER_NAME = "Location";

    private LocationPollStrategy(RestProxy restProxy, SwaggerMethodParser methodParser, String locationUrl, long delayInMilliseconds) {
        super(restProxy, methodParser, delayInMilliseconds);

        this.locationUrl = locationUrl;
    }

    @Override
    public HttpRequest createPollRequest() {
        return new HttpRequest(fullyQualifiedMethodName(), "GET", locationUrl);
    }

    @Override
    public Single<HttpResponse> updateFromAsync(HttpResponse httpPollResponse) {
        return ensureExpectedStatus(httpPollResponse, new int[] { 202 })
                .map(new Func1<HttpResponse, HttpResponse>() {
                    @Override
                    public HttpResponse call(HttpResponse response) {
                        final int httpStatusCode = response.statusCode();

                        updateDelayInMillisecondsFrom(response);

                        if (httpStatusCode == 202) {
                            String newLocationUrl = response.headerValue(HEADER_NAME);
                            if (newLocationUrl != null) {
                                locationUrl = newLocationUrl;
                            }
                        }
                        else {
                            done = true;
                        }
                        return response;
                    }
                });
    }

    @Override
    public boolean isDone() {
        return done;
    }

    /**
     * Try to create a new LocationOperationPollStrategy object that will poll the provided location
     * URL. If the provided HttpResponse doesn't have a Location header or the header is empty,
     * then null will be returned.
     * @param originalHttpRequest The original HTTP request.
     * @param httpResponse The HTTP response that the required header values for this pollStrategy
     *                     will be read from.
     * @param delayInMilliseconds The delay (in milliseconds) that the resulting pollStrategy will
     *                            use when polling.
     */
    static PollStrategy tryToCreate(RestProxy restProxy, SwaggerMethodParser methodParser, HttpRequest originalHttpRequest, HttpResponse httpResponse, long delayInMilliseconds) {
        final String locationUrl = getHeader(httpResponse);

        String pollUrl = null;
        if (locationUrl != null && !locationUrl.isEmpty()) {
            if (locationUrl.startsWith("/")) {
                try {
                    final URL originalRequestUrl = new URL(originalHttpRequest.url());
                    pollUrl = new URL(originalRequestUrl, locationUrl).toString();
                } catch (MalformedURLException ignored) {
                }
            }
            else {
                final String locationUrlLower = locationUrl.toLowerCase();
                if (locationUrlLower.startsWith("http://") || locationUrlLower.startsWith("https://")) {
                    pollUrl = locationUrl;
                }
            }
        }

        return pollUrl == null
                ? null
                : new LocationPollStrategy(restProxy, methodParser, pollUrl, delayInMilliseconds);
    }

    static String getHeader(HttpResponse httpResponse) {
        return httpResponse.headerValue(HEADER_NAME);
    }
}