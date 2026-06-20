package io.sqlcommenter.agent;

import io.sqlcommenter.agent.jboss.JBossModulesIntegration;
import io.sqlcommenter.agent.core.AgentConfig;
import io.sqlcommenter.agent.core.RuleEngine;
import io.sqlcommenter.agent.transformer.JdbcClassTransformer;
import io.sqlcommenter.agent.transformer.SqlCommenterRuntime;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.logging.Logger;

/**
 * Délégué d'initialisation chargé par un URLClassLoader isolé en mode agentmain.
 *
 * <p>Cette classe contient toute la logique d'initialisation qui référence des classes
 * "lourdes" (AgentConfig, RuleEngine, ASM...) qui ne doivent PAS être dans le bootstrap
 * classloader pour éviter les conflits de chargement en environnement modulaire (JBoss/WildFly).
 *
 * <p>Elle implémente {@link SqlCommenterRuntime.Instrumenter} — une interface définie dans
 * le bootstrap classloader. Comme les deux classloaders (bootstrap et delegate) partagent
 * la même définition de l'interface, l'assignation est valide sans LinkageError.
 *
 * <h2>Flux d'appel en agentmain</h2>
 * <pre>
 *   JVM cible → agentmain() [bootstrap CL]
 *     → URLClassLoader(parent=bootstrap) charge AgentDelegate
 *     → AgentDelegate.initialize() crée RuleEngine + JdbcClassTransformer
 *     → AgentDelegate implémente Instrumenter → SqlCommenterRuntime.setInstrumenter(this)
 *     → Bytecode JDBC appelle SqlCommenterRuntime.instrument() [bootstrap]
 *     → Appelle Instrumenter.instrument() → AgentDelegate.instrument() [delegate CL]
 *     → RuleEngine.instrument() [delegate CL]
 * </pre>
 *
 * <h2>Flux d'appel en premain</h2>
 * <pre>
 *   JVM démarrage → premain() [bootstrap CL]
 *     → AgentDelegate.initialize() directement (pas de URLClassLoader séparé)
 * </pre>
 */
public final class AgentDelegate implements SqlCommenterRuntime.Instrumenter {

    private static final Logger LOG = Logger.getLogger(AgentDelegate.class.getName());

    private final RuleEngine ruleEngine;

