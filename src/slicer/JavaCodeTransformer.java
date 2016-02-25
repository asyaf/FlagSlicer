package slicer;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Arrays;
import java.util.jar.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import javax.tools.*;


public class JavaCodeTransformer {

	public class ParsingInfo {
		public ParsingInfo(String className, String methodName, String flagHelperName) {
			this.className = className;
			this.methodName = methodName;
			this.flagHelperName = flagHelperName;
		}

		String className;
		String methodName;
		String flagHelperName;
	}
	
	private static class MethodChangerVisitor extends VoidVisitorAdapter {
	    @Override
	    public void visit(ClassOrInterfaceDeclaration n, Object arg) {
	    	System.out.println(n.getName());
	    	
	    	ParsingInfo parseInfo = (ParsingInfo) arg;
	    	if (n.getName().equals(parseInfo.className)) {
	    		List<BodyDeclaration> members = n.getMembers();
	    		for (int i = 0; i < members.size(); i++) {
	    			if (members.get(i) instanceof MethodDeclaration) {
	    	    		visit((MethodDeclaration) members.get(i), arg);
	    			}
	    		}
	    	}
	    }

	    @Override
		public void visit(MethodDeclaration n, Object arg) {
	    	System.out.println(n.getName());
	    	ParsingInfo parseInfo = (ParsingInfo) arg;

	    	if (n.getName().equals(parseInfo.methodName)) {
	    		visit(n.getBody(), arg);
	    		System.out.println("adding temp statement");
	    	    addStmtToMethodBody(n, parseInfo);
	    	}

		}
	    
	    @Override
	    public void visit(VariableDeclarationExpr n, Object arg) {
	    	if (!n.getType().equals(new PrimitiveType(PrimitiveType.Primitive.Double)) &&
	    		!n.getType().equals(new PrimitiveType(PrimitiveType.Primitive.Float)) &&
	    		!n.getType().equals(new PrimitiveType(PrimitiveType.Primitive.Int)) &&
	    		!n.getType().equals(new PrimitiveType(PrimitiveType.Primitive.Long)) &&
	    		!n.getType().equals(new PrimitiveType(PrimitiveType.Primitive.Short))) {
	    		// only support changes for these types (which support the '+' operation with integer)
	    		return;
	    	}
	    	
	    	List<VariableDeclarator> vars = n.getVars();
	    	ParsingInfo parseInfo = (ParsingInfo) arg;
	    	NameExpr flagHelper = new NameExpr(parseInfo.flagHelperName);
	    	for (int i = 0; i < vars.size(); i++) {
	    		BinaryExpr newInit = new BinaryExpr();
	    		newInit.setOperator(Operator.plus);
	    		newInit.setLeft(vars.get(i).getInit());
	    		newInit.setRight(flagHelper);
	    		vars.get(i).setInit(newInit);
	    	}
	    }
	    /*
	    @Override
	    public void visit(NameExpr n, Object arg) {
	    	ParsingInfo parseInfo = (ParsingInfo) arg;

	    	if (n.getName().equals(parseInfo.oldFlagName)) {
	    		n.setName(parseInfo.newFlagName);
	    	}
	    }
*/	    
	    
	    
	    private void addStmtToMethodBody(MethodDeclaration n, ParsingInfo parseInfo) {
    		// add a statement do the method body
    		PrimitiveType type = new PrimitiveType(PrimitiveType.Primitive.Int);
    		VariableDeclarationExpr expr = new VariableDeclarationExpr();
    		expr.setType(type);
    		NameExpr initExpr = new NameExpr("0");
    		VariableDeclaratorId varName = new VariableDeclaratorId(parseInfo.flagHelperName);
    		VariableDeclarator var = new VariableDeclarator(varName, initExpr);
    		ArrayList<VariableDeclarator> vars = new ArrayList<VariableDeclarator>();
    		vars.add(var);
    		expr.setVars(vars);
    		ExpressionStmt stmt = new ExpressionStmt(expr);
    		
    		List<Statement> stmts = new LinkedList<Statement>();
    		stmts.add(stmt);
    		
			n.getBody().getStmts().addAll(0, stmts);
	    }


	}
	
