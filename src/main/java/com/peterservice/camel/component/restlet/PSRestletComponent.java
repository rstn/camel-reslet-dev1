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

import org.apache.camel.Endpoint;
import org.apache.camel.impl.HeaderFilterStrategyComponent;
import org.apache.camel.util.URISupport;
import org.apache.camel.util.UnsafeUriCharactersEncoder;
import org.restlet.Component;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.Method;
import org.restlet.data.Parameter;
import org.restlet.data.Protocol;
import org.restlet.security.ChallengeAuthenticator;
import org.restlet.security.MapVerifier;
import org.restlet.util.Series;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Camel component embedded Restlet that produces and consumes exchanges.
 * 
 * @version
 */
public class PSRestletComponent extends HeaderFilterStrategyComponent {
    private static final Logger LOG = LoggerFactory.getLogger(PSRestletComponent.class);

    private final Map<String, Server> servers = new HashMap<String, Server>();
    private final Map<String, MethodBasedRouter> routers = new HashMap<String, MethodBasedRouter>();
    private final Component component;

    // options that can be set on the restlet server
    private Boolean controllerDaemon;
    private Integer controllerSleepTimeMs;
    private Integer inboundBufferSize;
    private Integer minThreads;
    private Integer maxThreads;
    private Integer workerThreads;
    private Integer maxConnectionsPerHost;
    private Integer maxTotalConnections;
    private Integer outboundBufferSize;
    private Boolean persistingConnections;
    private Boolean pipeliningConnections;
    private Integer threadMaxIdleTimeMs;
    private Boolean useForwardedForHeader;
    private Boolean reuseAddress;

    /* Peter-Service defined extensions for SSL */
    private String sslContextFactory;
    private String keystorePath;
    private String keystorePassword;
    private String trustStorePassword;
    private String trustStorePath;
    private String keyPassword;
    private String keystoreType;
    private String certAlgorithm;
    private String sslProtocol;
    private boolean needClientAuthentication;
    private boolean wantClientAuthentication;

    public PSRestletComponent() {
        this(new Component());
    }
    
    public PSRestletComponent(Component component) {
        // Allow the Component to be injected, so that the RestletServlet may be
        // configured within a webapp
        VirtualHostWithPSMatching vHost = new VirtualHostWithPSMatching(component.getDefaultHost());
        component.setDefaultHost(vHost);
        this.component = component;
    }

