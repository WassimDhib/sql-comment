package io.sqlcommenter.agent.jboss;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Intègre l'agent dans JBoss Modules en injectant le package
 * {@code io.sqlcommenter.agent.transformer} dans les tableaux statiques
 * {@code Module.systemPackages} et {@code Module.systemPaths} via réflexion.
 *
 * <h2>Mécanisme</h2>
 *
 * <p>JBoss Modules vérifie à chaque chargement de classe si le nom commence
 * par un des packages listés dans {@code Module.systemPackages}. Si oui, il
 * délègue au classloader système (dont le parent est le bootstrap). En ajoutant
 * notre package à ce tableau, on rend {@code SqlCommenterRuntime} visible depuis
 * TOUS les modules JBoss (IronJacamar, Oracle, etc.) sans modifier aucun fichier
 * de configuration ni redémarrer le serveur.
 *
 * <h2>Inspiration</h2>
 *
 * <p>Cette technique est utilisée par Byteman, Elastic APM, Datadog et d'autres
 * agents de production pour s'intégrer dans JBoss/WildFly sans modification
 * de la configuration du serveur.
 *
 * <h2>Sécurité</h2>
 *
 * <p>La modification est restreinte aux deux tableaux {@code String[]} du module
 * {@code org.jboss.modules.Module}. L'opération est idempotente (vérifie avant
 * d'ajouter) et thread-safe (les modules JBoss sont déjà démarrés, le tableau
 * est lu en lecture seule par la suite sauf pendant notre patch).
 */
public final class JBossModulesIntegration {

    private static final Logger LOG = Logger.getLogger(JBossModulesIntegration.class.getName());

    /** Package (suffixe ".") à injecter dans systemPackages. */
    private static final String PKG_DOT  = "io.sqlcommenter.agent.transformer.";
    /** Chemin (suffixe "/") correspondant pour systemPaths. */
    private static final String PKG_PATH = "io/sqlcommenter/agent/transformer/";

    private JBossModulesIntegration() {}