	/*
	private static class MissingParamsCollector extends VoidVisitorAdapter {
		public MissingParamsCollector() {
			this.params = new HashMap<String, Integer>();
		}
		
	    @Override
		public void visit(MethodDeclaration n, Object arg) {
	    	System.out.println(n.getName());
	    	String method = (String) arg;
	    	params.clear();

	    	if (n.getName().equals(method)) {
	    		visit(n.getBody(), null);
	    	}
		}
	    
	    @Override
	    public void visit(BinaryExpr n, Object arg) {
	    	params.put(n.getLeft().toString(), -1);
	    	params.put(n.getRight().toString(), -1);
	    }
	    
	    @Override
	    public void visit(NameExpr n, Object arg) {
	    	params.put(n.getName(), -1);
	    }
	    
	    public HashMap<String, Integer> getMissingParams() { return this.params; }
	    
		HashMap<String, Integer> params;
	}
	
	private static class AssignLineNumbersCollector extends VoidVisitorAdapter {
		public AssignLineNumbersCollector() {
		}
		
		public void setParams(HashMap<String, Integer> params) {
			this.params = params;
		}
		
	    @Override
		public void visit(MethodDeclaration n, Object arg) {
	    	String method = (String) arg;
	    	if (n.getName().equals(method)) {
	    		visit(n.getBody(), null);
	    	}
		}
	    	   
	    @Override
	    public void visit(AssignExpr n, Object arg) {
	    	String param = n.getTarget().toString();
	    	System.out.println(param);
	    	if (params.containsKey(param)) {
	    		params.put(param, n.getBeginLine());
	    	}
	    }
	    
	    @Override
	    public void visit(NameExpr n, Object arg) {
	    	if (params.containsKey(n.getName()) && params.get(n.getName()) == -1) {
	    		params.put(n.getName(), n.getBeginLine());
	    	}
	    }
	    
	    @Override
	    public void visit(VariableDeclarationExpr n, Object arg) {
	    	List<VariableDeclarator> vars = n.getVars();
	    	for (int i = 0; i < vars.size(); i++) {
	    		if (params.containsKey(vars.get(i).getId().getName()) &&
	    				params.get(vars.get(i).getId().getName()) == -1) {
	    			params.put(vars.get(i).getId().getName(), n.getBeginLine());
	    		}
	    	}
	    }
	    public HashMap<String, Integer> getParams() { return this.params; }
	    
		HashMap<String, Integer> params;
	}
*/
	
	
	
	
	public String Preprocess(String jarPath, String jarFileName, String fileName, String className,
			String methodName, String flagName, String flagHelperName) throws ParseException, IOException {
		System.out.println("jarPath = " + jarPath + ", fileName = " + fileName + ", className = " +
			className + ", methodName = " + methodName + ", flagName = " + flagName +
			", flagHelperName = " + flagHelperName);
		
		// TODO - JavaParser usage:
		// This (the jre included) is of the most recent version of JavaParser module, which supports
		// parsing of Java 1.8 . To build it, I needed to use Java 1.7 (otherwise some features in the
		// core of JavaPareser will not build). 
		// If this causes a problem when integrating with the path slicing code, we can either:
		// 1. Attempt to use an older version of JavaParser, which supports parsing of java 1.5.
		// 2. Perhaps run the code of the parsing and of the slicing separately - First parse, 
		// creating the changed .class file. Then run on it the slicing code, and on its result
		// (after it is translated back to source code), re-run the parsing to re-create a jar (if
		// we want). These 3 different runs can be combined via a shell script, and not in the actual
		// code. Perhaps that will solve the problem of different Java code versions.
		
	    FileInputStream in = new FileInputStream(jarPath + "\\" + fileName +".java");
	    CompilationUnit cu;
	    try {
	        // parse the file
	        cu = JavaParser.parse(in);
	    } finally {
	        in.close();
	    }

	    // prints the resulting compilation unit to default system output
	    System.out.println("Before: " + cu.toString());		
	    
	    ParsingInfo info = new ParsingInfo(className, methodName, flagHelperName);
	    
	    // visit and print the methods
	    System.out.println("Printing methods:");
	    new MethodChangerVisitor().visit(cu, info);
	    
	    System.out.println("After: " + cu.toString());
	    
	    return prepareAfterChange(jarPath, jarFileName, fileName, cu.toString());
	}
	
