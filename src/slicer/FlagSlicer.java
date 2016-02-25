package slicer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Enumeration;
import java.util.TreeSet;
import java.util.jar.*;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

import com.github.javaparser.ParseException;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.ShrikeBTMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.io.FileProvider;
import com.ibm.wala.util.strings.Atom;


public class FlagSlicer {
	String jarPath;
	String jarName;
	String fileName;
	String packageName;
	String className;
	String methodName;
	String flagName;
	String newJarPath;
	String newJarName;
	String outputSlicePath;
	ArrayList<String> srcFileLines;
	
	public FlagSlicer(String jarFilePath, String jarFileName, String file, String packageN,
			String classN, String method, String flag, String slicePath) {
		jarPath = jarFilePath;
		jarName = jarFileName;
		fileName = file;
		packageName = packageN;
		className = classN;
		methodName = method;
		flagName = flag;
		outputSlicePath = slicePath;
		srcFileLines = new ArrayList<String>();
	}
	
	private void uploadFileToMem() throws IOException {
		// Find the file inside the jar and extract it
		JarFile jar = new JarFile(newJarPath + File.separator + newJarName);
		String pathToFile = "";
		Enumeration<JarEntry> entries = jar.entries();
		while (entries.hasMoreElements()) {
			JarEntry file = (JarEntry) entries.nextElement();
			if (!file.getName().endsWith(fileName)) {
				continue;
			}
			pathToFile = newJarPath + File.separator + fileName;
			System.out.println("path to file: " + pathToFile);
			File f = new File(pathToFile);
			InputStream is = jar.getInputStream(file); // get the input stream
			FileOutputStream fos = new FileOutputStream(f);
			while (is.available() > 0) {  // write contents of 'is' to 'fos'
				fos.write(is.read());
			}
			fos.close();
			is.close();
		}
		
		System.out.println("extracted the file: " + pathToFile);
		// load the file to memory
	    String line;
	    try (
	        InputStream fis = new FileInputStream(pathToFile);
	        InputStreamReader isr = new InputStreamReader(fis, Charset.forName("UTF-8"));
	        BufferedReader br = new BufferedReader(isr);
	    ) {
	        while ((line = br.readLine()) != null) {
	        	System.out.println("uploading line: " + line);
	            srcFileLines.add(line);
	        }
	    } catch (FileNotFoundException e) {
	    	System.err.println("Did not find the source file: " + pathToFile);
			e.printStackTrace();
		} catch (IOException e) {
			System.err.println("io error occurred while reading file: " + pathToFile);
			e.printStackTrace();
		}
	}
	
	// looks for flag in method descriptor
	private int findParameterIndex(IMethod method) {
		int flagInd = -1;
		for(int i = 0; i < method.getNumberOfParameters(); ++i) {
			String param = method.getLocalVariableName(0, i);
			
			if(param.equals(flagName)) {
				flagInd = i;
			}
		}

		if(flagInd == -1) {
			System.err.println("Error: Flag '" + flagName + "' not found");
			System.exit(1);
		}		
		return flagInd;
	}
	
	private TreeSet<Integer> gatherSlicedLines(Collection<Statement> slice) {
		TreeSet<Integer> lineNumbers = new TreeSet<Integer>();
		for (Statement s : slice) {
			if (s.getKind() == Statement.Kind.NORMAL) { // ignore special kinds of statements
				int bcIndex, instructionIndex = ((NormalStatement) s).getInstructionIndex();
				try {
					bcIndex = ((ShrikeBTMethod) s.getNode().getMethod()).getBytecodeIndex(instructionIndex);
					try {
						int srcLineNumber = s.getNode().getMethod().getLineNumber(bcIndex);
						lineNumbers.add(srcLineNumber);
					} catch (Exception e) {
						System.err.println("Bytecode index no good");
						System.err.println(e.getMessage());
					}
				} catch (Exception e) {
//					System.err.println("it's probably not a BT method (e.g. it's a fakeroot method)");
//					System.err.println(e.getMessage());
				}
			}
		}

		return lineNumbers;
	}
	
	// format result as readable code
	private String formatSlice(TreeSet<Integer> lineNumbers) {
		String res = "Slice for: " + this.fileName + " ; " + this.packageName + "."  
				+ this.className + "." + this.methodName + "; flag name: " + this.flagName + "\n";
		Iterator<Integer> iter = lineNumbers.iterator();
		while (iter.hasNext()) {
			int lineNum = iter.next();
			if(srcFileLines.size() <= lineNum) {
				continue;
			}
			System.out.println("line " + lineNum + ": " + 
					srcFileLines.get(lineNum-1));
		}
		
		return res;
	}
	
