/*
 * Copyright (c) 2021 by Andrew Binstock.
 *
 * Portions of this file are copyright Oracle Corp.
 * Those portions are licensed under GPL v. 2.0
 * with the Oracle classpath exception. Due to the
 * requirements of that license, the portions that
 * are copyrighted by Andrew Binstock are licensed
 * using the same terms and requirements.
 */

package org.jacobin.jadis;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.NoSuchFileException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import org.jacobin.jadis.classfile.*;
import org.jacobin.jadis.exceptions.InternalError;

import static org.jacobin.jadis.Option.recognizedOptions;

/**
 *  "Main" class for javap, normally accessed from the command line
 *  via Main, or from JSR199 via DisassemblerTool.
 */
public class JavapTask implements Messages {
//    public class JavapTask implements DisassemblerTool.DisassemblerTask, Messages { //alb

    public JavapTask() {
        context = new Context();
        context.put(Messages.class, this);
        options = Options.instance(context);
        attributeFactory = new Attribute.Factory();
    }

    public void setLog( Writer log ) {
        this.log = (PrintWriter) log;
    }

    private static PrintWriter getPrintWriterForStream(OutputStream s) {
        return new PrintWriter(s == null ? System.err : s, true);
    }

    private DiagnosticListener<JavaFileObject> getDiagnosticListenerForStream(OutputStream s) {
        return getDiagnosticListenerForWriter(getPrintWriterForStream(s));
    }

    private DiagnosticListener<JavaFileObject> getDiagnosticListenerForWriter(Writer w) {
        final PrintWriter pw = (PrintWriter) w;
        return diagnostic -> {
            switch (diagnostic.getKind()) {
                case ERROR:
                    pw.print(getMessage("err.prefix"));
                    break;
                case WARNING:
                    pw.print(getMessage("warn.prefix"));
                    break;
                case NOTE:
                    pw.print(getMessage("note.prefix"));
                    break;
            }
            pw.print(" ");
            pw.println(diagnostic.getMessage(null));
        };
    }

    /** Result codes.
     */
    static final int
        EXIT_OK = 0,        // Compilation completed with no errors.
        EXIT_ERROR = 1,     // Completed but reported errors.
        EXIT_CMDERR = 2,    // Bad command-line arguments
        EXIT_SYSERR = 3,    // System error or resource exhaustion.
        EXIT_ABNORMAL = 4;  // Compiler terminated abnormally

    int run(String[] args) {
        try {
            try {
                handleOptions( Arrays.asList( args ), true );

                // the following gives consistent behavior with javac
                if (classes == null || classes.size() == 0) {
                    if (options.help || options.version || options.fullVersion)
                        return EXIT_OK;
                    else
                        return EXIT_CMDERR;
                }
                return run();
            } finally {
                if (defaultFileManager != null) {
                    try {
                        defaultFileManager.close();
                        defaultFileManager = null;
                    } catch (IOException e) {
                        throw new InternalError( e );
                    }
                }
            }
        } catch (BadArgs e) {
            reportError(e.key, e.args);
            if (e.showUsage) {
                printLines(getMessage("main.usage.summary", progname));
            }
            return EXIT_CMDERR;
        } catch ( InternalError e ) { // this is the jadis internal error, rather than the one in java.lang
            Object[] e_args = new Object[0];
            if (e.getCause() == null)
                e_args = e.args;
            else {
                e_args = new Object[e.args.length + 1];
                e_args[0] = e.getCause();
                System.arraycopy(e.args, 0, e_args, 1, e.args.length);
            }
            reportError("err.internal.error", e_args);
            return EXIT_ABNORMAL;
        } finally {
            log.flush();
        }
    }

