/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.duke.cs.osprey.control;

import java.util.ArrayList;

import cern.colt.matrix.DoubleFactory1D;
import cern.colt.matrix.DoubleFactory2D;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.structure.PDBFileReader;
import edu.duke.cs.osprey.structure.PDBFileWriter;
import edu.duke.cs.osprey.tests.DOFTests;
import edu.duke.cs.osprey.tests.EnergyTests;
import edu.duke.cs.osprey.tests.ToolTests;
import edu.duke.cs.osprey.tests.UnitTestSuite;
import edu.duke.cs.osprey.confspace.RC;
import edu.duke.cs.osprey.dof.DegreeOfFreedom;
import edu.duke.cs.osprey.dof.EllipseCoordDOF;
import edu.duke.cs.osprey.dof.FreeDihedral;

/**
 *
 * @author mhall44
 * Parse arguments and call high-level functions like DEE/A* and K*
   These will each be controlled by dedicated classes, unlike in the old KSParser
   to keep this class more concise, and to separate these functions for modularity purposes
 */

public class Main {

    
    
    public static void main(String[] args){
        //args expected to be "-c KStar.cfg command config_file_1.cfg ..."
        
        debuggingCommands(args);
        
        String command = "";
        try{
        	command = args[2];
        }
        catch(Exception e){
        	System.out.println("Command expects arguments (e.g. -c KStar.cfg {findGMEC|fcalcKStar} System.cfg DEE.cfg");
        	System.exit(1);
        }
        
        
        
        long startTime = System.currentTimeMillis();
        
        ConfigFileParser cfp = new ConfigFileParser(args);//args 1, 3+ are configuration files
        
        //load data filescloneclone
        cfp.loadData();        
        
        if(command.equalsIgnoreCase("findGMEC")){
            //I recommend that we change the command names a little to be more intuitive, e.g. 
            //"findGMEC" instead of doDEE
            GMECFinder gf = new GMECFinder(cfp);
            gf.calcGMEC();//this can be the n globally minimum-energy conformations for n>1, or just the 1 
            //These functions will handle their own output
        }
        else if(command.equalsIgnoreCase("calcKStar")){
            throw new UnsupportedOperationException("ERROR: Still need to implement K*");
            //KStarCalculator ksc = new KStarCalculator(params);
            //ksc.calcKStarScores();
        }
        else if(command.equalsIgnoreCase("RunTests")){
            UnitTestSuite.runAllTests();
        }
        //etc.
        else
            throw new RuntimeException("ERROR: OSPREY command unrecognized: "+command);
        
        long endTime = System.currentTimeMillis();
        System.out.println("Total OSPREY execution time: " + ((endTime-startTime)/60000) + " minutes.");
        System.out.println("OSPREY finished");
    }
    
    
    private static void debuggingCommands(String[] args){
        //anything we want to try as an alternate main function, for debugging purposes
        //likely will want to exit after doing this (specify here)
        //for normal operation, leave no uncommented code in this function
        
        /*EnvironmentVars.assignTemplatesToStruct = false;//this would change things
        Molecule m = PDBFileReader.readPDBFile("1CC8.ss.pdb");
        PDBFileWriter.writePDBFile(m, "1CC8.copy.pdb");
        
        System.exit(0);*/
    }
    
    
}