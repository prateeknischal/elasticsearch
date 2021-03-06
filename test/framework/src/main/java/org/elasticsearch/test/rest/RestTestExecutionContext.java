/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.test.rest;

import org.elasticsearch.Version;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.test.rest.client.RestTestClient;
import org.elasticsearch.test.rest.client.RestTestResponse;
import org.elasticsearch.test.rest.client.RestTestResponseException;
import org.elasticsearch.test.rest.spec.RestSpec;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution context passed across the REST tests.
 * Holds the REST client used to communicate with elasticsearch.
 * Caches the last obtained test response and allows to stash part of it within variables
 * that can be used as input values in following requests.
 */
public class RestTestExecutionContext implements Closeable {

    private static final ESLogger logger = Loggers.getLogger(RestTestExecutionContext.class);

    private final Stash stash = new Stash();

    private final RestSpec restSpec;

    private RestTestClient restTestClient;

    private RestTestResponse response;

    public RestTestExecutionContext(RestSpec restSpec) {
        this.restSpec = restSpec;
    }

    /**
     * Calls an elasticsearch api with the parameters and request body provided as arguments.
     * Saves the obtained response in the execution context.
     */
    public RestTestResponse callApi(String apiName, Map<String, String> params, List<Map<String, Object>> bodies,
                                    Map<String, String> headers) throws IOException {
        //makes a copy of the parameters before modifying them for this specific request
        HashMap<String, String> requestParams = new HashMap<>(params);
        for (Map.Entry<String, String> entry : requestParams.entrySet()) {
            if (stash.containsStashedValue(entry.getValue())) {
                entry.setValue(stash.getValue(entry.getValue()).toString());
            }
        }

        String body = actualBody(bodies);
        try {
            response = callApiInternal(apiName, requestParams, body, headers);
            return response;
        } catch(RestTestResponseException e) {
            response = e.getRestTestResponse();
            throw e;
        } finally {
            //we always stash the last response body
            stash.stashValue("body", response.getBody());
        }
    }

    private String actualBody(List<Map<String, Object>> bodies) throws IOException {
        if (bodies.isEmpty()) {
            return "";
        }

        if (bodies.size() == 1) {
            return bodyAsString(stash.replaceStashedValues(bodies.get(0)));
        }

        StringBuilder bodyBuilder = new StringBuilder();
        for (Map<String, Object> body : bodies) {
            bodyBuilder.append(bodyAsString(stash.replaceStashedValues(body))).append("\n");
        }
        return bodyBuilder.toString();
    }

    private String bodyAsString(Map<String, Object> body) throws IOException {
        return XContentFactory.jsonBuilder().map(body).string();
    }

    private RestTestResponse callApiInternal(String apiName, Map<String, String> params, String body, Map<String, String> headers)
            throws IOException  {
        return restTestClient.callApi(apiName, params, body, headers);
    }

    /**
     * Extracts a specific value from the last saved response
     */
    public Object response(String path) throws IOException {
        return response.evaluate(path, stash);
    }

    /**
     * Creates the embedded REST client when needed. Needs to be called before each test.
     */
    public void initClient(URL[] urls, Settings settings) throws Exception {
        if (restTestClient == null) {
            restTestClient = new RestTestClient(restSpec, settings, urls);
        }
    }

    /**
     * Clears the last obtained response and the stashed fields
     */
    public void clear() {
        logger.debug("resetting client, response and stash");
        response = null;
        stash.clear();
    }

    public Stash stash() {
        return stash;
    }

    /**
     * Returns the current es version as a string
     */
    public Version esVersion() {
        return restTestClient.getEsVersion();
    }

    /**
     * Closes the execution context and releases the underlying resources
     */
    @Override
    public void close() {
        if (restTestClient != null) {
            restTestClient.close();
        }
    }
}
