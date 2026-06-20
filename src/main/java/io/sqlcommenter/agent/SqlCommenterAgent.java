package io.sqlcommenter.agent;

import io.sqlcommenter.agent.transformer.SqlCommenterRuntime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Logger;

/**
 * SQL Commenter Java Agent — point d'entrée JVM.
 *
 * <h2>Architecture classloader pour JBoss/WildFly</h2>
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────┐
 * │  Bootstrap classloader                              │
 * │    SqlCommenterAgent   (agentmain stub minimal)     │
 * │    SqlCommenterRuntime (+ Instrumenter interface)   │
 * │    SqlCommenterRuntime.Instrumenter                 │
 * └──────────────────────────┬──────────────────────────┘
 *                            │ réflexion
 * ┌──────────────────────────▼──────────────────────────┐
 * │  AgentDelegate classloader (URLClassLoader isolé)   │
 * │    AgentConfig, RuleEngine, règles, ASM, ...        │
 * │    AgentDelegate implements SqlCommenterRuntime.    │
 * │                          Instrumenter               │
 * └─────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>En mode premain : pas de problème de classloader → init directe.
 * En mode agentmain : délégation via un URLClassLoader isolé pour éviter
 * {@link LinkageError} et {@link NoClassDefFoundError}.
 */
public class SqlCommenterAgent {