	/*
	 * Writes the changed code to a .java file, compiles it, creates the .class file and
	 * creates a jar file containing the .java and .class files.
	 */
	private String prepareAfterChange(String jarPath, String jarName, String fileName, String sourceCode) 
			throws IOException {
		// create the new source file
		// TODO - because we don't change the class name, the new source file will need
		// to have the same name as the original source file (if the class is public) for it
		// to compile properly. 
		// Therefore, user needs to provide a different path in which the new source file will be written
		// and compiled.
		// TODO - To make the compiler work, need to:
		// 1. Go to Window->Preferences->Java->Installed JREs and add the relevant jdk (1.7)
		// as a JRE.
		// 2. Go to the project - in Build Path->Add Libraries->Add JRE->Alternative, and set
		// the jdk added as a new project.
		// 3. Make sure to remove from the project the previous non-jdk jre used in it
		// 4. Clean the project (Project->Clean)
		// 5. re-build.
		String outputPath = jarPath + "\\Testing";
		File sourceFile = new File(outputPath + "\\" + fileName + ".java");
		FileWriter writer = new FileWriter(sourceFile);
		writer.write(sourceCode);
		writer.close();
		
		// create the new .class file
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			System.out.println("compiler is null");
		}
	    StandardJavaFileManager fileManager =
	            compiler.getStandardFileManager(null, null, null);
	   
	    fileManager.setLocation(StandardLocation.CLASS_OUTPUT,
	    		Arrays.asList(new File(jarPath + "\\Testing\\")));
	    compiler.getTask(null, fileManager, null, null, null,
	                     fileManager.getJavaFileObjectsFromFiles(Arrays.asList(sourceFile)))
	                    .call();
	    fileManager.close();
	    
