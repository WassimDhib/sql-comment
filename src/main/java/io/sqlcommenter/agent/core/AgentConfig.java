package io.sqlcommenter.agent.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.logging.Logger;

/**
 * Configuration immutable de l'agent SQL Commenter.
 *
 * <h2>Préfixes JDBC par défaut</h2>
 * <p>Les drivers JDBC natifs (oracle.jdbc.*, com.mysql.*, etc.) sont exclus des préfixes
 * par défaut sur JBoss/WildFly. En environnement JBoss Modules, chaque driver est chargé
 * dans son propre module isolé qui ne voit pas {@code SqlCommenterRuntime} — ce qui
 * provoque un {@code ClassNotFoundException} au moment de l'exécution du bytecode transformé.
 *
 * <p>Sur JBoss/WildFly, l'interception se fait au niveau des <strong>wrappers IronJacamar</strong>
 * ({@code org.jboss.jca.adapters.jdbc.*}) qui sont dans la couche système et voient le bootstrap.
 * Ces wrappers appellent le driver en interne, donc on capture bien toutes les requêtes.
 *
 * <p>Pour activer les drivers directement (environnements non-modulaires comme Tomcat,
 * Spring Boot, etc.), ajouter les préfixes via la propriété {@code jdbc_prefixes}.
 */
public final class AgentConfig {

    private static final Logger LOG = Logger.getLogger(AgentConfig.class.getName());

    public static final String KEY_CONFIG_FILE   = "config";
    public static final String KEY_RULES         = "rules";
    public static final String KEY_FORMAT        = "format";
    public static final String KEY_VERBOSE       = "verbose";
    public static final String KEY_JDBC_PREFIXES = "jdbc_prefixes";
    public static final String KEY_STATIC_TAGS   = "static_tags";

    public static final String FORMAT_URL_ENCODED = "url_encoded";
    public static final String FORMAT_PLAIN       = "plain";

    /**
     * Préfixes JDBC instrumentés par défaut.
     *
     * <p><strong>Stratégie JBoss/WildFly :</strong> on instrumente les wrappers IronJacamar
     * ({@code org.jboss.jca.adapters.jdbc.*}) plutôt que les drivers natifs. Ces wrappers :
     * <ul>
     *   <li>Sont dans la couche système JBoss (accès au bootstrap classloader)</li>
     *   <li>Wrappent TOUS les drivers configurés (Oracle, PostgreSQL, MySQL…)</li>
     *   <li>Exposent {@code prepareStatement}, {@code prepareCall}, {@code execute*}</li>
     * </ul>
     *
     * <p><strong>Pools de connexions :</strong> HikariCP, DBCP2, c3p0 sont inclus car
     * ils s'exécutent dans le classloader applicatif qui voit le bootstrap.
     *
     * <p><strong>Drivers natifs exclus par défaut :</strong> oracle.jdbc.*, com.mysql.*,
     * org.postgresql.*, etc. Ajouter via {@code jdbc_prefixes} si nécessaire (environnements
     * non-modulaires uniquement).
     */
    private static final List<String> DEFAULT_JDBC_PREFIXES;
    static {
        List<String> prefixes = new ArrayList<String>();
        // ── JBoss / WildFly — wrappers IronJacamar (couche système, voit le bootstrap) ──
        prefixes.add("org.jboss.jca.adapters.jdbc.");    // BaseWrapperManagedConnection, etc.
        prefixes.add("org.jboss.jca.adapters.jdbc.local.");
        prefixes.add("org.jboss.jca.adapters.jdbc.xa.");
        prefixes.add("org.jboss.ironjacamar.");          // IronJacamar standalone
        prefixes.add("org.jboss.as.connector.");         // WildFly datasource subsystem
        // ── Connection pools (classloader applicatif, délèguent au bootstrap) ─────────
        prefixes.add("com.zaxxer.hikari.");
        prefixes.add("org.apache.tomcat.jdbc.");
        prefixes.add("org.apache.commons.dbcp2.");
        prefixes.add("com.mchange.v2.c3p0.");
        // NOTE : oracle.jdbc.*, com.mysql.*, org.postgresql.* etc. sont intentionnellement
        // EXCLUS car leurs modules JBoss ne voient pas SqlCommenterRuntime.
        // Pour les activer sur un environnement non-modulaire (Tomcat, Spring Boot) :
        //   jdbc_prefixes=org.jboss.jca.adapters.jdbc.,com.zaxxer.hikari.,oracle.jdbc.,org.postgresql.
        DEFAULT_JDBC_PREFIXES = Collections.unmodifiableList(prefixes);
    }

