package effortanalyzer.library;

import java.util.ArrayList;
import java.util.List;

/**
 * CGLib → ByteBuddy migration compatibility rules.
 *
 * CGLib is being replaced by ByteBuddy as the standard bytecode generation library.
 * Spring Framework 5.x, Guice 5.x, and Mockito 4.x all migrated away from CGLib.
 */
public class CglibRules {

    public static final String LIBRARY = "CGLib (→ ByteBuddy)";

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();

        add(rules, "net.sf.cglib", null, "CRITICAL",
                "Migrate to net.bytebuddy (ByteBuddy). See https://bytebuddy.net",
                "CGLib is being replaced by ByteBuddy as the standard bytecode generation library");

        add(rules, "cglib", null, "CRITICAL",
                "Migrate to net.bytebuddy (ByteBuddy)",
                "CGLib package found; replace with ByteBuddy");

        add(rules, "net.sf.cglib.proxy.Enhancer", null, "CRITICAL",
                "Use: new ByteBuddy().subclass(cls).method(any()).intercept(InvocationHandlerAdapter.of(...)).make().load(classLoader)",
                "CGLib Enhancer → ByteBuddy subclass() + intercept()");

        add(rules, "net.sf.cglib.proxy.MethodInterceptor", null, "CRITICAL",
                "Use ByteBuddy InvocationHandlerAdapter.of((proxy, method, args) -> { ... })",
                "CGLib MethodInterceptor → ByteBuddy InvocationHandlerAdapter");

        add(rules, "net.sf.cglib.proxy.MethodProxy", null, "CRITICAL",
                "Use ByteBuddy's MethodDelegation.to(Interceptor.class)",
                "CGLib MethodProxy → ByteBuddy MethodDelegation");

        add(rules, "net.sf.cglib.proxy.Callback", null, "CRITICAL",
                "Use ByteBuddy's MethodDelegation or InvocationHandlerAdapter",
                "CGLib Callback → ByteBuddy delegation model");

        add(rules, "net.sf.cglib.proxy.CallbackFilter", null, "CRITICAL",
                "Use ByteBuddy's ElementMatcher (e.g. named(\"method\"), isDeclaredBy(...))",
                "CGLib CallbackFilter → ByteBuddy ElementMatcher");

        add(rules, "net.sf.cglib.proxy.NoOp", null, "WARNING",
                "Use ByteBuddy's SuperMethodCall.INSTANCE to call through to super",
                "CGLib NoOp → ByteBuddy SuperMethodCall");

        add(rules, "net.sf.cglib.proxy.Factory", null, "WARNING",
                "ByteBuddy generates classes without requiring this interface",
                "CGLib Factory interface not used in ByteBuddy model");

        add(rules, "net.sf.cglib.proxy.LazyLoader", null, "CRITICAL",
                "Use ByteBuddy with a lazy delegation strategy",
                "CGLib LazyLoader → ByteBuddy lazy delegation");

        add(rules, "net.sf.cglib.proxy.Dispatcher", null, "CRITICAL",
                "Use ByteBuddy's MethodDelegation with @RuntimeType",
                "CGLib Dispatcher → ByteBuddy MethodDelegation");

        add(rules, "net.sf.cglib.proxy.InvocationHandler", null, "CRITICAL",
                "Use java.lang.reflect.InvocationHandler with ByteBuddy's InvocationHandlerAdapter",
                "CGLib InvocationHandler → standard java.lang.reflect.InvocationHandler");

        add(rules, "net.sf.cglib.proxy.ProxyRefDispatcher", null, "WARNING",
                "Use ByteBuddy MethodDelegation with access to the proxy object",
                "CGLib ProxyRefDispatcher → ByteBuddy delegation");

        add(rules, "net.sf.cglib.transform", null, "CRITICAL",
                "Use ByteBuddy's bytecode manipulation API or ASM directly",
                "CGLib transform package → ByteBuddy or direct ASM");

        add(rules, "net.sf.cglib.reflect", null, "CRITICAL",
                "Use java.lang.reflect or ByteBuddy's reflection utilities",
                "CGLib reflect package → standard java.lang.reflect");

        add(rules, "net.sf.cglib.beans", null, "WARNING",
                "Use standard Java reflection or MapStruct for bean copying",
                "CGLib BulkBean/BeanCopier → MapStruct or manual mapping");

        add(rules, "net.sf.cglib.beans.BulkBean", null, "WARNING",
                "Use java.lang.reflect or a mapping library like MapStruct",
                "CGLib BulkBean → use reflection or mapping library");

        add(rules, "net.sf.cglib.beans.BeanCopier", null, "WARNING",
                "Use MapStruct, ModelMapper, or manual BeanUtils.copyProperties()",
                "CGLib BeanCopier → MapStruct or Apache Commons BeanUtils");

        add(rules, "net.sf.cglib.beans.BeanMap", null, "WARNING",
                "Use java.util.Map with jackson ObjectMapper.convertValue() or similar",
                "CGLib BeanMap → use Jackson or Introspector-based approach");

        add(rules, "net.sf.cglib.core.KeyFactory", null, "WARNING",
                "Use standard Java record or a custom key class",
                "CGLib KeyFactory → use Java records or custom equals/hashCode");

        add(rules, "org.springframework.cglib", null, "WARNING",
                "Spring repackages CGLib internally; Spring 5.3 uses ByteBuddy by default. "
                + "Ensure you are not referencing Spring's internal CGLib package.",
                "Spring's internal CGLib shaded package; Spring 5.3 migrated to ByteBuddy for AOP");

        return rules;
    }

    private static void add(List<DeprecatedApi> rules, String className, String methodName,
                             String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(LIBRARY, className, methodName, severity, replacement, description));
    }
}
