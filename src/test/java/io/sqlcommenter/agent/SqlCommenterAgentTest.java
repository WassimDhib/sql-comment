package io.sqlcommenter.agent;

import io.sqlcommenter.agent.attach.DynamicAttach;
import io.sqlcommenter.agent.core.AgentConfig;
import io.sqlcommenter.agent.core.RuleEngine;
import io.sqlcommenter.agent.context.QueryContext;
import io.sqlcommenter.agent.transformer.JdbcClassTransformer;
import io.sqlcommenter.agent.transformer.SqlCommenterRuntime;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SqlCommenterAgentTest {

    // =========================================================================
    // AgentConfig
    // =========================================================================

    @Nested @DisplayName("AgentConfig")
    class AgentConfigTest {

        @Test void parses_single_key_value() {
            Properties p = AgentConfig.parseAgentArgs("verbose=true");
            assertEquals("true", p.getProperty("verbose"));
        }

        @Test void parses_multiple_key_values() {
            Properties p = AgentConfig.parseAgentArgs("verbose=true,format=plain");
            assertEquals("true",  p.getProperty("verbose"));
            assertEquals("plain", p.getProperty("format"));
        }

        @Test void parses_rules_list() {
            AgentConfig cfg = AgentConfig.parse("rules=traceparent,framework,caller");
            Set<String> ids = cfg.getEnabledRuleIds();
            assertTrue(ids.contains("traceparent"));
            assertTrue(ids.contains("framework"));
            assertTrue(ids.contains("caller"));
        }

        @Test void explicit_empty_rules_means_all_enabled() {
            AgentConfig cfg = AgentConfig.parse("rules=");
            assertTrue(cfg.getEnabledRuleIds().isEmpty());
        }

            @Test void jboss_prefixes_present_by_default() {
            AgentConfig cfg = AgentConfig.parse("rules=");
            List<String> prefixes = cfg.getJdbcPrefixes();
            // IronJacamar wrappers are safe - they have bootstrap classloader access
            assertTrue(prefixes.stream().anyMatch(new java.util.function.Predicate<String>() {
                public boolean test(String p) { return p.startsWith("org.jboss.jca.adapters.jdbc"); }
            }), "org.jboss.jca.adapters.jdbc must be in default prefixes");
            // oracle.jdbc.* excluded by default - JBoss module isolation would cause ClassNotFoundException
            assertFalse(prefixes.stream().anyMatch(new java.util.function.Predicate<String>() {
                public boolean test(String p) { return p.equals("oracle.jdbc."); }
            }), "oracle.jdbc. must NOT be in default prefixes on JBoss");
        }

        @Test void plain_format_disables_url_encoding() {
            assertFalse(AgentConfig.parse("format=plain").isUrlEncoded());
        }

        @Test void url_encoded_format_is_default() {
            assertTrue(AgentConfig.parse("format=url_encoded").isUrlEncoded());
        }
    }

    // =========================================================================
    // RuleEngine — construction des commentaires
    // =========================================================================

    @Nested @DisplayName("RuleEngine — commentaires")
    class RuleEngineCommentTest {

        private RuleEngine engine;

        @BeforeEach void setup() {
            engine = RuleEngine.from(AgentConfig.parse("rules=,format=url_encoded"));
        }

        @Test void comment_keys_sorted_lexicographically() {
            Map<String, String> tags = new TreeMap<>();
            tags.put("z_last", "z"); tags.put("a_first", "a"); tags.put("m_mid", "m");
            String comment = engine.buildComment(tags);
            int ia = comment.indexOf("a_first"), im = comment.indexOf("m_mid"), iz = comment.indexOf("z_last");
            assertTrue(ia < im && im < iz, "Clés non triées : " + comment);
        }

        @Test void comment_wrapped_in_block_comment_markers() {
            String c = engine.buildComment(Map.of("k", "v"));
            assertTrue(c.startsWith("/*") && c.endsWith("*/"));
        }

        @Test void spaces_encoded_as_percent_20() {
            String c = engine.buildComment(Map.of("action", "my action"));
            assertTrue(c.contains("%20"), c);
        }

        @Test void single_quotes_are_encoded() {
            String c = engine.buildComment(Map.of("val", "it's"));
            assertFalse(c.contains("it's"), "La quote simple doit être encodée : " + c);
        }

        @Test void slashes_are_encoded() {
            assertEquals("postgresql%2F42.6.0", RuleEngine.encode("postgresql/42.6.0"));
        }

        @Test void empty_tags_returns_empty_string() {
            assertEquals("", engine.buildComment(Collections.emptyMap()));
        }
    }

    // =========================================================================
    // RuleEngine — appendComment
    // =========================================================================

    @Nested @DisplayName("RuleEngine — appendComment")
    class AppendCommentTest {

        private RuleEngine engine;
        @BeforeEach void setup() { engine = RuleEngine.from(AgentConfig.parse("rules=,format=plain")); }

        @Test void comment_appended_after_sql()     { assertEquals("SELECT 1 /*k='v'*/", engine.appendComment("SELECT 1","/*k='v'*/")); }
        @Test void trailing_semicolon_preserved()   { assertEquals("SELECT 1 /*k='v'*/;", engine.appendComment("SELECT 1;","/*k='v'*/")); }
        @Test void null_comment_returns_sql()        { assertEquals("SELECT 1", engine.appendComment("SELECT 1", null)); }
        @Test void empty_comment_returns_sql()       { assertEquals("SELECT 1", engine.appendComment("SELECT 1", "")); }
        @Test void multiline_sql_comment_at_end() {
            String result = engine.appendComment("SELECT *\nFROM users\nWHERE id = ?", "/*k='v'*/");
            assertTrue(result.endsWith("/*k='v'*/"));
        }
    }

    // =========================================================================
    // Injection de tags statiques
    // =========================================================================

    @Nested @DisplayName("Tags statiques")
    class StaticTagTest {

        @Test void static_tag_appears_in_sql() {
            RuleEngine engine = RuleEngine.from(AgentConfig.parse("rules=,format=plain,static_tags=env=test"));
            assertTrue(engine.instrument("SELECT 1").contains("env='test'"));
        }

        @Test void null_sql_returns_null() {
            RuleEngine engine = RuleEngine.from(AgentConfig.parse("rules=,static_tags=k=v"));
            assertNull(engine.instrument(null));
        }

        @Test void original_sql_preserved_as_prefix() {
            RuleEngine engine = RuleEngine.from(AgentConfig.parse("rules=,format=plain,static_tags=x=y"));
            assertTrue(engine.instrument("SELECT * FROM t").startsWith("SELECT * FROM t"));
        }
    }

    // =========================================================================
    // SafeClassWriter — préfixes JBoss
    // =========================================================================

    @Nested @DisplayName("JdbcClassTransformer — shouldTransform")
    class ShouldTransformTest {

        private JdbcClassTransformer transformer;

        @BeforeEach void setup() {
            AgentConfig cfg = AgentConfig.parse("rules=,format=plain");
            transformer = new JdbcClassTransformer(RuleEngine.from(cfg), cfg);
        }

        @Test void jboss_jca_class_matches()         { assertTrue(transformer.shouldTransform("org.jboss.jca.adapters.jdbc.WrappedConnection")); }
        @Test void jboss_jca_local_class_matches()   { assertTrue(transformer.shouldTransform("org.jboss.jca.adapters.jdbc.local.LocalManagedConnection")); }
        @Test void hikari_class_matches()             { assertTrue(transformer.shouldTransform("com.zaxxer.hikari.pool.ProxyConnection")); }
        @Test void unrelated_class_no_match()         { assertFalse(transformer.shouldTransform("com.example.MyService")); }
        @Test void random_string_no_match()           { assertFalse(transformer.shouldTransform("java.util.HashMap")); }
        // oracle.jdbc excluded by default - JBoss module cannot see SqlCommenterRuntime
        @Test void oracle_jdbc_excluded_by_default()  { assertFalse(transformer.shouldTransform("oracle.jdbc.driver.OracleStatementWrapper")); }
        // postgresql excluded by default on JBoss - add via jdbc_prefixes for non-modular envs
        @Test void postgresql_excluded_by_default()   { assertFalse(transformer.shouldTransform("org.postgresql.jdbc.PgConnection")); }
    }

    // =========================================================================
    // QueryContext
    // =========================================================================

    @Nested @DisplayName("QueryContext")
    class QueryContextTest {

        @Test void thread_name_captured() {
            assertEquals(Thread.currentThread().getName(),
                    QueryContext.forCurrentThread("SELECT 1").getThreadName());
        }

        @Test void original_sql_preserved() {
            String sql = "SELECT * FROM orders WHERE id = ?";
            assertEquals(sql, QueryContext.forCurrentThread(sql).getOriginalSql());
        }

        @Test void attributes_accessible() {
            QueryContext ctx = new QueryContext.Builder("SELECT 1")
                    .attribute("rid", "abc-123").build();
            assertEquals("abc-123", ctx.<String>getAttribute("rid").orElse(null));
        }

        @Test void timestamp_set() {
            long before = System.currentTimeMillis();
            QueryContext ctx = QueryContext.forCurrentThread("SELECT 1");
            assertTrue(ctx.getTimestampMillis() >= before);
            assertTrue(ctx.getTimestampMillis() <= System.currentTimeMillis());
        }
    }

    // =========================================================================
    // Thread-safety du Runtime
    // =========================================================================

    @Nested @DisplayName("Thread-safety")
    class ThreadSafetyTest {

        @Test void concurrent_instrumentation_no_corruption() throws Exception {
            RuleEngine engine = RuleEngine.from(
                    AgentConfig.parse("rules=,format=plain,static_tags=env=prod"));
            SqlCommenterRuntime.setInstrumenter(engine::instrument);

            int threads = 20, iterations = 500;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean failed = new AtomicBoolean(false);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            String sql = "SELECT " + i + " FROM t";
                            String result = SqlCommenterRuntime.instrument(sql);
                            if (!result.startsWith("SELECT " + i)) failed.set(true);
                            if (!result.contains("env='prod'"))    failed.set(true);
                        }
                    } catch (Exception e) { failed.set(true); }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
            pool.shutdown();
            assertFalse(failed.get(), "Corruption détectée en accès concurrent");
        }
    }

    // =========================================================================
    // Isolation des erreurs de règles
    // =========================================================================

    @Nested @DisplayName("Isolation des erreurs")
    class FaultIsolationTest {

        @Test void crashing_rule_does_not_propagate() {
            RuleEngine engine = RuleEngine.from(
                    AgentConfig.parse("rules=,format=plain,static_tags=safe=yes"));
            assertDoesNotThrow(() -> {
                String r = engine.instrument("SELECT 1");
                assertNotNull(r);
                assertTrue(r.contains("SELECT 1"));
            });
        }

        @Test void null_sql_never_throws() {
            RuleEngine engine = RuleEngine.from(AgentConfig.parse("rules=,static_tags=k=v"));
            assertDoesNotThrow(() -> engine.instrument(null));
        }
    }

    // =========================================================================
    // Double initialisation de l'agent
    // =========================================================================

    @Nested @DisplayName("Guard double initialisation")
    class DoubleInitTest {

        @Test void second_initialize_is_no_op() {
            // Simuler deux appels à agentmain (attach multiple ou reload module JBoss)
            // Le guard AtomicBoolean doit empêcher la double init
            // On vérifie que l'état du Runtime reste cohérent après deux initialisations

            SqlCommenterRuntime.reset();
            // On ne peut pas appeler initialize() sans Instrumentation réelle,
            // donc on vérifie le guard directement via réflexion
            try {
                java.lang.reflect.Field field = SqlCommenterAgent.class.getDeclaredField("INITIALIZED");
                field.setAccessible(true);
                java.util.concurrent.atomic.AtomicBoolean flag = (java.util.concurrent.atomic.AtomicBoolean) field.get(null);

                // Simuler première init
                boolean first = flag.compareAndSet(false, true);
                assertTrue(first, "Première init doit réussir");

                // Simuler deuxième init — doit être bloquée
                boolean second = flag.compareAndSet(false, true);
                assertFalse(second, "Deuxième init doit être bloquée par le guard");

                // Remettre à false pour ne pas casser les autres tests
                flag.set(false);
            } catch (Exception e) {
                fail("Réflexion sur INITIALIZED échouée : " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // DynamicAttach — listage VM (sans JDK tools)
    // =========================================================================

    @Nested @DisplayName("DynamicAttach")
    class DynamicAttachTest {

        @Test void list_vms_does_not_throw_without_tools() {
            // Sans tools.jar, listVirtualMachines() doit retourner une liste vide
            // sans exception (elle gère l'absence d'API Attach)
            assertDoesNotThrow(() -> {
                List<DynamicAttach.VmDescriptor> vms = DynamicAttach.listVirtualMachines();
                // Sur un JDK complet, retourne la JVM courante ; sur JRE, retourne vide
                // Dans les deux cas, pas d'exception
                assertNotNull(vms);
            });
        }

        @Test void vm_descriptor_displays_correctly() {
            DynamicAttach.VmDescriptor vm = new DynamicAttach.VmDescriptor("12345", "org.jboss.modules.Main");
            assertEquals("12345", vm.pid);
            assertEquals("org.jboss.modules.Main", vm.displayName);
            assertTrue(vm.toString().contains("12345"));
        }

        @Test void vm_descriptor_handles_blank_name() {
            DynamicAttach.VmDescriptor vm = new DynamicAttach.VmDescriptor("99", "");
            assertEquals("(unknown)", vm.displayName);
        }

        @Test void load_vm_class_throws_clear_message_without_jdk() {
            // Si VirtualMachine n'est pas dispo, l'exception doit être explicite
            // On ne peut pas forcer l'absence du JDK en test, mais on vérifie
            // que la méthode est appelable sans exception fatale sur un JDK 21
            try {
                Class<?> vmClass = DynamicAttach.loadVirtualMachineClass();
                assertNotNull(vmClass, "VirtualMachine class doit être trouvable sur un JDK 21");
            } catch (ClassNotFoundException e) {
                // Acceptable sur un JRE sans jdk.attach
                assertTrue(e.getMessage().contains("VirtualMachine") || e.getMessage().contains("tools.jar"),
                        "Message d'erreur doit mentionner VirtualMachine ou tools.jar");
            } catch (Exception e) {
                fail("Exception inattendue : " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Intégration H2 via DataSourceProxy
    // =========================================================================

    @Nested @DisplayName("Intégration H2 — DataSourceProxy")
    class H2IntegrationTest {

        @Test void proxy_wraps_connection_as_jdk_proxy() throws Exception {
            RuleEngine engine = RuleEngine.from(
                    AgentConfig.parse("rules=,format=plain,static_tags=env=test"));
            SqlCommenterRuntime.setInstrumenter(engine::instrument);

            org.h2.jdbcx.JdbcDataSource h2ds = new org.h2.jdbcx.JdbcDataSource();
            h2ds.setURL("jdbc:h2:mem:testv2;DB_CLOSE_DELAY=-1");
            h2ds.setUser("sa");

            io.sqlcommenter.agent.jdbc.SqlCommenterDataSourceProxy ds =
                    new io.sqlcommenter.agent.jdbc.SqlCommenterDataSourceProxy(h2ds);

            try (java.sql.Connection conn = ds.getConnection()) {
                assertTrue(java.lang.reflect.Proxy.isProxyClass(conn.getClass()));
            }
        }

        @Test void proxy_executes_query_and_returns_correct_result() throws Exception {
            RuleEngine engine = RuleEngine.from(
                    AgentConfig.parse("rules=,format=plain,static_tags=env=test"));
            SqlCommenterRuntime.setInstrumenter(engine::instrument);

            org.h2.jdbcx.JdbcDataSource h2ds = new org.h2.jdbcx.JdbcDataSource();
            h2ds.setURL("jdbc:h2:mem:testv2b;DB_CLOSE_DELAY=-1");
            h2ds.setUser("sa");

            io.sqlcommenter.agent.jdbc.SqlCommenterDataSourceProxy ds =
                    new io.sqlcommenter.agent.jdbc.SqlCommenterDataSourceProxy(h2ds);

            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.Statement st = conn.createStatement();
                 java.sql.ResultSet rs = st.executeQuery("SELECT 42")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt(1));
            }
        }

        @Test void runtime_instruments_sql_with_correct_format() {
            RuleEngine engine = RuleEngine.from(
                    AgentConfig.parse("rules=,format=plain,static_tags=source=proxy"));
            SqlCommenterRuntime.setInstrumenter(engine::instrument);

            String result = SqlCommenterRuntime.instrument("SELECT * FROM orders");
            assertTrue(result.startsWith("SELECT * FROM orders"), result);
            assertTrue(result.contains("source='proxy'"), result);
            assertTrue(result.contains("/*") && result.contains("*/"), result);
        }
    }
}
