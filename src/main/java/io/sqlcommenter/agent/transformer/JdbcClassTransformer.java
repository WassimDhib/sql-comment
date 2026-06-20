package io.sqlcommenter.agent.transformer;

import io.sqlcommenter.agent.core.AgentConfig;
import io.sqlcommenter.agent.core.RuleEngine;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * ASM bytecode transformer for JDBC instrumentation.
 *
 * <h2>Strategy: COMPUTE_FRAMES + always-Object ClassWriter + AdviceAdapter</h2>
 * <p>This is the third and final iteration of the transformer design.
 * See class history in git for why COMPUTE_MAXS and SafeClassWriter approaches failed.
 *
 * <h2>Stack consistency in the injected try-catch</h2>
 * <p>Each execution path reaching the "after" label must have the same stack depth.
 * The try-catch is structured so that ALL catch paths POP the exception before
 * jumping to "after", leaving an empty stack at "after" regardless of path taken.
 */
public final class JdbcClassTransformer implements ClassFileTransformer {

    private static final Logger LOG = Logger.getLogger(JdbcClassTransformer.class.getName());

    static final String RUNTIME_CLASS =
            "io/sqlcommenter/agent/transformer/SqlCommenterRuntime";

    private static final Set<String> TARGET_METHODS = Collections.unmodifiableSet(
        new HashSet<String>(Arrays.asList(
            "execute", "executeQuery", "executeUpdate", "executeLargeUpdate",
            "prepareStatement", "prepareCall", "nativeSQL"
        ))
    );