    private void handleOptions( Iterable<String> args, boolean allowClasses ) throws BadArgs {
            if (diagnosticListener == null)
              diagnosticListener = getDiagnosticListenerForWriter( log );

//        if (fileManager == null)
//            fileManager = getDefaultFileManager(diagnosticListener, log);

        Iterator<String> iter = args.iterator();
        boolean noArgs = !iter.hasNext();

        while (iter.hasNext()) {
            String arg = iter.next();
            if (arg.startsWith("-"))
                handleOption(arg, iter);
            else if (allowClasses) {
                if (classes == null)
                    classes = new ArrayList<>();
                classes.add(arg);
                while (iter.hasNext())
                    classes.add(iter.next());
            } else
                throw new BadArgs("err.unknown.option", arg).showUsage(true);
        }

        if (options.accessOptions.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (String opt: options.accessOptions) {
                if (sb.length() > 0)
                    sb.append(" ");
                sb.append(opt);
            }
            throw new BadArgs("err.incompatible.options", sb);
        }

        if ((classes == null || classes.size() == 0) &&
                !(noArgs || options.help || options.version || options.fullVersion)) {
            throw new BadArgs("err.no.classes.specified");
        }

        if (noArgs || options.help)
            showHelp();

        if (options.version || options.fullVersion)
            showVersion(options.fullVersion);
    }

    private void handleOption(String name, Iterator<String> rest) throws BadArgs {
        for (Option o: recognizedOptions) {
            if (o.matches(name)) {
                if (o.hasArg) {
                    if (rest.hasNext())
                        o.process(this, name, rest.next());
                    else
                        throw new BadArgs("err.missing.arg", name).showUsage(true);
                } else
                    o.process(this, name, null);

                if (o.ignoreRest()) {
                    while (rest.hasNext())
                        rest.next();
                }
                return;
            }
        }

        try {
            if (fileManager.handleOption(name, rest))
                return;
        } catch (IllegalArgumentException e) {
            throw new BadArgs("err.invalid.use.of.option", name).showUsage(true);
        }

        throw new BadArgs("err.unknown.option", name).showUsage(true);
    }

    public int run() {
        if (classes == null || classes.isEmpty()) {
            return EXIT_ERROR;
        }

        context.put(PrintWriter.class, log);
//        ClassWriter classWriter = ClassWriter.instance(context);
//        SourceWriter sourceWriter = SourceWriter.instance(context);
//        sourceWriter.setFileManager(fileManager);

        if (options.moduleName != null) {
            try {
                moduleLocation = findModule(options.moduleName);
                if (moduleLocation == null) {
                    reportError("err.cant.find.module", options.moduleName);
                    return EXIT_ERROR;
                }
            } catch (IOException e) {
                reportError("err.cant.find.module.ex", options.moduleName, e);
                return EXIT_ERROR;
            }
        }

        int result = EXIT_OK;

        for (String className: classes) {
            try {
                result = writeClass(); //ALB in lieu of immediately below code--to shut up the compiler.
//                result = writeClass(classWriter, className);
//            } catch (ConstantPoolException e) {
//                reportError("err.bad.constant.pool", className, e.getLocalizedMessage());
//                result = EXIT_ERROR;
            } catch (EOFException e) {
                reportError("err.end.of.file", className);
                result = EXIT_ERROR;
            } catch (FileNotFoundException | NoSuchFileException e) {
                reportError("err.file.not.found", e.getLocalizedMessage());
                result = EXIT_ERROR;
            } catch (IOException e) {
                //e.printStackTrace();
                Object msg = e.getLocalizedMessage();
                if (msg == null) {
                    msg = e;
                }
                reportError("err.ioerror", className, msg);
                result = EXIT_ERROR;
            } catch (OutOfMemoryError e) {
                reportError("err.nomem");
                result = EXIT_ERROR;
            } catch (FatalError e) {
                Object msg = e.getLocalizedMessage();
                if (msg == null) {
                    msg = e;
                }
                reportError("err.fatal.err", msg);
                result = EXIT_ERROR;
            } catch (Throwable t) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                t.printStackTrace(pw);
                pw.close();
                reportError("err.crash", t.toString(), sw.toString());
                result = EXIT_ABNORMAL;
            }
        }