	private String createSlice() throws IOException, ClassHierarchyException, CancelException {
		String path = "./src/slicer/Java60RegressionExclusions.txt";
		path.replace('/', File.pathSeparatorChar);
		File f = new File(path);
		File exFile = new FileProvider().getFile(f.getAbsolutePath());
		AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(jarPath + File.pathSeparator + jarName, exFile);
		IClassHierarchy cha = ClassHierarchy.make(scope);
		Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(
				scope, cha, "L" + packageName + "/" + className);
		AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
		CallGraphBuilder builder = Util.makeZeroOneCFABuilder(options, new AnalysisCache(), cha, scope);
		CallGraph cg;
		
		Collection<Statement> result = new LinkedHashSet<Statement>();
		// gather statements in order to be able to process function end
        Collection<SSAInstruction> funcEnd = new LinkedHashSet<SSAInstruction>();    
		
        TreeSet<Integer> resultLines = new TreeSet<Integer>();
        
		try {
			cg = builder.makeCallGraph(options, null);
			Atom name = Atom.findOrCreateUnicodeAtom(methodName);
			
			// get first method with the input name
			CGNode node = findMethod(cg, name);
			IMethod method = node.getMethod();
			int flagInd = findParameterIndex(method);
			
			// verify that found param is indeed boolean
			TypeReference type = method.getParameterType(flagInd);
			if(type != TypeReference.Boolean) {
				System.err.println("Error: Parameter is not boolean");
				System.exit(1);
			}
			
			IR ir = node.getIR();
			int paramLoc = ir.getSymbolTable().getParameter(flagInd);
	        int start = -1;
	        int end = -1;
			// search for branch instructions that are affected by our flag
		    for (Iterator<SSAInstruction> it = ir.iterateAllInstructions(); it.hasNext();) {
		        SSAInstruction s = it.next();
		     
		        funcEnd.add(s);
		        
		        // find branch statements that are affected by flag parameter
		        if (s instanceof SSAConditionalBranchInstruction) {
		        	SSAConditionalBranchInstruction branch = (SSAConditionalBranchInstruction) s;
		        	if(branch.getUse(0) == paramLoc) {
		        		start = branch.iindex + 1;
		        		end = branch.getTarget();
		        		continue;
		        	}
		        	
		        	funcEnd.clear();
		        }
		        
	            // compute forward and backward slice for all statements inside branch
	        	if(start <= s.iindex && s.iindex < end) {
	        		processStatement(builder, cg, node, s, result);
	        	}
		      }

	        // lines of slice computed so far
	        resultLines = gatherSlicedLines(result);     

	        // check if end of function depends on statements 
	        // in the computed slice
	        for(SSAInstruction s : funcEnd) {
	        	Collection<Statement> tmpSlice = 
	        			new LinkedHashSet<Statement>();
	        	processStatement(builder, cg, node, s, tmpSlice);

		        TreeSet<Integer> tmpLines = gatherSlicedLines(tmpSlice);	
		        
		        for(Integer i : tmpLines) {
		        	// if line depends on some line in the slice, all the 
		        	// lines it depends on should be added
		        	if(resultLines.contains(i)) {
		        		resultLines.addAll(tmpLines);
		        		break;
		        	}
		        }
	        }
			
		} catch (IllegalArgumentException | CallGraphBuilderCancelException e) {
			e.printStackTrace();
		}	
		
		return formatSlice(resultLines);
	}
	
	private void processStatement(CallGraphBuilder builder, 
			CallGraph cg, CGNode node, SSAInstruction s,
			Collection<Statement> collect) throws IllegalArgumentException, CancelException {
		Statement statement = new NormalStatement(node, s.iindex);
		
		// don't process invalid instructions
		if(s.iindex == -1) {
			return;
		}

		// add current statement
		collect.add(statement);
		Collection<Statement> computeBackwardSlice = 
				Slicer.computeBackwardSlice(statement, cg, builder.getPointerAnalysis(),
						DataDependenceOptions.FULL, ControlDependenceOptions.NONE);
		
		collect.addAll(computeBackwardSlice);
	}

	
	private static CGNode findMethod(CallGraph cg, Atom name) {
		for (Iterator<? extends CGNode> it = cg.getSuccNodes(cg.getFakeRootNode()); it.hasNext();) {
			CGNode n = it.next();
			if (n.getMethod().getName().equals(name)) {
				return n;
			}
		}
		// if it's not a successor of fake root, just iterate over everything
		for (CGNode n : cg) {
			if (n.getMethod().getName().equals(name)) {
				return n;
			}
		}
		Assertions.UNREACHABLE("failed to find method " + name);
		return null;
	}
	
