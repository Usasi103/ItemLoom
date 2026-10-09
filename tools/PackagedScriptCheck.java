import java.util.Map;
import dev.itemloom.compat.ni.NiScripts;

/** Run using ONLY the final plugin JAR as its classpath, not Gradle's unshaded dependencies. */
class PackagedScriptCheck {
    public static void main(String[] args) {
        String program = "var data = [1, 2, 3]; function sum() { return data.reduce(function(a,b){return a+b;}, 0); }";
        try (NiScripts scripts = new NiScripts(Map.of("arrays.js", program), Map.of())) {
            Object result = scripts.invoke("arrays.js", "sum", Map.of());
            if (!(result instanceof Number number) || number.intValue() != 6) throw new AssertionError(result);
            if (!"undefined".equals(scripts.evaluate("typeof isolated", Map.of()))) throw new AssertionError();
            scripts.evaluate("var isolated = 17;", Map.of());
            if (!"undefined".equals(scripts.evaluate("typeof isolated", Map.of()))) throw new AssertionError();
            if (((Number) scripts.evaluate("Java.type('java.lang.Integer').parseInt('42')", Map.of())).intValue() != 42) throw new AssertionError();
        }
        System.out.println("PACKAGED_SCRIPT_CHECK passed");
    }
}
