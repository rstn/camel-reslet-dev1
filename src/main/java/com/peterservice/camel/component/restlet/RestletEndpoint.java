/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.peterservice.camel.component.restlet;

import java.util.List;
import java.util.Map;

import org.apache.camel.Consumer;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.component.restlet.DefaultRestletBinding;
import org.apache.camel.component.restlet.RestletBinding;
import org.apache.camel.component.restlet.RestletHeaderFilterStrategy;
import org.apache.camel.impl.DefaultEndpoint;
import org.apache.camel.spi.HeaderFilterStrategy;
import org.apache.camel.spi.HeaderFilterStrategyAware;
import org.apache.camel.util.CollectionStringBuffer;
import org.restlet.data.Method;

/**
 * Represents a <a href="http://www.restlet.org/"> endpoint</a>
 *
 * @version 
 */
public class RestletEndpoint extends DefaultEndpoint implements HeaderFilterStrategyAware {

    private static final int DEFAULT_PORT = 80;
    private static final String DEFAULT_PROTOCOL = "http";
    private static final String DEFAULT_HOST = "localhost";

    private Method restletMethod = Method.GET;

    // Optional and for consumer only. This allows a single route to service multiple methods.
    // If it is non-null then restletMethod is ignored.
    private Method[] restletMethods;

    private String protocol = DEFAULT_PROTOCOL;
    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;
    private String uriPattern;

    // Optional and for consumer only. This allows a single route to service multiple URI patterns.
    // The URI pattern defined in the endpoint will still be honored.
    private List<String> restletUriPatterns;

    private Map<String, String> restletRealm;
    private HeaderFilterStrategy headerFilterStrategy;
    private RestletBinding restletBinding;
    private boolean throwExceptionOnFailure = true;

    //! Исключения в конструкторах крайне нежелательны
    public RestletEndpoint(final PSRestletComponent component, final String remaining) throws Exception {
        super(remaining, component);
    }
    
    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public boolean isLenientProperties() {
        // true to allow dynamic URI options to be configured and passed to external system.
        return true;
    }

    @Override
    public boolean equals(Object object) {
        return super.equals(object);
    }
    
    @Override
    public int hashCode() {
        return super.hashCode();
    }
    
    @Override
    public Producer createProducer() throws Exception {
        return null;
    }

    @Override
    public Consumer createConsumer(final Processor processor) throws Exception {
        final RestletConsumer answer = new RestletConsumer(this, processor);
        configureConsumer(answer);
        return answer;
    }

    public void connect(final RestletConsumer restletConsumer) throws Exception {
        ((PSRestletComponent) getComponent()).connect(restletConsumer);
    }

    public void disconnect(final RestletConsumer restletConsumer) throws Exception {
        ((PSRestletComponent) getComponent()).disconnect(restletConsumer);
    }

    public Method getRestletMethod() {
        return restletMethod;
    }

    public void setRestletMethod(Method restletMethod) {
        this.restletMethod = restletMethod;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(final String protocol) {
        this.protocol = protocol;
    }

    public String getHost() {
        return host;
    }

    public void setHost(final String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(final int port) {
        this.port = port;
    }

    public String getUriPattern() {
        return uriPattern;
    }

    public void setUriPattern(final String uriPattern) {
        this.uriPattern = uriPattern;
    }

    public RestletBinding getRestletBinding() {
        return restletBinding;
    }

    public void setRestletBinding(final RestletBinding restletBinding) {
        this.restletBinding = restletBinding;
    }

    @Override
    public void setHeaderFilterStrategy(final HeaderFilterStrategy headerFilterStrategy) {
        this.headerFilterStrategy = headerFilterStrategy;
        if (restletBinding instanceof HeaderFilterStrategyAware) {
            ((HeaderFilterStrategyAware) restletBinding).setHeaderFilterStrategy(headerFilterStrategy);
        }
    }

    @Override
    public HeaderFilterStrategy getHeaderFilterStrategy() {
        return headerFilterStrategy;
    }

    public void setRestletRealm(final Map<String, String> restletRealm) {
        this.restletRealm = restletRealm;
    }

    public Map<String, String> getRestletRealm() {
        return restletRealm;
    }

    @Override
    public ExchangePattern getExchangePattern() {
        // should always use in out for restlet
        return ExchangePattern.InOut;
    }

    public void setRestletMethods(final Method[] restletMethods) {
        this.restletMethods = restletMethods.clone();
    }

    public Method[] getRestletMethods() {
        return restletMethods;
    }

    public void setRestletUriPatterns(final List<String> restletUriPatterns) {
        this.restletUriPatterns = restletUriPatterns;
    }

    public List<String> getRestletUriPatterns() {
        return restletUriPatterns;
    }

    public boolean isThrowExceptionOnFailure() {
        return throwExceptionOnFailure;
    }

    public void setThrowExceptionOnFailure(final boolean throwExceptionOnFailure) {
        this.throwExceptionOnFailure = throwExceptionOnFailure;
    }
    
    // Update the endpointUri with the restlet method information
    protected void updateEndpointUri() {
        String endpointUri = getEndpointUri();
        CollectionStringBuffer methods = new CollectionStringBuffer(",");
        if (getRestletMethods() != null && getRestletMethods().length > 0) {
            // list the method(s) as a comma seperated list
            for (Method method : getRestletMethods()) {
                methods.append(method.getName());
            }
        } else {
            // otherwise consider the single method we own
            methods.append(getRestletMethod());
        }

        // update the uri
        endpointUri = endpointUri + "?restletMethods=" + methods;
        setEndpointUri(endpointUri);
    }

    @Override
    protected void doStart() throws Exception {
        if (headerFilterStrategy == null) {
            headerFilterStrategy = new RestletHeaderFilterStrategy();
        }
        if (restletBinding == null) {
            restletBinding = new DefaultRestletBinding();
        }
        if (restletBinding instanceof HeaderFilterStrategyAware) {
            ((HeaderFilterStrategyAware) restletBinding).setHeaderFilterStrategy(getHeaderFilterStrategy());
        }
    }

    @Override
    protected void doStop() throws Exception {
        // noop
    }
}
