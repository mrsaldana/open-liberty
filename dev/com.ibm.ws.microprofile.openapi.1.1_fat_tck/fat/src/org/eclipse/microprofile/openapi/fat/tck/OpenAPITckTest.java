/*******************************************************************************
 * Copyright (c) 2018, 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.microprofile.openapi.fat.tck;

import java.util.HashMap;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.ibm.websphere.simplicity.PortType;

import componenttest.annotation.AllowedFFDC;
import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.topology.impl.LibertyServer;
import componenttest.topology.utils.tck.TCKResultsInfo.Type;
import componenttest.topology.utils.tck.TCKRunner;
/**
 * This is a test class that runs a whole Maven TCK as one test FAT test.
 * There is a detailed output on specific
 */
@RunWith(FATRunner.class)
public class OpenAPITckTest {

    @Server("FATServer")
    public static LibertyServer server;

    @BeforeClass
    public static void setUp() throws Exception {
        server.startServer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.stopServer("CWWKO1650E", "CWWKO1651W"); 
    }

    @Test
    @AllowedFFDC // The tested deployment exceptions cause FFDC so we have to allow for this.
    public void testOpenAPI11Tck() throws Exception {
        String protocol = "http";
        String host = server.getHostname();
        String port = Integer.toString(server.getPort(PortType.WC_defaulthost));
        
        Map<String, String> additionalProps = new HashMap<>();
        additionalProps.put("test.url", protocol + "://" + host + ":" + port);

        String bucketName = "com.ibm.ws.microprofile.openapi.1.1_fat_tck";
        String testName = this.getClass() + ":testOpenAPI11Tck";
        Type type = Type.MICROPROFILE;
        String specName = "Open API";
        TCKRunner.runTCK(server, bucketName, testName, type, specName, additionalProps);
   }

}