    private final AgentConfig config;
    private final List<String> jdbcPrefixes;
    private final Set<String> alreadyTransformed =
        Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    public JdbcClassTransformer(RuleEngine ruleEngine, AgentConfig config) {
        this.config       = config;
        this.jdbcPrefixes = config.getJdbcPrefixes();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain pd,
                            byte[] classfileBuffer) throws IllegalClassFormatException {

        if (className == null || classfileBuffer == null) return null;
        String dotName = className.replace('/', '.');
        if (!shouldTransform(dotName)) return null;
        if (classBeingRedefined == null && !alreadyTransformed.add(className)) return null;

        try {
            byte[] result = rewrite(classfileBuffer, dotName);
            if (result != null && config.isVerbose())
                LOG.fine("[JdbcTransformer] Transformed: " + dotName);
            return result;
        } catch (Throwable e) {
            LOG.warning("[JdbcTransformer] Failed: " + dotName + " — "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            alreadyTransformed.remove(className);
            return null;
        }
    }

    public boolean shouldTransform(String dotClassName) {
        for (String prefix : jdbcPrefixes)
            if (dotClassName.startsWith(prefix)) return true;
        return false;
    }

    private byte[] rewrite(byte[] original, String className) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String t1, String t2) {
                // Always return Object — avoids any class loading attempt.
                // Valid because Object is always a correct common supertype.
                return "java/lang/Object";
            }
        };
        cr.accept(new SqlInjectingClassVisitor(cw, className, config.isVerbose()),
                ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    // -------------------------------------------------------------------------
    // ClassVisitor
    // -------------------------------------------------------------------------

    private static final class SqlInjectingClassVisitor extends ClassVisitor {
        private final String className;
        private final boolean verbose;
        private boolean isInterface;
        private int injectedCount = 0;

        SqlInjectingClassVisitor(ClassVisitor cv, String cls, boolean v) {
            super(Opcodes.ASM9, cv); this.className = cls; this.verbose = v;
        }

        @Override
        public void visitEnd() {
            if (injectedCount > 0)
                LOG.info("[JdbcTransformer] " + className + ": " + injectedCount + " method(s) instrumented");
            super.visitEnd();
        }

        @Override
        public void visit(int v, int acc, String n, String s, String sup, String[] i) {
            isInterface = (acc & Opcodes.ACC_INTERFACE) != 0;
            super.visit(v, acc, n, s, sup, i);
        }

        @Override
        public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
            MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
            boolean isAbstract = (acc & Opcodes.ACC_ABSTRACT) != 0;
            boolean isNative   = (acc & Opcodes.ACC_NATIVE)   != 0;
            if (!isInterface && !isAbstract && !isNative
                    && desc.startsWith("(Ljava/lang/String;")
                    && TARGET_METHODS.contains(name)) {
                // INFO-level log so we can confirm exactly which methods get instrumented
                LOG.info("[JdbcTransformer] INJECT: " + className + "#" + name + desc);
                injectedCount++;
                return new DefensiveInjectingMethodVisitor(mv, acc, name, desc,
                        className.replace('/', '.'));
            }
            // Log SKIP only for methods that take a String somewhere AND match a target name
            // (avoids noise from unrelated execute(OperationContext,ModelNode) methods)
            if (!isInterface && TARGET_METHODS.contains(name) && desc.contains("Ljava/lang/String;")) {
                LOG.info("[JdbcTransformer] SKIP " + className + "#" + name + desc
                    + " (not first-param String)");
            }
            return mv;
        }
    }

    // -------------------------------------------------------------------------
    // MethodVisitor — AdviceAdapter + try-catch with consistent stack
    // -------------------------------------------------------------------------

    /**
     * Injects at method entry (using AdviceAdapter + COMPUTE_FRAMES):
     *
     * <pre>
     *   Label tryStart, tryEnd, catchNCDFE, warnTry, warnTryEnd, warnCatch, after
     *
     *   TRY[tryStart..tryEnd] CATCH NoClassDefFoundError → catchNCDFE
     *   TRY[warnTry..warnTryEnd] CATCH Throwable → warnCatch
     *
     *   tryStart:
     *     arg0 = SqlCommenterRuntime.instrument(arg0)  // may throw NCDFE at linkage time
     *   tryEnd:
     *     GOTO after                                    // success: stack empty, jump over catch
     *
     *   catchNCDFE:                                     // stack: [NCDFE]
     *   warnTry:
     *     DUP                                           // stack: [NCDFE, NCDFE]
     *     LDC ownerClass                                // stack: [NCDFE, NCDFE, String]
     *     INVOKESTATIC __warnOnce(Throwable, String)    // stack: [NCDFE]
     *   warnTryEnd:
     *     POP                                           // stack: [] — discard NCDFE
     *     GOTO after
     *
     *   warnCatch:                                      // stack: [Throwable-from-warnOnce]
     *     POP                                           // stack: [] — discard
     *     // (original NCDFE was DUP'd and consumed by __warnOnce attempt)
     *     // BUT: if __warnOnce throws, we're inside the DUP branch.
     *     // The stack at warnCatch entry is [Throwable-from-warnOnce] with original NCDFE already popped?
     *     // NO — let's think again:
     *     //
     *     // Before DUP: stack = [NCDFE]
     *     // After DUP:  stack = [NCDFE, NCDFE]  
     *     // After LDC:  stack = [NCDFE, NCDFE, String]
     *     // If INVOKESTATIC throws: JVM clears operand stack of the try block
     *     //   and pushes only the exception → stack = [Throwable-from-warnOnce]
     *     // The original [NCDFE, NCDFE, String] is discarded.
     *     POP                                           // stack: []
     *     // fall through
     *
     *   after:                                          // stack: [] always
     *     (original method body)
     * </pre>
     *
     * <p>With COMPUTE_FRAMES, ASM automatically computes the StackMapFrame at each
     * label target. No manual visitFrame() needed.
     */
    private static final class DefensiveInjectingMethodVisitor extends AdviceAdapter {

        private final String ownerDotName;

        DefensiveInjectingMethodVisitor(MethodVisitor mv, int acc, String name,
                                        String desc, String ownerDotName) {
            super(Opcodes.ASM9, mv, acc, name, desc);
            this.ownerDotName = ownerDotName;
        }

        @Override
        protected void onMethodEnter() {
            Label tryStart    = new Label();
            Label tryEnd      = new Label();
            Label catchNCDFE  = new Label();
            Label warnTry     = new Label();
            Label warnTryEnd  = new Label();
            Label warnCatch   = new Label();
            Label after       = new Label();

            // Declare try-catch blocks first
            mv.visitTryCatchBlock(tryStart, tryEnd, catchNCDFE,
                    "java/lang/NoClassDefFoundError");
            mv.visitTryCatchBlock(warnTry, warnTryEnd, warnCatch,
                    "java/lang/Throwable");

            // === try: call instrument ===
            mv.visitLabel(tryStart);
            loadArg(0);
            invokeStatic(Type.getType("L" + RUNTIME_CLASS + ";"),
                    new Method("instrument", "(Ljava/lang/String;)Ljava/lang/String;"));
            storeArg(0);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, after);   // success → skip catch

            // === catch(NoClassDefFoundError): log warning, discard ===
            mv.visitLabel(catchNCDFE);
            // stack: [NCDFE]
            mv.visitLabel(warnTry);
            mv.visitInsn(Opcodes.DUP);              // [NCDFE, NCDFE]
            mv.visitLdcInsn(ownerDotName);           // [NCDFE, NCDFE, String]
            invokeStatic(Type.getType("L" + RUNTIME_CLASS + ";"),
                    new Method("__warnOnce",
                            "(Ljava/lang/Throwable;Ljava/lang/String;)V"));
            // [NCDFE]
            mv.visitLabel(warnTryEnd);
            mv.visitInsn(Opcodes.POP);              // [] — discard original NCDFE
            mv.visitJumpInsn(Opcodes.GOTO, after);

            // === catch(Throwable from __warnOnce): swallow ===
            mv.visitLabel(warnCatch);
            // stack: [Throwable] — the warnOnce exception
            // The JVM cleared the operand stack when the exception was thrown,
            // so the original NCDFE is no longer there.
            mv.visitInsn(Opcodes.POP);              // [] — discard warnOnce exception
            // fall through to after

            // === after: empty stack, original method body continues ===
            mv.visitLabel(after);
        }
    }
}
