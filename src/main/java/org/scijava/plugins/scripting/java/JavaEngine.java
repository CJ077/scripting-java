/*
 * #%L
 * JSR-223-compliant Java scripting language plugin.
 * %%
 * Copyright (C) 2008 - 2014 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.plugins.scripting.java;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes.Name;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.scijava.command.CommandService;
import org.scijava.minimaven.BuildEnvironment;
import org.scijava.minimaven.Coordinate;
import org.scijava.minimaven.MavenProject;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.PluginService;
import org.scijava.script.AbstractScriptEngine;
import org.scijava.util.FileUtils;
import org.scijava.util.LineOutputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * A pseudo-{@link ScriptEngine} compiling and executing Java classes.
 * <p>
 * Thanks to <a href="https://github.com/scijava/minimaven">MiniMaven</a>, this
 * script engine can handle individual Java classes as well as trivial Maven
 * projects (triggered when the proviede script path suggests that the file is
 * part of a Maven project).
 * </p>
 * 
 * @author Johannes Schindelin
 */
public class JavaEngine extends AbstractScriptEngine {

	private final static String DEFAULT_GROUP_ID = "net.imagej";
	private final static String DEFAULT_VERSION = "1.0.0-SNAPSHOT";

	private final static String XALAN_INDENT_AMOUNT = "{http://xml.apache.org/xslt}indent-amount";
	{
		engineScopeBindings = new JavaEngineBindings();
	}

	@Parameter
	private PluginService pluginService;

	@Parameter
	private CommandService commandService;

	@Parameter
	private JavaService javaService;

	@Override
	public Object eval(String script) throws ScriptException {
		return eval(new StringReader(script));
	}

	@Override
	public Object eval(Reader reader) throws ScriptException {
		final String path = (String)get(FILENAME);
		File file = path == null ? null : new File(path);

		final Writer writer = getContext().getErrorWriter();
		try {
			final Builder builder = new Builder(file, reader, writer);
			final MavenProject project = builder.project;
			String mainClass = builder.mainClass;

			try {
				project.build(true);
				if (mainClass == null) {
					mainClass = project.getMainClass();
					if (mainClass == null) {
						throw new ScriptException(
								"No main class found for file " + file);
					}
				}

				// make class loader
				String[] paths = project.getClassPath(false).split(
						File.pathSeparator);
				URL[] urls = new URL[paths.length];
				for (int i = 0; i < urls.length; i++)
					urls[i] = new URL("file:" + paths[i]
							+ (paths[i].endsWith(".jar") ? "" : "/"));
				URLClassLoader classLoader = new URLClassLoader(urls,
						getClass().getClassLoader());

				// needed for sezpoz
				Thread.currentThread().setContextClassLoader(classLoader);

				// launch main class
				final Class<?> clazz = classLoader.loadClass(mainClass);
				javaService.run(clazz);
			} finally {
				builder.cleanup();
			}
		} catch (Exception e) {
			if (writer != null) {
				final PrintWriter err = new PrintWriter(writer);
				e.printStackTrace(err);
				err.flush();
			} else {
				if (e instanceof ScriptException)
					throw (ScriptException) e;
				throw new ScriptException(e);
			}
		}
		return null;
	}

	public void compile(final File file, final Writer errorWriter) {
		try {
			final Builder builder = new Builder(file, null, errorWriter);
			try {
				builder.project.build();
			} finally {
				builder.cleanup();
			}
		} catch (Throwable t) {
			printOrThrow(t, errorWriter);
		}
	}

	/**
	 * Packages the build product into a {@code .jar} file.
	 * 
	 * @param file a {@code .java} or {@code pom.xml} file
	 * @param includeSources whether to include the sources or not
	 * @param output the {@code .jar} file to write to
	 * @param errorWriter the destination for error messages
	 */
	public void makeJar(final File file, final boolean includeSources, final File output, final Writer errorWriter) {
		try {
			final Builder builder = new Builder(file, null, errorWriter);
			try {
				builder.project.build(true, true, includeSources);
				final File target = builder.project.getTarget();
				if (output != null && !target.equals(output)) {
					BuildEnvironment.copyFile(target, output);
				}
			} finally {
				builder.cleanup();
			}
		} catch (Throwable t) {
			printOrThrow(t, errorWriter);
		}
	}

	private void printOrThrow(Throwable t, Writer errorWriter) {
		RuntimeException e = t instanceof RuntimeException ? (RuntimeException) t
				: new RuntimeException(t);
		if (errorWriter == null) {
			throw e;
		}
		final PrintWriter err = new PrintWriter(errorWriter);
		e.printStackTrace(err);
		err.flush();
	}

	private class Builder {
		private final PrintStream err;
		private final File temporaryDirectory;
		private String mainClass;
		private MavenProject project;