    private static final Logger LOG = Logger.getLogger(SqlCommenterAgent.class.getName());
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        initialize(agentArgs, instrumentation, false);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        initialize(agentArgs, instrumentation, true);
    }

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    static void initialize(String agentArgs, Instrumentation instrumentation, boolean dynamic) {
        if (!INITIALIZED.compareAndSet(false, true)) {
            LOG.info("[SqlCommenterAgent] Déjà initialisé — skip.");
            return;
        }
        try {
            LOG.info("[SqlCommenterAgent] Initialisation (dynamic=" + dynamic + ") args=" + agentArgs);

            // Localiser le JAR agent
            File agentJar = locateAgentJar();
            if (agentJar == null) {
                LOG.severe("[SqlCommenterAgent] Impossible de localiser le JAR agent");
                INITIALIZED.set(false);
                return;
            }

            if (dynamic) {
                initDynamic(agentArgs, instrumentation, agentJar);
            } else {
                initPremain(agentArgs, instrumentation, agentJar);
            }

        } catch (Throwable t) {
            logThrowable("[SqlCommenterAgent] Échec initialisation", t);
            INITIALIZED.set(false);
        }
    }

    // -------------------------------------------------------------------------
    // Mode premain : init directe (pas de problème de classloader)
    // -------------------------------------------------------------------------

    private static void initPremain(String agentArgs, Instrumentation instrumentation, File agentJar)
            throws Exception {
        // Injecter le JAR complet dans le bootstrap (aucune classe encore chargée)
        instrumentation.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(agentJar));
        LOG.info("[SqlCommenterAgent] JAR complet ajouté au bootstrap : " + agentJar);

        // Init directe — toutes les classes visibles depuis le bootstrap
        AgentDelegate.initialize(agentArgs, instrumentation, agentJar, false);
    }

    // -------------------------------------------------------------------------
    // Mode agentmain : délégation via URLClassLoader isolé
    // -------------------------------------------------------------------------

    /**
     * Stratégie en 3 étapes pour éviter LinkageError et NoClassDefFoundError :
     *
     * <ol>
     *   <li>Injecter UNIQUEMENT SqlCommenterRuntime (+ Instrumenter) dans le bootstrap
     *       via un mini-JAR en mémoire → permet au bytecode transformé de l'appeler</li>
     *   <li>Créer un URLClassLoader isolé (parent=bootstrap) pointant sur le JAR agent →
     *       charge AgentConfig, RuleEngine, ASM, etc. sans conflit avec le bootstrap</li>
     *   <li>Charger et appeler AgentDelegate via réflexion depuis ce classloader →
     *       AgentDelegate implémente SqlCommenterRuntime.Instrumenter (définie dans le
     *       bootstrap) → pas de LinkageError sur l'interface</li>
     * </ol>
     */
    private static void initDynamic(String agentArgs, Instrumentation instrumentation, File agentJar)
            throws Exception {

        // Étape 1 : injecter SqlCommenterRuntime + Instrumenter + JBossModulesIntegration
        // dans le bootstrap. JBossModulesIntegration y est incluse pour pouvoir l'appeler
        // depuis le contexte bootstrap qui peut voir le system classloader (jboss-modules.jar).
        injectRuntimeToBootstrap(instrumentation);

        // Étape 1b : patcher Module.systemPackages pour rendre SqlCommenterRuntime visible
        // depuis tous les modules JBoss (IronJacamar, Oracle, etc.) SANS modifier la config.
        // Appelé ICI depuis le bootstrap CL, AVANT la création du URLClassLoader delegate,
        // pour que le TCCL/system CL soit encore celui du thread Attach Listener.
        try {
            Class<?> jbossInteg = Class.forName(
                "io.sqlcommenter.agent.jboss.JBossModulesIntegration");
            jbossInteg.getMethod("configure").invoke(null);
        } catch (ClassNotFoundException e) {
            LOG.fine("[SqlCommenterAgent] JBossModulesIntegration non chargée depuis bootstrap, tentative delegate...");
        } catch (Exception e) {
            LOG.warning("[SqlCommenterAgent] JBossModulesIntegration.configure() : " + e.getMessage());
        }

        // Étape 2 : créer un URLClassLoader isolé pour les classes de l'agent
        // Parent = bootstrap classloader (null) pour éviter de remonter vers l'app CL
        // et créer un double chargement
        URLClassLoader delegateCl = new URLClassLoader(
                new URL[]{agentJar.toURI().toURL()},
                null  // parent = bootstrap uniquement
        );
        LOG.info("[SqlCommenterAgent] Delegate classloader créé : " + agentJar);

        // Étape 3 : charger et invoquer AgentDelegate via réflexion
        Class<?> delegateClass = delegateCl.loadClass(
                "io.sqlcommenter.agent.AgentDelegate");
        Method initMethod = delegateClass.getMethod(
                "initialize", String.class, Instrumentation.class, File.class, boolean.class);
        initMethod.invoke(null, agentArgs, instrumentation, agentJar, true);

        LOG.info("[SqlCommenterAgent] Initialisation dynamique terminée.");
    }

    // -------------------------------------------------------------------------
    // Mini-JAR bootstrap : SqlCommenterRuntime + Instrumenter uniquement
    // -------------------------------------------------------------------------

    private static void injectRuntimeToBootstrap(Instrumentation instrumentation) throws Exception {
        // Classes à injecter dans le bootstrap
        String[] classesToInject = {
            "io/sqlcommenter/agent/transformer/SqlCommenterRuntime.class",
            "io/sqlcommenter/agent/transformer/SqlCommenterRuntime$Instrumenter.class",
            "io/sqlcommenter/agent/jboss/JBossModulesIntegration.class"
        };

        File miniJar = buildMiniJar(classesToInject);
        if (miniJar == null) {
            LOG.warning("[SqlCommenterAgent] Mini-JAR non créé — bootstrap injection ignorée");
            return;
        }
        miniJar.deleteOnExit();
        instrumentation.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(miniJar));
        LOG.info("[SqlCommenterAgent] Mini-JAR Runtime ajouté au bootstrap : " + miniJar);
    }

    private static File buildMiniJar(String[] classPaths) {
        try {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            JarOutputStream jos = new JarOutputStream(buf, manifest);

            ClassLoader cl = SqlCommenterAgent.class.getClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();

            for (String cp : classPaths) {
                java.io.InputStream is = cl.getResourceAsStream(cp);
                if (is == null) {
                    LOG.warning("[SqlCommenterAgent] Ressource introuvable : " + cp);
                    continue;
                }
                byte[] bytes = readAll(is);
                is.close();
                jos.putNextEntry(new JarEntry(cp));
                jos.write(bytes);
                jos.closeEntry();
            }
            jos.close();

            File tmp = File.createTempFile("sqlcommenter-bootstrap-", ".jar");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(buf.toByteArray());
            fos.close();
            tmp.setReadable(true, false);
            return tmp;

        } catch (Exception e) {
            LOG.warning("[SqlCommenterAgent] Erreur création mini-JAR : " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    static File locateAgentJar() {
        try {
            java.security.CodeSource cs =
                SqlCommenterAgent.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null)
                return new File(cs.getLocation().toURI()).getCanonicalFile();
        } catch (Exception ignored) {}
        return null;
    }

    private static byte[] readAll(java.io.InputStream is) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }
    // -------------------------------------------------------------------------
    // Logging helper
    // -------------------------------------------------------------------------

    /**
     * Logs a Throwable with full cause chain.
     * Unwraps InvocationTargetException to get the real underlying cause.
     */
    static void logThrowable(String msg, Throwable t) {
        // Unwrap InvocationTargetException layers
        Throwable real = t;
        while (real instanceof java.lang.reflect.InvocationTargetException
               && real.getCause() != null) {
            real = real.getCause();
        }
        // Build log message with full cause chain
        StringBuilder sb = new StringBuilder(msg);
        sb.append("\n  Exception: ").append(real.getClass().getName())
          .append(": ").append(real.getMessage());
        // Stack trace
        StackTraceElement[] stack = real.getStackTrace();
        int limit = Math.min(stack.length, 15);
        for (int i = 0; i < limit; i++) {
            sb.append("\n    at ").append(stack[i]);
        }
        if (stack.length > limit) sb.append("\n    ... ").append(stack.length - limit).append(" more");
        // Cause chain
        Throwable cause = real.getCause();
        int depth = 0;
        while (cause != null && depth++ < 5) {
            sb.append("\nCaused by: ").append(cause.getClass().getName())
              .append(": ").append(cause.getMessage());
            StackTraceElement[] cs = cause.getStackTrace();
            int cl = Math.min(cs.length, 5);
            for (int i = 0; i < cl; i++) sb.append("\n    at ").append(cs[i]);
            if (cs.length > cl) sb.append("\n    ... ").append(cs.length - cl).append(" more");
            cause = cause.getCause();
        }
        LOG.severe(sb.toString());
    }

}
