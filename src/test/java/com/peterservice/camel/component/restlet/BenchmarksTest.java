package com.peterservice.camel.component.restlet;

import org.openjdk.jmh.annotations.*;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.data.Method;
import org.restlet.resource.ServerResource;
import org.restlet.routing.VirtualHost;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Created by Pavel.Kuznetsov on 30.07.2015.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
public class BenchmarksTest {
    private static VirtualHostWithPSMatching vHost;

    @Setup
    public static void initVH() throws Exception {
        VirtualHost virtualHost = new VirtualHost(new Context());
        vHost = new VirtualHostWithPSMatching(virtualHost);
        File xmlConfigFile = new File("../resources/service_config.xml");
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(xmlConfigFile);
        NodeList nodeList = doc.getElementsByTagName("function");
        for (int i = 0; i < nodeList.getLength(); i++) {
            String str = nodeList.item(i).getAttributes().getNamedItem("url").getTextContent();
            if (str.isEmpty() || (str.charAt(0) == '/'))
                str = "subscribers" + str;
            else
                str = "subscribers/" + str;
            vHost.attach(str, ServerResource.class);
        }
    }
    @Benchmark
    @Warmup(iterations = 100, time = 1, timeUnit = TimeUnit.MILLISECONDS)
    @Measurement(iterations = 10000, time = 1, timeUnit = TimeUnit.MILLISECONDS)
    public String testGetCustom2() {
        Request request = new Request(Method.GET, "subscribers/48yh/charges/runBilling");
        return vHost.getOriginalCustom(request).getTemplate().getPattern();
    }

    @Benchmark
    @Warmup(iterations = 100, time = 1, timeUnit = TimeUnit.MILLISECONDS)
    @Measurement(iterations = 10000, time = 1, timeUnit = TimeUnit.MILLISECONDS)
    public String testGetCustom() {
        Request request = new Request(Method.GET, "subscribers/48yh/charges/runBilling");
        return vHost.getCustom(request, null).getTemplate().getPattern();
    }
}
