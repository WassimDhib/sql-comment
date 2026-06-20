package io.sqlcommenter.agent.attach;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Logger;

/**
 * CLI tool for dynamic attachment of the SQL Commenter agent to a running JVM.
 * Compatible with Java 8, 11, 17, 21.
 *
 * <h2>JDK 17+ compatibility</h2>
 * <p>The Java module system (JDK 9+) restricts reflective access to internal classes.
 * {@code sun.tools.attach.HotSpotVirtualMachine} is NOT exported from {@code jdk.attach}.
 * The public API {@code com.sun.tools.attach.VirtualMachine} IS exported.
 *
 * <p>Fix: always use {@code vmClass} (the public VirtualMachine class) to look up
 * methods, never {@code vm.getClass()} which returns the non-exported implementation.
 *
 * <h2>JDK 8 client → JDK 21 server: "Non-numeric value found"</h2>
 * <p>Known protocol incompatibility — treated as likely success. Verify in JBoss logs.
 */
public final class DynamicAttach {

    private static final Logger LOG = Logger.getLogger(DynamicAttach.class.getName());

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || containsFlag(args, "--help", "-h")) { printHelp(); return; }
        if (containsFlag(args, "--list", "-l")) { listVMs(); return; }

        String pid       = getOption(args, "--pid",  "-p");
        String nameQuery = getOption(args, "--name", "-n");
        String agentArgs = getOption(args, "--args", "-a");
        String agentJar  = getOption(args, "--jar",  "-j");

        if (pid == null && nameQuery == null) {
            System.err.println("[DynamicAttach] Error: --pid or --name required.");
            printHelp(); System.exit(1);
        }
        if (agentJar == null) agentJar = detectAgentJar();
        if (agentJar == null) {
            System.err.println("[DynamicAttach] Error: cannot locate agent JAR. Use --jar.");
            System.exit(1);
        }
        if (pid == null) {
            pid = findPidByName(nameQuery);
            if (pid == null) {
                System.err.println("[DynamicAttach] No JVM found matching: " + nameQuery);
                System.exit(1);
            }
        }

        boolean success = attachToProcess(pid, agentJar, agentArgs);
        System.exit(success ? 0 : 1);
    }

    // -------------------------------------------------------------------------
    // List JVMs
    // -------------------------------------------------------------------------

    public static void listVMs() throws Exception {
        List<VmDescriptor> vms = listVirtualMachines();
        if (vms.isEmpty()) { System.out.println("No local JVMs found."); return; }
        System.out.printf("%-8s  %s%n", "PID", "Description");
        System.out.println("--------  ----------------------------------------------------------------");
        for (VmDescriptor vm : vms) System.out.printf("%-8s  %s%n", vm.pid, vm.displayName);
    }

    // -------------------------------------------------------------------------
    // Attach
    // -------------------------------------------------------------------------

    public static boolean attachToProcess(String pid, String agentJar, String agentArgs)
            throws Exception {

        File jar = resolveAndValidate(agentJar);

        System.out.println("[DynamicAttach] Attaching to PID " + pid + "...");
        System.out.println("[DynamicAttach] JAR : " + jar.getAbsolutePath());
        System.out.println("[DynamicAttach] Args: " + (agentArgs != null ? agentArgs : "(none)"));

        // Load the PUBLIC VirtualMachine class (exported from jdk.attach in JDK 9+)
        Class<?> vmClass = loadVirtualMachineClass();

        Object vm = null;
        try {
            // attach(String pid) — looked up on the PUBLIC class, works on all JDKs
            vm = vmClass.getMethod("attach", String.class).invoke(null, pid);

            // ── KEY FIX for JDK 17+ ──────────────────────────────────────────
            // Use vmClass (public API) to look up loadAgent — NOT vm.getClass()
            // vm.getClass() returns sun.tools.attach.VirtualMachineImpl which is
            // in an unexported package of jdk.attach → IllegalAccessException on JDK 17+
            Method loadAgent = vmClass.getMethod("loadAgent", String.class, String.class);
            loadAgent.invoke(vm, jar.getAbsolutePath(), agentArgs != null ? agentArgs : "");

            System.out.println("[DynamicAttach] SUCCESS — agent loaded in PID " + pid);
            return true;

        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            return handleError(cause, pid, jar, agentArgs, vmClass);
        } catch (IllegalAccessException e) {
            // Should not happen with vmClass-based lookup, but handle gracefully
            System.err.println("[DynamicAttach] IllegalAccessException: " + e.getMessage());
            System.err.println("[DynamicAttach] Try running with: --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED");
            throw e;
        } finally {
            if (vm != null) {
                // Use vmClass for detach too — same reason
                try { vmClass.getMethod("detach").invoke(vm); } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    private static boolean handleError(Throwable cause, String pid, File jar,
                                        String agentArgs, Class<?> vmClass) throws Exception {
        if (cause == null) { System.err.println("[DynamicAttach] Unknown error"); return false; }

        String msg = cause.getMessage() != null ? cause.getMessage() : "";

        // "Non-numeric value found" — JDK 8 client <-> JDK 21 server protocol mismatch
        // The agent IS loaded — this is a known incompatibility in readInt() response parsing
        if (msg.contains("Non-numeric value found")) {
            System.out.println();
            System.out.println("[DynamicAttach] NOTE: 'Non-numeric value found' — known JDK 8→JDK 21");
            System.out.println("[DynamicAttach] protocol mismatch. Agent likely loaded successfully.");
            System.out.println("[DynamicAttach]");
            System.out.println("[DynamicAttach] => Check JBoss server.log:");
            System.out.println("[DynamicAttach]    grep 'SqlCommenterAgent\\|AgentDelegate' server.log");
            System.out.println("[DynamicAttach]    Expected: INFO [AgentDelegate] Prêt. Règles actives : [...]");
            System.out.println("[DynamicAttach]");
            System.out.println("[DynamicAttach] => If agent loaded but no SQL comments:");
            System.out.println("[DynamicAttach]    Install JBoss module (see JBOSS_INSTALL.md)");
            System.out.println("[DynamicAttach]    jboss-cli.sh --connect");
            System.out.println("[DynamicAttach]    /subsystem=ee:list-add(name=global-modules,value={name=io.sqlcommenter.agent})");
            System.out.println("[DynamicAttach]    :reload");

            // Retry with /tmp path
            if (!jar.getAbsolutePath().equals("/tmp/sqlcommenter-agent.jar")) {
                System.out.println("[DynamicAttach] Retrying with /tmp/sqlcommenter-agent.jar...");
                File tmpJar = copyToTmp(jar);
                try {
                    Object vm2 = vmClass.getMethod("attach", String.class).invoke(null, pid);
                    try {
                        vmClass.getMethod("loadAgent", String.class, String.class)
                               .invoke(vm2, tmpJar.getAbsolutePath(), agentArgs != null ? agentArgs : "");
                        System.out.println("[DynamicAttach] SUCCESS on retry.");
                        return true;
                    } finally {
                        try { vmClass.getMethod("detach").invoke(vm2); } catch (Exception ignored) {}
                    }
                } catch (InvocationTargetException e2) {
                    String msg2 = e2.getCause() != null ? e2.getCause().getMessage() : "";
                    if (msg2.contains("Non-numeric value found")) {
                        System.out.println("[DynamicAttach] Same response on retry — treating as SUCCESS.");
                        System.out.println("[DynamicAttach] Verify in server.log.");
                        return true;
                    }
                }
            } else {
                System.out.println("[DynamicAttach] Already at /tmp — treating as SUCCESS.");
                return true;
            }
        }

        // IllegalAccessException — JDK 17+ module access (should not happen with vmClass lookup)
        if (cause instanceof IllegalAccessException ||
            (cause.getClass().getName().contains("IllegalAccess"))) {
            System.err.println("[DynamicAttach] FAILED: Module access denied.");
            System.err.println("[DynamicAttach] Run with JDK 17+ and ensure jdk.attach is available.");
            System.err.println("[DynamicAttach] Or use: --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED");
        }

        // Cannot connect
        if (msg.contains("unable to open socket") || msg.contains("No such file") ||
            msg.contains("no such process")) {
            System.err.println("[DynamicAttach] FAILED: Cannot connect to PID " + pid);
            System.err.println("[DynamicAttach] => Verify PID is running: ps -p " + pid);
            System.err.println("[DynamicAttach] => Run as same OS user as JBoss (e.g. sudo -u jboss)");
        }

        System.err.println("[DynamicAttach] Error: " + cause.getClass().getSimpleName() + ": " + msg);
        if (cause instanceof Exception) throw (Exception) cause;
        throw new RuntimeException(cause);
    }

    // -------------------------------------------------------------------------
    // VirtualMachine class loading
    // -------------------------------------------------------------------------

    /**
     * Loads {@code com.sun.tools.attach.VirtualMachine} reflectively.
     *
     * <p>Strategy:
     * <ol>
     *   <li>JDK 9+: {@code Class.forName("com.sun.tools.attach.VirtualMachine")}
     *       — always works if the {@code jdk.attach} module is available (it is in all JDKs)</li>
     *   <li>JDK 8: load from {@code tools.jar} via URLClassLoader</li>
     * </ol>
     *
     * <p><strong>Important</strong>: always call methods on this class object,
     * never on {@code vm.getClass()} which returns the non-exported implementation.
     */
    public static Class<?> loadVirtualMachineClass() throws Exception {
        // JDK 9+ (11, 17, 21): com.sun.tools.attach is in module jdk.attach, exported
        try { return Class.forName("com.sun.tools.attach.VirtualMachine"); }
        catch (ClassNotFoundException ignored) {}

        // JDK 8: load from tools.jar
        String jh = System.getProperty("java.home");
        for (String suffix : new String[]{"/../lib/tools.jar", "/lib/tools.jar", "/../tools.jar"}) {
            File f = new File(jh + suffix).getCanonicalFile();
            if (f.exists()) {
                java.net.URLClassLoader cl = new java.net.URLClassLoader(
                    new java.net.URL[]{f.toURI().toURL()},
                    DynamicAttach.class.getClassLoader());
                try { return Class.forName("com.sun.tools.attach.VirtualMachine", true, cl); }
                catch (ClassNotFoundException ignored) {}
            }
        }

        throw new ClassNotFoundException(
            "com.sun.tools.attach.VirtualMachine not found.\n"
            + "  JDK 8 : ensure tools.jar is at " + jh + "/../lib/tools.jar\n"
            + "  JDK 9+: ensure jdk.attach module is available (standard JDK install)");
    }

    // -------------------------------------------------------------------------
    // List VMs
    // -------------------------------------------------------------------------

    public static List<VmDescriptor> listVirtualMachines() {
        List<VmDescriptor> result = new ArrayList<VmDescriptor>();
        try {
            Class<?> vmClass = loadVirtualMachineClass();
            for (Object d : (List<?>) vmClass.getMethod("list").invoke(null)) {
                result.add(new VmDescriptor(
                    (String) d.getClass().getMethod("id").invoke(d),
                    (String) d.getClass().getMethod("displayName").invoke(d)));
            }
        } catch (Exception e) {
            System.err.println("[DynamicAttach] Attach API unavailable: " + e.getMessage());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String findPidByName(String query) throws Exception {
        for (VmDescriptor vm : listVirtualMachines()) {
            if (vm.displayName.toLowerCase().contains(query.toLowerCase())) {
                System.out.println("[DynamicAttach] Found: PID=" + vm.pid + " (" + vm.displayName + ")");
                return vm.pid;
            }
        }
        return null;
    }

    private static File resolveAndValidate(String agentJar) throws IOException {
        File jar = new File(agentJar).getCanonicalFile();
        if (!jar.exists()) throw new IOException("Agent JAR not found: " + agentJar);
        try (JarFile jf = new JarFile(jar)) {
            Manifest mf = jf.getManifest();
            String ac = mf != null ? mf.getMainAttributes().getValue("Agent-Class") : null;
            System.out.println("[DynamicAttach] Manifest Agent-Class: " + ac);
            if (ac == null) System.err.println("[DynamicAttach] WARNING: Agent-Class missing!");
        } catch (Exception e) {
            System.err.println("[DynamicAttach] WARNING: Cannot read manifest: " + e.getMessage());
        }
        return jar;
    }

    private static File copyToTmp(File source) throws IOException {
        File dest = new File(System.getProperty("java.io.tmpdir"), "sqlcommenter-agent.jar");
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        dest.setReadable(true, false);
        return dest;
    }

    private static String detectAgentJar() {
        try {
            java.security.CodeSource cs = DynamicAttach.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null)
                return new File(cs.getLocation().toURI()).getAbsolutePath();
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean containsFlag(String[] args, String... flags) {
        for (String a : args) for (String f : flags) if (a.equals(f)) return true;
        return false;
    }

    private static String getOption(String[] args, String... keys) {
        for (int i = 0; i < args.length - 1; i++)
            for (String k : keys) if (args[i].equals(k)) return args[i + 1];
        return null;
    }

    private static void printHelp() {
        System.out.println("SQL Commenter Agent - Dynamic Attach (Java 8, 11, 17, 21)");
        System.out.println("  --list              List local JVMs");
        System.out.println("  --pid  <pid>        Target JVM PID");
        System.out.println("  --name <name>       Target JVM by name substring");
        System.out.println("  --args <args>       Agent arguments");
        System.out.println("  --jar  <path>       Agent JAR path");
        System.out.println();
        System.out.println("Notes:");
        System.out.println("  JDK 17+: uses public VirtualMachine API (no --add-opens needed)");
        System.out.println("  JDK 21+ target: add -XX:+EnableDynamicAgentLoading to JBoss startup");
        System.out.println("  'Non-numeric value found': JDK8 client->JDK21 server, check server.log");
    }

    public static final class VmDescriptor {
        public final String pid;
        public final String displayName;
        public VmDescriptor(String pid, String displayName) {
            this.pid = pid;
            this.displayName = (displayName == null || displayName.trim().isEmpty())
                    ? "(unknown)" : displayName;
        }
        public String toString() { return pid + " - " + displayName; }
    }
}
