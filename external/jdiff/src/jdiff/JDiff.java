package jdiff;

import com.google.doclava.javadoc.RootDocImpl;
import com.sun.javadoc.DocErrorReporter;
import com.sun.javadoc.RootDoc;
import java.io.*;
import java.lang.Process;
import java.lang.Runtime;
import java.lang.reflect.*; // Used for invoking Javadoc indirectly
import java.util.*;
import java.util.HashSet;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic.Kind;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

/**
 * Generates HTML describing the changes between two sets of Java source code.
 *
 * See the file LICENSE.txt for copyright details.
 * @author Matthew Doar, mdoar@pobox.com.
 */
public class JDiff implements Doclet {

    public static Reporter reporter;

    @Override
    public void init(Locale locale, Reporter reporter) {
        JDiff.reporter = reporter;
    }

    @Override
    public String getName() {
        return "JDiff";
    }

    @Override
    public Set<? extends Option> getSupportedOptions() {
        return Options.getSupportedOptions();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_5;
    }

    @Override
    public boolean run(DocletEnvironment environment) {
        if (!Options.writeXML && !Options.compareAPIs) {
            JDiff.reporter.print(Kind.ERROR,
                    "First use the -apiname option to generate an XML file for one API.");
            JDiff.reporter.print(Kind.ERROR,
                    "Then use the -apiname option again to generate another XML file for a different version of the API.");
            JDiff.reporter.print(Kind.ERROR,
                    "Finally use the -oldapi option and -newapi option to generate a report about how the APIs differ.");
            return false;
        }
        return start(new RootDocImpl(environment));
    }

    /**
     * Doclet-mandated start method. Everything begins here.
     *
     * @param root  a RootDoc object passed by Javadoc
     * @return true if document generation succeeds
     */
    public static boolean start(RootDoc root) {
        if (root != null)
            System.out.println("JDiff: doclet started ...");
        JDiff jd = new JDiff();
        return jd.startGeneration(root);
    }

    /**
     * Generate the summary of the APIs.
     *
     * @param root  the RootDoc object passed by Javadoc
     * @return true if no problems encountered within JDiff
     */
    protected boolean startGeneration(RootDoc newRoot) {
        long startTime = System.currentTimeMillis();

        // Open the file where the XML representing the API will be stored.
        // and generate the XML for the API into it.
        if (Options.writeXML) {
            RootDocToXML.writeXML(newRoot);
        }

        if (Options.compareAPIs) {
        String tempOldFileName = Options.oldFileName;
        if (Options.oldDirectory != null) {
        tempOldFileName = Options.oldDirectory;
        if (!tempOldFileName.endsWith(JDiff.DIR_SEP)) {
            tempOldFileName += JDiff.DIR_SEP;
        }
        tempOldFileName += Options.oldFileName;
        }

            // Check the file for the old API exists
            File f = new File(tempOldFileName);
            if (!f.exists()) {
                System.out.println("Error: file '" + tempOldFileName + "' does not exist for the old API");
                return false;
            }
            // Check the file for the new API exists

        String tempNewFileName = Options.newFileName;
            if (Options.newDirectory != null) {
        tempNewFileName = Options.newDirectory;
        if (!tempNewFileName.endsWith(JDiff.DIR_SEP)) {
            tempNewFileName += JDiff.DIR_SEP;
        }
        tempNewFileName += Options.newFileName;
            }
            f = new File(tempNewFileName);
            if (!f.exists()) {
                System.out.println("Error: file '" + tempNewFileName + "' does not exist for the new API");
                return false;
            }

            // Read the file where the XML representing the old API is stored
            // and create an API object for it.
            System.out.print("JDiff: reading the old API in from file '" + tempOldFileName + "'...");
            // Read the file in, but do not add any text to the global comments
            API oldAPI = XMLToAPI.readFile(tempOldFileName, false, Options.oldFileName);

            // Read the file where the XML representing the new API is stored
            // and create an API object for it.
            System.out.print("JDiff: reading the new API in from file '" + tempNewFileName + "'...");
            // Read the file in, and do add any text to the global comments
            API newAPI = XMLToAPI.readFile(tempNewFileName, true, Options.newFileName);

            // Compare the old and new APIs.
            APIComparator comp = new APIComparator();

            comp.compareAPIs(oldAPI, newAPI);

            // Read the file where the XML for comments about the changes between
            // the old API and new API is stored and create a Comments object for
            // it. The Comments object may be null if no file exists.
            int suffix = Options.oldFileName.lastIndexOf('.');
            String commentsFileName = "user_comments_for_" + Options.oldFileName.substring(0, suffix);
            suffix = Options.newFileName.lastIndexOf('.');
            commentsFileName += "_to_" + Options.newFileName.substring(0, suffix) + ".xml";
            commentsFileName = commentsFileName.replace(' ', '_');
                if (HTMLReportGenerator.commentsDir !=null) {
                  commentsFileName = HTMLReportGenerator.commentsDir + DIR_SEP + commentsFileName;
                } else if (HTMLReportGenerator.outputDir != null) {
                  commentsFileName = HTMLReportGenerator.outputDir + DIR_SEP + commentsFileName;
                }
            System.out.println("JDiff: reading the comments in from file '" + commentsFileName + "'...");
            Comments existingComments = Comments.readFile(commentsFileName);
            if (existingComments == null)
                System.out.println(" (the comments file will be created)");

            // Generate an HTML report which summarises all the API differences.
            HTMLReportGenerator reporter = new HTMLReportGenerator();
            reporter.generate(comp, existingComments);

            // Emit messages about which comments are now unused and
            // which are new.
            Comments newComments = reporter.getNewComments();
            Comments.noteDifferences(existingComments, newComments);

            // Write the new comments out to the same file, with unused comments
            // now commented out.
            System.out.println("JDiff: writing the comments out to file '" + commentsFileName + "'...");
            Comments.writeFile(commentsFileName, newComments);
        }

        System.out.print("JDiff: finished (took " + (System.currentTimeMillis() - startTime)/1000 + "s");
        if (Options.writeXML)
            System.out.println(", not including scanning the source files).");
        else if (Options.compareAPIs)
            System.out.println(").");
       return true;
    }