	   // createJarFile(outputPath, jarPath, jarName, fileName);
	    return updateJarFile(jarPath, jarName, fileName, outputPath);
	}
	
	
	private String updateJarFile(String jarPath, String jarName, String fileName, String outputPath) 
			throws IOException {
		// copy the original jar file
		String newJarName = jarName + "_temp";
		String oldJarPath = jarPath + "\\" + jarName + ".jar";
		String newJarPath = outputPath + "\\" + newJarName + ".jar";
		String cmd = "";
		// check if windows or not
		if (System.getProperty("os.name").toLowerCase().indexOf("win") >= 0) {
			cmd = "cmd /c xcopy " + oldJarPath + " " + newJarPath + "*"; 
		} else {
			oldJarPath.replace("\\", "/");
			newJarPath.replace("\\", "/");
			cmd = "cp " + oldJarPath + " " + newJarPath;
		}
		System.out.println(cmd);
		Runtime.getRuntime().exec(cmd);
		
		// preparation before updating the jar file - need the .java to be in the same directory
		// as the .class, so that when we update the jar file they will be overwritten.
		FileSearch fileSearch = new FileSearch();
		String classFileName = fileName + ".class";
		fileSearch.searchDirectory(new File(outputPath), classFileName);
		int count = fileSearch.getResult().size();
		if(count != 1){
			System.err.println("Did not find the file: " + classFileName + " in the path " + outputPath);
		}
		String pathToClass = fileSearch.getResult().get(0);
		String relativePathToClass = pathToClass.replace(outputPath + File.separator, "");
		System.out.println(relativePathToClass);
		
		// moving the source file to where the class file is located
		String sourceFile = outputPath + "\\" + fileName + ".java";
		cmd = "";
		// check if windows or not
		if (System.getProperty("os.name").toLowerCase().indexOf("win") >= 0) {
			cmd = "cmd /c move " + sourceFile + " " + pathToClass; 
		} else {
			sourceFile.replace("\\", "/");
			pathToClass.replace("\\", "/");
			cmd = "mv " + sourceFile + " " + pathToClass;
		}
		System.out.println(cmd);
		Runtime.getRuntime().exec(cmd);
		// update the new jar file with the changed source file and class file
		// TODO - this requires the jar tool provided with the jdk to be in the PATH env variable.
		String pathWithClass = relativePathToClass + File.separator + fileName + ".class";
		String pathWithSource = relativePathToClass + File.separator + fileName + ".java";
		cmd = "";
		// check if windows or not
		if (System.getProperty("os.name").toLowerCase().indexOf("win") >= 0) {
			cmd = "jar uf " + newJarName + ".jar " + pathWithSource + " " + pathWithClass; 
		} else {
			outputPath.replace("\\", "/");
			pathWithClass.replace("\\", "/");
			cmd = "jar uf " + newJarName + ".jar " + pathWithSource + " " + pathWithClass; 
		}
		
		System.out.println(cmd);
		File workDir = new File(outputPath);
		Runtime.getRuntime().exec(cmd, null, workDir);
		
		return newJarPath;
	}
	
	// TODO - remove if not used (problem - the jar file created contains the manifest in the same
	// level as C:, don't know how to fix that yet.
	private void createJarFile(String inputPath, String outputPath, String jarName, String fileName) throws IOException {
		String newJarName = jarName + "_temp.jar";
		
		// TODO - the manifest is created in root ("C:\\") - find out how to create it in
		// the inputPath directory...
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		JarOutputStream target = new JarOutputStream(
				new FileOutputStream(outputPath + "\\" + newJarName), manifest);
		addFileToJar(new File(inputPath), target, inputPath);
		target.close();
	}
	
	private void addFileToJar(File source, JarOutputStream target, String inputPath) throws IOException {
	  BufferedInputStream in = null;
	  try
	  {
	    if (source.isDirectory())
	    {
	   
	      String name = source.getPath().replace(inputPath, "").replace("\\", "/");
	      System.out.println("source.getPath = " + source.getPath() + ", name = " + name);
	      if (!name.isEmpty())
	      {
	        if (!name.endsWith("/"))
	          name += "/";
	          
	        JarEntry entry = new JarEntry(name);
	        entry.setTime(source.lastModified());
	        target.putNextEntry(entry);
	        target.closeEntry();
	      }
	      for (File nestedFile: source.listFiles())
	        addFileToJar(nestedFile, target, inputPath);
	      return;
	    }

	    JarEntry entry = new JarEntry(source.getPath().replace("\\", "/"));
	    entry.setTime(source.lastModified());
	    target.putNextEntry(entry);
	    in = new BufferedInputStream(new FileInputStream(source));

	    byte[] buffer = new byte[1024];
	    while (true)
	    {
	      int count = in.read(buffer);
	      if (count == -1)
	        break;
	      target.write(buffer, 0, count);
	    }
	    target.closeEntry();
	  }
	  finally
	  {
	    if (in != null)
	      in.close();
	  }
	}
	
	/*
	public HashMap<String, Integer> GetMissingAssignsLineNumbers(String fullFilePath,
			String missingFilePath, String methodName) throws ParseException, IOException {
		HashMap<String, Integer> paramName2LineNum = new HashMap<String, Integer>();
		
		paramName2LineNum = GetMissingParams(missingFilePath, methodName);
		
		System.out.println(paramName2LineNum);
		paramName2LineNum = GetAssignLineNumbers(fullFilePath, methodName, paramName2LineNum);
		
		return paramName2LineNum;
	}
	
	private HashMap<String, Integer> GetMissingParams(String filePath, String method)
			throws ParseException, IOException {
	    FileInputStream in = new FileInputStream(filePath);
	    CompilationUnit cu;
	    try {
	        // parse the file
	        cu = JavaParser.parse(in);
	    } finally {
	        in.close();
	    }
	    	    
	    // visit and print the methods
	    System.out.println("Printing methods:");
	    MissingParamsCollector collect = new MissingParamsCollector();
	    collect.visit(cu, method);
	    HashMap<String, Integer> paramName2LineNum = collect.getMissingParams();
		System.out.println(paramName2LineNum);
		return paramName2LineNum;

	}
	
	private HashMap<String, Integer> GetAssignLineNumbers(String filePath, String method,
			HashMap<String, Integer> params)
			throws ParseException, IOException {
	    FileInputStream in = new FileInputStream(filePath);
	    CompilationUnit cu;
	    try {
	        // parse the file
	        cu = JavaParser.parse(in);
	    } finally {
	        in.close();
	    }
	    	    
	    AssignLineNumbersCollector collect = new AssignLineNumbersCollector();
	    collect.setParams(params);
	    collect.visit(cu, method);
	    HashMap<String, Integer> paramName2LineNum = collect.getParams();
		System.out.println(paramName2LineNum);
		return paramName2LineNum;

	}*/

	public static void main(String[] args) {
		// TODO Auto-generated method stub
		JavaCodeTransformer jcTrans = new JavaCodeTransformer();
		try {
			String res = jcTrans.Preprocess("C:\\Users\\aviv\\Desktop\\wala", "slicerTest", "Test", "Test", "foo", "flag", "flag_helper");
			System.err.println("res: " + res);
		} catch (ParseException | IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

}
