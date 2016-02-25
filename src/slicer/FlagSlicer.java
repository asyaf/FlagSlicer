package slicer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Enumeration;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.jar.*;

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
import com.ibm.wala.ipa.slicer.SDG;
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
	ArrayList<String> srcFileLines;
	
	public FlagSlicer(String jarFilePath, String jarFileName, String file, String packageN,
			String classN, String method, String flag) {
		jarPath = jarFilePath;
		jarName = jarFileName;
		fileName = file;
		packageName = packageN;
		className = classN;
		methodName = method;
		flagName = flag;
		srcFileLines = new ArrayList<String>();
	}
	
	private void uploadFileToMem() throws IOException {
		// Find the file inside the jar and extract it
		// TODO - use the jar path passed to get the file path
		JarFile jar = new JarFile(jarPath + File.separator + jarName);
		String pathToFile = "";
		Enumeration<JarEntry> entries = jar.entries();
		while (entries.hasMoreElements()) {
			JarEntry file = (JarEntry) entries.nextElement();
			if (!file.getName().endsWith(fileName)) {
				continue;
			}
			pathToFile = jarPath + File.separator + fileName;
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
	
	// output result as readable code
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
//						System.err.println("Source line = " + srcFileLines.get(srcLineNumber-1));
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
	
	private void printSlicedLines(TreeSet<Integer> lineNumbers) {
		System.out.println("Result slice: ");
		Iterator<Integer> iter = lineNumbers.iterator();
		while (iter.hasNext()) {
			int lineNum = iter.next();
			if(srcFileLines.size() <= lineNum) {
				continue;
			}
			System.out.println("line " + lineNum + ": " + 
					srcFileLines.get(lineNum-1));
		}
	}
	
	private void createSlice() throws IOException, ClassHierarchyException, CancelException {
		String path = "./src/slicer/Java60RegressionExclusions.txt";
		path.replace('/', File.pathSeparatorChar);
		File f = new File(path);
		File exFile = new FileProvider().getFile(f.getAbsolutePath());
		AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(jarPath + File.separator + jarName, exFile);
		IClassHierarchy cha = ClassHierarchy.make(scope);
		Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(
				scope, cha, "L" + packageName + "/" + className);
		AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
		CallGraphBuilder builder = Util.makeZeroOneCFABuilder(options, new AnalysisCache(), cha, scope);
		CallGraph cg;
		
		Collection<Statement> result = new LinkedHashSet<Statement>();
		// gather statements in order to be able to process function end
        Collection<SSAInstruction> funcEnd = new LinkedHashSet<SSAInstruction>();    
		
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
	        TreeSet<Integer> resultLines = gatherSlicedLines(result);     

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
			
			printSlicedLines(resultLines);
		} catch (IllegalArgumentException | CallGraphBuilderCancelException e) {
			e.printStackTrace();
		}	
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

	public static void dumpSlice(Collection<Statement> slice) {
		dumpSlice(slice, new PrintWriter(System.err));
	}

	public static void dumpSlice(Collection<Statement> slice, PrintWriter w) {
		w.println("SLICE:\n");
		int i = 1;
		for (Statement s : slice) {
			String line = (i++) + "   " + s;
			w.println(line);
			w.flush();
		}
	}
	
	public static void main(String args[]) {
		if(args.length != 7) {
			System.err.println(
					"Usage: <jarPath> <jarFileName> <fileName> <packageName> <className> <methodName> <flagName>");
			System.exit(1);
		}
		
		FlagSlicer t = new FlagSlicer(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
		
		try {
			t.uploadFileToMem();
			t.createSlice();
		} catch (ClassHierarchyException | IOException | CancelException e) {
			System.err.println("Error: can't slice program");
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}
}