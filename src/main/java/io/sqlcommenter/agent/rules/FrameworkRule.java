package io.sqlcommenter.agent.rules;

import io.sqlcommenter.agent.context.QueryContext;
import java.util.*;

/**
 * Détecte le framework actif depuis la pile d'appel.
 *
 * <p>Inclut les frameworks JBoss/WildFly :
 * <ul>
 *   <li>{@code org.jboss.ejb} — EJB 3.x via WildFly</li>
 *   <li>{@code org.jboss.seam} — Seam Framework</li>
 *   <li>{@code io.quarkus} — Quarkus (basé sur WildFly Swarm/MicroProfile)</li>
 * </ul>
 */
public class FrameworkRule implements CommentRule {

    private String overrideFramework;

    @Override public String getId() { return "framework"; }
    @Override public String getDescription() { return "Détecte le framework actif (Spring, Hibernate, JBoss EJB…)"; }

    @Override
    public void configure(Properties props) { overrideFramework = props.getProperty("rule.framework.name"); }

    @Override
    public Map<String, String> extract(QueryContext ctx) {
        if (overrideFramework != null) return Collections.singletonMap("framework", overrideFramework);
        for (StackTraceElement frame : ctx.getStackTrace()) {
            String fw = detectFramework(frame.getClassName());
            if (fw != null) return Collections.singletonMap("framework", fw);
        }
        return Collections.emptyMap();
    }

    private static String detectFramework(String cls) {
        // ── Spring ──────────────────────────────────────────────────────────
        if (cls.startsWith("org.springframework.web"))        return "spring_web";
        if (cls.startsWith("org.springframework.data"))       return "spring_data";
        if (cls.startsWith("org.springframework"))            return "spring";
        // ── JBoss / WildFly ─────────────────────────────────────────────────
        if (cls.startsWith("org.jboss.ejb"))                  return "jboss_ejb";
        if (cls.startsWith("org.jboss.seam"))                 return "seam";
        if (cls.startsWith("org.jboss.as"))                   return "wildfly";
        if (cls.startsWith("io.quarkus"))                     return "quarkus";
        // ── Persistance ─────────────────────────────────────────────────────
        if (cls.startsWith("org.hibernate"))                  return "hibernate";
        if (cls.startsWith("org.mybatis"))                    return "mybatis";
        if (cls.startsWith("org.jooq"))                       return "jooq";
        if (cls.startsWith("com.querydsl"))                   return "querydsl";
        if (cls.startsWith("jakarta.persistence") ||
            cls.startsWith("javax.persistence"))              return "jpa";
        // ── Autres ──────────────────────────────────────────────────────────
        if (cls.startsWith("io.micronaut"))                   return "micronaut";
        if (cls.startsWith("com.baomidou.mybatisplus"))       return "mybatis_plus";
        return null;
    }
}