    public Integer getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(Integer workerThreads) {
        this.workerThreads = workerThreads;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    public String getTrustStorePath() {
        return trustStorePath;
    }

    public void setTrustStorePath(String trustStorePath) {
        this.trustStorePath = trustStorePath;
    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        RestletEndpoint result = new RestletEndpoint(this, remaining);
        setEndpointHeaderFilterStrategy(result);
        setProperties(result, parameters);
        // set the endpoint uri according to the parameter
        result.updateEndpointUri();

        // construct URI so we can use it to get the splitted information
        URI u = new URI(remaining);
        String protocol = u.getScheme();

        String uriPattern = u.getPath();
        if (parameters.size() > 0) {
            uriPattern = uriPattern + "?" + URISupport.createQueryString(parameters);
        }

        int port = 0;
        String host = u.getHost();
        if (u.getPort() > 0) {
            port = u.getPort();
        }

        result.setProtocol(protocol);
        result.setUriPattern(uriPattern);
        result.setHost(host);
        if (port > 0) {
            result.setPort(port);
        }

        return result;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        component.start();
    }

    @Override
    protected void doStop() throws Exception {
        component.stop();
        // component stop will stop servers so we should clear our list as well
        servers.clear();
        // routers map entries are removed as consumer stops and servers map
        // is not touch so to keep in sync with component's servers
        super.doStop();
    }

    @Override
    protected boolean useIntrospectionOnEndpoint() {
        // we invoke setProperties ourselves so we can construct "user" uri on
        // on the remaining parameters
        return false;
    }

    public void connect(RestletConsumer consumer) throws Exception {
        RestletEndpoint endpoint = consumer.getEndpoint();
        addServerIfNecessary(endpoint);

        // if restlet servlet server is created, the offsetPath is set in component context
        // see http://restlet.tigris.org/issues/show_bug.cgi?id=988
        String offsetPath = (String) this.component.getContext()
                .getAttributes().get("org.restlet.ext.servlet.offsetPath");

       this.component.getContext().getAttributes().put("org.restlet.autoWire","false");
        
        if (endpoint.getUriPattern() != null && endpoint.getUriPattern().length() > 0) {
            attachUriPatternToRestlet(offsetPath, endpoint.getUriPattern(), endpoint, consumer.getRestlet());
        }

        if (endpoint.getRestletUriPatterns() != null) {
            for (String uriPattern : endpoint.getRestletUriPatterns()) {
                attachUriPatternToRestlet(offsetPath, uriPattern, endpoint, consumer.getRestlet());
            }
        }
    }

    private void removeRoutes(RestletEndpoint endpoint, List<MethodBasedRouter> routesToRemove) throws Exception {
        for (MethodBasedRouter router : routesToRemove) {
            if (endpoint.getRestletMethods() != null) {
                Method[] methods = endpoint.getRestletMethods();
                for (Method method : methods) {
                    if (router!=null && method!=null) {
                        router.removeRoute(method);
                    }
                }
            } else {
                router.removeRoute(endpoint.getRestletMethod());
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Detached restlet uriPattern: {} method: {}", router.getUriPattern(),endpoint.getRestletMethod());
            }

            // remove router if its no longer in use
            if (!router.hasRoutes()) {
                deattachUriPatternFrimRestlet(router.getUriPattern(), router);
                if (!router.isStopped()) {
                    router.stop();
                }
                routers.remove(router.getUriPattern());
            }
        }
    }
    
    public void disconnect(RestletConsumer consumer) throws Exception {
        RestletEndpoint endpoint = consumer.getEndpoint();
        List<MethodBasedRouter> routesToRemove = new ArrayList<MethodBasedRouter>();

        String pattern = decodePattern(endpoint.getUriPattern());
        if (pattern != null && !pattern.isEmpty()) {
            routesToRemove.add(getMethodRouter(pattern, false));
        }

        if (endpoint.getRestletUriPatterns() != null) {
            for (String uriPattern : endpoint.getRestletUriPatterns()) {
                routesToRemove.add(getMethodRouter(uriPattern, false));
            }
        }
        removeRoutes(endpoint, routesToRemove);
    }

    private MethodBasedRouter getMethodRouter(String uriPattern, boolean addIfEmpty) {
        synchronized (routers) {
            MethodBasedRouter result = routers.get(uriPattern);
            if (result == null && addIfEmpty) {
                result = new MethodBasedRouter(uriPattern);
                LOG.debug("Added method based router: {}", result);
                routers.put(uriPattern, result);
            }
            return result;
        }
    }
    
    protected Server createServer(RestletEndpoint endpoint) {
        return new Server(component.getContext().createChildContext(), Protocol.valueOf(endpoint.getProtocol()), endpoint.getPort());
    }

    protected void addServerIfNecessary(RestletEndpoint endpoint) throws Exception {
        String key = buildKey(endpoint);
        Server server;
        synchronized (servers) {
            server = servers.get(key);
            if (server == null) {
                server = createServer(endpoint);
                component.getServers().add(server);

                // Add any Restlet server parameters that were included
                Series<Parameter> params = server.getContext().getParameters();

                if (getControllerDaemon() != null) {
                    params.add("controllerDaemon", getControllerDaemon().toString());
                }
                if (getControllerSleepTimeMs() != null) {
                    params.add("controllerSleepTimeMs", getControllerSleepTimeMs().toString());
                }
                if (getInboundBufferSize() != null) {
                    params.add("inboundBufferSize", getInboundBufferSize().toString());
                }
                if (getMinThreads() != null) {
                    params.add("minThreads", getMinThreads().toString());
                }
                if (getMaxThreads() != null) {
                    params.add("maxThreads", getMaxThreads().toString());
                }
                if (getWorkerThreads() != null) {
                    params.add("workerThreads", getWorkerThreads().toString());
                }
                if (getMaxConnectionsPerHost() != null) {
                    params.add("maxConnectionsPerHost", getMaxConnectionsPerHost().toString());
                }
                if (getMaxTotalConnections() != null) {
                    params.add("maxTotalConnections", getMaxTotalConnections().toString());
                }
                if (getOutboundBufferSize() != null) {
                    params.add("outboundBufferSize", getOutboundBufferSize().toString());
                }
                if (getPersistingConnections() != null) {
                    params.add("persistingConnections", getPersistingConnections().toString());
                }
                if (getPipeliningConnections() != null) {
                    params.add("pipeliningConnections", getPipeliningConnections().toString());
                }
                if (getThreadMaxIdleTimeMs() != null) {
                    params.add("threadMaxIdleTimeMs", getThreadMaxIdleTimeMs().toString());
                }
                if (getUseForwardedForHeader() != null) {
                    params.add("useForwardedForHeader", getUseForwardedForHeader().toString());
                }
                if (getReuseAddress() != null) {
                    params.add("reuseAddress", getReuseAddress().toString());
                }

                if (isWantClientAuthentication()) {
                    params.add("wantClientAuthentication", String.valueOf(isWantClientAuthentication()));
                }
                if (isNeedClientAuthentication()) {
                    params.add("needClientAuthentication", String.valueOf(isNeedClientAuthentication()));
                }
                if (getKeyPassword() != null) {
                    params.add("keyPassword", getKeyPassword());
                }
                if (getKeystorePassword() != null) {
                    params.add("keystorePassword", getKeystorePassword());
                }
                if (getKeystorePath() != null) {
                    params.add("keystorePath", getKeystorePath());
                }
                if (getKeystoreType() != null) {
                    params.add("keystoreType", getKeystoreType());
                }
                if (getSslContextFactory() != null) {
                    params.add("sslContextFactory", getSslContextFactory());
                }
                if (getCertAlgorithm() != null) {
                    params.add("certAlgorithm", getCertAlgorithm());
                }
                if (getSslProtocol() != null) {
                    params.add("sslProtocol", getSslProtocol());
                }
                if (getTrustStorePassword() != null) {
                    params.add("truststorePassword", getTrustStorePassword());
                }
                if (getTrustStorePath() != null) {
                    params.add("truststorePath", getTrustStorePath());
                }

                LOG.debug("Setting parameters: {} to server: {}", params, server);
                server.getContext().setParameters(params);

                servers.put(key, server);
                LOG.debug("Added server: {}", key);
                server.start();
            }
        }
    }

    private static String buildKey(RestletEndpoint endpoint) {
        return endpoint.getHost() + ":" + endpoint.getPort();
    }

    private void attachUriPatternToRestlet(String offsetPath, String uriPattern,
            RestletEndpoint endpoint, Restlet target) throws Exception {
        String newUriPattern = decodePattern(uriPattern);
        Restlet newTarget = target;
        MethodBasedRouter router = getMethodRouter(newUriPattern, true);
        
        Map<String, String> realm = endpoint.getRestletRealm();
        if (realm != null && realm.size() > 0) {
            ChallengeAuthenticator guard = new ChallengeAuthenticator(component.getContext()
                .createChildContext(), ChallengeScheme.HTTP_BASIC, "Camel-Restlet Endpoint Realm");
            MapVerifier verifier = new MapVerifier();
            for (Map.Entry<String, String> entry : realm.entrySet()) {
                verifier.getLocalSecrets().put(entry.getKey(), entry.getValue().toCharArray());
            }
            guard.setVerifier(verifier);
            guard.setNext(newTarget);
            newTarget = guard;
            LOG.debug("Target has been set to guard: {}", guard);
        }

        if (endpoint.getRestletMethods() != null) {
            Method[] methods = endpoint.getRestletMethods();
            for (Method method : methods) {
                router.addRoute(method, newTarget);
                LOG.debug("Attached restlet uriPattern: {} method: {}", newUriPattern, method);
            }
        } else {
            Method method = endpoint.getRestletMethod();
            router.addRoute(method, newTarget);
            LOG.debug("Attached restlet uriPattern: {} method: {}", newUriPattern, method);
        }

        if (!router.hasBeenAttached()) {
            if (offsetPath == null) {
                component.getDefaultHost().attach(newUriPattern, router);
            } else {
                component.getDefaultHost().attach(offsetPath + newUriPattern, router);
            }
            LOG.debug("Attached methodRouter uriPattern: {}", newUriPattern);
        }

        if (!router.isStarted()) {
            router.start();
            LOG.debug("Started methodRouter uriPattern: {}", newUriPattern);
        }
    }

    private void deattachUriPatternFrimRestlet(String uriPattern, Restlet target) throws Exception {
        component.getDefaultHost().detach(target);
        LOG.debug("Deattached methodRouter uriPattern: {}", uriPattern);
    }

    /**
     *  @deprecated (deprecated)
     */
    @Override
    @Deprecated
    protected String preProcessUri(String uri) {
        // If the URI was not valid (i.e. contains '{' and '}'
        // it was most likely encoded by normalizeEndpointUri in DefaultCamelContext.getEndpoint(String)
        return UnsafeUriCharactersEncoder.encode(uri.replaceAll("%7B", "(").replaceAll("%7D", ")"));
    }
    
    private static String decodePattern(String pattern) {
        if (pattern == null) {
            return null;
        }
        return pattern.replaceAll("\\(", "{").replaceAll("\\)", "}");
    }

    public Boolean getControllerDaemon() {
        return controllerDaemon;
    }

    public void setControllerDaemon(Boolean controllerDaemon) {
        this.controllerDaemon = controllerDaemon;
    }

    public Integer getControllerSleepTimeMs() {
        return controllerSleepTimeMs;
    }

    public void setControllerSleepTimeMs(Integer controllerSleepTimeMs) {
        this.controllerSleepTimeMs = controllerSleepTimeMs;
    }

    public Integer getInboundBufferSize() {
        return inboundBufferSize;
    }

    public void setInboundBufferSize(Integer inboundBufferSize) {
        this.inboundBufferSize = inboundBufferSize;
    }

    public Integer getMaxConnectionsPerHost() {
        return maxConnectionsPerHost;
    }

    public void setMaxConnectionsPerHost(Integer maxConnectionsPerHost) {
        this.maxConnectionsPerHost = maxConnectionsPerHost;
    }

    public Integer getMaxThreads() {
        return maxThreads;
    }

    public void setMaxThreads(Integer maxThreads) {
        this.maxThreads = maxThreads;
    }

    public Integer getMaxTotalConnections() {
        return maxTotalConnections;
    }

    public void setMaxTotalConnections(Integer maxTotalConnections) {
        this.maxTotalConnections = maxTotalConnections;
    }

    public Integer getMinThreads() {
        return minThreads;
    }

    public void setMinThreads(Integer minThreads) {
        this.minThreads = minThreads;
    }

    public Integer getOutboundBufferSize() {
        return outboundBufferSize;
    }

    public void setOutboundBufferSize(Integer outboundBufferSize) {
        this.outboundBufferSize = outboundBufferSize;
    }

    public Boolean getPersistingConnections() {
        return persistingConnections;
    }

    public void setPersistingConnections(Boolean persistingConnections) {
        this.persistingConnections = persistingConnections;
    }

    public Boolean getPipeliningConnections() {
        return pipeliningConnections;
    }

    public void setPipeliningConnections(Boolean pipeliningConnections) {
        this.pipeliningConnections = pipeliningConnections;
    }

    public Integer getThreadMaxIdleTimeMs() {
        return threadMaxIdleTimeMs;
    }

    public void setThreadMaxIdleTimeMs(Integer threadMaxIdleTimeMs) {
        this.threadMaxIdleTimeMs = threadMaxIdleTimeMs;
    }

    public Boolean getUseForwardedForHeader() {
        return useForwardedForHeader;
    }

    public void setUseForwardedForHeader(Boolean useForwardedForHeader) {
        this.useForwardedForHeader = useForwardedForHeader;
    }

    public Boolean getReuseAddress() {
        return reuseAddress;
    }

    public void setReuseAddress(Boolean reuseAddress) {
        this.reuseAddress = reuseAddress;
    }

    public boolean isWantClientAuthentication() {
        return wantClientAuthentication;
    }

    public void setWantClientAuthentication(boolean wantClientAuthentication) {
        this.wantClientAuthentication = wantClientAuthentication;
    }

    public String getSslProtocol() {
        return sslProtocol;
    }

    public void setSslProtocol(String sslProtocol) {
        this.sslProtocol = sslProtocol;
    }

    public String getSslContextFactory() {
        return sslContextFactory;
    }

    public void setSslContextFactory(String sslContextFactory) {
        this.sslContextFactory = sslContextFactory;
    }

    public boolean isNeedClientAuthentication() {
        return needClientAuthentication;
    }

    public void setNeedClientAuthentication(boolean needClientAuthentication) {
        this.needClientAuthentication = needClientAuthentication;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public void setKeystorePath(String keystorePath) {
        this.keystorePath = keystorePath;
    }

    public String getKeystorePassword() {
        return keystorePassword;
    }

    public void setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    public String getKeyPassword() {
        return keyPassword;
    }

    public void setKeyPassword(String keyPassword) {
        this.keyPassword = keyPassword;
    }

    public String getCertAlgorithm() {
        return certAlgorithm;
    }

    public void setCertAlgorithm(String certAlgorithm) {
        this.certAlgorithm = certAlgorithm;
    }

    public String getKeystoreType() {
        return keystoreType;
    }

    public void setKeystoreType(String keystoreType) {
        this.keystoreType = keystoreType;
    }
}
