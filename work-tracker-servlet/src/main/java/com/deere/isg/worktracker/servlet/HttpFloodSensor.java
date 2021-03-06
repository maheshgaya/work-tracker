/**
 * Copyright 2019 Deere & Company
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.deere.isg.worktracker.servlet;

import com.deere.isg.worktracker.FloodSensor;
import com.deere.isg.worktracker.OutstandingWork;
import org.slf4j.Logger;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public class HttpFloodSensor<W extends HttpWork> extends FloodSensor<W> {
    public static final int SC_TOO_MANY_REQUESTS = 429;
    private ConnectionLimits<W> connectionLimits;

    public HttpFloodSensor(OutstandingWork<W> outstanding) {
        this(outstanding, new ConnectionLimits<>());
    }

    public HttpFloodSensor(OutstandingWork<W> outstanding, ConnectionLimits<W> connectionLimits) {
        super(outstanding);
        this.connectionLimits = connectionLimits;
    }

    /**
     * Checks if the request is within limits and if it may proceed. Otherwise, it
     * redirects the response to {@code SC_TOO_MANY_REQUESTS} error.
     * <p>
     * By default, if the response is just of an ServletResponse, it will always return true.
     * If the response is of an HttpServletResponse, it will have to pass the limit checks
     *
     * @param response Should pass a response to allow redirect {@code SC_TOO_MANY_REQUESTS}
     * @return {@code true} if a request can proceed
     */
    public boolean mayProceedOrRedirectTooManyRequest(ServletResponse response) {
        return !(response instanceof HttpServletResponse) || mayProceedOrRedirectTooManyRequest((HttpServletResponse) response);
    }

    private boolean mayProceedOrRedirectTooManyRequest(HttpServletResponse response) {
        return !getOutstanding().current()
                .flatMap(work -> checkLimits()
                        .map(fn -> fn.apply(work))
                        .filter(Optional::isPresent)
                        .peek(o -> respondTooManyRequests((HttpServletResponse) response, o.orElse(1)))
                        .findFirst())
                .isPresent();
    }

    private void respondTooManyRequests(HttpServletResponse response, Integer waitTime) {
        response.setStatus(SC_TOO_MANY_REQUESTS);
        response.setHeader("Retry-After", waitTime.toString());
    }

    /**
     * Provides a stream of limit checks from {@link ConnectionLimits}
     * Default checks are for too many total, same service,
     * same session, and same user requests
     *
     * @return A stream of Functions for checking {@link #shouldRetryLater(HttpWork, ConnectionLimits.Limit)}
     */
    @Override
    protected Stream<Function<W, Optional<Integer>>> checkLimits() {
        return connectionLimits.getConnectionLimits().stream().map(limit -> w -> shouldRetryLater(w, limit));
    }

    @Override
    protected void setLogger(Logger logger) {
        super.setLogger(logger);
    }

    protected ConnectionLimits<W>.Limit getConnectionLimit(String key) {
        return connectionLimits.getConnectionLimit(key);
    }

    protected Optional<Integer> shouldRetryLater(W work, ConnectionLimits<W>.Limit connectionLimit) {
        if (connectionLimit.getFunction() != null) {
            return shouldRetryLater(
                    work,
                    connectionLimit.getFunction(),
                    connectionLimit.getLimit(),
                    connectionLimit.getTypeName(),
                    connectionLimit.getMessage()
            );
        }
        return shouldRetryLater(
                work,
                connectionLimit.getPredicate(),
                connectionLimit.getLimit(),
                connectionLimit.getTypeName(),
                connectionLimit.getMessage()
        );
    }
}