		/**
		 * Constructs a wrapper around a possibly temporary project.
		 * 
		 * @param file the {@code .java} file to build (or null, if {@code reader} is set).
		 * @param reader provides the Java source if {@code file} is {@code null} 
		 * @param errorWriter where to write the error output.
		 * @throws ScriptException
		 * @throws IOException
		 * @throws ParserConfigurationException
		 * @throws SAXException
		 * @throws TransformerConfigurationException
		 * @throws TransformerException
		 * @throws TransformerFactoryConfigurationError
		 */
		private Builder(final File file, final Reader reader,
				final Writer errorWriter) throws ScriptException, IOException,
				ParserConfigurationException, SAXException,
				TransformerConfigurationException, TransformerException,
				TransformerFactoryConfigurationError {
			if (errorWriter == null) {
				err = null;
			} else {
				err = new PrintStream(new LineOutputStream() {

					@Override
					public void println(final String line) throws IOException {
						errorWriter.append(line).append('\n');
					}

				});
			}

			boolean verbose = "true".equals(get("verbose"));
			boolean debug = "true".equals(get("debug"));
			BuildEnvironment env = new BuildEnvironment(err, true, verbose,
					debug);

			if (file == null || !file.exists())
				try {
					project = writeTemporaryProject(env, reader);
					temporaryDirectory = project.getDirectory();
					mainClass = project.getMainClass();
				} catch (Exception e) {
					throw new ScriptException(e);
				}
			else {
				temporaryDirectory = null;
				if (file.getName().equals("pom.xml")) {
					project = env.parse(file, null);
				} else {
					mainClass = getFullClassName(file);
					project = getMavenProject(env, file, mainClass);
				}
			}
		}

		private void cleanup() {
			if (err != null)
				err.close();
			if (err != null)
				err.close();
			if (temporaryDirectory != null
					&& !FileUtils.deleteRecursively(temporaryDirectory)) {
				temporaryDirectory.deleteOnExit();
			}
		}
	}

	private MavenProject getMavenProject(final BuildEnvironment env,
			final File file, final String mainClass) throws IOException,
			ParserConfigurationException, SAXException, ScriptException,
			TransformerConfigurationException, TransformerException,
			TransformerFactoryConfigurationError {
		String path = file.getAbsolutePath();
		if (!path.replace(File.separatorChar, '.').endsWith("." + mainClass + ".java")) {
			throw new ScriptException("Class " + mainClass + " in invalid directory: " + path);
		}
		path = path.substring(0, path.length() - mainClass.length() - 5);
		if (path.replace(File.separatorChar, '/').endsWith("/src/main/java/")) {
			path = path.substring(0, path.length() - "src/main/java/".length());
			final File pom = new File(path, "pom.xml");
			if (pom.exists()) return env.parse(pom, null);
		}
		return writeTemporaryProject(env, new FileReader(file));
	}

	private static String getFullClassName(final File file) throws IOException {
		String name = file.getName();
		if (!name.endsWith(".java")) {
			throw new UnsupportedOperationException();
		}
		name = name.substring(0, name.length() - 5);

		String packageName = "";
		final Pattern packagePattern = Pattern.compile("package ([a-zA-Z0-9_.]*).*");
		final Pattern classPattern = Pattern.compile(".*public class ([a-zA-Z0-9_]*).*");
		final BufferedReader reader = new BufferedReader(new FileReader(file));
		for (;;) {
			String line = reader.readLine().trim();
			if (line == null) break;
		outerLoop:
			while (line.startsWith("/*")) {
				int end = line.indexOf("*/", 2);
				while (end < 0) {
						line = reader.readLine();
						if (line == null) break outerLoop;
						end = line.indexOf("*/");
				}
				line = line.substring(end + 2).trim();
			}
			if (line == null || line.equals("") || line.startsWith("//")) continue;
			final Matcher packageMatcher = packagePattern.matcher(line);
			if (packageMatcher.matches()) {
				packageName = packageMatcher.group(1) + ".";
			}
			final Matcher classMatcher = classPattern.matcher(line);
			if (classMatcher.matches()) {
				name = classMatcher.group(1);
				break;
			}
		}
		reader.close();
		return packageName + name; // the 'package' statement must be the first in the file
	}

	private static MavenProject writeTemporaryProject(final BuildEnvironment env,
		final Reader reader) throws IOException, ParserConfigurationException,
		SAXException, TransformerConfigurationException, TransformerException,
		TransformerFactoryConfigurationError
	{
		final File directory = FileUtils.createTemporaryDirectory("java", "");
		final File file = new File(directory, ".java");

		final BufferedReader in = new BufferedReader(reader);
		final Writer out = new FileWriter(file);
		for (;;) {
			final String line = in.readLine();
			if (line == null) break;
			out.write(line);
			out.write('\n');
		}
		in.close();
		out.close();

		final String mainClass = getFullClassName(file);
		final File result = new File(directory, "src/main/java/" + mainClass.replace('.', '/') + ".java");
		if (!result.getParentFile().mkdirs()) {
			throw new IOException("Could not make directory for " + result);
		}
		if (!file.renameTo(result)) {
			throw new IOException("Could not move " + file + " into the correct location");
		}

		// write POM
		final String artifactId = mainClass.substring(mainClass.lastIndexOf('.') + 1);
		return fakePOM(env, directory, artifactId, mainClass, true);
	}