    /**
     * Injecte notre package dans {@code Module.systemPackages} et
     * {@code Module.systemPaths} via réflexion.
     *
     * <p>Si JBoss Modules n'est pas présent (Tomcat, Spring Boot…), retourne
     * silencieusement {@code false}.
     *
     * @return {@code true} si l'injection a réussi
     */
    /**
     * Charge org.jboss.modules.Module en essayant plusieurs classloaders.
     * Notre delegate URLClassLoader a parent=bootstrap et ne voit pas jboss-modules.jar.
     * Le system classloader et le TCCL le voient car JBoss les configure ainsi.
     */
    private static Class<?> loadModuleClass() {
        String className = "org.jboss.modules.Module";
        // 1. TCCL — dans JBoss, l'Attach Listener thread peut avoir un CL qui voit Module
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) {
            try { return Class.forName(className, true, tccl); }
            catch (ClassNotFoundException ignored) {}
        }
        // 2. System classloader — jboss-modules.jar est souvent sur le system CP
        ClassLoader system = ClassLoader.getSystemClassLoader();
        if (system != null && system != tccl) {
            try { return Class.forName(className, true, system); }
            catch (ClassNotFoundException ignored) {}
        }
        // 3. Bootstrap (parent classloader) via Class.forName standard
        try { return Class.forName(className); }
        catch (ClassNotFoundException ignored) {}
        return null;
    }

        public static boolean configure() {
        try {
            // Must use a classloader that can see jboss-modules.jar.
            // Our delegate URLClassLoader has parent=bootstrap which cannot.
            // The system classloader (or TCCL) CAN, as JBoss adds jboss-modules.jar there.
            Class<?> moduleClass = loadModuleClass();
            if (moduleClass == null) {
                LOG.fine("[JBossModules] org.jboss.modules.Module introuvable — pas dans JBoss");
                return false;
            }
            LOG.info("[JBossModules] JBoss Modules détecté — injection du package système...");

            boolean pkgOk  = injectIntoArray(moduleClass, "systemPackages", PKG_DOT);
            boolean pathOk = injectIntoArray(moduleClass, "systemPaths",    PKG_PATH);

            if (pkgOk && pathOk) {
                LOG.info("[JBossModules] Package " + PKG_DOT.substring(0, PKG_DOT.length() - 1)
                        + " ajouté aux system packages de JBoss Modules. "
                        + "SqlCommenterRuntime est maintenant visible depuis tous les modules.");
                return true;
            } else if (pkgOk || pathOk) {
                LOG.warning("[JBossModules] Injection partielle (pkgs=" + pkgOk + " paths=" + pathOk + ")");
                return false;
            } else {
                LOG.warning("[JBossModules] Package déjà présent ou injection ignorée.");
                return true; // déjà présent = OK
            }

        } catch (Throwable t) {
            LOG.warning("[JBossModules] Échec de l'injection : " + t.getClass().getSimpleName()
                    + ": " + t.getMessage()
                    + " — essayer -Djboss.modules.system.pkgs=...,io.sqlcommenter.agent.transformer");
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Réflexion sur le tableau static final
    // -------------------------------------------------------------------------

    /**
     * Remplace {@code Module.<fieldName>} par une nouvelle copie du tableau
     * contenant l'entrée supplémentaire {@code entry}, si elle n'y est pas déjà.
     *
     * <p>La technique « static final field replacement » via réflexion fonctionne
     * sur JDK 8 et JDK 11. Sur JDK 17+, l'accès aux champs {@code final} static
     * d'autres modules est restreint sauf si {@code --add-opens} est fourni.
     * On tente d'abord sans add-opens (JDK 8/11), puis avec Unsafe (JDK 17+).
     */
    private static boolean injectIntoArray(Class<?> moduleClass, String fieldName, String entry)
            throws Exception {

        Field field = moduleClass.getDeclaredField(fieldName);
        field.setAccessible(true);

        String[] current = (String[]) field.get(null);

        // Idempotence : déjà présent ?
        for (String s : current) {
            if (s.equals(entry)) {
                LOG.fine("[JBossModules] " + fieldName + " contient déjà " + entry);
                return false; // false = "rien fait" mais pas une erreur
            }
        }

        // Créer le nouveau tableau
        String[] extended = Arrays.copyOf(current, current.length + 1);
        extended[current.length] = entry.intern();

        // Tenter de remplacer le champ static final
        // Méthode 1 : Field.set() standard (JDK 8/11)
        try {
            trySetStaticFinal(field, extended);
            return true;
        } catch (Exception e1) {
            LOG.fine("[JBossModules] Field.set() échoué (" + e1.getMessage() + "), tentative Unsafe...");
        }

        // Méthode 2 : sun.misc.Unsafe.putObject() (JDK 17+)
        try {
            setViaUnsafe(moduleClass, fieldName, extended);
            return true;
        } catch (Exception e2) {
            LOG.warning("[JBossModules] Unsafe échoué : " + e2.getMessage());
            throw e2;
        }
    }

    /**
     * Méthode classique pour JDK 8/11 : rend le champ non-final via réflexion
     * sur le champ {@code modifiers}, puis appelle {@code Field.set()}.
     */
    private static void trySetStaticFinal(Field field, Object value) throws Exception {
        // Supprimer le modificateur FINAL pour permettre la modification
        try {
            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (Exception e) {
            // JDK 17+ bloque l'accès à Field.modifiers → essayer quand même set()
        }
        field.set(null, value);
    }

    /**
     * Méthode alternative via {@code sun.misc.Unsafe} pour JDK 17+.
     * {@code Unsafe.putObject()} ignore les restrictions du module system.
     */
    private static void setViaUnsafe(Class<?> targetClass, String fieldName, Object value)
            throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);

        Field field = targetClass.getDeclaredField(fieldName);
        field.setAccessible(true);

        // Unsafe.staticFieldOffset(Field)
        Long offset = (Long) unsafeClass.getMethod("staticFieldOffset", Field.class)
                .invoke(unsafe, field);

        // Unsafe.staticFieldBase(Field) — returns the Class for static fields
        Object base = unsafeClass.getMethod("staticFieldBase", Field.class)
                .invoke(unsafe, field);

        // Unsafe.putObjectVolatile(Object, long, Object) — thread-safe write
        unsafeClass.getMethod("putObjectVolatile", Object.class, long.class, Object.class)
                .invoke(unsafe, base, offset, value);
    }
}