        return result;
    }

    int writeClass() throws IOException, EOFException { ///invented by ALB to silence compiler
        int i = 42;
        if( i > 10 )
            throw new EOFException();
        else if ( i > 20 )
            throw new FileNotFoundException();
        return i;
    }

//    protected int writeClass(ClassWriter classWriter, String className)
//            throws IOException, ConstantPoolException {
//        JavaFileObject fo = open(className);
//        if (fo == null) {
//            reportError("err.class.not.found", className);
//            return EXIT_ERROR;
//        }
//
//        ClassFileInfo cfInfo = read(fo);
//        if (!className.endsWith(".class")) {
//            if (cfInfo.cf.this_class == 0) {
//                if (!className.equals("module-info")) {
//                    reportWarning("warn.unexpected.class", fo.getName(), className);
//                }
//            } else {
//                String cfName = cfInfo.cf.getName();
//                if (!cfName.replaceAll("[/$]", ".").equals(className.replaceAll("[/$]", "."))) {
//                    reportWarning("warn.unexpected.class", fo.getName(), className);
//                }
//            }
//        }
//        write(cfInfo);

//        if (options.showInnerClasses) {
//            ClassFile cf = cfInfo.cf;
//            Attribute a = cf.getAttribute(Attribute.InnerClasses);
//            if (a instanceof InnerClasses_attribute) {
//                InnerClasses_attribute inners = (InnerClasses_attribute) a;
//                try {
//                    int result = EXIT_OK;
//                    for (int i = 0; i < inners.classes.length; i++) {
//                        int outerIndex = inners.classes[i].outer_class_info_index;
//                        ConstantPool.CONSTANT_Class_info outerClassInfo = cf.constant_pool.getClassInfo(outerIndex);
//                        String outerClassName = outerClassInfo.getName();
//                        if (outerClassName.equals(cf.getName())) {
//                            int innerIndex = inners.classes[i].inner_class_info_index;
//                            ConstantPool.CONSTANT_Class_info innerClassInfo = cf.constant_pool.getClassInfo(innerIndex);
//                            String innerClassName = innerClassInfo.getName();
//                            classWriter.println("// inner class " + innerClassName.replaceAll("[/$]", "."));
//                            classWriter.println();
//                            result = writeClass(classWriter, innerClassName);
//                            if (result != EXIT_OK) return result;
//                        }
//                    }
//                    return result;
//                } catch (ConstantPoolException e) {
//                    reportError("err.bad.innerclasses.attribute", className);
//                    return EXIT_ERROR;
//                }
//            } else if (a != null) {
//                reportError("err.bad.innerclasses.attribute", className);
//                return EXIT_ERROR;
//            }
//        }
//
//        return EXIT_OK;
//    }

    protected JavaFileObject open(String className) throws IOException {
        // for compatibility, first see if it is a class name
        JavaFileObject fo = getClassFileObject(className);
        if (fo != null)
            return fo;

        // see if it is an inner class, by replacing dots to $, starting from the right
        String cn = className;
        int lastDot;
        while ((lastDot = cn.lastIndexOf(".")) != -1) {
            cn = cn.substring(0, lastDot) + "$" + cn.substring(lastDot + 1);
            fo = getClassFileObject(cn);
            if (fo != null)
                return fo;
        }

        if (!className.endsWith(".class"))
            return null;

        if (fileManager instanceof StandardJavaFileManager) {
            StandardJavaFileManager sfm = (StandardJavaFileManager) fileManager;
            try {
                fo = sfm.getJavaFileObjects(className).iterator().next();
                if (fo != null && fo.getLastModified() != 0) {
                    return fo;
                }
            } catch (IllegalArgumentException ignore) {
            }
        }

        // see if it is a URL, and if so, wrap it in just enough of a JavaFileObject
        // to suit javap's needs
        if (className.matches("^[A-Za-z]+:.*")) {
            try {
                final URI uri = new URI(className);
                final URL url = uri.toURL();
                final URLConnection conn = url.openConnection();
                conn.setUseCaches(false);
                return new JavaFileObject() {
                    public Kind getKind() {
                        return JavaFileObject.Kind.CLASS;
                    }

                    public boolean isNameCompatible(String simpleName, Kind kind) {
                        throw new UnsupportedOperationException();
                    }

                    public NestingKind getNestingKind() {
                        throw new UnsupportedOperationException();
                    }

                    public Modifier getAccessLevel() {
                        throw new UnsupportedOperationException();
                    }

                    public URI toUri() {
                        return uri;
                    }

                    public String getName() {
                        return uri.toString();
                    }

                    public InputStream openInputStream() throws IOException {
                        return conn.getInputStream();
                    }

                    public OutputStream openOutputStream() throws IOException {
                        throw new UnsupportedOperationException();
                    }

                    public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
                        throw new UnsupportedOperationException();
                    }

                    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                        throw new UnsupportedOperationException();
                    }

                    public Writer openWriter() throws IOException {
                        throw new UnsupportedOperationException();
                    }

                    public long getLastModified() {
                        return conn.getLastModified();
                    }

                    public boolean delete() {
                        throw new UnsupportedOperationException();
                    }

                };
            } catch (URISyntaxException | IOException ignore) {
            }
        }

        return null;
    }
    private JavaFileObject getClassFileObject(String className) throws IOException {
        try {
            JavaFileObject fo;
            if (moduleLocation != null) {
                fo = fileManager.getJavaFileForInput(moduleLocation, className, JavaFileObject.Kind.CLASS);
            } else {
                fo = fileManager.getJavaFileForInput(StandardLocation.PLATFORM_CLASS_PATH, className, JavaFileObject.Kind.CLASS);
                if (fo == null)
                    fo = fileManager.getJavaFileForInput(StandardLocation.CLASS_PATH, className, JavaFileObject.Kind.CLASS);
            }
            return fo;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Location findModule(String moduleName) throws IOException {
        Location[] locns = {
            StandardLocation.UPGRADE_MODULE_PATH,
            StandardLocation.SYSTEM_MODULES,
            StandardLocation.MODULE_PATH
        };
        for (Location segment: locns) {
            for (Set<Location> set: fileManager.listLocationsForModules(segment)) {
                Location result = null;
                for (Location l: set) {
                    String name = fileManager.inferModuleName(l);
                    if (name.equals(moduleName)) {
                        if (result == null)
                            result = l;
                        else
                            throw new IOException("multiple definitions found for " + moduleName);
                    }
                }
                if (result != null)
                    return result;
            }
        }
        return null;
    }

    private void showHelp() {
        printLines(getMessage("main.usage", progname));
        for (Option o: recognizedOptions) {
            String name = o.aliases[0].replaceAll("^-+", "").replaceAll("-+", "_"); // there must always be at least one name
            if (name.startsWith("X") || name.equals("fullversion"))
                continue;
            printLines(getMessage("main.opt." + name));
        }

        String[] fmOptions = {
            "--module-path", "--system",
            "--class-path", "-classpath", "-cp",
            "-bootclasspath",
            "--multi-release"
        };

        for (String o: fmOptions) {
            if (fileManager.isSupportedOption(o) == -1)
                continue;
            String name = o.replaceAll("^-+", "").replaceAll("-+", "_");
            printLines(getMessage("main.opt." + name));
        }

        printLines(getMessage("main.usage.foot"));
    }

    private void showVersion(boolean full) {
        printLines(version(full ? "full" : "release"));
    }

    private void printLines(String msg) {
        log.println(msg.replace("\n", nl));
    }

    private static final String nl = System.getProperty("line.separator");

    private static final String versionRBName = "org.jacobin.jadis.resources.version";
    private static ResourceBundle versionRB;

    private String version(String key) {
        // key=version:  mm.nn.oo[-milestone]
        // key=full:     mm.mm.oo[-milestone]-build
        if (versionRB == null) {
            try {
                versionRB = ResourceBundle.getBundle(versionRBName);
            } catch (MissingResourceException e) {
                return getMessage("version.resource.missing", System.getProperty("java.version"));
            }
        }
        try {
            return versionRB.getString(key);
        }
        catch (MissingResourceException e) {
            return getMessage("version.unknown", System.getProperty("java.version"));
        }
    }

    private void reportError(String key, Object... args) {
        diagnosticListener.report(createDiagnostic(Diagnostic.Kind.ERROR, key, args));
    }

    private void reportNote(String key, Object... args) {
        diagnosticListener.report(createDiagnostic(Diagnostic.Kind.NOTE, key, args));
    }

    private void reportWarning(String key, Object... args) {
        diagnosticListener.report(createDiagnostic(Diagnostic.Kind.WARNING, key, args));
    }

    private Diagnostic<JavaFileObject> createDiagnostic(
            final Diagnostic.Kind kind, final String key, final Object... args) {
        return new Diagnostic<>() {
            public Kind getKind() {
                return kind;
            }

            public JavaFileObject getSource() {
                return null;
            }

            public long getPosition() {
                return Diagnostic.NOPOS;
            }

            public long getStartPosition() {
                return Diagnostic.NOPOS;
            }

            public long getEndPosition() {
                return Diagnostic.NOPOS;
            }

            public long getLineNumber() {
                return Diagnostic.NOPOS;
            }

            public long getColumnNumber() {
                return Diagnostic.NOPOS;
            }

            public String getCode() {
                return key;
            }

            public String getMessage(Locale locale) {
                return JavapTask.this.getMessage(locale, key, args);
            }

            @Override
            public String toString() {
                return getClass().getName() + "[key=" + key + ",args=" + Arrays.asList(args) + "]";
            }

        };

    }

    public String getMessage(String key, Object... args) {
        return getMessage(task_locale, key, args);
    }

    public String getMessage(Locale locale, String key, Object... args) {
        if (bundles == null) {
            // could make this a HashMap<Locale,SoftReference<ResourceBundle>>
            // and for efficiency, keep a hard reference to the bundle for the task
            // locale
            bundles = new HashMap<>();
        }

        if (locale == null)
            locale = Locale.getDefault();

        ResourceBundle b = bundles.get(locale);
        if (b == null) {
            try {
                b = ResourceBundle.getBundle("org.jacobin.jadis.resources.javap", locale);
                bundles.put(locale, b);
            } catch (MissingResourceException e) {
                throw new InternalError("Cannot find javap resource bundle for locale " + locale);
            }
        }

        try {
            return MessageFormat.format(b.getString(key), args);
        } catch (MissingResourceException e) {
            throw new InternalError(e, key);
        }
    }

    protected Context context;
    JavaFileManager fileManager;
    JavaFileManager defaultFileManager;
    PrintWriter log;
    DiagnosticListener<? super JavaFileObject> diagnosticListener;
    List<String> classes;
    Location moduleLocation;
    Options options;
    //ResourceBundle bundle;
    Locale task_locale;
    Map<Locale, ResourceBundle> bundles;
    protected Attribute.Factory attributeFactory;

    private static final String progname = "javap";

    private static class SizeInputStream extends FilterInputStream {
        SizeInputStream(InputStream in) {
            super(in);
        }

        int size() {
            return size;
        }

        @Override
        public int read(byte[] buf, int offset, int length) throws IOException {
            int n = super.read(buf, offset, length);
            if (n > 0)
                size += n;
            return n;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            size += 1;
            return b;
        }

        private int size;
    }

    public class BadArgs extends Exception {
        static final long serialVersionUID = 8765093759964640721L;
        BadArgs(String key, Object... args) {
            super(JavapTask.this.getMessage(key, args));
            this.key = key;
            this.args = args;
        }

        BadArgs showUsage(boolean b) {
            showUsage = b;
            return this;
        }

        final String key;
        final Object[] args;
        boolean showUsage;
    }
}