    private final Properties props;

    private AgentConfig(Properties props) { this.props = props; }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static AgentConfig parse(String agentArgs) {
        Properties merged = new Properties();
        loadClasspathDefaults(merged);
        Properties inline = parseAgentArgs(agentArgs);
        String configFile = inline.getProperty(KEY_CONFIG_FILE, merged.getProperty(KEY_CONFIG_FILE));
        if (configFile != null) loadExternalFile(merged, configFile);
        merged.putAll(inline);
        return new AgentConfig(merged);
    }

    // -------------------------------------------------------------------------
    // Accesseurs
    // -------------------------------------------------------------------------

    public String get(String key)                  { return props.getProperty(key); }
    public String get(String key, String def)      { return props.getProperty(key, def); }
    public boolean getBoolean(String key, boolean def) {
        String v = props.getProperty(key);
        return v == null ? def : "true".equalsIgnoreCase(v.trim());
    }

    public boolean isVerbose()    { return getBoolean(KEY_VERBOSE, false); }
    public String  getFormat()    { return get(KEY_FORMAT, FORMAT_URL_ENCODED); }
    public boolean isUrlEncoded() { return FORMAT_URL_ENCODED.equalsIgnoreCase(getFormat()); }

    public Set<String> getEnabledRuleIds() {
        String raw = get(KEY_RULES);
        if (raw == null || raw.trim().isEmpty()) return Collections.emptySet();
        Set<String> ids = new LinkedHashSet<String>();
        for (String id : raw.split(",")) { String t = id.trim(); if (!t.isEmpty()) ids.add(t); }
        return Collections.unmodifiableSet(ids);
    }

    public Map<String, String> getStaticTags() {
        String raw = get(KEY_STATIC_TAGS);
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyMap();
        Map<String, String> tags = new LinkedHashMap<String, String>();
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) tags.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return Collections.unmodifiableMap(tags);
    }

    public List<String> getJdbcPrefixes() {
        String raw = get(KEY_JDBC_PREFIXES);
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_JDBC_PREFIXES;
        List<String> prefixes = new ArrayList<String>();
        for (String p : raw.split(",")) { String t = p.trim(); if (!t.isEmpty()) prefixes.add(t); }
        return Collections.unmodifiableList(prefixes);
    }

    public Properties getRawProperties() { return new Properties(props); }

    // -------------------------------------------------------------------------
    // Parsing args
    // -------------------------------------------------------------------------

    public static Properties parseAgentArgs(String agentArgs) {
        Properties props = new Properties();
        if (agentArgs == null || agentArgs.trim().isEmpty()) return props;
        String[] tokens = agentArgs.split(",(?=[\\w.]+\\=)");
        for (String token : tokens) {
            int eq = token.indexOf('=');
            if (eq > 0) props.setProperty(token.substring(0, eq).trim(), token.substring(eq + 1).trim());
        }
        return props;
    }

    private static void loadClasspathDefaults(Properties target) {
        try (InputStream is = AgentConfig.class.getClassLoader()
                .getResourceAsStream("sqlcommenter.properties")) {
            if (is != null) target.load(is);
        } catch (Exception e) {
            LOG.fine("[AgentConfig] Pas de sqlcommenter.properties sur le classpath");
        }
    }

    private static void loadExternalFile(Properties target, String path) {
        File file = new File(path);
        if (!file.exists()) { LOG.warning("[AgentConfig] Fichier introuvable : " + path); return; }
        try (FileInputStream fis = new FileInputStream(file)) {
            target.load(fis);
            LOG.info("[AgentConfig] Config chargée : " + path);
        } catch (Exception e) {
            LOG.warning("[AgentConfig] Échec lecture " + path + " : " + e.getMessage());
        }
    }
}