	private static String fakeArtifactId(final BuildEnvironment env, final String name) {
		int dot = name.indexOf('.');
		final String prefix = dot < 0 ? name : dot == 0 ? "dependency" : name.substring(0, dot);
		if (!env.containsProject(DEFAULT_GROUP_ID, prefix)) {
			return prefix;
		}
		for (int i = 1; ; i++) {
			final String artifactId = prefix + "-" + i;
			if (!env.containsProject(DEFAULT_GROUP_ID, artifactId)) {
				return artifactId;
			}
		}
	}

	private static MavenProject fakePOM(final BuildEnvironment env,
			final File directory, final String artifactId, final String mainClass, boolean writePOM)
					throws IOException, ParserConfigurationException, SAXException,
					TransformerConfigurationException, TransformerException,
					TransformerFactoryConfigurationError {
		final Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
		final Element project = pom.createElement("project");
		pom.appendChild(project);
		project.setAttribute("xmlns", "http://maven.apache.org/POM/4.0.0");
		project.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
		project.setAttribute("xsi:schemaLocation", "http://maven.apache.org/POM/4.0.0 " +
				"http://maven.apache.org/xsd/maven-4.0.0.xsd");

		append(pom, project, "groupId", DEFAULT_GROUP_ID);
		append(pom, project, "artifactId", artifactId);
		append(pom, project, "version", DEFAULT_VERSION);

		final Element build = append(pom, project, "build", null);

		if (mainClass != null) {
			final Element plugins = append(pom, build, "plugins", null);
			final Element plugin = append(pom, plugins, "plugin", null);
			append(pom, plugin, "artifactId", "maven-jar-plugin");
			final Element configuration = append(pom, plugin, "configuration", null);
			final Element archive = append(pom, configuration, "archive", null);
			final Element manifest = append(pom, archive, "manifest", null);
			append(pom, manifest, "mainClass", mainClass);
		}

		Element dependencies = append(pom, project, "dependencies", null);
		for (Coordinate dependency : getAllDependencies(env)) {
			Element dep = append(pom, dependencies, "dependency", null);
			append(pom, dep, "groupId", dependency.getGroupId());
			append(pom, dep, "artifactId", dependency.getArtifactId());
			append(pom, dep, "version", dependency.getVersion());
		}
		final Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty(XALAN_INDENT_AMOUNT, "4");
		if (directory.getPath().replace(File.separatorChar, '/').endsWith("/src/main/java")) {
			final File projectRootDirectory = directory.getParentFile().getParentFile().getParentFile();
			final File pomFile = new File(projectRootDirectory, "pom.xml");
			if (!pomFile.exists()) {
				final FileWriter writer = new FileWriter(pomFile);
				transformer.transform(new DOMSource(pom), new StreamResult(writer));
				return env.parse(pomFile);
			}
		}
		if (writePOM) {
			transformer.transform(new DOMSource(pom), new StreamResult(new File(directory, "pom.xml")));
		}
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		transformer.transform(new DOMSource(pom), new StreamResult(out));
		return env.parse(new ByteArrayInputStream(out.toByteArray()), directory, null, null);
	}

	private static Element append(final Document document, final Element parent, final String tag, final String content) {
		Element child = document.createElement(tag);
		if (content != null) child.appendChild(document.createCDATASection(content));
		parent.appendChild(child);
		return child;
	}

	private static List<Coordinate> getAllDependencies(final BuildEnvironment env) {
		final List<Coordinate> result = new ArrayList<Coordinate>();
		for (ClassLoader loader = env.getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
			if (loader instanceof URLClassLoader) {
				for (final URL url : ((URLClassLoader)loader).getURLs()) {
					if (url.getProtocol().equals("file")) {
						final File file = new File(url.getPath());
						if (url.toString().matches(".*/target/surefire/surefirebooter[0-9]*\\.jar")) {
							getSurefireBooterURLs(file, url, env, result);
							continue;
						}
						result.add(fakeDependency(env, file));
					}
				}
			}
		}
		return result;
	}

	private static Coordinate fakeDependency(final BuildEnvironment env, final File file) {
		final String artifactId = fakeArtifactId(env, file.getName());
		Coordinate dependency = new Coordinate(DEFAULT_GROUP_ID, artifactId, "1.0.0");
		env.fakePOM(file, dependency);
		return dependency;
	}

	private static void getSurefireBooterURLs(final File file, final URL baseURL,
		final BuildEnvironment env, final List<Coordinate> result)
	{
		try {
			final JarFile jar = new JarFile(file);
			Manifest manifest = jar.getManifest();
			if (manifest != null) {
				final String classPath =
					manifest.getMainAttributes().getValue(Name.CLASS_PATH);
				if (classPath != null) {
					for (final String element : classPath.split(" +"))
						try {
							final File dependency = new File(new URL(baseURL, element).getPath());
							result.add(fakeDependency(env, dependency));
						}
						catch (MalformedURLException e) {
							e.printStackTrace();
						}
				}
			}
		}
		catch (final IOException e) {
			e.printStackTrace();
		}
	}

}