    private AgentDelegate(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    // -------------------------------------------------------------------------
    // Point d'entrée (appelé directement en premain, via réflexion en agentmain)
    // -------------------------------------------------------------------------

    /**
     * Initialise le moteur de règles, enregistre le transformer et connecte
     * l'instrumenter à {@link SqlCommenterRuntime}.
     *
     * @param agentArgs     arguments de l'agent (rules=..., format=..., etc.)
     * @param instrumentation l'instance Instrumentation JVM
     * @param agentJar      fichier JAR de l'agent (pour les retransformations)
     * @param dynamic       true si appelé depuis agentmain (attach dynamique)
     */
    public static void initialize(String agentArgs, Instrumentation instrumentation,
                                  File agentJar, boolean dynamic) throws Exception {
        JBossModulesIntegration.configure();
        LOG.info("[AgentDelegate] Initialize (dynamic=" + dynamic + ")");

        // Vérifier que jboss.modules.system.pkgs inclut notre package.
        // Sans ça, les modules JBoss (IronJacamar, Oracle) ne voient pas
        // SqlCommenterRuntime et le bytecode injecté échoue silencieusement.
        checkSystemPackagesConfig();

        AgentConfig config = AgentConfig.parse(agentArgs);
        RuleEngine ruleEngine = RuleEngine.from(config);

        // Créer le délégué qui implémente Instrumenter
        AgentDelegate delegate = new AgentDelegate(ruleEngine);

        // Enregistrer dans SqlCommenterRuntime (bootstrap classloader)
        // L'interface Instrumenter est dans le bootstrap → pas de LinkageError
        SqlCommenterRuntime.setInstrumenter(delegate);

        // Enregistrer le transformer JDBC
        JdbcClassTransformer transformer = new JdbcClassTransformer(ruleEngine, config);
        instrumentation.addTransformer(transformer, true);

        // En mode dynamic : retransformer les classes JDBC déjà chargées
        if (dynamic) {
            retransformLoadedClasses(instrumentation, transformer, config);
        }

        List<String> ruleIds = ruleEngine.getRuleIds();
        LOG.info("[AgentDelegate] Prêt. Règles actives : " + ruleIds);
    }

    // -------------------------------------------------------------------------
    // Vérification de la configuration jboss.modules.system.pkgs
    // -------------------------------------------------------------------------

    private static final String REQUIRED_SYSTEM_PKG = "io.sqlcommenter.agent.transformer";

    /**
     * Vérifie que {@code jboss.modules.system.pkgs} inclut notre package.
     *
     * <p>Dans JBoss/WildFly, les modules sont isolés et ne délèguent au classloader
     * système (donc au bootstrap où vit SqlCommenterRuntime) QUE pour les packages
     * listés dans cette propriété. Si notre package n'y est pas, le bytecode injecté
     * lève {@code NoClassDefFoundError} à l'exécution et aucun commentaire n'apparaît.
     *
     * <p>Cette propriété ne peut être définie qu'au démarrage de la JVM. Si elle est
     * absente, on logue un avertissement détaillé avec la marche à suivre.
     */
    private static void checkSystemPackagesConfig() {
        try {
            // Détecter si on tourne dans JBoss Modules
            boolean inJBoss = false;
            try {
                Class.forName("org.jboss.modules.Module");
                inJBoss = true;
            } catch (ClassNotFoundException e) {
                // Pas dans JBoss (Tomcat, Spring Boot, etc.) → pas concerné
            }
            if (!inJBoss) return;

            String pkgs = System.getProperty("jboss.modules.system.pkgs", "");
            if (pkgs.contains(REQUIRED_SYSTEM_PKG)) {
                LOG.info("[AgentDelegate] jboss.modules.system.pkgs OK — "
                        + REQUIRED_SYSTEM_PKG + " est visible depuis tous les modules.");
            } else {
                LOG.warning("[AgentDelegate] ATTENTION : jboss.modules.system.pkgs n'inclut PAS "
                    + REQUIRED_SYSTEM_PKG
                    + "\n  Conséquence : les modules JBoss (IronJacamar, Oracle) ne verront pas"
                    + "\n  SqlCommenterRuntime. Le bytecode injecté échouera silencieusement et"
                    + "\n  AUCUN commentaire SQL n'apparaîtra."
                    + "\n  Valeur actuelle: jboss.modules.system.pkgs=" + (pkgs.isEmpty() ? "(vide)" : pkgs)
                    + "\n  SOLUTION (dans standalone.conf, puis REDÉMARRER) :"
                    + "\n    JAVA_OPTS=\"$JAVA_OPTS -Djboss.modules.system.pkgs=" + (pkgs.isEmpty() ? "" : pkgs + ",") + REQUIRED_SYSTEM_PKG + "\""
                    + "\n  Voir JBOSS_INSTALL.md");
            }
        } catch (Throwable t) {
            LOG.fine("[AgentDelegate] checkSystemPackagesConfig: " + t.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Instrumenter interface — appelé depuis SqlCommenterRuntime (bootstrap)
    // -------------------------------------------------------------------------

    @Override
    public String instrument(String sql) {
        return ruleEngine.instrument(sql);
    }

    // -------------------------------------------------------------------------
    // Retransformation
    // -------------------------------------------------------------------------

    private static void retransformLoadedClasses(Instrumentation instrumentation,
                                                  JdbcClassTransformer transformer,
                                                  AgentConfig config) {
        if (!instrumentation.isRetransformClassesSupported()) {
            LOG.warning("[AgentDelegate] Retransformation non supportée par cette JVM");
            return;
        }

        java.util.List<Class<?>> targets = new java.util.ArrayList<Class<?>>();
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            String name = clazz.getName();
            if (!transformer.shouldTransform(name)) continue;
            if (clazz.isInterface() || clazz.isAnnotation() || clazz.isEnum()) continue;
            if (clazz.isSynthetic() || name.contains("$$") || name.contains("$Proxy")) continue;
            if (!instrumentation.isModifiableClass(clazz)) continue;
            targets.add(clazz);
        }

        LOG.info("[AgentDelegate] Retransformation de " + targets.size() + " classes JDBC...");

        // Log the exact class names being retransformed — critical for diagnosis.
        // Especially want to confirm WrappedConnection / WrappedPreparedStatement are present.
        StringBuilder classList = new StringBuilder("[AgentDelegate] Target classes:");
        for (Class<?> c : targets) classList.append("\n  - ").append(c.getName());
        LOG.info(classList.toString());

        int succeeded = 0, failed = 0;
        // Retransform ONE AT A TIME so a single failure doesn't abort a whole batch,
        // and we can log exactly which class failed (always, not just verbose).
        for (Class<?> c : targets) {
            try {
                instrumentation.retransformClasses(c);
                succeeded++;
            } catch (Throwable ex) {
                failed++;
                LOG.warning("[AgentDelegate] Retransform FAILED: " + c.getName()
                        + " — " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        LOG.info("[AgentDelegate] Retransformation terminée. succeeded=" + succeeded
                + " failed=" + failed);
    }
}
