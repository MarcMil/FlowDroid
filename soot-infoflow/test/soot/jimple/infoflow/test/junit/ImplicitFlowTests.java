/*******************************************************************************
 * Copyright (c) 2012 Secure Software Engineering Group at EC SPRIDE.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors: Christian Fritz, Steven Arzt, Siegfried Rasthofer, Eric
 * Bodden, and others.
 ******************************************************************************/
package soot.jimple.infoflow.test.junit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Ignore;
import org.junit.Test;

import soot.jimple.infoflow.IInfoflow;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.InfoflowConfiguration.ImplicitFlowMode;
import soot.jimple.infoflow.InfoflowConfiguration.StaticFieldTrackingMode;
import soot.jimple.infoflow.config.IInfoflowConfig;
import soot.jimple.infoflow.taintWrappers.EasyTaintWrapper;
import soot.options.Options;

/**
 * Test class for implicit flows
 * 
 * @author Steven Arzt
 */
public class ImplicitFlowTests extends JUnitTests {

	@Override
	protected IInfoflow initInfoflow() {
		IInfoflow infoflow = super.initInfoflow();
		infoflow.getConfig().setImplicitFlowMode(ImplicitFlowMode.AllImplicitFlows);
		return infoflow;
	}

	@Test
	public void userCodeTest1() {
		IInfoflow infoflow = initInfoflow();
		infoflow.getConfig().setInspectSinks(false);
		infoflow.getConfig().setCodeEliminationMode(InfoflowConfiguration.CodeEliminationMode.NoCodeElimination);

		List<String> epoints = new ArrayList<String>();
		epoints.add("<soot.jimple.infoflow.test.ImplicitFlowTestCode: void userCodeTest1()>");
		infoflow.computeInfoflow(appPath, libPath, epoints, sources, sinks);
		negativeCheckInfoflow(infoflow);
	}

}
