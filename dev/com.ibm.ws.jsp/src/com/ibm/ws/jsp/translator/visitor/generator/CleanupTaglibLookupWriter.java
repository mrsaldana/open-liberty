/*******************************************************************************
 * Copyright (c) 1997, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.jsp.translator.visitor.generator;

public class CleanupTaglibLookupWriter extends MethodWriter {
    public CleanupTaglibLookupWriter(boolean isThreadTagPooling) {
        println();
        if (isThreadTagPooling) {
        	println("private void cleanupTaglibLookup(HttpServletRequest request, java.util.HashMap _jspx_TagLookup) {");
        }
        else {
            println("private void cleanupTaglibLookup(java.util.HashMap _jspx_TagLookup) {");
        }
    }

    public void complete() {
        println("}");
    }
}