    /**
     * This method is only called when running JDiff as a standalone
     * application, and uses ANT to execute the build configuration in the
     * XML configuration file passed in.
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            //showUsage();
            System.out.println("Looking for a local 'build.xml' configuration file");
        } else if (args.length == 1) {
            if (args[0].compareTo("-help") == 0 ||
                args[0].compareTo("-h") == 0 ||
                args[0].compareTo("?") == 0) {
                showUsage();
            } else if (args[0].compareTo("-version") == 0) {
                System.out.println("JDiff version: " + JDiff.version);
            }
            return;
        }
        int rc = runAnt(args);
        return;
    }

    /**
     * Display usage information for JDiff.
     */
    public static void showUsage() {
        System.out.println("usage: java jdiff.JDiff [-version] [-buildfile <XML configuration file>]");
        System.out.println("If no build file is specified, the local build.xml file is used.");
    }

    /**
     * Invoke ANT by reflection.
     *
     * @return The integer return code from running ANT.
     */
    public static int runAnt(String[] args) {
        String className = null;
        Class c = null;
        try {
            className = "org.apache.tools.ant.Main";
            c = Class.forName(className);
        } catch (ClassNotFoundException e1) {
            System.err.println("Error: ant.jar not found on the classpath");
            return -1;
        }
        try {
            Class[] methodArgTypes = new Class[1];
            methodArgTypes[0] = args.getClass();
            Method mainMethod = c.getMethod("main", methodArgTypes);
            Object[] methodArgs = new Object[1];
            methodArgs[0] = args;
            // The object can be null because the method is static
            Integer res = (Integer)mainMethod.invoke(null, methodArgs);
            System.gc(); // Clean up after running ANT
            return res.intValue();
        } catch (NoSuchMethodException e2) {
            System.err.println("Error: method \"main\" not found");
            e2.printStackTrace();
        } catch (IllegalAccessException e4) {
            System.err.println("Error: class not permitted to be instantiated");
            e4.printStackTrace();
        } catch (InvocationTargetException e5) {
            System.err.println("Error: method \"main\" could not be invoked");
            e5.printStackTrace();
        } catch (Exception e6) {
            System.err.println("Error: ");
            e6.printStackTrace();
        }
        System.gc(); // Clean up after running ANT
        return -1;
    }

    /**
     * The file separator for the local filesystem, forward or backward slash.
     */
    static String DIR_SEP = System.getProperty("file.separator");

    /** Details for where to find JDiff. */
    static final String jDiffLocation = "https://www.jdiff.org";
    /** Contact email address for the primary JDiff maintainer. */
    static final String authorEmail = "mdoar@pobox.com";

    /** A description for HTML META tags. */
    static final String jDiffDescription = "JDiff is a Javadoc doclet which generates an HTML report of all the packages, classes, constructors, methods, and fields which have been removed, added or changed in any way, including their documentation, when two APIs are compared.";
    /** Keywords for HTML META tags. */
    static final String jDiffKeywords = "diff, jdiff, javadiff, java diff, java difference, API difference, difference between two APIs, API diff, Javadoc, doclet";

    /** The current JDiff version. */
    static final String version = "1.1.0";

    /** The current virtual machine version. */
    static String javaVersion = System.getProperty("java.version");

    /** Set to enable increased logging verbosity for debugging. */
    private static boolean trace = false;

} //JDiff