	public void writeSlice(String slice) throws IOException {
		File file = new File(this.outputSlicePath);
		FileWriter writer = new FileWriter(file);
		writer.write(slice);
		writer.close();
	}
	
	public void sliceMethod() throws ParseException, IOException, ClassHierarchyException, CancelException, InterruptedException {
		// 1. preprocess the code
		String flagHelperName = this.flagName + "_temp";
		JavaCodeTransformer codeTrs = new JavaCodeTransformer();
		String jarNameNoExtension = this.jarName.substring(0, this.jarName.lastIndexOf('.'));
		String fileNameNoExtension = this.fileName.substring(0, this.fileName.lastIndexOf('.'));
		String res = codeTrs.Preprocess(this.jarPath, jarNameNoExtension, fileNameNoExtension, this.className, 
				this.methodName, this.flagName, flagHelperName);
		Path path = Paths.get(res);
		this.newJarName = path.getFileName().toString();
		this.newJarPath = path.getParent().toString();
		// 2. upload it to memory
		uploadFileToMem();
		// 3. perform slicing
		String slice = createSlice();
		// 4. write the slice to a file
		writeSlice(slice);
		// 4. postprocess the slice
		codeTrs.Postprocess(this.outputSlicePath);
	}
	
	public static void main(String args[]) {
		Options options = new Options();
		options.addOption("p", "jarPath", true, "Full path to the directory containing the jar file which contains the code to slice");
		options.addOption("j", "jarFileName", true, "The name of the jar file containing the code to slice (including .jar extension)");
		options.addOption("f", "fileName", true, "The name of the file containing the code to slice. The name should include the .java extension. File must be in the jar file provided.");
		options.addOption("k", "packageName", true, "The name of the package containing the class to slice");
		options.addOption("c", "className", true, "The name of the class containing the method to slice");
		options.addOption("m", "methodName", true, "The name of the method to slice");
		options.addOption("l", "flagName", true, "The name of the flag (and input parameter of the method) according to which to slice");
		options.addOption("o", "outputPath", true, "The path to the output file which will contain the slice (the file should not exist yet)");
		
		CommandLineParser parser = new DefaultParser();
		CommandLine cmd;
		try {
			cmd = parser.parse(options,  args);
			HelpFormatter help = new HelpFormatter();
			
			String jarPath = "";
			if (cmd.hasOption("p")) {
				jarPath = cmd.getOptionValue("p");
			} else {
				help.printHelp("flagSlicer", options);
				System.exit(1);
			}
			
			String jarFileName = "";
			if (cmd.hasOption("j")) {
				jarFileName = cmd.getOptionValue("j");
			} else {
				help.printHelp("flagSlicer", options);
				System.exit(1);
			}
			
			String fileName = "";
			if (cmd.hasOption("f")) {
				fileName = cmd.getOptionValue("f");
			} else {
				help.printHelp("flagSlicer", options);
				System.exit(1);
			}
			
			String packageName = "";
			if (cmd.hasOption("k")) {
				packageName = cmd.getOptionValue("k");
			} else {
				help.printHelp("flagSlicer", options);
				System.exit(1);
			}
			
			String className = "";
			if (cmd.hasOption("c")) {
				className = cmd.getOptionValue("c");
			} else {
				help.printHelp("flagSlicer", options);
				System.exit(1);
			}
			
			String methodName = "";
			if (cmd.hasOption("m")) {
				methodName = cmd.getOptionValue("m");
			} else {
				help.printHelp("flagSlicer", options);
				System.exit(1);
			}
			
			String flagName = "";
			if (cmd.hasOption("l")) {
				flagName = cmd.getOptionValue("l");
			} else {
				help.printHelp("flagSlicer", options);
				System.exit(1);
			}
			
			String outputPath = "";
			if (cmd.hasOption("o")) {
				outputPath = cmd.getOptionValue("o");
			} else {
				help.printHelp("flagSlicer", options);
				System.exit(1);
			}
			
			FlagSlicer t = new FlagSlicer(jarPath, jarFileName, fileName, packageName, className, methodName, flagName, outputPath);
			t.sliceMethod();

		} catch (org.apache.commons.cli.ParseException e) {
			// TODO Auto-generated catch block
			System.err.println("failed to parse arguments: " + e.getMessage());
			e.printStackTrace();
		} catch (ClassHierarchyException  | IOException | CancelException | ParseException | InterruptedException e) {
			// TODO Auto-generated catch block
			System.err.println("Error: can't slice program");
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}
}