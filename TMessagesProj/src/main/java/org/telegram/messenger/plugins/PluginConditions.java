package org.telegram.messenger.plugins;

import android.util.LruCache;
import org.mvel2.MVEL;
import org.mvel2.ParserContext;
import org.mvel2.optimizers.OptimizerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public final class PluginConditions {
    private static final LruCache<String, Serializable> cache = new LruCache<>(128);

    static {
        OptimizerFactory.setDefaultOptimizer(OptimizerFactory.SAFE_REFLECTIVE);
    }

    private PluginConditions() {}

    public static boolean matches(String expression, Object receiver, Map<String, Object> variables) {
        if (expression == null || expression.isEmpty()) return true;
        try {
            Serializable compiled = compile(expression);
            return Boolean.TRUE.equals(MVEL.executeExpression(compiled, receiver, variables));
        } catch (RuntimeException e) {
            return false;
        }
    }

    static Serializable compile(String expression) {
        Serializable compiled = cache.get(expression);
        if (compiled == null) {
            ParserContext parser = new ParserContext();
            parser.getParserConfiguration().setClassLoader(PluginConditions.class.getClassLoader());
            compiled = MVEL.compileExpression(expression, parser);
            cache.put(expression, compiled);
        }
        return compiled;
    }

    public static boolean hookMatches(String expression, Object receiver, Object param, Object object) {
        HashMap<String, Object> variables = new HashMap<>();
        variables.put("param", param);
        variables.put("object", object);
        return matches(expression, receiver, variables);
    }
}
