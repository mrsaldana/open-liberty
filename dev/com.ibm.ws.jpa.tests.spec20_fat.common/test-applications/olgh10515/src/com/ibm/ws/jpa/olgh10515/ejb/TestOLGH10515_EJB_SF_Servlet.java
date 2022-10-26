/*******************************************************************************
 * Copyright (c) 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package com.ibm.ws.jpa.olgh10515.ejb;

import javax.annotation.PostConstruct;
import javax.servlet.annotation.WebServlet;

import org.junit.Test;

import com.ibm.ws.jpa.olgh10515.testlogic.JPATestOLGH10515Logic;
import com.ibm.ws.testtooling.testinfo.JPAPersistenceContext;
import com.ibm.ws.testtooling.testinfo.JPAPersistenceContext.PersistenceContextType;
import com.ibm.ws.testtooling.testinfo.JPAPersistenceContext.PersistenceInjectionType;
import com.ibm.ws.testtooling.vehicle.web.EJBDBTestVehicleServlet;

@SuppressWarnings("serial")
@WebServlet(urlPatterns = "/TestOLGH10515_EJB_SF_Servlet")
public class TestOLGH10515_EJB_SF_Servlet extends EJBDBTestVehicleServlet {

    @PostConstruct
    private void initFAT() {
        testClassName = JPATestOLGH10515Logic.class.getName();
        ejbJNDIName = "ejb/OLGH10515SFEJB";

        jpaPctxMap.put("test-jpa-resource-amjta",
                       new JPAPersistenceContext("test-jpa-resource-amjta", PersistenceContextType.APPLICATION_MANAGED_JTA, PersistenceInjectionType.JNDI, "java:comp/env/jpa/OLGH10515_AMJTA"));
        jpaPctxMap.put("test-jpa-resource-amrl",
                       new JPAPersistenceContext("test-jpa-resource-amrl", PersistenceContextType.APPLICATION_MANAGED_RL, PersistenceInjectionType.JNDI, "java:comp/env/jpa/OLGH10515_AMRL"));
        jpaPctxMap.put("test-jpa-resource-cmts",
                       new JPAPersistenceContext("test-jpa-resource-cmts", PersistenceContextType.CONTAINER_MANAGED_TS, PersistenceInjectionType.JNDI, "java:comp/env/jpa/OLGH10515_CMTS"));
    }

    @Test
    public void jpa_spec20_olgh10515_testJoinOnCollectionTable_EJB_SF_AMJTA_Web() throws Exception {
        final String testName = "jpa_spec20_olgh10515_testJoinOnCollectionTable_EJB_SF_AMJTA_Web";
        final String testMethod = "testJoinOnCollectionTable";
        final String testResource = "test-jpa-resource-amjta";
        executeTest(testName, testMethod, testResource);
    }

    @Test
    public void jpa_spec20_olgh10515_testJoinOnCollectionTable_EJB_SF_AMRL_Web() throws Exception {
        final String testName = "jpa_spec20_olgh10515_testJoinOnCollectionTable_EJB_SF_AMRL_Web";
        final String testMethod = "testJoinOnCollectionTable";
        final String testResource = "test-jpa-resource-amrl";
        executeTest(testName, testMethod, testResource);
    }

    @Test
    public void jpa_spec20_olgh10515_testJoinOnCollectionTable_EJB_SF_CMTS_Web() throws Exception {
        final String testName = "jpa_spec20_olgh10515_testJoinOnCollectionTable_EJB_SF_CMTS_Web";
        final String testMethod = "testJoinOnCollectionTable";
        final String testResource = "test-jpa-resource-cmts";
        executeTest(testName, testMethod, testResource);
    }
}
